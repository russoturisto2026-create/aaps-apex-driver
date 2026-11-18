package app.aaps.pump.apex.deep

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.NoticeKind
import app.aaps.pump.apex.account.basal.BasalPeriod
import app.aaps.pump.apex.account.basal.Delivered
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat

/**
 * The basal period when the pump's numbers do not let it close as usual, see [BasalPeriod].
 *
 * The problem. A period is closed by the move of the pump's own numbers since the heartbeat it
 * began at: the basal fields of the daily statistics, or, on a pump without them, the count of the
 * day less our bolus rows. A bolus that runs never reaches a period: there is no reconciliation then,
 * and a period begins and ends only at one that succeeded, see `Reconciliation`. Some things still
 * break that count:
 * - Bolus rows above the move of the count: the rows are wrong, the basal cannot be told.
 * - A count that holds more than the records explain, after the journal is read again: what is left is basal, but
 *   basal cannot be more than the pump's maximum basal gives.
 * - The pump's numbers cannot be held against the mark: the daily statistics came broken, the day
 *   the period began in is gone from the answer, the count went back, or the pump changed the kind
 *   of numbers it gives (its objects were found again otherwise).
 *
 * When it applies. At the heartbeat that closes a period, in these cases. A period whose numbers move
 * as usual never comes here.
 *
 * The way out.
 * - Bolus rows above the count: the period gets no basal, nothing is taken off the next one, the
 *   error is logged.
 * - The basal by the count more than the pump's maximum basal can give over the
 *   period: basal cannot be more, so the rest is insulin no record explains. The basal row gets what
 *   the maximum basal gives, the rest is written as a bolus in the middle of the period, and the user
 *   is told, see [capAtMaxBasal].
 * - Broken statistics: the period waits for the next heartbeat. No number to hold the mark against:
 *   the period begins anew, and its rows stay as ordered.
 */
class BasalPeriodDeep(
    private val aapsLogger: AAPSLogger,
    private val trace: ApexTrace
) {

    /** What [capAtMaxBasal] leaves: [basalUnits] for the basal row, and [writes], the bolus row and the notice for the rest. */
    class Capped(val basalUnits: Double, val writes: List<AapsWrite>)

    /**
     * The basal of a period by the count from [startMs] to [endMs], [units], held against what the pump's
     * maximum basal, [maxBasalPerHour] U/h, gives over it, in whole steps of the pump. Not above it: all
     * of it is basal. Above it: the basal row gets that much, the rest is a bolus row in the middle of the
     * period and a notice. With no maximum known, all of it is basal.
     */
    fun capAtMaxBasal(startMs: Long, endMs: Long, units: Double, maxBasalPerHour: Double?): Capped {
        if (maxBasalPerHour == null || maxBasalPerHour <= 0.0) return Capped(units, emptyList())
        val boundSteps = Math.round(maxBasalPerHour * (endMs - startMs) / ApexClock.HOUR_MS / ApexProtocol.DOSE_SCALE)
        val unitsSteps = Math.round(units / ApexProtocol.DOSE_SCALE)
        if (unitsSteps <= boundSteps) return Capped(units, emptyList())
        val bound = boundSteps * ApexProtocol.DOSE_SCALE
        val rest = (unitsSteps - boundSteps) * ApexProtocol.DOSE_SCALE
        val middleMs = startMs + (endMs - startMs) / 2
        aapsLogger.error(LTag.PUMP, "Apex: the basal period from $startMs to $endMs is $units U by the count, the maximum basal $maxBasalPerHour U/h gives $bound U: $bound U is basal, $rest U is written as a bolus at $middleMs, the user is told")
        trace.event(ApexTraceCat.STATE, "basal_period_above_max", "from" to startMs, "to" to endMs, "units" to units, "bound" to bound, "rest" to rest)
        return Capped(
            bound,
            listOf(
                AapsWrite.Bolus(endMs * ABOVE_MAX_ID_FACTOR + 1, middleMs, rest, AapsWrite.NORMAL),
                AapsWrite.Notice(NoticeKind.BASAL_ABOVE_MAX, units = rest, atMs = middleMs)
            )
        )
    }

    /** The bolus rows of the period from [startMs] to [endMs] are [overUnits] above the move of the pump's count: logged, nothing else. */
    fun bolusesOverCount(startMs: Long, endMs: Long, overUnits: Double) {
        if (overUnits <= 0.0) return
        aapsLogger.error(LTag.PUMP, "Apex: the bolus rows of the period from $startMs to $endMs are $overUnits U above the move of the pump's count: the bolus rows are wrong, the period gets no basal, nothing is taken off the next one")
        trace.event(ApexTraceCat.STATE, "basal_period_boluses_over_count", "from" to startMs, "to" to endMs, "over" to overUnits)
    }

    companion object {

        /**
         * The row number of the bolus for the rest above the maximum basal is the period's end times this,
         * plus one: far above the numbers of the journal's rows, which stand on the moment of the first row.
         */
        private const val ABOVE_MAX_ID_FACTOR = 10L

        /**
         * Why a period begun at [start] cannot be closed by the daily statistics [now], or null when it can:
         * the mark is of the count kind, the statistics came broken, or the day kept is gone from them.
         */
        fun cannotCloseByDays(start: BasalPeriod.Mark, now: Delivered.DayRecord): BasalPeriod.Step? {
            val from = start.day ?: return BasalPeriod.Step.Restart("count_kind_changed")
            if (now.broken) return BasalPeriod.Step.Postpone("days_broken")
            if (ApexDailyRecord.indexOfKept(now.days, from) == null) return BasalPeriod.Step.Restart("kept_day_gone")
            return null
        }

        /** Why a period begun at [start] cannot be closed by the pump's count: the mark is of the day record kind. Null when it can. */
        fun countKindLost(start: BasalPeriod.Mark): BasalPeriod.Step? =
            if (start.delivered is Delivered.Count) null else BasalPeriod.Step.Restart("day_record_kind_changed")

        /** Why the count [now] cannot be held against the mark [from] of the same pump day: it went back. Null when it can. */
        fun countWentBack(from: Delivered.Count, now: Delivered.Count): BasalPeriod.Step? =
            if (now.units < from.units - ApexProtocol.DOSE_SCALE / 2) BasalPeriod.Step.Restart("count_went_back") else null
    }
}
