package app.aaps.pump.apex.account.basal

import app.aaps.pump.apex.account.time.ApexClock

/**
 * The rows of a closed period. The pump runs one temporary basal or none, so at the mark:
 * - Everything before the mark, back to the period's start, is one row of the basal that went in.
 *   A row begun before the period is cut at its start; any other row that reaches the period or
 *   lies past it is taken out.
 * - After the mark there is only what the status shows running now: one row from the mark, at its
 *   rate, for the minutes it has left; none when no temporary basal runs. The ends of the rows in
 *   AAPS are not used.
 *
 * The row after the mark is a new one: the next heartbeat takes it out with the rest of its period.
 * The plan is made first and carried out after, so that it can be tested on its own.
 */
object BasalPeriodRows {

    /** A temporary basal row as AAPS holds it, the rate in the pump's units. */
    class Row(val pumpId: Long, val startMs: Long, val durationMs: Long, val rate: Double, val isAbsolute: Boolean, val suspend: Boolean) {

        val endMs: Long get() = startMs + durationMs
    }

    /** The temporary basal the status shows running at the mark: [rate] U/h for [leftMs] more. */
    class Running(val rate: Double, val leftMs: Long)

    sealed interface Op {

        /** A row under [pumpId] with these bounds: a new one, or the one of that id placed again. */
        data class Place(val pumpId: Long, val startMs: Long, val durationMs: Long, val rate: Double, val isAbsolute: Boolean, val suspend: Boolean) : Op

        /** The row of [pumpId] is taken out of AAPS. */
        data class Remove(val pumpId: Long) : Op
    }

    /** The plan, and the row placed after the mark for the one that runs; null when none runs. */
    class Plan(val ops: List<Op>, val fact: Op.Place, val running: Op.Place?)

    /**
     * The id of the period's own row: a millisecond after its start. The start is a whole minute,
     * and another's row has the id of the pump's stamp, a whole minute too.
     */
    fun factId(startMs: Long): Long = startMs + 1

    /** The id of the row after the mark. */
    fun continuedId(endMs: Long): Long = endMs + 2

    /**
     * @param rows every row that reaches the period or lies past its start, read before anything is written
     * @param units the basal that went in over the period, U
     * @param running what the status shows running at the mark, [endMs]; null when none runs
     */
    fun plan(rows: List<Row>, startMs: Long, endMs: Long, units: Double, running: Running?): Plan {
        val ops = ArrayList<Op>()
        val touched = rows.filter { it.pumpId != factId(startMs) && it.endMs > startMs }
        for (row in touched) {
            if (row.startMs < startMs) ops += Op.Place(row.pumpId, row.startMs, startMs - row.startMs, row.rate, row.isAbsolute, row.suspend)
            else ops += Op.Remove(row.pumpId)
        }
        // A period the pump stood suspended through stays a suspension in the list of AAPS.
        val inPeriod = touched.filter { it.startMs < endMs }
        val suspend = units <= 0.0 && inPeriod.isNotEmpty() && inPeriod.all { it.suspend }
        val fact = Op.Place(factId(startMs), startMs, endMs - startMs, units / ((endMs - startMs) / ApexClock.HOUR_MS.toDouble()), true, suspend)
        ops += fact
        val after = running?.takeIf { it.leftMs > 0L }?.let { Op.Place(continuedId(endMs), endMs, it.leftMs, it.rate, true, false) }
        after?.let { ops += it }
        return Plan(ops, fact, after)
    }
}
