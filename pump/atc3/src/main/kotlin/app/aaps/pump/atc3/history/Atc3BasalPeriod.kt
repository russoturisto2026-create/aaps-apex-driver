package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.Atc3Const

/**
 * The basal of a passed half hour, as the pump delivered it.
 *
 * A temporary basal command is an order, and the pump delivers it in portions on a count of its
 * own: every command begins that count anew, the scheduled rate stands while a temporary basal
 * runs and begins anew on every half hour. So the rows AAPS holds of what was ordered and the
 * insulin that went in sit apart, a part of a portion at every change, and the parts do not come
 * back by themselves. What does not drift is the pump's own count of insulin delivered today.
 *
 * In the exact mode the driver therefore closes its books every half hour:
 *
 * - **the half hour is the watershed.** The first status read in a new half hour of the clock is
 *   the end of the period before it; the driver asks for one such read itself, see [msToNextRead].
 *   A period is what lies between two such reads, not thirty minutes to the second;
 * - **boluses first.** A period is closed only when every bolus in it is matched to its record,
 *   which is what the bolus journal is read for at the watershed. Then the rest of the count is
 *   basal: the portions that came apart from what was ordered;
 * - **the rest is the basal of the period**, written as one row at `rest / length` in place of the
 *   rows of the commands, see [Atc3HistorySync.writeBasalFact]. A passed stretch is recorded as it
 *   went, not as it was meant to go: it is what the insulin on board is worked out from;
 * - **the journal is read again on the cycle after.** A bolus given on the pump's own buttons that
 *   ran across the watershed has no record until it ends. When one turns up that was not there,
 *   the period just closed is closed again, up to that read, where the bolus is whole in the count
 *   and whole in the journal;
 * - **the pump's midnight** takes its count back to nothing, and the day that ended is in the
 *   pump's journal of daily totals: the period across midnight is counted to that total and on
 *   from nothing.
 *
 * The running half hour keeps the rows of the commands, as they were ordered: the loop needs the
 * insulin on board now, and the count of a period is only known at its end.
 *
 * Pure arithmetic over plain values. The caller reads the pump and writes the rows.
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
     * @param settled true when the bolus journal was read at this read and no bolus of ours is
     *   still waiting for its record
     * @param dayTotalUnits what the pump's journal of daily totals holds for the day the period
     *   began in, when this read is in the day after; null when it could not be had
     * @param force true to close at this read whether or not it is a watershed: a period being
     *   closed again for a bolus that ran across its end
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
        // The count holds every bolus the journal does, so what is left is the basal, in whole
        // steps of the pump.
        return Step.Close(Math.round((counted - bolusUnits) / Atc3Const.DOSE_SCALE) * Atc3Const.DOSE_SCALE)
    }

    /** True when [readMs] ends the period begun at [startMs]: it is at or past the period's watershed. */
    fun isDue(startMs: Long, readMs: Long): Boolean = readMs >= watershedAfter(startMs)

    /**
     * The half hour of the clock the period begun at [startMs] ends on: the next one, or the one
     * after when the next is only minutes away. A period of a few minutes -- the mode switched on
     * just before a half hour -- would be a rate worked out of one portion or none, so it goes on
     * to the half hour after, and ends there, not at whatever read comes once it is long enough.
     */
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
