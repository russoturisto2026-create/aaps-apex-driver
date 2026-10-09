package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.protocol.Atc3BolusHistory
import app.aaps.pump.atc3.protocol.Atc3BolusRecord
import app.aaps.pump.atc3.protocol.Atc3StatusV1

/** A bolus row AAPS holds of this pump, as the rows of a minute are compared: when, and how much in the pump's steps. */
data class Atc3BolusRow(val timestamp: Long, val rawUnits: Int)

/**
 * One read of the pump's bolus journal against the rows AAPS holds, one minute at a time.
 *
 * A record has no number; it is known by the minute its bolus started in and by its doses. So in
 * each minute, as many records of a dose as AAPS has rows of are the ones AAPS has, and the rest are
 * written. Nothing is remembered about the records: the rows are the memory, and a row the user
 * deleted is still a row. The pump's past, from before the import boundary, records older than a day
 * and empty records are passed over. An extended or dual bolus becomes no row: the loop cannot count
 * it, so the user is told to enter it.
 */
object Atc3BolusReconciler {

    /**
     * A row to write; [expected] is the bolus of ours this is the record of, null for anyone else's.
     * [timestamp] is the start of the record's minute, or the moment AAPS adopted the pump when that
     * falls inside the minute: AAPS refuses a row dated before it, by a second as well as by a day.
     */
    data class Write(val timestamp: Long, val units: Double, val pumpId: Long, val expected: ExpectedBolus?)

    /** An extended or dual bolus the pump gave, which no row carries. */
    data class Extended(val timestamp: Long, val units: Double)

    data class Outcome(
        val writes: List<Write>,
        val extended: List<Extended>,
        val ledger: Atc3HistoryLedger,
        /** Rows AAPS holds beyond the pump's records of their minutes, for the trace. */
        val rowsBeyond: Int,
        /** Where our boluses' records sat against their starts, minutes, or null when none was found. */
        val clockShiftMinutes: Int?,
        /** The records passed over as the pump's past, older than a day, or empty: named in the log, nothing else. */
        val skipped: List<Atc3BolusRecord> = emptyList()
    )

    /**
     * @param records what the pump returned, in any order
     * @param rows the rows AAPS holds of this pump over the records' minutes, deleted ones included
     * @param phoneNow the phone's clock now
     * @param earliestAcceptedMs the moment AAPS adopted this pump: anything older it refuses
     */
    fun reconcile(
        records: List<Atc3BolusRecord>,
        rows: List<Atc3BolusRow>,
        ledger: Atc3HistoryLedger,
        phoneNow: Long,
        earliestAcceptedMs: Long
    ): Outcome {
        var current = ledger
        if (!current.firstPassDone) {
            // What the pump held before AAPS adopted it is its past.
            val from = if (earliestAcceptedMs > 0L) earliestAcceptedMs else phoneNow
            current = current.withWatermark(Atc3StatusV1.wallClockUtcSeconds(from), from)
        }
        val (skipped, fresh) = records.partition { isPast(it, current, phoneNow, earliestAcceptedMs) }
        val extended = fresh.filter { it.carriesExtendedPart }.map { Extended(minuteStartOf(it.timestamp), it.totalDeliveredUnits) }

        val writes = ArrayList<Write>()
        var expected = current.expected
        val shifts = ArrayList<Int>()
        var rowsBeyond = 0
        for ((minute, ofMinute) in fresh.filterNot { it.carriesExtendedPart }.groupBy { minuteStartOf(it.timestamp) }.toSortedMap()) {
            val held = rows.filter { it.timestamp in minute until minute + 60_000L }.map { it.rawUnits }.toMutableList()
            var next = held.size
            // Still inside the minute: a record dated before the adoption moment was skipped above.
            val at = maxOf(minute, earliestAcceptedMs)
            for (record in ofMinute) {
                if (held.remove(record.rawDelivered)) continue
                val own = expected.firstOrNull { fits(record, it) }
                if (own != null) {
                    expected = expected - own
                    shifts.add((minuteOf(record.pumpClockUtcSeconds) - minuteOf(own.startUtcSeconds)).toInt())
                }
                writes.add(Write(at, record.deliveredUnits, minute + next++, own))
            }
            rowsBeyond += held.size
        }
        // A record of a later minute than ours could sit in says ours came, or never will: the pump writes
        // in order, and under an alarm it writes nothing at all.
        val latestMinute = records.maxOfOrNull { minuteOf(it.pumpClockUtcSeconds) }
        if (latestMinute != null) expected = expected.filter { minuteOf(it.startUtcSeconds) + 1 >= latestMinute }
        current = current.copy(expected = expected)
        val clockShift = if (shifts.isEmpty()) null else shifts.firstOrNull { it != 0 } ?: 0
        return Outcome(writes, extended, current, rowsBeyond, clockShift, skipped)
    }

    /**
     * True when the pump holds records this answer left out: the newest record of the last answer is
     * not in it, so ten or more came since, or there was no answer yet. An answer that holds everything
     * the pump has leaves nothing out.
     */
    fun recordsMissing(history: Atc3BolusHistory, lastAnswerNewestUtcSeconds: Long): Boolean {
        if (history.records.size >= history.recordCount) return false
        if (lastAnswerNewestUtcSeconds == 0L) return true
        val minute = minuteOf(lastAnswerNewestUtcSeconds)
        return history.records.none { minuteOf(it.pumpClockUtcSeconds) == minute }
    }

    /** Whether this record is nothing to write: empty, the pump's past behind the boundary on both clocks, older than a day, or from before AAPS adopted the pump. */
    private fun isPast(record: Atc3BolusRecord, ledger: Atc3HistoryLedger, phoneNow: Long, earliestAcceptedMs: Long): Boolean =
        record.isEmptyRecord ||
            (record.pumpClockUtcSeconds < ledger.importFromUtcSeconds && record.timestamp <= ledger.importFromPhoneMs) ||
            record.timestamp < phoneNow - Atc3Const.RECONCILE_MAX_AGE_MS ||
            record.timestamp < earliestAcceptedMs

    /** Whether this record can be that bolus of ours: the same dose asked, in the minute ours started in or one either side. */
    private fun fits(record: Atc3BolusRecord, expected: ExpectedBolus): Boolean =
        record.rawRequested == Atc3HistoryLedger.raw(expected.units) &&
            minuteOf(record.pumpClockUtcSeconds) in (minuteOf(expected.startUtcSeconds) - 1)..(minuteOf(expected.startUtcSeconds) + 1)

    /** The minute a pump clock key falls in, counted in minutes. */
    private fun minuteOf(utcSeconds: Long): Long = Math.floorDiv(utcSeconds, 60L)

    /** The first millisecond of the minute [timestamp] lies in. */
    fun minuteStartOf(timestamp: Long): Long = timestamp - Math.floorMod(timestamp, 60_000L)
}
