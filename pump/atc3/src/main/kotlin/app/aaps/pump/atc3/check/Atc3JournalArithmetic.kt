package app.aaps.pump.atc3.check

import app.aaps.pump.atc3.clock.Atc3DayClock

/**
 * What the AAPS journal counts as delivered over an interval, by AAPS's own rules, so that the
 * comparison with the pump's count measures the pump and not the rules:
 *
 * - a bolus counts in full at its time;
 * - a temporary basal runs for its duration or until the next one begins, whichever is first;
 * - an absolute one delivers its rate, a percentage one that share of the scheduled rate;
 * - where none runs, the scheduled rate does.
 */
object Atc3JournalArithmetic {

    /** One temporary basal row of AAPS, as far as the arithmetic cares. */
    data class Row(
        val startMs: Long,
        /** The row's own end, its time plus its duration. */
        val endMs: Long,
        /** U/h for an absolute row, null for a percentage one. */
        val rateUnitsPerHour: Double?,
        /** For a percentage row, null for an absolute one. */
        val percent: Int? = null
    )

    /** The answer in its parts, so a trace can say which part disagreed. */
    data class Breakdown(val bolusUnits: Double, val temporaryBasalUnits: Double, val scheduledUnits: Double) {

        val totalUnits: Double get() = bolusUnits + temporaryBasalUnits + scheduledUnits
    }

    /**
     * Insulin the journal accounts for over `[fromMs, toMs)`.
     *
     * @param bolusUnits the boluses of the interval, summed by the caller
     * @param rows every temporary basal that may overlap the interval, in any order
     * @param scheduledChangesAt moments inside the interval where the scheduled rate changes other than on the half hour: a profile switched on
     * @param scheduledAt the profile's rate, U/h, at an instant
     */
    fun insulin(
        fromMs: Long,
        toMs: Long,
        bolusUnits: Double,
        rows: List<Row>,
        scheduledChangesAt: List<Long> = emptyList(),
        scheduledAt: (Long) -> Double
    ): Breakdown {
        if (toMs <= fromMs) return Breakdown(0.0, 0.0, 0.0)
        // Sorted: the walk forward would step over a change out of order.
        val changes = scheduledChangesAt.sorted()
        val bolus = bolusUnits

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

    /** Integrate a rate constant between the moments it can change: each half hour, and each profile switch. */
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
    private fun nextHalfHour(ms: Long): Long = Atc3DayClock.halfHourOf(ms) + Atc3DayClock.HALF_HOUR_MS

    private const val HOUR_MS = 3_600_000.0
}
