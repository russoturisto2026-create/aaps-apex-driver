package app.aaps.pump.apex.basal

import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.statistics.StatisticsDays
import kotlin.math.min

/**
 * Whether the last finished temporary basal (0x0B) has to be read at this reconciliation. The
 * status of the last reconciliation is held against the status read now:
 * - Level 1: both show the same temporary basal running (rate, mode, length, start minute). Nothing
 *   can have passed: another's would have replaced it for good. Nothing is read.
 * - Level 2a: neither shows one. Another's may have begun and ended between them, and the status shows
 *   nothing of it. The daily statistics do: a temporary basal that ran moved the temporary basal field, and
 *   any one, a zero one too, stopped the basal field. So 0x0B is read only when the temporary basal field
 *   moved, or the basal field is short of the schedule by [SHORT_STEPS] steps or more. Off the statistics
 *   (none given, see [StatisticsDays.movedSinceKept]) it is read.
 * - Level 2b: they differ. The one that ran ended, or another began; 0x0B tells how.
 * - The first reconciliation has nothing to hold against: nothing is read.
 */
object TbrGate {

    class Decision(val read: Boolean, val reason: String)

    /**
     * The basal comes in portions of one step, so the field may be a step behind the schedule at either end of
     * the span: two steps short are within that, more is a stop.
     */
    private const val SHORT_STEPS = 3

    /** [days]: how the statistics moved since the day kept; null when not known. */
    fun decide(last: ApexStatusV1?, now: ApexStatusV1, days: StatisticsDays.Moved? = null): Decision {
        if (last == null) return Decision(false, "first reconciliation")
        val before = TbrFacts.runningOf(last)
        val running = TbrFacts.runningOf(now)
        return when {
            before == null && running == null -> noneBeforeAndNow(last, now, days)
            before != null && running != null && before.sameAs(running) -> Decision(false, "the same temporary basal runs")
            else -> Decision(true, "the temporary basal changed")
        }
    }

    private fun noneBeforeAndNow(last: ApexStatusV1, now: ApexStatusV1, days: StatisticsDays.Moved?): Decision {
        if (days == null) return Decision(true, "no temporary basal before and now, the statistics do not tell: one may have passed between")
        if (days.tbrUnits > ApexProtocol.ROUNDING) return Decision(true, "no temporary basal before and now, but its field moved by ${days.tbrUnits} U: one ran between")
        val due = min(last.scheduledBasalRate, now.scheduledBasalRate) * days.ms / 3_600_000.0
        if (due - days.basalUnits >= SHORT_STEPS * ApexProtocol.DOSE_SCALE - ApexProtocol.ROUNDING)
            return Decision(true, "no temporary basal before and now, but the basal field moved ${days.basalUnits} U of $due U: one stopped it between")
        return Decision(false, "no temporary basal before and now, and the statistics show none between")
    }
}
