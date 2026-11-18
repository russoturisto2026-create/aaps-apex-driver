package app.aaps.pump.apex.account.bolus

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.LongNonKey
import app.aaps.pump.apex.account.AapsCarrier
import app.aaps.pump.apex.account.AapsToday
import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.basal.BasalPeriod
import app.aaps.pump.apex.account.basal.BasalPeriodKeeper
import app.aaps.pump.apex.account.basal.Delivered
import app.aaps.pump.apex.account.basal.PeriodBoluses
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.deep.BolusByDaysDeep
import app.aaps.pump.apex.deep.BolusDeep
import app.aaps.pump.apex.deep.BolusMoveDeep
import app.aaps.pump.apex.deep.BolusReconcileDeep
import app.aaps.pump.apex.deep.BrokenRecordDeep
import app.aaps.pump.apex.deep.JournalReadAgainDeep
import app.aaps.pump.apex.deep.PlausibleRecordDeep
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.pulse.PictureReader
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.runBlocking

/**
 * Every answer of the pump's bolus journal goes through here, the one read after our own bolus
 * and the one of each heartbeat picture alike. The answer is laid over the copy kept from the
 * last one, see [JournalSnapshot]; the records new since are the only source of bolus rows in
 * AAPS. When they are the records our commands wait for, the plain path writes them, see
 * [BolusAccount]. Everything else is in the deep files:
 * - records nobody here asked for, and faulty cells: [BolusDeep];
 * - an answer that does not show the newest record kept: [JournalReadAgainDeep];
 * - the rows held against the pump's own total of the span, and the bounds of a row: [BolusReconcileDeep],
 *   with the total from [BolusMoveDeep].
 *
 * Our bolus on a pump that answers its last bolus record alone is confirmed by that record only, see
 * [settleOurLast]: no journal, no count.
 *
 * The note of our bolus stays until an answer settled shows a new record: that answer closes every
 * note of ours, whether its record came or not. An answer with no new record leaves the notes: the
 * record of our bolus may come a moment after it ends, and it must still be known as ours. The status the next reconciliation compares with is kept by the caller, and only
 * when the reconciliation succeeded, see `Reconciliation`.
 */
