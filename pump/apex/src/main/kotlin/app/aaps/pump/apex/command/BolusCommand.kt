package app.aaps.pump.apex.command

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusSize
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.apex.ApexPumpClock
import app.aaps.pump.apex.R
import app.aaps.pump.apex.account.bolus.BolusAccount
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.bolus.BolusJournal
import app.aaps.pump.apex.account.store.Action
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.deep.BrokenRecordDeep
import app.aaps.pump.apex.deep.PlausibleRecordDeep
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.pulse.HeartbeatTick
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import app.aaps.pump.apex.deep.ReadAgainDeep
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlin.math.abs

/**
 * A bolus from the order to the pump's record of it, all in one turn on the queue. The order is
 * kept as an open action with the place the journal stood at, see [BolusAccount.commandAccepted].
 * The row in AAPS comes from the pump's record, never from the order, and only when the record
 * confirms the bolus.
 *
 * 1. Just before the command, the top of the bolus journal is read once: the last record alone when the
 *    probe at the link found it (`FirmwareDiagnosis.lastBolusRecord`), else the journal of ten records.
 *    A read that goes wrong is read again by the one rule, see [ReadAgainDeep]. The top is the top of the
 *    copy kept, or not. No sound answer: the bolus is not sent, it ends as a command not carried out.
 *    Three reads that brought nothing are a pump that does not work, see [ReadAgainDeep]: nothing more
 *    is asked of it until it comes back.
 * 2. The bolus: a state of the whole driver, see [ApexBolusDelivery]: nothing is asked of the pump in
 *    it, neither a reconciliation nor a journal. It ends with the completion frame, the accepted cancel,
 *    or the end by the ordered dose (see [BolusTiming]) and five seconds more.
 * 3. After it, the top is read again the same way, up to [AFTER_READS] times, until it shows a new
 *    record: the pump may write the record a moment after the bolus ends. The top before was the top of
 *    the copy, and the new top lies right on it: it confirms our bolus by itself, see
 *    [BolusJournal.settleOurLast]. Otherwise the journal of ten records is settled as it is, with no
 *    statistics and no count, see [BolusJournal.settle]: every sound new record gets its row, ours and
 *    others'. No new record after these reads, or no sound answer: nothing more is read, the note of our
 *    bolus stays, and the heartbeat finds the record and settles it.
 * 4. Records that are not sound (a faulty cell, an answer that does not lay over the copy) are not
 *    written: the deep look is the heartbeat's.
 *
 * After our bolus nothing else is reconciled and nothing else is read: no status, no statistics, no
 * temporary basal. A heartbeat noted during the bolus is answered as a heartbeat once the bolus is
 * settled. A pump that answers the order with silence shows by its progress frames whether it took it.
 */
