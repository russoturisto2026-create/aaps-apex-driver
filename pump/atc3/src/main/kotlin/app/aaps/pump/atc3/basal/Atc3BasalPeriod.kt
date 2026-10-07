package app.aaps.pump.atc3.basal

import app.aaps.pump.atc3.clock.Atc3DayClock
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.protocol.Atc3Protocol

/**
 * The basal of a passed half hour as the pump delivered it, for the exact basal mode. The pump
 * delivers a rate in portions on a count of its own, so the rows of what was ordered and what went
 * in drift apart; what does not drift is the pump's count of the day.
 *
 * - The first read in a new half hour of the clock ends the period before it; the driver asks for
 *   that read itself, see [msToNextRead].
 * - Boluses first: a period is closed only with every bolus in it matched to its record, and the
 *   rest of the count is its basal, written as one row, see [Atc3BasalFact.writeBasalFact].
 * - On the cycle after, the bolus journal is read again: a bolus that ran across the end has the
 *   period closed again, up to that read.
 * - Across the pump's midnight the day's total joins the two counts.
 *
 * The running half hour keeps the rows as ordered: the loop needs insulin on board now. Arithmetic
 * only; the caller reads the pump and writes the rows.
 */
object Atc3BasalPeriod {

    /** A status read a period is counted from or to. */
    data class Mark(
        val readMs: Long,
        val counterUnits: Double,
        /** Boluses learned after this moment are the period's; see [Atc3HistorySync.bolusesLearnedAfter]. */
        val learnedAfterMs: Long
    )

    /** The period closed last, kept until the journal read of the cycle after has looked at it. */
    data class Closed(
        val start: Mark,
        val endReadMs: Long,
        /** When it was closed: a bolus learned after this that began by [endReadMs] ran across its end. */
        val closedAtMs: Long,
        /** How many times it was written, for the id of its row. */
        val writes: Int,
        /** True once the journal was read again and held no such bolus. */
        val checked: Boolean
    )

    data class State(val start: Mark? = null, val closed: Closed? = null) {

        fun encode(): String {
            val s = start?.let { "${it.readMs};${it.counterUnits};${it.learnedAfterMs}" } ?: ""
            val c = closed?.let {
                "${it.start.readMs};${it.start.counterUnits};${it.start.learnedAfterMs};${it.endReadMs};${it.closedAtMs};${it.writes};${if (it.checked) 1 else 0}"
            } ?: ""
            return "$s|$c"
        }

        companion object {

            fun decode(text: String): State {
                val parts = text.split('|')
                val s = parts.getOrNull(0).orEmpty().split(';')
                val start = markOf(s, 0)
                val c = parts.getOrNull(1).orEmpty().split(';')
                val closedStart = markOf(c, 0)
                val end = c.getOrNull(3)?.toLongOrNull()
                val at = c.getOrNull(4)?.toLongOrNull()
                val writes = c.getOrNull(5)?.toIntOrNull()
                val closed = if (closedStart != null && end != null && at != null && writes != null)
                    Closed(closedStart, end, at, writes, c.getOrNull(6) == "1")
                else null
                return State(start, closed)
            }

            private fun markOf(p: List<String>, from: Int): Mark? {
                val read = p.getOrNull(from)?.toLongOrNull() ?: return null
                val counter = p.getOrNull(from + 1)?.toDoubleOrNull() ?: return null
                val learned = p.getOrNull(from + 2)?.toLongOrNull() ?: return null
                return Mark(read, counter, learned)
            }
        }
    }

    /** What a status read comes to for the period under way. */
    sealed interface Step {

        /** Not a watershed, or the period is too short yet to be closed by itself. */
        data object Wait : Step

        /** The period ends at this read, and [units] of basal went in over it. */
        data class Close(val units: Double) : Step

        /** A watershed, but something is still owed; the next read asks again. */
        data class Postpone(val why: String) : Step

        /** There is no count to close from: the period begins anew at this read. */
        data class Restart(val why: String) : Step
    }

    /**
     * @param bolusUnits the boluses learned since the period began
     * @param settled true when the bolus journal was read now and no bolus of ours waits for its record
     * @param dayTotalUnits the daily total of the period's first day, when this read is in the next; null when not had
     * @param force true to close at this read whatever it is: a period closed again
     */
    fun step(
        start: Mark,
        readMs: Long,
        counterUnits: Double,
        bolusUnits: Double,
        settled: Boolean,
        dayTotalUnits: Double? = null,
        force: Boolean = false
    ): Step {
        if (readMs <= start.readMs) return Step.Wait
        val newDay = dayChanged(start.readMs, readMs)
        if (!force && !isDue(start.readMs, readMs)) return Step.Wait
        // More than one midnight in between: the driver was not there, and there is no period.
        if (newDay && !Atc3DayClock.sameDay(start.readMs + DAY_MS, readMs)) return Step.Restart("days_apart")
        if (!settled) return Step.Postpone("boluses")
        val counted =
            if (newDay) (dayTotalUnits ?: return Step.Postpone("day_total")) - start.counterUnits + counterUnits
            else counterUnits - start.counterUnits
        // The count holds every bolus the journal does: the rest is basal, in whole pump steps.
        return Step.Close(Math.round((counted - bolusUnits) / Atc3Protocol.DOSE_SCALE) * Atc3Protocol.DOSE_SCALE)
    }

    /** True when [readMs] ends the period begun at [startMs]: it is at or past the period's watershed. */
    fun isDue(startMs: Long, readMs: Long): Boolean = readMs >= watershedAfter(startMs)

    /** The half hour the period begun at [startMs] ends on: the next one, or the one after when the next is minutes away. */
    fun watershedAfter(startMs: Long): Long {
        val next = Atc3DayClock.halfHourOf(startMs) + Atc3DayClock.HALF_HOUR_MS
        return if (next - startMs < MIN_PERIOD_MS) next + Atc3DayClock.HALF_HOUR_MS else next
    }

    /** True when the pump's midnight lies between the two. */
    fun dayChanged(startMs: Long, readMs: Long): Boolean = !Atc3DayClock.sameDay(startMs, readMs)

    /** How long to wait before asking the pump for the read a period ends at: a little past the next half hour. */
    fun msToNextRead(nowMs: Long): Long = Atc3DayClock.halfHourOf(nowMs) + Atc3DayClock.HALF_HOUR_MS + AFTER_BOUNDARY_MS - nowMs

    /** A period shorter than this is not closed by itself. */
    private const val MIN_PERIOD_MS = 10 * 60_000L

    /** How far past the half hour the read is asked for, so that it falls after it on either clock. */
    const val AFTER_BOUNDARY_MS = 3_000L
    private const val DAY_MS = 24 * 60 * 60_000L
}
