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
import app.aaps.pump.apex.account.basal.Delivered
import app.aaps.pump.apex.account.bolus.BolusAccount
import app.aaps.pump.apex.account.bolus.BolusCountMark
import app.aaps.pump.apex.account.bolus.BolusJournal
import app.aaps.pump.apex.account.bolus.BolusSpanEnd
import app.aaps.pump.apex.account.store.Action
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.deep.BrokenRecordDeep
import app.aaps.pump.apex.deep.PlausibleRecordDeep
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.pulse.BolusGate
import app.aaps.pump.apex.pulse.HeartbeatTick
import app.aaps.pump.apex.pulse.JournalRead
import app.aaps.pump.apex.state.ApexAbilities
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlin.math.abs

/**
 * A bolus from the order to the pump's record of it, all in one turn on the queue. The order is
 * kept as an open action with the place the journal stood at, see [BolusAccount.commandAccepted];
 * the pump's progress frames are followed to its completion frame or the cancel; then the bolus
 * journal is read until the record is on top, and after it the daily statistics when the pump has
 * them, see [ApexAbilities], else the status with the pump's count. The answer goes
 * through the same settling as every journal answer, see [BolusJournal]: the row in AAPS comes from
 * the record, never from the order, and is held against the move of the bolus field. The day read
 * here is kept as the start of the next span, as a heartbeat keeps its own. A record that is not there yet leaves the action open for the next
 * heartbeat's answer. A pump that answers the order with silence shows by its progress frames
 * whether it took it. A heartbeat that comes while the bolus runs is answered between its progress
 * frames, see [HeartbeatTick.runDuringBolus]: the basal and temporary basal part of the picture
 * on time, the bolus part by the record when the bolus ends.
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

        val outcome = delivery.bolus(
                units = units,
                onAccepted = { at ->
                    action = account.commandAccepted(at, units, type.name)
                    trace.event(ApexTraceCat.STATE, "bolus_accepted", "order" to at, "units" to units, "type" to type.name)
                },
                onProgress = { delivered ->
                    bolusProgressData.updateProgress(PumpInsulin(delivered))
                    action?.let { account.progress(it, delivered) }
                },
                onHeartbeat = { at -> heartbeatTick.runDuringBolus(at) }
        )
        val order = action
        if (outcome !is ApexBolusOutcome.Delivered || order == null) {
            val comment = if (outcome is ApexBolusOutcome.Refused) rh.gs(R.string.apex_refused_by_pump) else rh.gs(R.string.apex_bolus_failed)
            aapsLogger.error(LTag.PUMP, "Apex: bolus of $units U not started, $comment")
            trace.event(ApexTraceCat.STATE, "bolus_not_started", "units" to units, "outcome" to outcome.toString())
            return Result(false, false, 0.0, comment)
        }
        account.finished(order, outcome.reportedUnits, outcome.completed)

        // The journal first, then the second word on what went in: the daily statistics when the pump has
        // them, else the status with the pump's count. Read after the journal, it counts every record the
        // journal shows. Each read keeps the exchange's gap from the pump's last frame, see ApexConst.REQUEST_GAP_MS.
        val answer = answerWithRecord(serial)
        val hasDays = ApexAbilities.of(store, serial, pumpState.version?.firmwareText).dayRecord
        val today = if (hasDays) readToday() else null
        val card = if (hasDays) null else manager.readStatus()
        val count = card?.let { BolusSpanEnd.Count(it.readAtMs, Delivered.Count.of(it.status.deliveredTodayUnits, it.status.clockSeconds), it.status.reservoirUnits) }
        val end = today?.let { BolusSpanEnd.DayRecord(it.days) } ?: count
        val settled = answer != null && journal.settle(
            serial, answer, end, ApexClock.offsetMs(store, serial, pumpClock.phoneOffsetMs()), today?.atMs ?: card?.readAtMs ?: System.currentTimeMillis(),
            closeBasal = { card != null && heartbeatTick.settleBasalAt(serial, card) }
        )
        if (!settled) aapsLogger.warn(LTag.PUMP, "Apex: the record of the bolus of $units U is not settled yet, the next heartbeat's journal does it")
        else {
            today?.let { keepDayAfterBolus(serial, it) }
            count?.let { keepCountAfterBolus(serial, it) }
        }
        answer?.entries?.firstOrNull()?.record?.let { heartbeatTick.showBolusEnded(serial, it) }
        val delivered = outcome.reportedUnits
        trace.event(ApexTraceCat.STATE, "bolus_done", "order" to order.acceptedAtMs, "units" to delivered, "completed" to outcome.completed, "cancelled" to outcome.cancelled, "settled" to settled)

        val short = units - delivered >= ApexProtocol.DOSE_SCALE
        if (short && !outcome.cancelled) {
            aapsLogger.error(LTag.PUMP, "Apex: the pump delivered $delivered U of the $units U ordered")
            return Result(false, true, delivered, rh.gs(R.string.apex_bolus_short, delivered, units))
        }
        return Result(true, true, delivered, null)
    }

    /** The pump's days at one moment: the daily statistics, today first, today's record, and when they were read. */
    private class Today(val atMs: Long, val days: ApexJournalAnswer<ApexDailyRecord>, val record: ApexDailyRecord)

    /**
     * Today's daily statistics read after the record of the bolus. The whole bolus is in them. No
     * delivery of ours runs now, so the sum of the parts is the pump's count of today. Null when the
     * pump did not answer.
     */
    private fun readToday(): Today? {
        // Asked again while the pump is silent or answers with no day, as every read.
        repeat(BrokenRecordDeep.READS_PER_JOURNAL) { attempt ->
            val days = manager.readDailyStats()
            val record = days?.entries?.firstOrNull()?.record
            if (days != null && record != null) return Today(System.currentTimeMillis(), days, record)
            aapsLogger.warn(LTag.PUMP, "Apex: the daily statistics after the bolus came ${if (days == null) "with no answer" else "with no day"}, read ${attempt + 1} of ${BrokenRecordDeep.READS_PER_JOURNAL}")
        }
        aapsLogger.warn(LTag.PUMP, "Apex: the pump did not answer the daily statistics after the bolus, today is compared at the next heartbeat")
        return null
    }

    /**
     * The day read once the record of the bolus is settled and its row is in AAPS is kept as the start
     * of the next span, see [BolusGate.keep]. AAPS is not compared with it: that is done at the
     * heartbeat only, where every gap is laid side by side.
     */
    private fun keepDayAfterBolus(serial: String, day: Today) {
        BolusGate(store, serial).keep(JournalRead(day.days, repeatedBroken = false), day.atMs)
        val today = day.record
        val atMs = day.atMs
        aapsLogger.info(LTag.PUMP, "Apex: daily statistics after the bolus: bolus ${today.bolusUnits} basal ${today.basalUnits} temporary ${today.tbrUnits} U at $atMs")
        trace.event(ApexTraceCat.STATE, "day_after_bolus", "at" to atMs, "bolus" to today.bolusUnits, "basal" to today.basalUnits, "tbr" to today.tbrUnits)
    }

    /**
     * The pump's count read once the record of the bolus is settled is kept as the start of the next
     * span, as a heartbeat keeps its own, see [BolusCountMark]: it holds every bolus settled so far.
     */
    private fun keepCountAfterBolus(serial: String, count: BolusSpanEnd.Count) {
        store.transaction {
            BolusAccount(store, serial).clearSettledSinceHeartbeat()
            BolusCountMark(store, serial).move(count)
        }
        aapsLogger.info(LTag.PUMP, "Apex: the pump's count after the bolus: ${count.count.units} U, reservoir ${count.reservoirUnits} U at ${count.atMs}")
        trace.event(ApexTraceCat.STATE, "count_after_bolus", "at" to count.atMs, "count" to count.count.units, "reservoir" to count.reservoirUnits)
    }

    /**
     * The bolus journal read after the bolus just ended, after the usual gap from its last frame.
     * The record follows the end by a moment: the answer is read again while its top is still the
     * record kept from the last answer, or a record looks broken, up to
     * [BrokenRecordDeep.READS_PER_JOURNAL] times. The last answer read is returned whatever its
     * top, so that it is settled all the same; null when no read brought a record: silence, or an
     * answer with no record at all, which is silence too (see [PlausibleRecordDeep]), each counted as one of the reads.
     */
    private fun answerWithRecord(serial: String): ApexJournalAnswer<ApexBolusRecord>? {
        val kept = store.snapshot(serial, SnapshotKind.BOLUS)?.frames
        val topKept = kept?.firstOrNull()?.let { ApexResponseFrame(it).recordIdentity() }
        val offsetMs = ApexClock.offsetMs(store, serial, pumpClock.phoneOffsetMs())
        var answer: ApexJournalAnswer<ApexBolusRecord>? = null
        repeat(BrokenRecordDeep.READS_PER_JOURNAL) { attempt ->
            val read = manager.readBolusHistory()
            if (read == null || read.entries.isEmpty()) {
                aapsLogger.warn(LTag.PUMP, "Apex: the bolus journal ${if (read == null) "got no answer" else "answer has no record at all, taken as silence"}, read ${attempt + 1} of ${BrokenRecordDeep.READS_PER_JOURNAL}")
                return@repeat
            }
            answer = read
            val topNow = read.entries.firstOrNull()?.frame?.recordIdentity()
            val moved = topNow != null && (topKept == null || !topNow.contentEquals(topKept))
            val broken = BrokenRecordDeep.brokenBolus(read, kept, PlausibleRecordDeep(store, serial, System.currentTimeMillis()), offsetMs)
            if (moved && broken == null) return read
            aapsLogger.warn(LTag.PUMP, "Apex: the bolus journal ${if (moved) "has a record that looks broken" else "has no new record yet"}, read ${attempt + 1} of ${BrokenRecordDeep.READS_PER_JOURNAL}")
        }
        return answer
    }
}
