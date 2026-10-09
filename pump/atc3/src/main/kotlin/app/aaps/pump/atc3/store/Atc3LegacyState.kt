package app.aaps.pump.atc3.store

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.basal.Atc3BasalPeriod
import app.aaps.pump.atc3.history.ActiveTbr
import app.aaps.pump.atc3.history.Atc3HistoryLedger
import app.aaps.pump.atc3.history.LearnedBolus
import app.aaps.pump.atc3.keys.Atc3StringNonKey
import app.aaps.pump.atc3.link.Atc3LinkWatch
import app.aaps.pump.atc3.protocol.Atc3Protocol

/**
 * The state as earlier versions kept it, a key and a text form for each part, read once on the first
 * start after an update, see [Atc3Store]. Each part is read as leniently as before: a field a later
 * version added may be missing, and what does not read is dropped rather than taking the rest with it.
 */
object Atc3LegacyState {

    /** The ledger last: an erase cut short leaves it, and the parts already gone are kept by the document. */
    private val KEYS = listOf(
        Atc3StringNonKey.CheckLearned, Atc3StringNonKey.BasalPeriod, Atc3StringNonKey.LastAnswer,
        Atc3StringNonKey.LinkStop, Atc3StringNonKey.HistoryLedger
    )

    /**
     * [base] with each part an earlier key holds put in its place, or null when they hold nothing. A
     * part whose key is empty stays as [base] has it: an earlier build writes a key when its part
     * changes, and an erase cut short leaves some keys and not others.
     */
    fun read(preferences: Preferences, base: Atc3StoredState, onBadLine: (String) -> Unit = {}): Atc3StoredState? {
        val stored = KEYS.associateWith { preferences.getIfExists(it).orEmpty() }.filterValues { it.isNotBlank() }
        if (stored.isEmpty()) return null
        var state = base
        stored[Atc3StringNonKey.HistoryLedger]?.let { state = state.copy(ledger = ledger(it, serialOf(it).orEmpty(), onBadLine)) }
        stored[Atc3StringNonKey.CheckLearned]?.let { state = state.copy(learned = learned(it)) }
        stored[Atc3StringNonKey.BasalPeriod]?.let { state = state.copy(basalPeriod = basalPeriod(it)) }
        stored[Atc3StringNonKey.LastAnswer]?.let { state = state.copy(lastAnswer = stop(it)) }
        stored[Atc3StringNonKey.LinkStop]?.let { state = state.copy(linkStop = stop(it)) }
        return state
    }

    fun erase(preferences: Preferences) = KEYS.forEach { preferences.remove(it) }

    fun learned(text: String): List<LearnedBolus> =
        text.split('|').filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split(';')
            val at = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val units = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            val start = p.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
            LearnedBolus(at, units, start)
        }

    fun stop(text: String): Atc3LinkWatch.Stop? {
        val parts = text.split(';')
        val readMs = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val counter = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return Atc3LinkWatch.Stop(readMs, counter)
    }

    fun basalPeriod(text: String): Atc3BasalPeriod.State {
        val parts = text.split('|')
        val start = markOf(parts.getOrNull(0).orEmpty().split(';'))
        val c = parts.getOrNull(1).orEmpty().split(';')
        val closedStart = markOf(c)
        val end = c.getOrNull(3)?.toLongOrNull()
        val at = c.getOrNull(4)?.toLongOrNull()
        // What earlier versions kept after these, of the writer that is gone, is passed over.
        val closed = if (closedStart != null && end != null && at != null) Atc3BasalPeriod.Closed(closedStart, end, at) else null
        return Atc3BasalPeriod.State(start, closed)
    }

    private fun markOf(p: List<String>): Atc3BasalPeriod.Mark? {
        val read = p.getOrNull(0)?.toLongOrNull() ?: return null
        val counter = p.getOrNull(1)?.toDoubleOrNull() ?: return null
        val learned = p.getOrNull(2)?.toLongOrNull() ?: return null
        return Atc3BasalPeriod.Mark(read, counter, learned)
    }

    private const val LEDGER_VERSION = "atc3-ledger-v1"

    private fun units(raw: Int): Double = raw * Atc3Protocol.DOSE_SCALE

    /** The serial of the pump a stored ledger belongs to, or null when there is none to read. */
    fun serialOf(stored: String): String? {
        val lines = stored.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty() || lines[0].trim() != LEDGER_VERSION) return null
        return lines.firstOrNull { it.startsWith("w|") }?.split("|")?.getOrNull(4)?.takeIf { it.isNotBlank() }
    }

    /**
     * Read a ledger back; anything unreadable, older or another pump's gives an empty one, which only
     * means taking stock again. What earlier versions kept of bolus records is not read: AAPS's rows
     * say which records it has.
     */
    fun ledger(stored: String, serial: String, onBadLine: (String) -> Unit = {}): Atc3HistoryLedger {
        val lines = stored.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty() || lines[0].trim() != LEDGER_VERSION) return Atc3HistoryLedger(serial = serial)
        var ledger = Atc3HistoryLedger(serial = serial)
        for (line in lines.drop(1)) {
            val f = line.split("|")
            // A line that does not parse is dropped, and said: a lost pending bolus is insulin nothing will close.
            runCatching {
                when (f[0]) {
                    "w"  -> {
                        if (f[4] != serial) return Atc3HistoryLedger(serial = serial)
                        ledger = ledger.copy(
                            importFromUtcSeconds = f[1].toLong(),
                            firstPassDone = f[2] == "1",
                            importFromPhoneMs = f.getOrNull(5)?.toLongOrNull() ?: 0L
                        )
                    }

                    // The notes of our temporary basals, "o", served the journal shaping that is gone: not read.
                    "t"  -> ledger = ledger.withActiveTbr(
                        ActiveTbr(
                            pumpId = f[1].toLong(),
                            startedAtMs = f[2].toLong(),
                            rate = units(f[3].toInt()),
                            ours = f[4] == "1",
                            ownDurationMs = f[5].toLong(),
                            pumpStartUtcSeconds = f.getOrNull(6)?.toLongOrNull(),
                            suspension = f.getOrNull(7) == "1",
                            pumpStartMs = f.getOrNull(8)?.toLongOrNull()
                        )
                    )

                    else -> Unit
                }
            }.onFailure { onBadLine(line) }
        }
        return ledger
    }
}