@SingleIn(AppScope::class)
@Inject
class BolusJournal(
    private val aapsLogger: AAPSLogger,
    private val store: ApexStore,
    private val manager: ApexManager,
    private val reader: PictureReader,
    private val carrier: AapsCarrier,
    private val aapsToday: AapsToday,
    private val preferences: Preferences,
    private val pumpState: ApexPumpState,
    private val trace: ApexTrace
) {

    private val readAgainDeep = JournalReadAgainDeep(aapsLogger, store, manager, reader, carrier, aapsToday, trace)

    /**
     * Settle [answer], read at [nowMs] under the clock offset [offsetMs], and write the rows it
     * gives. [end] is what the pump gave to tell how much went in as boluses over the span, when
     * it gave it: the rows are held against it, see [BolusReconcileDeep]; what that found is returned.
     * An answer that does not show the newest record kept is read again, see [JournalReadAgainDeep].
     * Null when nothing of the answer is kept, and the next answer shows the same records again: the
     * pump fell silent on a read the settling needed, the two reads differed, or a faulty cell came and
     * the span cannot be closed yet. An answer with no record read is silence; an empty journal, a count of 0, is a state, see [ApexJournalAnswer.empty].
     * [closeBasal] is called once, before the count is asked how much went in as boluses: the basal
     * rows of the span stand by then, and what the count moved past them went in as boluses. [comparedAtMs] is the moment this read is compared at, the
     * heartbeat: no row of a faulty cell stands after it, see [BolusDeep]. [deep] false after our bolus: an
     * answer that does not lay over the copy is not read again, the heartbeat does it. [readAgain] true for the
     * answer read once more, see [readJournalAgain]: what it leaves unexplained is taken as it is.
     */
    fun settle(
        serial: String, answer: ApexJournalAnswer<ApexBolusRecord>, end: BolusSpanEnd?, offsetMs: Long, nowMs: Long, comparedAtMs: Long = nowMs,
        closeBasal: () -> Unit = {}, readAgain: Boolean = false, deep: Boolean = true
    ): BolusReconcileDeep.Checked? {
        if (answer.entries.isEmpty() && !answer.empty) {
            aapsLogger.warn(LTag.PUMP, "Apex: the bolus answer has no record it could read, taken as silence: nothing is settled")
            return null
        }
        val reconcile = BolusReconcileDeep(aapsLogger, store, trace, serial, ::newestReservoir)
        // One transaction for every mark of the driver; the rows go to AAPS inside it, before it commits.
        // Cut short after the rows, the marks are not kept: the next answer shows the same records under the
        // same numbers, and the rows are written again over themselves. Cut short before them, nothing is lost.
        return store.transaction { settleRows(serial, answer, end, offsetMs, nowMs, comparedAtMs, closeBasal, reconcile, readAgain, deep) }
    }

    /**
     * Our bolus confirmed by the pump's last bolus record [record], read alone after it ended, see
     * `BolusCommand`: the record right above the top of the journal copy, which was read alone just
     * before the bolus and was that top. The record goes on top of the copy as the next one, see
     * [JournalSnapshot.takeOnTop], so the row has the number the journal would give it, and the next
     * journal read knows the record already. It is ours when the plain path expects it, see
     * [BolusAccount.expects]: then its row is written and the command closed. False and nothing kept
     * otherwise: the full reconciliation with the journal settles it.
     */
    fun settleOurLast(serial: String, record: ApexJournalAnswer<ApexBolusRecord>, offsetMs: Long, nowMs: Long): Boolean {
        val top = record.entries.singleOrNull() ?: return false
        val reconcile = BolusReconcileDeep(aapsLogger, store, trace, serial, ::newestReservoir)
        return store.transaction {
            val journal = JournalSnapshot(store, serial)
            val before = journal.mark()
            val account = BolusAccount(store, serial)
            val added = journal.takeOnTop(JournalSnapshot.Answered(top.frame, top.record.clockSeconds), offsetMs, nowMs)
            if (added == null || account.openActions().size != 1 || !account.expects(added, nowMs, newestReservoir())) {
                journal.rollBack(before)
                return@transaction false
            }
            val carried = reconcile.inBounds(account.settle(added, notBeforeMs(), nowMs), nowMs)
            account.closeAll()
            carried.forEach { carrier.carry(it, serial) }
            tally(serial, carried)
            aapsLogger.info(LTag.PUMP, "Apex: our bolus confirmed by the last bolus record alone: ${top.record.deliveredUnits} U of ${top.record.requestedUnits} U")
            trace.event(ApexTraceCat.STATE, "bolus_confirmed_by_last_record", "units" to top.record.deliveredUnits, "asked" to top.record.requestedUnits)
            true
        }
    }

    /**
     * The rows of [answer], written, and the marks of the driver that go with them: the copy of the
     * journal, the commands closed, the boluses settled since the heartbeat. Null when nothing of the
     * answer is kept, see [settle]. Run inside a transaction of the store.
     */
    private fun settleRows(
        serial: String, answer: ApexJournalAnswer<ApexBolusRecord>, end: BolusSpanEnd?, offsetMs: Long, nowMs: Long, comparedAtMs: Long,
        closeBasal: () -> Unit, reconcile: BolusReconcileDeep, readAgain: Boolean, deep: Boolean
    ): BolusReconcileDeep.Checked? {
        val journal = JournalSnapshot(store, serial)
        val account = BolusAccount(store, serial)
        val settledBefore = account.settledSinceHeartbeat().sum()
        val before = journal.mark()
        val added = journal.take(JournalSnapshot.boluses(answer), offsetMs, nowMs)?.also { readAgainDeep.wholeNotNeeded(serial) }
            ?: (if (deep) readAgainDeep.readAgain(serial, journal, answer, offsetMs, nowMs, reconcile.bounds(nowMs)) else null) ?: return null
        var faultyRowId: Long? = null
        // Asked only when a faulty cell, or the statistics, need it: the count asks AAPS for the basal of the span.
        val move = lazy {
            if (end is BolusSpanEnd.Count) closeBasal()
            BolusMoveDeep(store, serial) { startMs, endMs -> runBlocking { aapsToday.basalBetween(startMs, endMs) } }.over(end)
        }
        val writes = when {
            added.firstRead                                  -> emptyList<AapsWrite>().also { aapsLogger.info(LTag.PUMP, "Apex: ${added.records.size} bolus records kept as the past, nothing of them is written") }
            added.records.isEmpty()                          -> emptyList()
            account.expects(added, nowMs, newestReservoir()) -> account.settle(added, notBeforeMs(), nowMs)
            // A faulty cell is closed by the span's bolus move: when it cannot be had yet the answer is taken back, the next one shows the same records.
            else                                             -> reconcile.settleFaults(added, move, notBeforeMs(), nowMs, comparedAtMs)?.also { faultyRowId = it.faultyRowId }?.writes
                ?: return null.also { journal.rollBack(before) }
        }
        if (added.records.isNotEmpty()) account.closeAll().forEach {
            aapsLogger.warn(LTag.PUMP, "Apex: no record of our bolus of ${it.amount} U accepted at ${it.acceptedAtMs} in this answer, the command is closed: the daily statistics tell what went in, when the pump has them")
            trace.event(ApexTraceCat.STATE, "bolus_no_record", "order" to it.acceptedAtMs, "units" to it.amount, "seen" to it.seenUnits)
        }
        // A journal started anew is the past: nothing of it is held against the statistics.
        val checked = if (added.firstRead) BolusReconcileDeep.Checked(BolusByDaysDeep.Result(writes, change = null), offMove = false)
        else reconcile.check(writes, end, move, settledBefore, nowMs, faultyRowId, readAgain)
        val byDays = checked.result
        val rows = faultyRowId?.let { BolusDeep(store, serial).noticeFor(byDays.writes, it) } ?: byDays.writes
        val carried = reconcile.inBounds(rows, nowMs)
        carried.forEach { carrier.carry(it, serial) }
        tally(serial, carried)
        return checked
    }

    /** The bolus rows among [carried] are added to the boluses of the period under way, but only when that period is counted from the pump's count, see [PeriodBoluses]. */
    private fun tally(serial: String, carried: List<AapsWrite>) {
        val mark = BasalPeriod.Mark.decode(store.state(serial, BasalPeriodKeeper.STATE_PERIOD)) ?: return
        if (mark.delivered !is Delivered.Count) return
        val tally = PeriodBoluses(store, serial)
        carried.filterIsInstance<AapsWrite.Bolus>().forEach { tally.add(it.pumpId, it.amountUnits) }
    }

    /**
     * The journal read once more when the records did not explain the pump's move and no bolus runs, see
     * `Reconciliation`: the record of a bolus that just ended is there by now. Null when no sound answer came.
     */
    fun readJournalAgain(serial: String, offsetMs: Long): ApexJournalAnswer<ApexBolusRecord>? {
        val bounds = PlausibleRecordDeep.now(store, serial, pumpState)
        val kept = store.snapshot(serial, SnapshotKind.BOLUS)?.frames
        return reader.readJournal("bolus journal read again", manager::readBolusHistory) { BrokenRecordDeep.brokenBolus(it, kept, bounds, offsetMs) }?.answer
    }

    /** The reservoir of the newest status read, U; null when none was read. */
    private fun newestReservoir(): Double? = pumpState.statusCard?.status?.reservoirUnits

    /** The moment AAPS registered this pump: the core refuses a row dated before it. */
    private fun notBeforeMs(): Long = preferences.get(LongNonKey.ActivePumpChangeTimestamp)
}
