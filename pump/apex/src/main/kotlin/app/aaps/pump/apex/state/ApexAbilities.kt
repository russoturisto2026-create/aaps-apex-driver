package app.aaps.pump.apex.state

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.account.ReferencePoints
import app.aaps.pump.apex.account.store.ApexStore

/**
 * Which optional objects a pump has. A pump that does not have one never answers its read, so the
 * driver must not ask for it and must work from what the pump does give. Kept per pump and
 * firmware: a new firmware is probed again. Until a pump is probed, all are taken as present.
 */
data class ApexAbilities(
    /** The last temporary basal command, with its start stamp. */
    val lastTbrCommand: Boolean = true,
    /** The last finished temporary basal, with how and when it ended. */
    val finishedTbr: Boolean = true,
    /** The daily statistics record. */
    val dayRecord: Boolean = true,
    /** The second status report. */
    val statusV2: Boolean = true
) {

    /** One line for the store: the firmware text, then one digit for each object. */
    fun encode(firmware: String): String = listOf(firmware, bit(lastTbrCommand) + bit(finishedTbr) + bit(dayRecord) + bit(statusV2)).joinToString(SEPARATOR)

    /** For logs. */
    val text: String get() = "last command $lastTbrCommand, last finished $finishedTbr, day record $dayRecord, status V2 $statusV2"

    private fun bit(has: Boolean) = if (has) "1" else "0"

    companion object {

        /** The store's key of what was probed. */
        const val STATE_KEY = "abilities"

        private const val SEPARATOR = "|"
        private const val BITS = 4

        /** What a pump that was not probed is taken to have: everything. */
        val UNKNOWN = ApexAbilities()

        /** What is stored for this pump, if it was probed with this [firmware]; else null. */
        fun stored(store: ApexStore, serial: String, firmware: String?): ApexAbilities? {
            if (serial.isEmpty() || firmware == null) return null
            return decode(store.state(serial, STATE_KEY) ?: return null, firmware)
        }

        /** What this pump has: what was probed, else [UNKNOWN]. */
        fun of(store: ApexStore, serial: String, firmware: String?): ApexAbilities = stored(store, serial, firmware) ?: UNKNOWN

        /**
         * Keep [abilities] for this pump. When they differ from what was kept, or nothing was kept,
         * the reference points between heartbeats are dropped, see [ReferencePoints].
         */
        fun store(aapsLogger: AAPSLogger, store: ApexStore, serial: String, firmware: String, abilities: ApexAbilities) {
            val before = stored(store, serial, firmware)
            store.transaction {
                store.putState(serial, STATE_KEY, abilities.encode(firmware))
                if (before != abilities) ReferencePoints.drop(store, serial)
            }
            if (before != abilities) {
                aapsLogger.info(LTag.PUMP, "Apex: the abilities are now: ${abilities.text}; were: ${before?.text ?: "not kept"}; the reference points of the spans are dropped and begin anew at the next heartbeat")
            }
        }

        /** The abilities in [text], or null when it is not well formed or was probed with another firmware than [firmware]. */
        fun decode(text: String, firmware: String): ApexAbilities? {
            val parts = text.split(SEPARATOR)
            if (parts.size != 2 || parts[0] != firmware) return null
            val bits = parts[1].takeIf { it.length == BITS && it.all { c -> c == '0' || c == '1' } } ?: return null
            return ApexAbilities(bits[0] == '1', bits[1] == '1', bits[2] == '1', bits[3] == '1')
        }
    }
}
