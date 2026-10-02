package app.aaps.pump.atc3.history

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sign

/**
 * Where the difference between the pump's count and the AAPS journal is written, once it has grown
 * past the tolerance. See [Atc3StateCheck].
 *
 * The difference is the pump's delivery mechanics, not a dose: the pump gives insulin in portions
 * on a grid of its own, restarts its profile count on every half hour of its clock and rounds every
 * temporary basal to its portions, while the journal counts a rate over its time. So it belongs to
 * the basal, and it is written there, on the rules decided for it:
 *
 * - **a rate stays what was ordered; only time moves.** A row with an invented average rate is
 *   insulin nobody asked for at a rate nobody set, and it is drawn on the graph as such;
 * - **it is written once a half hour, into the half hour of the pump's clock that has ended**, the
 *   slot the pump's own count restarted in, and only a loop cycle after its end: a bolus is
 *   recorded at its start and counted to its end, so by then every bolus begun in the half hour
 *   is over and in AAPS. Never into the time still running, where the loop works;
 * - **only the mechanics are written**, never more than [mechanicsBound] allows. Beyond that it is
 *   insulin from outside, which the pump's journals have to account for, or an error;
 * - **a closed temporary basal row of that half hour is shortened**, the latest first. Its end is
 *   moved back by `units ÷ (scheduled − rate)`, and the time it gives up runs at the scheduled
 *   rate. Only shortening: the next row usually begins where it ends, and a row lengthened into
 *   it would lie under it. So a pump ahead of the journal shortens a row below the scheduled rate,
 *   and a pump behind it a row above;
 * - **a half hour with no such row takes a correction row** in its time at the scheduled rate:
 *   zero when the pump is behind, the pump's highest basal rate when it is ahead, for as long as
 *   it takes. Both are rates the pump can run, and the highest one keeps the row shortest;
 * - **what does not fit stays** in the difference for the next half hour.
 *
 * Pure arithmetic over plain values: the caller reads the rows and writes the edits.
 */
object Atc3BasalCorrection {

    /** A temporary basal row of this pump, as the correction sees it. */
    data class Row(
        val pumpId: Long,
        val startMs: Long,
        /** Where the row itself ends, its start plus its duration. */
        val endMs: Long,
        val rateUnitsPerHour: Double,
        /** False for a row whose time is a fact of its own, a stop the pump made. */
        val editable: Boolean
    )

    sealed interface Edit {

        /** What the edit moves into the journal, units: positive adds insulin, negative takes it. */
        val units: Double

        /** The row under [pumpId] is cut to [durationMs], its rate and start untouched. */
        data class Shorten(val pumpId: Long, val startMs: Long, val rateUnitsPerHour: Double, val oldEndMs: Long, val durationMs: Long, override val units: Double) : Edit

        /** A correction row of its own. */
        data class Insert(val startMs: Long, val durationMs: Long, val rateUnitsPerHour: Double, override val units: Double) : Edit
    }

    data class Plan(val edits: List<Edit>, val halfHourStartMs: Long, val halfHourEndMs: Long) {

        val units: Double get() = edits.sumOf { it.units }
    }

