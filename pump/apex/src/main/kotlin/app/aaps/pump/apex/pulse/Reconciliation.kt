package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.ApexConst
import app.aaps.pump.apex.aaps.AapsCarrier
import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.basal.BasalPeriodKeeper
import app.aaps.pump.apex.bolus.BolusCells
import app.aaps.pump.apex.bolus.BolusGate
import app.aaps.pump.apex.bolus.BolusReconciliation
import app.aaps.pump.apex.statistics.StatisticsDays
import app.aaps.pump.apex.store.ApexStore
import app.aaps.pump.apex.basal.TbrReconciliation
import app.aaps.pump.apex.clock.ApexClock
import app.aaps.pump.apex.keys.ApexBooleanKey
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The reconciliation: the pump's new picture against the status kept at the last one that succeeded.
 * The status kept is today's day of the daily statistics, see [BolusGate]. Every row of the day was
 * matched to it. The status is kept only when a reconciliation succeeds, and the next one compares
 * with it.
 *
 * - First, always, right after Status V1 and before anything else is read: is a bolus running, see
 *   [quietStatus]. On a running bolus there is no reconciliation: nothing more is read, nothing is
 *   settled and nothing is kept, and the next heartbeat compares with the
 *   same status kept. Nothing is read around our own bolus, see `BolusCommand`: its picture waits
 *   for this reconciliation.
 * - No status kept yet (a new link, the reference points dropped). With no bolus running,
 *   the journal is settled and the picture is the first status kept; the comparison of the day starts
 *   from it. A bolus running then: nothing is kept.
 * - Any other heartbeat: the new records of the bolus journal are settled and held against how the
 *   bolus field of the statistics moved since the status kept, see `BolusReconciliation`. No bolus runs,
 *   so every record is written: the rows must sum to the move, and a difference is a fault.
 *
 * Only a reconciliation that succeeded keeps the status, moves the basal period, see
 * [BasalPeriodKeeper], and is shown as the comparison of the day. The temporary basal part is settled
 * at every reconciliation, after the boluses, see [TbrReconciliation].
 */
