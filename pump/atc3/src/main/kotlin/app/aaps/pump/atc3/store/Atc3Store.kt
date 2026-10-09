package app.aaps.pump.atc3.store

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.keys.Atc3StringNonKey
import app.aaps.pump.atc3.basal.Atc3BasalPeriod
import app.aaps.pump.atc3.history.Atc3HistoryLedger
import app.aaps.pump.atc3.history.LearnedBolus
import app.aaps.pump.atc3.link.Atc3LinkWatch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The driver's state that outlives the process, as one document under one key. Every change writes
 * the whole of it, so the parts never disagree on disk, and a new pump forgets all of it at once.
 *
 * A document that does not read in full is copied under a key of its own before the next change
 * writes over it, the user is told, and what did read is used. Each part reads on its own, so one
 * part that does not read loses only that part.
 */
@Singleton
class Atc3Store @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val uiInteraction: UiInteraction,
    private val rh: ResourceHelper
) {

    private var loaded: Atc3StoredState? = null

    val state: Atc3StoredState
        @Synchronized get() = loaded ?: load().also { loaded = it }

    /** Change the state and write it, unless the change leaves it as it was. */
    @Synchronized
    fun update(change: (Atc3StoredState) -> Atc3StoredState) {
        val before = state
        val after = change(before)
        if (after == before) return
        // Written before it is taken: a state that cannot be written is not one the driver goes on with.
        val text = encode(after)
        preferences.put(Atc3StringNonKey.State, text)
        loaded = after
    }

    /**
     * Read from disk. What earlier keys hold is put over the document: the first start after an update,
     * or an earlier build that ran since and wrote them, which makes them the newer state, see [Atc3LegacyState].
     */
    private fun load(): Atc3StoredState {
        val document = readDocument()
        return Atc3LegacyState.read(preferences, document) { line ->
            aapsLogger.error(LTag.PUMP, "ATC3: a ledger line could not be read and was dropped: $line")
        }?.let { moveLegacy(it) } ?: document
    }

    private fun readDocument(): Atc3StoredState {
        val stored = preferences.getIfExists(Atc3StringNonKey.State)
        if (stored.isNullOrEmpty()) return Atc3StoredState()
        val read = runCatching { readParts(stored) }.getOrNull()
        if (read != null && read.lost.isEmpty() && read.version <= Atc3StoredState.VERSION) return read.state
        setAside(stored, read)
        return read?.state ?: Atc3StoredState()
    }

    /** Take over the earlier keys: written in the new form first, so a process killed in between loses nothing. */
    private fun moveLegacy(legacy: Atc3StoredState): Atc3StoredState {
        preferences.put(Atc3StringNonKey.State, encode(legacy))
        Atc3LegacyState.erase(preferences)
        aapsLogger.debug(LTag.PUMP, "ATC3: the stored state was moved to one document")
        return legacy
    }

    /**
     * Keep a document that did not read in full where nothing writes over it, and tell the user: a
     * bolus waiting for its record may be among what was lost, and is then taken stock of again. A
     * copy already kept stays: the first is the one closest to what was lost; the log has each in full.
     */
    private fun setAside(stored: String, read: Read?) {
        if (preferences.getIfExists(Atc3StringNonKey.StateSetAside).isNullOrEmpty()) preferences.put(Atc3StringNonKey.StateSetAside, stored)
        val what = when {
            read == null                             -> "nothing of it"
            read.version > Atc3StoredState.VERSION   -> "version ${read.version}, newer than this driver"
            else                                     -> "not ${read.lost.joinToString()}"
        }
        aapsLogger.error(LTag.PUMP, "ATC3: the stored state did not read in full ($what), it is kept aside: $stored")
        uiInteraction.addNotification(Notification.PUMP_ERROR, rh.gs(R.string.atc3_state_set_aside), Notification.URGENT)
    }

    /** A document read part by part: the state from what read, and the parts that did not. */
    class Read(val state: Atc3StoredState, val version: Int, val lost: List<String>)

    companion object {

        /** Defaults are written out: a default worked out on reading would go through the timezone in force then. */
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun encode(state: Atc3StoredState): String = json.encodeToString(Atc3StoredState.serializer(), state)

        /**
         * Each part on its own: a part that does not read is left at its default and named in [Read.lost].
         * A document written before it carried a version is version 1.
         *
         * @throws IllegalArgumentException when the text is not a JSON object at all
         */
        fun readParts(text: String): Read {
            val document = json.parseToJsonElement(text).jsonObject
            val lost = mutableListOf<String>()
            val empty = Atc3StoredState()

            fun <T> part(name: String, serializer: KSerializer<T>, default: T): T {
                val element = document[name] ?: return default
                return runCatching { json.decodeFromJsonElement(serializer, element) }.getOrElse {
                    lost += name
                    default
                }
            }

            val state = Atc3StoredState(
                ledger = part("ledger", Atc3HistoryLedger.serializer(), empty.ledger),
                learned = part("learned", ListSerializer(LearnedBolus.serializer()), empty.learned),
                basalPeriod = part("basalPeriod", Atc3BasalPeriod.State.serializer(), empty.basalPeriod),
                lastAnswer = part("lastAnswer", Atc3LinkWatch.Stop.serializer().nullable, empty.lastAnswer),
                linkStop = part("linkStop", Atc3LinkWatch.Stop.serializer().nullable, empty.linkStop)
            )
            val version = (document["version"] as? JsonPrimitive)?.intOrNull ?: 1
            return Read(state, version, lost)
        }
    }
}
