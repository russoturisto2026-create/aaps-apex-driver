package app.aaps.pump.apex.account.basal

import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexProtocol

/**
 * The period of the basal account. The pump delivers a rate in portions on a count of its own, so
 * what was ordered and what went in drift apart inside a period; what does not drift is the pump's
 * day record: its basal and temporary basal fields together are the basal that went in. So:
 *
 * - inside the period AAPS holds the rows as they were ordered;
 * - the period is closed at a heartbeat, by the day record read at it, see [isDue] for which
 *   heartbeat; the basal of the period is the move of the two fields since the heartbeat it began
 *   at, in whole steps of the pump;
 * - boluses first: when the bolus journal was read at the heartbeat, it is settled before the
 *   period is closed, so that a bolus row and the basal row of the same span come from the same
 *   picture. The fields themselves do not depend on the bolus: a heartbeat that comes while our
 *   bolus runs closes the period like any other;
 * - across the pump's midnight the day the period began in is found again below the new one, and
 *   each day after it counts whole, as the bolus gate does it;
 * - a day the period began in that is not in the answer any more, or went back below what it
 *   held, is a day the pump began over: there is no count to close from, the period begins anew
 *   and its rows stay as ordered.
 *
 * A pump without the daily statistics tells only the count of all the insulin of its day. The
 * basal of the period is then the move of that count, less the boluses with a row in AAPS in the
 * period, in whole steps. That is the basal the pump delivered: it is never below zero, and
 * nothing of one period is taken off another. Boluses above the move of the count cannot be: the
 * bolus rows are wrong then, the period gets no basal and the error is logged. There is no count
 * to close from when the pump's
 * day changed (the count started over) or the count went back. A heartbeat that comes while our
 * bolus runs neither closes nor begins such a period: the count holds a part of the bolus and its
 * row is not written yet.
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
     * @param settled false when the bolus journal of this heartbeat was read and not settled
     * @param duringBolus true when this heartbeat came while our bolus runs: the count then holds a part
     * of the bolus and its row is not written yet, so a period counted from the count is not closed at it
     * @param bolusUnits the boluses to take off the move of the count: the rows written since the
     * period began, see [PeriodBoluses]; used only when the period is counted from the count
     */
    fun step(start: Mark, mode: ExactBasalMode, atMs: Long, now: Delivered, settled: Boolean, duringBolus: Boolean = false, bolusUnits: Double = 0.0): Step {
        if (!isDue(mode, start.atMs, atMs)) return Step.Wait
        if (!settled) return Step.Postpone("boluses")
        return when (now) {
            is Delivered.DayRecord -> byDayRecord(start, now)
            is Delivered.Count     -> if (duringBolus) Step.Postpone("bolus_runs") else byCount(start, now, bolusUnits)
        }
    }

    private fun byDayRecord(start: Mark, now: Delivered.DayRecord): Step {
        val from = start.day ?: return Step.Restart("count_kind_changed")
        if (now.broken) return Step.Postpone("days_broken")
        val days = now.days
        val at = ApexDailyRecord.indexOfKept(days, from) ?: return Step.Restart("kept_day_gone")
        val moved = days.take(at).sumOf { it.basalUnits + it.tbrUnits } + (days[at].basalUnits + days[at].tbrUnits) - (from.basalUnits + from.tbrUnits)
        return Step.Close(Math.round(moved / ApexProtocol.DOSE_SCALE) * ApexProtocol.DOSE_SCALE)
    }

    private fun byCount(start: Mark, now: Delivered.Count, bolusUnits: Double): Step {
        val from = start.delivered as? Delivered.Count ?: return Step.Restart("day_record_kind_changed")
        if (now.pumpDay != from.pumpDay) return Step.Restart("pump_day_changed")
        if (now.units < from.units - ApexProtocol.DOSE_SCALE / 2) return Step.Restart("count_went_back")
        val moved = Math.round((now.units - from.units) / ApexProtocol.DOSE_SCALE).coerceAtLeast(0L)
        val boluses = Math.round(bolusUnits / ApexProtocol.DOSE_SCALE)
        return Step.Close(maxOf(0L, moved - boluses) * ApexProtocol.DOSE_SCALE, maxOf(0L, boluses - moved) * ApexProtocol.DOSE_SCALE)
    }

    /**
     * Why a period cannot begin at this heartbeat, or null when it can. A period of the count kind
     * takes the boluses written during it off the move of the pump's count. So it begins only at a
     * count that holds no bolus still to be written: not while our bolus runs, and not while the
     * bolus journal of this heartbeat is not settled. Else a bolus given before the period would be
     * taken off the basal of the periods after it.
     */
    fun cannotBegin(now: Delivered, settled: Boolean, duringBolus: Boolean): String? = when {
        now !is Delivered.Count -> null
        duringBolus             -> "bolus_runs"
        !settled                -> "journal_unsettled"
        else                    -> null
    }

    /** True when the heartbeat at [atMs] ends the period begun at [startMs]: in the exact mode, the next heartbeat. */
    fun isDue(mode: ExactBasalMode, startMs: Long, atMs: Long): Boolean = when (mode) {
        ExactBasalMode.OFF       -> false
        ExactBasalMode.HEARTBEAT -> atMs > startMs
    }
}