    /**
     * The edits that write [units] into the journal.
     *
     * @param units what the pump counted beyond the journal: positive when it delivered more
     * @param halfHourEnd the end of the half hour the edits go into
     * @param nowMs the read the difference was found at; no row ending after it is edited
     * @param rows this pump's temporary basal rows around that half hour, in any order
     * @param openPumpId the row still running, never edited
     * @param maxBasal the pump's highest basal rate, U/h
     * @param scheduledAt the profile's rate at an instant, U/h
     */
    fun plan(
        units: Double,
        halfHourEnd: Long,
        nowMs: Long,
        rows: List<Row>,
        openPumpId: Long?,
        maxBasal: Double,
        scheduledAt: (Long) -> Double
    ): Plan {
        val halfHourStart = halfHourEnd - HALF_HOUR_MS
        val edits = ArrayList<Edit>()
        var left = units
        if (abs(left) < NOTHING_UNITS) return Plan(edits, halfHourStart, halfHourEnd)

        // Each row ends where it says or where the next one begins, as AAPS counts it.
        val ordered = rows.sortedBy { it.startMs }
        val effective = ordered.mapIndexed { index, row ->
            val next = ordered.drop(index + 1).firstOrNull { it.startMs > row.startMs }
            row to (if (next != null) minOf(row.endMs, next.startMs) else row.endMs)
        }.filter { (row, end) -> end > row.startMs }
        val ends = HashMap<Long, Long>()

        // Closed rows ending inside the half hour, the latest first.
        val candidates = effective
            .filter { (row, end) -> row.editable && row.pumpId != openPumpId && end > halfHourStart && end <= halfHourEnd && end <= nowMs }
            .sortedByDescending { (_, end) -> end }
        for ((row, end) in candidates) {
            if (abs(left) < NOTHING_UNITS) break
            val scheduled = scheduledAt(end - 1)
            // What a millisecond of this row given back to the scheduled rate moves, in the
            // direction the difference wants.
            val gain = (scheduled - row.rateUnitsPerHour) * sign(left)
            if (gain < MIN_RATE_GAP) continue
            val room = end - maxOf(row.startMs + MIN_ROW_MS, halfHourStart)
            if (room <= 0L) continue
            val wanted = (abs(left) / gain * HOUR_MS).toLong()
            val cut = minOf(wanted, room)
            if (cut <= 0L) continue
            val moved = sign(left) * gain * cut / HOUR_MS
            edits.add(Edit.Shorten(row.pumpId, row.startMs, row.rateUnitsPerHour, end, end - cut - row.startMs, moved))
            ends[row.pumpId] = end - cut
            left -= moved
        }

        // What is left goes into the scheduled time of the half hour, the latest stretch first.
        if (abs(left) >= NOTHING_UNITS) {
            val covered = effective.map { (row, end) -> row.startMs to (ends[row.pumpId] ?: end) }
                .filter { (a, b) -> b > halfHourStart && a < halfHourEnd }
                .sortedBy { it.first }
            val gaps = ArrayList<Pair<Long, Long>>()
            var cursor = halfHourStart
            for ((a, b) in covered) {
                if (a > cursor) gaps.add(cursor to minOf(a, halfHourEnd))
                if (b > cursor) cursor = b
            }
            if (cursor < halfHourEnd) gaps.add(cursor to halfHourEnd)
            for ((a, b) in gaps.sortedByDescending { it.second }) {
                if (abs(left) < NOTHING_UNITS) break
                val scheduled = scheduledAt(b - 1)
                val rate = if (left > 0) maxBasal else 0.0
                val gain = abs(rate - scheduled)
                if (gain < MIN_RATE_GAP || (left > 0 && rate <= scheduled) || (left < 0 && scheduled <= 0.0)) continue
                val wanted = (abs(left) / gain * HOUR_MS).toLong()
                val length = minOf(wanted, b - a)
                if (length < MIN_INSERT_MS) continue
                val moved = sign(left) * gain * length / HOUR_MS
                edits.add(Edit.Insert(b - length, length, rate, moved))
                left -= moved
            }
        }
        return Plan(edits, halfHourStart, halfHourEnd)
    }

    /**
     * How far the pump's count can sit from the journal over `[fromMs, toMs)` by its delivery
     * mechanics alone, units, both ways.
     *
     * - the scheduled rate: the pump counts its portions afresh at every half hour of its clock,
     *   so in each half hour, however a temporary basal cut it, it is less than one portion away;
     * - each temporary basal row: it delivers the whole portions nearest its time, at most half a
     *   portion away;
     * - a bolus: delivered exactly as recorded, nothing.
     *
     * @param rows this pump's temporary basal rows overlapping the interval
     */
    fun mechanicsBound(fromMs: Long, toMs: Long, rows: List<Row>, scheduledAt: (Long) -> Double): Double {
        if (toMs <= fromMs) return 0.0
        var bound = 0.0
        var cursor = halfHourOf(fromMs)
        while (cursor < toMs) {
            bound += profilePortion(scheduledAt(maxOf(cursor, fromMs)))
            cursor += HALF_HOUR_MS
        }
        for (row in rows) {
            if (row.endMs <= fromMs || row.startMs >= toMs || row.rateUnitsPerHour <= 0.0) continue
            bound += tbrPortion(row.rateUnitsPerHour) / 2
        }
        return bound
    }

    /** The portion the pump gives the scheduled rate in, units. */
    fun profilePortion(rate: Double): Double = when {
        rate < 1.0 - RATE_EPSILON -> 0.025
        rate < 2.0 - RATE_EPSILON -> 0.05
        else                      -> 0.1
    }

    /** The portion the pump gives a temporary basal in, units. */
    fun tbrPortion(rate: Double): Double = if (rate <= 5.0 + RATE_EPSILON) 0.025 else 0.1

    /** The start of the half hour of the local calendar that [ms] falls in. */
    fun halfHourOf(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.MILLISECOND, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MINUTE, if (get(Calendar.MINUTE) < 30) 0 else 30)
    }.timeInMillis

    /** Less than this is nothing to write, units. */
    const val NOTHING_UNITS = 0.0005

    /** A row this close to the scheduled rate moves too little per minute to be worth cutting, U/h. */
    const val MIN_RATE_GAP = 0.025

    /** A shortened row keeps at least this much of itself. */
    const val MIN_ROW_MS = 60_000L

    /** A correction row shorter than this is not written. */
    const val MIN_INSERT_MS = 1_000L

    const val HALF_HOUR_MS = 30 * 60_000L
    private const val RATE_EPSILON = 1e-6
    private const val HOUR_MS = 3_600_000.0
}