@SingleIn(AppScope::class)
@Inject
class Reconciliation(
    private val aapsLogger: AAPSLogger,
    private val store: ApexStore,
    private val bolusReconciliation: BolusReconciliation,
    private val tbrReconciliation: TbrReconciliation,
    private val periodKeeper: BasalPeriodKeeper,
    private val runningBolus: RunningBolus,
    private val carrier: AapsCarrier,
    private val preferences: Preferences,
    private val trace: ApexTrace
) {

    /**
     * The first step of every reconciliation, right after Status V1 [card] is read and before anything else:
     * is a bolus running, see [RunningBolus]. Null when one runs: no reconciliation, nothing more is read
     * or kept. Else the last Status V1 the check read: the picture goes on from it.
     */
    fun quietStatus(serial: String, card: ApexPumpState.StatusCard): ApexPumpState.StatusCard? {
        val start = !hasStatusKept(serial)
        runningBolus.quietStatus(card, if (start) "the start, no status kept yet" else "the heartbeat")?.let { return it }
        aapsLogger.info(LTag.PUMP, "Apex: a bolus runs: no reconciliation, nothing more is read, the next heartbeat compares with the same status")
        trace.event(ApexTraceCat.STATE, "reconciliation", "state" to "none", "why" to "bolus_runs", "start" to start)
        return null
    }

    /**
     * What came of a reconciliation: [picture] is the one the status was kept by; [succeeded] false when nothing
     * was kept. [told]: the line drawn, told once the reconciliation is kept, see [run].
     */
    class Result(val picture: PumpPicture, val succeeded: Boolean, val told: List<AapsWrite> = emptyList())

    /**
     * Reconcile [picture]. [withPeriod] false for the start before a command, off the heartbeats: the
     * basal period waits for its heartbeat, so that its bounds stay on the heartbeats. One transaction of the
     * store: a reconciliation that breaks on an error keeps nothing in the driver's base, see [CodeFault].
     * The line drawn, its notice and the loop opened, is told only after that: a reconciliation cut short keeps
     * no line and tells none, and the next one draws it again and tells it once.
     */
    fun run(serial: String, picture: PumpPicture, offsetMs: Long, withPeriod: Boolean = true): Result {
        val result = store.transaction { settle(serial, picture, offsetMs, withPeriod) }
        result.told.forEach { carrier.carry(it, serial) }
        return result
    }

    private fun settle(serial: String, picture: PumpPicture, offsetMs: Long, withPeriod: Boolean): Result {
        // The statistics are what everything is held against, and they show every step the motor gave today:
        // an answer with no day is not what the pump holds. Nothing is settled, the next heartbeat reads again.
        if (picture.days.answer.entries.isEmpty()) return notReconciled(picture, "statistics_no_day")
        // Before the status is kept below: the refill is told once, whether the journal settles or not.
        val refill = refill(serial, picture)
        if (picture.start) dropClockLogAhead(serial)
        val checked = picture.boluses?.let {
            bolusReconciliation.settle(serial, it.answer, picture.days.answer, offsetMs, picture.readAtMs, comparedAtMs = picture.heartbeatAtMs, allDays = picture.start)
        }
        tbrReconciliation.settle(serial, picture.status, picture.tbr, offsetMs, ApexClock.minuteStart(picture.heartbeatAtMs))
        if (picture.boluses != null && checked == null) return notReconciled(picture, "journal_not_settled", refill)
        keep(serial, picture)
        if (withPeriod) periodKeeper.onHeartbeat(serial, picture)
        trace.event(ApexTraceCat.STATE, "reconciliation", "state" to "succeeded", "at" to picture.heartbeatAtMs)
        return Result(picture, succeeded = true, told = refill + checked?.told.orEmpty())
    }

    /** At the start: no line of the clock log may have counted more bolus records than the driver, see [ApexClock.dropLogAhead]. */
    private fun dropClockLogAhead(serial: String) {
        val topSeq = BolusCells(store, serial).topSeq()
        val dropped = ApexClock.dropLogAhead(store, serial, topSeq)
        if (dropped == 0) return
        aapsLogger.warn(LTag.PUMP, "Apex: $dropped line(s) of the clock log counted more than $topSeq bolus records, they are dropped")
        trace.event(ApexTraceCat.STATE, "clock_log_ahead", "dropped" to dropped, "topSeq" to topSeq)
    }

    private fun notReconciled(picture: PumpPicture, why: String, told: List<AapsWrite> = emptyList()): Result {
        aapsLogger.info(LTag.PUMP, "Apex: no reconciliation at the heartbeat of ${picture.heartbeatAtMs}, $why: the status is not kept, the next one compares with the same status")
        trace.event(ApexTraceCat.STATE, "reconciliation", "state" to "none", "why" to why, "at" to picture.heartbeatAtMs)
        return Result(picture, succeeded = false, told = told)
    }

    /**
     * The insulin change, when the reservoir grew by [ApexConst.REFILL_MIN_UNITS] or more since the status kept.
     * The pump only takes insulin out, and a refill is done on the pump alone: the growth is all the driver sees
     * of it, at this heartbeat. Only when the user turned it on, see [ApexBooleanKey.RefillEvent].
     */
    private fun refill(serial: String, picture: PumpPicture): List<AapsWrite> {
        if (!preferences.get(ApexBooleanKey.RefillEvent)) return emptyList()
        val before = TbrReconciliation.lastOf(store, serial)?.status?.reservoirUnits ?: return emptyList()
        val now = picture.status.status.reservoirUnits
        if (now - before < ApexConst.REFILL_MIN_UNITS) return emptyList()
        return listOf(AapsWrite.Refill(picture.heartbeatAtMs, before, now))
    }

    /** True when a status is kept to compare with: a day of the statistics. */
    fun hasStatusKept(serial: String): Boolean = StatisticsDays(store, serial).active() != null

    /** The status kept for the next reconciliation: today's day of [picture]. */
    private fun keep(serial: String, picture: PumpPicture) = StatisticsDays(store, serial).keepReconciled(picture.days.answer, picture.readAtMs, closedToo = picture.start)
}
