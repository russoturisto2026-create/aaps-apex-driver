package app.aaps.pump.atc3.history

import java.util.Calendar

/**
 * What the AAPS journal counts as delivered over an interval, worked out the way AAPS itself does.
 *
 * This is the AAPS side of the state check: the pump says how much it delivered between two of its
 * snapshots, and this says how much the rows AAPS holds account for over the same interval. The two
 * have to be computed by the same rules or the comparison measures the rules and not the pump, so
 * the rules are AAPS's own:
 *
 * - a bolus counts in full at its timestamp;
 * - a temporary basal runs from its timestamp for its duration, **or until the next temporary
 *   basal begins**, whichever is first — AAPS cuts a running one when a later one arrives, and a
 *   row that was not cut still ends where the next one starts;
 * - an absolute temporary basal delivers its rate; a percentage one delivers that much of the
 *   scheduled basal;
 * - wherever no temporary basal runs, the scheduled basal of the profile runs.
 *
 * Pure arithmetic over plain values, so it can be tested without a database. The caller reads the
 * rows and supplies the profile.
 */
object Atc3JournalArithmetic {

    /** One temporary basal row of AAPS, as far as the arithmetic cares. */
    data class Row(
        val startMs: Long,
        /** Where the row itself ends, its timestamp plus its duration. */
        val endMs: Long,
        /** The rate, U/h, for an absolute row; null for a percentage row. */
        val rateUnitsPerHour: Double?,
        /** The percentage of the scheduled basal, for a percentage row; null for an absolute one. */
        val percent: Int? = null
    )

    /** The answer, kept in its parts so a trace can say which part disagreed. */
    data class Breakdown(val bolusUnits: Double, val temporaryBasalUnits: Double, val scheduledUnits: Double) {

        val totalUnits: Double get() = bolusUnits + temporaryBasalUnits + scheduledUnits
    }

    /**
     * Insulin the journal accounts for over `[fromMs, toMs)`.
     *
     * @param boluses timestamp and amount of every bolus that may fall in the interval
     * @param rows every temporary basal that may overlap the interval, in any order
     * @param scheduledChangesAt moments inside the interval where the scheduled rate changes for a
     *   reason other than the half hour -- a profile switched on, in practice. The half hours are
     *   known here; a switch is not, and the caller is the only one who can say when one was made.
     *   Given in any order; the integration wants them in the order they happen and sorts them.
     * @param scheduledAt the profile's basal rate, U/h, at an instant
     */
    fun insulin(
        fromMs: Long,
        toMs: Long,
        boluses: List<Pair<Long, Double>>,
        rows: List<Row>,
        bolusFromMs: Long = fromMs,
        bolusToMs: Long = toMs,
        scheduledChangesAt: List<Long> = emptyList(),
        scheduledAt: (Long) -> Double
    ): Breakdown {
        if (toMs <= fromMs) return Breakdown(0.0, 0.0, 0.0)
        // Sorted here rather than trusted: the integration walks forward and takes the first
        // change past where it stands, so one out of order would be stepped over and the rate
        // before it carried on past its end.
        val changes = scheduledChangesAt.sorted()
        // The boluses on a window of their own when the caller says so: a stranger's bolus is
        // written at second 59 of its minute, past the snapshot that already counts it.
        val bolus = boluses.filter { it.first in bolusFromMs until bolusToMs }.sumOf { it.second }

        // Each row ends where it says or where the next one begins, in the order they begin.
        val ordered = rows.sortedBy { it.startMs }
        val effective = ordered.mapIndexed { index, row ->
            val next = ordered.drop(index + 1).firstOrNull { it.startMs > row.startMs }
            val end = if (next != null) minOf(row.endMs, next.startMs) else row.endMs
            row to end
        }.filter { (row, end) -> end > row.startMs }

        var tbr = 0.0
        var covered = ArrayList<Pair<Long, Long>>()
        for ((row, end) in effective) {
            val a = maxOf(row.startMs, fromMs)
            val b = minOf(end, toMs)
            if (b <= a) continue
            covered.add(a to b)
            tbr += when {
                row.rateUnitsPerHour != null -> row.rateUnitsPerHour * (b - a) / HOUR_MS
                row.percent != null          ->
                    integrateScheduled(a, b, changes) { scheduledAt(it) * row.percent / 100.0 }
                else                         -> 0.0
            }
        }

        // What the rows do not cover runs at the scheduled rate.
        var scheduled = 0.0
        var cursor = fromMs
        for ((a, b) in covered.sortedBy { it.first }) {
            if (a > cursor) scheduled += integrateScheduled(cursor, a, changes, scheduledAt)
            if (b > cursor) cursor = b
        }
        if (toMs > cursor) scheduled += integrateScheduled(cursor, toMs, changes, scheduledAt)

        return Breakdown(bolus, tbr, scheduled)
    }

    /**
     * Integrate a rate that is constant between the moments it can change, U/h over milliseconds,
     * by splitting the interval at each of them.
     *
     * A profile's own rate changes on the half hour. The profile itself changes whenever one is
     * switched on, which is no respecter of half hours, and the pump is put on the new rate at that
     * moment. Split only on the half hour and the whole stretch is credited the rate read at its
     * start, which for a stretch containing a switch is a rate that stopped running inside it.
     */
    private fun integrateScheduled(
        fromMs: Long,
        toMs: Long,
        changesAt: List<Long>,
        rateAt: (Long) -> Double
    ): Double {
        var units = 0.0
        var cursor = fromMs
        while (cursor < toMs) {
            val nextChange = changesAt.firstOrNull { it > cursor } ?: Long.MAX_VALUE
            val next = minOf(nextHalfHour(cursor), nextChange, toMs)
            units += rateAt(cursor) * (next - cursor) / HOUR_MS
            cursor = next
        }
        return units
    }

    /** The first half hour boundary strictly after [ms], on the local calendar. */
    private fun nextHalfHour(ms: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.MILLISECOND, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MINUTE, if (get(Calendar.MINUTE) < 30) 0 else 30)
        }
        calendar.add(Calendar.MINUTE, 30)
        return calendar.timeInMillis
    }

    private const val HOUR_MS = 3_600_000.0
}
