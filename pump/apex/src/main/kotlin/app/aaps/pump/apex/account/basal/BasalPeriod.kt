package app.aaps.pump.apex.account.basal

import app.aaps.pump.apex.deep.BasalPeriodDeep
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
 * - when the pump's numbers cannot be held against the mark, the period waits or begins anew, see
 *   [BasalPeriodDeep].
 *
 * A pump without the daily statistics tells only the count of all the insulin of its day. The
 * basal of the period is then the move of that count, less the boluses with a row in AAPS in the
 * period, in whole steps. That is the basal the pump delivered: it is never below zero, and
 * nothing of one period is taken off another. The pump's count starts over at its midnight: the
 * period then begins anew with the new day. A count that does not move as usual,
 * or bolus rows above it: see [BasalPeriodDeep].
 *
 * Arithmetic only; the keeper holds the period in the store and writes the rows.
 */
object BasalPeriod {

    /** The heartbeat a period is counted from: when it came, and what the pump had delivered by then. */
    class Mark(val atMs: Long, val delivered: Delivered) {

        /** A mark of the day record kind: the newest day of the statistics as it stood then. */
        constructor(atMs: Long, day: ApexDailyRecord) : this(atMs, Delivered.DayRecord(listOf(day)))

        /** The newest day of the statistics at this heartbeat; null for a mark of the count kind. */
        val day: ApexDailyRecord? get() = (delivered as? Delivered.DayRecord)?.days?.firstOrNull()

        /**
         * One line for the store. The day record kind: the moment, the day's key and its three fields
         * in pump steps. The count kind: the moment, the letter C, the count in pump steps and the pump's day.
         */
        fun encode(): String = when (delivered) {
            is Delivered.DayRecord -> {
                val day = day ?: error("a mark of the day record kind holds a day")
                listOf(atMs, day.dayClockSeconds, steps(day.bolusUnits), steps(day.basalUnits), steps(day.tbrUnits)).joinToString(SEPARATOR)
            }

            is Delivered.Count     -> listOf(atMs, COUNT_KIND, steps(delivered.units), delivered.pumpDay).joinToString(SEPARATOR)
        }

        companion object {

            private const val COUNT_KIND = "C"

            fun decode(line: String?): Mark? {
                val parts = line?.split(SEPARATOR) ?: return null
                if (parts.size == COUNT_PARTS && parts[1] == COUNT_KIND) {
                    val atMs = parts[0].toLongOrNull() ?: return null
                    val count = parts[2].toLongOrNull() ?: return null
                    val pumpDay = parts[3].toLongOrNull() ?: return null
                    return Mark(atMs, Delivered.Count(units(count), pumpDay))
                }
                if (parts.size != DAY_PARTS) return null
                val numbers = parts.map { it.toLongOrNull() ?: return null }
                return Mark(numbers[0], ApexDailyRecord(0, units(numbers[2]), units(numbers[3]), units(numbers[4]), numbers[1]))
            }

            private fun steps(units: Double): Long = Math.round(units / ApexProtocol.DOSE_SCALE)
            private fun units(steps: Long): Double = steps * ApexProtocol.DOSE_SCALE
            private const val SEPARATOR = ";"
            private const val DAY_PARTS = 5
            private const val COUNT_PARTS = 4
        }
    }

    /** What a heartbeat comes to for the period under way. */
    sealed interface Step {

        /** Not the heartbeat the period ends at. */
        data object Wait : Step

        /** The period ends at this heartbeat: [units] of basal went in over it, in whole steps of the pump. [overUnits]: how much the bolus rows are above the move of the count, an error of the bolus rows; nothing is done with it but the log. */
        data class Close(val units: Double, val overUnits: Double = 0.0) : Step

        /** The heartbeat the period ends at, but it cannot be closed yet; the next heartbeat asks again. */
        data class Postpone(val why: String) : Step

        /** There is no count to close from: the period begins anew at this heartbeat, its rows stay as ordered. */
        data class Restart(val why: String) : Step
    }

    /**
     * @param now what the pump had delivered by this heartbeat
     * @param bolusUnits the boluses to take off the move of the count: the rows written since the
     * period began, see [PeriodBoluses]; used only when the period is counted from the count
     */
    fun step(start: Mark, atMs: Long, now: Delivered, bolusUnits: Double = 0.0): Step {
        if (!isDue(start.atMs, atMs)) return Step.Wait
        return when (now) {
            is Delivered.DayRecord -> byDayRecord(start, now)
            is Delivered.Count     -> byCount(start, now, bolusUnits)
        }
    }

    private fun byDayRecord(start: Mark, now: Delivered.DayRecord): Step {
        BasalPeriodDeep.cannotCloseByDays(start, now)?.let { return it }
        val from = requireNotNull(start.day) { "a mark of the day record kind holds a day" }
        val days = now.days
        val at = requireNotNull(ApexDailyRecord.indexOfKept(days, from)) { "the day kept is in the answer" }
        val moved = days.take(at).sumOf { it.basalUnits + it.tbrUnits } + (days[at].basalUnits + days[at].tbrUnits) - (from.basalUnits + from.tbrUnits)
        return Step.Close(Math.round(moved / ApexProtocol.DOSE_SCALE) * ApexProtocol.DOSE_SCALE)
    }

    private fun byCount(start: Mark, now: Delivered.Count, bolusUnits: Double): Step {
        BasalPeriodDeep.countKindLost(start)?.let { return it }
        val from = start.delivered as Delivered.Count
        // The pump's count starts over at its midnight: a new period begins with the new day.
        if (now.pumpDay != from.pumpDay) return Step.Restart("pump_day_changed")
        BasalPeriodDeep.countWentBack(from, now)?.let { return it }
        val moved = Math.round((now.units - from.units) / ApexProtocol.DOSE_SCALE).coerceAtLeast(0L)
        val boluses = Math.round(bolusUnits / ApexProtocol.DOSE_SCALE)
        return Step.Close(maxOf(0L, moved - boluses) * ApexProtocol.DOSE_SCALE, maxOf(0L, boluses - moved) * ApexProtocol.DOSE_SCALE)
    }

    /** True when the heartbeat at [atMs] ends the period begun at [startMs]: the next heartbeat. */
    fun isDue(startMs: Long, atMs: Long): Boolean = atMs > startMs
}
