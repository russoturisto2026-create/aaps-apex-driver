package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.account.basal.BasalPeriodKeeper
import app.aaps.pump.apex.account.basal.Delivered
import app.aaps.pump.apex.account.bolus.BolusAccount
import app.aaps.pump.apex.account.bolus.BolusCountMark
import app.aaps.pump.apex.account.bolus.BolusJournal
import app.aaps.pump.apex.account.bolus.BolusSpanEnd
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.tbr.TbrJournal
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.deep.RunningBolusDeep
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The reconciliation: the pump's new picture against the status kept at the last one that succeeded.
 * The status kept holds the pump's totals of the day: the day of the daily statistics, or the pump's
 * count on a pump without them, see [BolusGate] and [BolusCountMark]. Every row of the day was matched
 * to these totals. The status is kept only when a reconciliation succeeds, and the next one compares
 * with it.
 *
 * - First, always, right after Status V1 and before anything else is read: is a bolus running, see
 *   [quietStatus]. On a running bolus there is no reconciliation: nothing more is read, nothing is
 *   settled and nothing is kept, and the next heartbeat compares with the
 *   same status kept. After our own bolus only its record is settled, see `BolusCommand`; the
 *   reconciliation is the heartbeat's.
 * - The start: no status is kept yet (a new link, the pump's objects found again otherwise). With no
 *   bolus running, the journal is settled and the picture is the first status kept; the comparison of
 *   the day starts from it. A bolus running at the start: no start.
 * - Any other heartbeat: the new records of the bolus journal are settled and held against how the
 *   pump's number moved since the status kept, see `BolusReconcileDeep`. They explain it: the
 *   reconciliation succeeded. They do not, and no bolus runs: a record may follow the end of a bolus by
 *   a moment, so the journal is read once more, then the pump's numbers, and what the records still
 *   leave is taken as it is.
 *
 * Only a reconciliation that succeeded keeps the status, moves the basal period, see
 * [BasalPeriodKeeper], and is shown as the comparison of the day. The temporary basal part is settled
 * at every reconciliation, before the boluses ask the pump's count, see [TbrJournal].
 */
@SingleIn(AppScope::class)
@Inject
class Reconciliation(
    private val aapsLogger: AAPSLogger,
    private val store: ApexStore,
    private val reader: PictureReader,
    private val bolusJournal: BolusJournal,
    private val tbrJournal: TbrJournal,
    private val periodKeeper: BasalPeriodKeeper,
    private val runningBolus: RunningBolusDeep,
    private val trace: ApexTrace
) {

    /**
     * The first step of every reconciliation, right after Status V1 [card] is read and before anything else:
     * is a bolus running, see [RunningBolusDeep]. Null when one runs: no reconciliation, nothing more is read
     * or kept. Else the last Status V1 the check read: the picture goes on from it.
     */
    fun quietStatus(serial: String, card: ApexPumpState.StatusCard, dayRecord: Boolean): ApexPumpState.StatusCard? {
        val start = !hasStatusKept(serial, dayRecord)
        runningBolus.quietStatus(card, if (start) "the start, no status kept yet" else "the heartbeat")?.let { return it }
        aapsLogger.info(LTag.PUMP, "Apex: a bolus runs: no reconciliation, nothing more is read, the next heartbeat compares with the same status")
        trace.event(ApexTraceCat.STATE, "reconciliation", "state" to "none", "why" to "bolus_runs", "start" to start)
        return null
    }

    /** What came of a reconciliation: [picture] is the one the status was kept by; [succeeded] false when nothing was kept. */
    class Result(val picture: PumpPicture, val succeeded: Boolean)

    /**
     * Reconcile [picture]. [withPeriod] false for the start before a command, off the heartbeats: the
     * basal period waits for its heartbeat, so that its bounds stay on the heartbeats.
     */
    fun run(serial: String, picture: PumpPicture, offsetMs: Long, withPeriod: Boolean = true): Result {
        var tbrSettled = false
        val settleTbr = {
            if (!tbrSettled) {
                tbrSettled = true
                tbrJournal.settle(serial, picture.status, picture.tbr, offsetMs, ApexClock.minuteStart(picture.heartbeatAtMs))
            }
            true
        }
        val checked = picture.boluses?.let {
            bolusJournal.settle(serial, it.answer, spanEnd(picture), offsetMs, picture.readAtMs, comparedAtMs = picture.heartbeatAtMs, closeBasal = settleTbr)
        }
        settleTbr()
        if (picture.boluses != null && checked == null) return notReconciled(picture, "journal_not_settled")
        val kept = if (checked?.offMove == true) readAgain(serial, picture, offsetMs, settleTbr) ?: return notReconciled(picture, "not_answered") else picture
        keep(serial, kept)
        if (withPeriod) periodKeeper.onHeartbeat(serial, kept)
        trace.event(ApexTraceCat.STATE, "reconciliation", "state" to "succeeded", "at" to picture.heartbeatAtMs)
        return Result(kept, succeeded = true)
    }

    /**
     * The records do not explain the move and no bolus runs: the journal read once more, then the pump's
     * numbers, so that they count every record the journal showed; the journal is settled against them and
     * what it leaves is taken as it is. The picture with the numbers read anew, or null when the pump did
     * not answer or the journal was not settled.
     */
    private fun readAgain(serial: String, picture: PumpPicture, offsetMs: Long, settleTbr: () -> Boolean): PumpPicture? {
        val answer = bolusJournal.readJournalAgain(serial, offsetMs) ?: return null
        val fresh = reader.refresh(picture) ?: return null
        bolusJournal.settle(serial, answer, spanEnd(fresh), offsetMs, fresh.readAtMs, comparedAtMs = picture.heartbeatAtMs, closeBasal = settleTbr, readAgain = true) ?: return null
        return fresh
    }

    private fun notReconciled(picture: PumpPicture, why: String): Result {
        aapsLogger.info(LTag.PUMP, "Apex: no reconciliation at the heartbeat of ${picture.heartbeatAtMs}, $why: nothing is kept, the next one compares with the same status")
        trace.event(ApexTraceCat.STATE, "reconciliation", "state" to "none", "why" to why, "at" to picture.heartbeatAtMs)
        return Result(picture, succeeded = false)
    }

    /** True when a status is kept to compare with: the day of the statistics when the pump gives them, [dayRecord], else the count. */
    fun hasStatusKept(serial: String, dayRecord: Boolean): Boolean =
        if (dayRecord) BolusGate(store, serial).hasDayKept() else BolusCountMark(store, serial).get() != null

    /**
     * The status kept for the next reconciliation: today's day of [picture], or its count on a pump without
     * the statistics. It counts every bolus settled so far, so the account's list of them starts over.
     */
    private fun keep(serial: String, picture: PumpPicture) {
        store.transaction {
            val days = picture.days
            if (days != null) BolusGate(store, serial).keep(days, picture.readAtMs)
            else {
                BolusAccount(store, serial).clearSettledSinceHeartbeat()
                (spanEnd(picture) as? BolusSpanEnd.Count)?.let { BolusCountMark(store, serial).move(it) }
            }
        }
    }

    companion object {

        /**
         * What the pump gave at [picture] to tell how much went in as boluses over the span: the daily
         * statistics, or, on a pump without them, the count of Status V1.
         */
        fun spanEnd(picture: PumpPicture): BolusSpanEnd? =
            picture.days?.let { BolusSpanEnd.DayRecord(it.answer) }
                ?: (picture.delivered() as? Delivered.Count)?.let { BolusSpanEnd.Count(picture.heartbeatAtMs, it, picture.status.reservoirUnits) }
    }
}