@SingleIn(AppScope::class)
@Inject
class BolusCommand(
    private val aapsLogger: AAPSLogger,
    private val bolusProgressData: BolusProgressData,
    private val rh: ResourceHelper,
    private val manager: ApexManager,
    private val delivery: ApexBolusDelivery,
    private val store: ApexStore,
    private val pumpClock: ApexPumpClock,
    private val pumpState: ApexPumpState,
    private val journal: BolusJournal,
    private val heartbeatTick: HeartbeatTick,
    private val trace: ApexTrace
) {

    /** What the command reports: [success] false with [enacted] true is a bolus that fell short. */
    class Result(val success: Boolean, val enacted: Boolean, val deliveredUnits: Double, val comment: String?)

    suspend fun deliver(info: DetailedBolusInfo, serial: String): Result {
        val asked = info.insulin
        val units = PumpType.APEX.determineCorrectBolusSize(asked)
        if (abs(units - asked) > 1e-9) {
            aapsLogger.warn(LTag.PUMP, "Apex: a bolus of $asked U is not on the pump's step, delivering $units U")
            info.insulin = units
        }
        if (units <= 0.0) return Result(false, false, 0.0, rh.gs(R.string.apex_bolus_zero))
        val type = info.bolusType
        val account = BolusAccount(store, serial)
        var action: Action? = null
        var heartbeatDuringBolus: Long? = null
        // The top of the journal just before the command: the short way is open when it is the top of the copy kept.
        val shortRecord = pumpState.diagnosis?.lastBolusRecord == true
        val before = readTop(serial, shortRecord) ?: return notSent(units)
        val onTop = before.entries.firstOrNull()?.let { JournalSnapshot(store, serial).topIs(it.frame) } == true
        aapsLogger.info(LTag.PUMP, "Apex: bolus of $units U, the top of the journal ${if (onTop) "is" else "is not"} the top of the copy kept")

        // Settings not read yet: the slow speed, the longer wait, is the safe one.
        val slow = pumpState.settings?.lowBolusSpeed != false
        val outcome = delivery.bolus(
                units = units,
                durationMs = BolusTiming.durationMs(units, slow),
                onAccepted = { at ->
                    action = account.commandAccepted(at, units, type.name)
                    trace.event(ApexTraceCat.STATE, "bolus_accepted", "order" to at, "units" to units, "type" to type.name)
                },
                onProgress = { delivered ->
                    bolusProgressData.updateProgress(PumpInsulin(delivered))
                    action?.let { account.progress(it, delivered) }
                },
                onHeartbeat = { at ->
                    heartbeatDuringBolus = at
                    aapsLogger.info(LTag.PUMP, "Apex: heartbeat during our bolus: no reconciliation while it runs, it is answered when the bolus ends")
                }
        )
        val order = action
        if (outcome !is ApexBolusOutcome.Delivered || order == null) {
            val comment = if (outcome is ApexBolusOutcome.Refused) rh.gs(R.string.apex_refused_by_pump) else rh.gs(R.string.apex_bolus_failed)
            aapsLogger.error(LTag.PUMP, "Apex: bolus of $units U not started, $comment")
            trace.event(ApexTraceCat.STATE, "bolus_not_started", "units" to units, "outcome" to outcome.toString())
            // A heartbeat that came while the command was sent is answered as a heartbeat.
            (heartbeatDuringBolus ?: delivery.takeNotedHeartbeat())?.let { heartbeatTick.run(it) }
            return Result(false, false, 0.0, comment)
        }
        account.finished(order, outcome.reportedUnits)

        val settled = settleAfter(serial, units, before, onTop, shortRecord)
        val answered = heartbeatDuringBolus?.let { heartbeatTick.run(it) } ?: true
        val delivered = outcome.reportedUnits
        trace.event(ApexTraceCat.STATE, "bolus_done", "order" to order.acceptedAtMs, "units" to delivered, "completed" to outcome.completed, "cancelled" to outcome.cancelled, "on_top" to onTop, "settled" to settled, "answered" to answered)

        val short = units - delivered >= ApexProtocol.DOSE_SCALE
        if (short && !outcome.cancelled) {
            aapsLogger.error(LTag.PUMP, "Apex: the pump delivered $delivered U of the $units U ordered")
            return Result(false, true, delivered, rh.gs(R.string.apex_bolus_short, delivered, units))
        }
        return Result(true, true, delivered, null)
    }

    /** The read before the bolus brought nothing sound: no command goes to the pump. */
    private fun notSent(units: Double): Result {
        aapsLogger.error(LTag.PUMP, "Apex: bolus of $units U not sent: the read before it brought nothing sound")
        trace.event(ApexTraceCat.STATE, "bolus_not_started", "units" to units, "outcome" to "no_read_before")
        return Result(false, false, 0.0, rh.gs(R.string.apex_bolus_not_sent))
    }

    /**
     * The top of the bolus journal, read by the one rule of reading again, see [ReadAgainDeep]: the last
     * record alone when [shortRecord], what the probe at the link found, else the journal of ten records.
     * Null when no sound answer came.
     */
    private fun readTop(serial: String, shortRecord: Boolean): ApexJournalAnswer<ApexBolusRecord>? {
        val kept = store.snapshot(serial, SnapshotKind.BOLUS)?.frames
        val offsetMs = ApexClock.offsetMs(store, serial, pumpClock.phoneOffsetMs())
        val bounds = PlausibleRecordDeep.now(store, serial, pumpState)
        val read = if (shortRecord) manager::readLastBolusRecord else manager::readBolusHistory
        return ReadAgainDeep.journal(aapsLogger, if (shortRecord) "last bolus record" else "bolus journal", read, manager::pumpNotWorking) { BrokenRecordDeep.brokenBolus(it, kept, bounds, offsetMs) }?.answer
    }

    /**
     * After the bolus: the top read again until it shows a record newer than the top [before] the bolus,
     * up to [AFTER_READS] times. [onTop], the top before was the top of the copy, and the new top lies
     * right on it (alone, or with the copy's top below it in the journal): our bolus is confirmed by it
     * alone. Otherwise the journal of ten records is settled as it is. False when nothing was settled:
     * the heartbeat settles it.
     */
    private fun settleAfter(serial: String, units: Double, before: ApexJournalAnswer<ApexBolusRecord>, onTop: Boolean, shortRecord: Boolean): Boolean {
        val after = readNewTop(serial, units, before, shortRecord) ?: return false
        val offsetMs = ApexClock.offsetMs(store, serial, pumpClock.phoneOffsetMs())
        val top = after.entries.firstOrNull()
        val copy = JournalSnapshot(store, serial)
        val rightOnTop = top != null && !copy.topIs(top.frame) && (after.entries.size == 1 || copy.topIs(after.entries[1].frame))
        if (onTop && rightOnTop && journal.settleOurLast(serial, ApexJournalAnswer(listOf(top), 1), offsetMs, System.currentTimeMillis())) return true
        val answer = (if (shortRecord) readTop(serial, shortRecord = false) else after) ?: return false.also {
            aapsLogger.warn(LTag.PUMP, "Apex: the journal after the bolus of $units U did not answer, the heartbeat settles it")
        }
        val settled = journal.settle(serial, answer, end = null, offsetMs, System.currentTimeMillis(), deep = false) != null
        if (!settled) aapsLogger.warn(LTag.PUMP, "Apex: the journal after the bolus of $units U holds records that are not sound, the heartbeat looks at them")
        return settled
    }

    /**
     * The top read after the bolus until it shows a record newer than the top [before] it, up to
     * [AFTER_READS] reads. Null when no read showed one, or no sound answer came: the note of our bolus
     * stays, and the heartbeat finds its record.
     */
    private fun readNewTop(serial: String, units: Double, before: ApexJournalAnswer<ApexBolusRecord>, shortRecord: Boolean): ApexJournalAnswer<ApexBolusRecord>? {
        val topBefore = before.entries.firstOrNull()?.frame?.recordIdentity()
        for (read in 1..AFTER_READS) {
            val after = readTop(serial, shortRecord) ?: return null.also {
                aapsLogger.warn(LTag.PUMP, "Apex: the read after the bolus of $units U brought nothing sound, nothing more is read, the heartbeat settles it")
            }
            val top = after.entries.firstOrNull()?.frame?.recordIdentity()
            if (top != null && (topBefore == null || !top.contentEquals(topBefore))) return after
            aapsLogger.info(LTag.PUMP, "Apex: no new bolus record after the bolus of $units U, read $read of $AFTER_READS")
        }
        aapsLogger.warn(LTag.PUMP, "Apex: no new bolus record after $AFTER_READS reads, the note of the bolus of $units U stays, the heartbeat finds its record")
        trace.event(ApexTraceCat.STATE, "bolus_record_late", "units" to units)
        return null
    }

    private companion object {

        /** Reads of the top after our bolus while it shows no new record: the pump may write it a moment after the bolus ends. */
        const val AFTER_READS = 3
    }
}
