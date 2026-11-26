package app.aaps.pump.apex.basal

import app.aaps.pump.apex.statistics.Delivered
import app.aaps.pump.apex.statistics.StatisticsDays
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexProtocol

/**
 * The period of the basal account. The pump delivers a rate in portions on a count of its own, so
 * what was ordered and what went in drift apart inside a period; what does not drift is the pump's
 * day record: its basal and temporary basal fields together are the basal that went in. So:
 *
 * - inside the period AAPS holds the rows as they were ordered;
 * - a period begins and ends only at a heartbeat whose reconciliation succeeded, see `Reconciliation`:
 *   no bolus ran, and the new records explain how the pump's numbers moved. The basal of the period
 *   is the move of the two fields since the heartbeat it began at, in whole steps of the pump;
 * - boluses first: the bolus journal is settled before the period is closed, so that a bolus row and
 *   the basal row of the same span come from the same picture;
 * - across the pump's midnight the day the period began in is found again below the new one, and
 *   each day after it counts whole, as the bolus gate does it;
 * - when the pump's numbers cannot be held against the mark, the period waits or begins anew, see [step].
 *
 * Arithmetic only; the keeper holds the period in the store and writes the rows.
 */
object BasalPeriod {

    /** The heartbeat a period is counted from: when it came, and what the pump had delivered by then. */
    class Mark(val atMs: Long, val day: ApexDailyRecord) {

        /** One line for the store: the moment, the day's key and its three fields in pump steps. */
        fun encode(): String = listOf(atMs, day.dayClockSeconds, steps(day.bolusUnits), steps(day.basalUnits), steps(day.tbrUnits)).joinToString(SEPARATOR)

        companion object {

            /** The mark in [line]; a mark of the count kind kept by an older driver is none. */
            fun decode(line: String?): Mark? {
                val parts = line?.split(SEPARATOR) ?: return null
                if (parts.size != DAY_PARTS) return null
                val numbers = parts.map { it.toLongOrNull() ?: return null }
                return Mark(numbers[0], ApexDailyRecord(0, units(numbers[2]), units(numbers[3]), units(numbers[4]), numbers[1]))
            }

            private fun steps(units: Double): Long = Math.round(units / ApexProtocol.DOSE_SCALE)
            private fun units(steps: Long): Double = steps * ApexProtocol.DOSE_SCALE
            private const val SEPARATOR = ";"
            private const val DAY_PARTS = 5
        }
    }

    /** What a heartbeat comes to for the period under way. */
    sealed interface Step {

        /** Not the heartbeat the period ends at. */
        data object Wait : Step

        /** The period ends at this heartbeat: [units] of basal went in over it, in whole steps of the pump. */
        data class Close(val units: Double) : Step

        /** The heartbeat the period ends at, but it cannot be closed yet; the next heartbeat asks again. */
        data class Postpone(val why: String) : Step

        /** There is no count to close from: the period begins anew at this heartbeat, its rows stay as ordered. */
        data class Restart(val why: String) : Step
    }

    /**
     * @param now what the pump had delivered by this heartbeat. Broken statistics: the period waits for the
     * next heartbeat. The day the period began in is gone from the answer: it begins anew, its rows stay as ordered.
     */
    fun step(start: Mark, atMs: Long, now: Delivered): Step {
        if (!isDue(start.atMs, atMs)) return Step.Wait
        if (now.broken) return Step.Postpone("days_broken")
        val from = start.day
        val days = now.days
        val at = ApexDailyRecord.indexOfKept(days, from) ?: return Step.Restart("kept_day_gone")
        val moved = StatisticsDays.counted(days.take(at)).sumOf { it.basalUnits + it.tbrUnits } + (days[at].basalUnits + days[at].tbrUnits) - (from.basalUnits + from.tbrUnits)
        return Step.Close(Math.round(moved / ApexProtocol.DOSE_SCALE) * ApexProtocol.DOSE_SCALE)
    }

    /** True when the heartbeat at [atMs] ends the period begun at [startMs]: the next heartbeat. */
    fun isDue(startMs: Long, atMs: Long): Boolean = atMs > startMs
}
