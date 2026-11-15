package app.aaps.pump.apex.account.bolus

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.LongNonKey
import app.aaps.pump.apex.account.AapsCarrier
import app.aaps.pump.apex.account.AapsToday
import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.NoticeKind
import app.aaps.pump.apex.account.ReferencePoints
import app.aaps.pump.apex.account.basal.BasalPeriod
import app.aaps.pump.apex.account.basal.BasalPeriodKeeper
import app.aaps.pump.apex.account.basal.Delivered
import app.aaps.pump.apex.account.basal.PeriodBoluses
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.deep.BolusByDaysDeep
import app.aaps.pump.apex.deep.BolusDeep
import app.aaps.pump.apex.deep.BrokenRecordDeep
import app.aaps.pump.apex.deep.JournalRestartDeep
import app.aaps.pump.apex.deep.PlausibleRecordDeep
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.pulse.PictureReader
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

/**
 * Every answer of the pump's bolus journal goes through here, the one read after our own bolus
 * and the one of each heartbeat picture alike. The answer is laid over the copy kept from the
 * last one, see [JournalSnapshot]; the records new since are the only source of bolus rows in
 * AAPS. When they are the records our commands wait for, the plain path writes them, see
 * [BolusAccount]; anything else goes to [BolusDeep]. An answer that does not show the newest
 * record kept is read again, and then the whole journal is read, see [JournalRestartDeep].
 *
 * A bolus row is made only from a record, and a sound record is written as it says. The daily
 * statistics, or the pump's count, are read after the journal; their move over the span gives the
 * dose of the faulty cells, and nothing else, see [BolusByDaysDeep].
 * When the move and the records do not agree, the log says so, and the day table of the screen
 * shows the difference.
 *
 * A pump without the daily statistics has only its count of the day: the count less the basal of
 * the span stands where the bolus field stands, see [BolusMove]. When the move cannot be had yet,
 * the answer is taken back and the next one settles it over a longer span. When it can never be
 * had, the faulty cells get no amount and the other records are written.
 *
 * Each answer settled closes every command of ours, whether its record came or not: what is left
 * is the journal copy and the day kept, for the next heartbeat or bolus to compare with.
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
    private val trace: ApexTrace
) {

    /**
     * Settle [answer], read at [nowMs] under the clock offset [offsetMs], and write the rows it
     * gives. [end] is what the pump gave to tell how much went in as boluses over the span, when
     * it gave it: the rows are held against it, see [byMove]. An answer that does not show the
     * newest record kept is read again, see [JournalRestartDeep]. False when nothing of the answer
     * is kept, and the next answer shows the same records again: the pump fell silent on a read the
     * settling needed, the two reads differed, or a faulty cell came and the span cannot be closed
     * yet. An answer with no record is silence, see [PlausibleRecordDeep]. A row the pump cannot
     * have made is not written, and the user is told. [closeBasal] is called once, before the
     * count is asked how much went in as boluses: the basal rows of the span must stand by then.
     * When it says they do not, the count is not used and the answer waits for the next read.
     * [comparedAtMs] is the moment this read is compared at, the heartbeat: no row of a faulty cell
     * stands after it, see [BolusDeep].
     */
    fun settle(
        serial: String, answer: ApexJournalAnswer<ApexBolusRecord>, end: BolusSpanEnd?, offsetMs: Long, nowMs: Long, comparedAtMs: Long = nowMs,
        closeBasal: () -> Boolean = { true }
    ): Boolean {
        if (answer.entries.isEmpty()) {
            aapsLogger.warn(LTag.PUMP, "Apex: the bolus answer has no record at all, taken as silence: nothing is settled")
            return false
        }
        val journal = JournalSnapshot(store, serial, SnapshotKind.BOLUS)
        val account = BolusAccount(store, serial)
        val settledBefore = account.settledSinceHeartbeat().sum()
        val before = journal.mark()
        val added = journal.take(JournalSnapshot.boluses(answer), offsetMs, nowMs)?.also { wholeNotNeeded(serial) } ?: readAgain(serial, journal, answer, offsetMs, nowMs) ?: return false
        var faultyRowId: Long? = null
        // Asked only when a faulty cell, or the statistics, need it: the count asks AAPS for the basal of the span.
        val move = lazy {
            if (end is BolusSpanEnd.Count && !closeBasal()) BolusMove.Answer.NotYet("the basal rows of the span are not settled")
            else BolusMove(store, serial) { startMs, endMs -> runBlocking { aapsToday.basalBetween(startMs, endMs) } }.over(end)
        }
        val writes = when {
            added.firstRead                -> emptyList<AapsWrite>().also { aapsLogger.info(LTag.PUMP, "Apex: ${added.records.size} bolus records kept as the past, nothing of them is written") }
            added.records.isEmpty()        -> emptyList()
            account.expects(added, nowMs)  -> account.settle(added, notBeforeMs(), nowMs)
            // A faulty cell is closed by the span's bolus move: when it cannot be had yet the answer is taken back, the next one shows the same records.
            else                           -> settleDeep(serial, added, move, nowMs, comparedAtMs)?.also { faultyRowId = it.faultyRowId }?.writes
                ?: return false.also { journal.rollBack(before) }
        }
        account.closeAll().forEach {
            aapsLogger.warn(LTag.PUMP, "Apex: no record of our bolus of ${it.amount} U accepted at ${it.acceptedAtMs} in this answer, the command is closed: the daily statistics tell what went in, when the pump has them")
            trace.event(ApexTraceCat.STATE, "bolus_no_record", "order" to it.acceptedAtMs, "units" to it.amount, "seen" to it.seenUnits)
        }
        // A journal started anew is the past: nothing of it is held against the statistics.
        val byDays = if (added.firstRead) BolusByDaysDeep.Result(writes, change = null) else byMove(serial, writes, end, move, settledBefore, nowMs, faultyRowId)
        val rows = faultyRowId?.let { BolusDeep(store, serial).noticeFor(byDays.writes, it) } ?: byDays.writes
        val carried = inBounds(serial, rows, nowMs)
        carried.forEach { carrier.carry(it, serial) }
        tally(serial, carried)
        return true
    }

    /** The bolus rows among [carried] are added to the boluses of the period under way, but only when that period is counted from the pump's count, see [PeriodBoluses]. */
    private fun tally(serial: String, carried: List<AapsWrite>) {
        val mark = BasalPeriod.Mark.decode(store.state(serial, BasalPeriodKeeper.STATE_PERIOD)) ?: return
        if (mark.delivered !is Delivered.Count) return
        val tally = PeriodBoluses(store, serial)
        carried.filterIsInstance<AapsWrite.Bolus>().forEach { tally.add(it.pumpId, it.amountUnits) }
    }

    /** [rows] without a bolus row above the reservoir of the last status: such a row is not written, a notice takes its place. */
    private fun inBounds(serial: String, rows: List<AapsWrite>, nowMs: Long): List<AapsWrite> {
        val bounds = PlausibleRecordDeep(store, serial, nowMs)
        return rows.map { row ->
            if (row !is AapsWrite.Bolus || !bounds.aboveReservoir(row.amountUnits)) return@map row
            aapsLogger.error(LTag.PUMP, "Apex: bolus row ${row.pumpId} of ${row.amountUnits} U is more than the reservoir held, ${bounds.reservoirUnits} U: not written")
            trace.event(ApexTraceCat.STATE, "bolus_row_out_of_bounds", "id" to row.pumpId, "units" to row.amountUnits, "reservoir" to bounds.reservoirUnits)
            AapsWrite.Notice(NoticeKind.IMPLAUSIBLE_ROW, units = row.amountUnits, atMs = row.timestampMs)
        }
    }

    /**
     * [first] did not show the newest record kept: the journal is read again. The second answer laid
     * over the copy is settled as usual; two answers that differ settle nothing. Two answers alike
     * that show nothing of the copy start the journal anew only when the boluses were last held
     * against AAPS longer ago than insulin acts; else the whole journal is read and laid over the
     * copy, see [JournalRestartDeep]. Null when nothing is kept.
     */
    private fun readAgain(serial: String, journal: JournalSnapshot, first: ApexJournalAnswer<ApexBolusRecord>, offsetMs: Long, nowMs: Long): JournalSnapshot.Added? {
        aapsLogger.warn(LTag.PUMP, "Apex: the bolus answer does not show the last record kept, reading the journal again")
        val bounds = PlausibleRecordDeep(store, serial, nowMs)
        val second = reader.readJournal("bolus journal again", manager::readBolusHistory) { BrokenRecordDeep.brokenBolus(it, bounds = bounds, offsetMs = offsetMs) }?.answer ?: return null
        journal.take(JournalSnapshot.boluses(second), offsetMs, nowMs)?.let { return it.also { wholeNotNeeded(serial) } }
        if (!JournalRestartDeep.startsAnew(first, second)) {
            aapsLogger.warn(LTag.PUMP, "Apex: two bolus answers differ and neither shows the last record kept, nothing is settled until the next read")
            trace.event(ApexTraceCat.STATE, "bolus_journal_unlaid", "first" to first.entries.size, "second" to second.entries.size)
            return null
        }
        if (JournalRestartDeep.startedOver(second)) {
            aapsLogger.error(LTag.PUMP, "Apex: two bolus answers alike show nothing of the last record kept, and the pump holds only ${second.entries.size} records: the pump started its journal over, a fault")
            trace.event(ApexTraceCat.STATE, "bolus_journal_started_over", "records" to second.entries.size, "count" to second.recordCount)
            return journal.startOver(JournalSnapshot.boluses(second), offsetMs, nowMs)
        }
        val lastHeldMs = lastHeldMs(serial)
        if (JournalRestartDeep.actsNoMore(lastHeldMs, nowMs, runBlocking { aapsToday.insulinActsMs() })) {
            aapsLogger.error(LTag.PUMP, "Apex: two bolus answers alike show nothing of the last record kept, and the boluses were last held against AAPS at $lastHeldMs, longer ago than insulin acts: the account of boluses starts clean, the user is told")
            trace.event(ApexTraceCat.STATE, "bolus_journal_restart", "records" to second.entries.size, "held" to lastHeldMs)
            return startClean(serial, journal, second, NoticeKind.JOURNAL_CLEAN_START, offsetMs, nowMs)
        }
        aapsLogger.warn(LTag.PUMP, "Apex: two bolus answers alike show nothing of the last record kept, and the boluses were held against AAPS at $lastHeldMs: reading the whole journal")
        val kept = store.snapshot(serial, SnapshotKind.BOLUS)?.frames
        val whole = reader.readJournal("whole bolus journal", manager::readWholeBolusHistory) { BrokenRecordDeep.brokenBolus(it, kept, bounds, offsetMs) }?.answer
        if (whole == null) {
            val unread = (store.state(serial, STATE_WHOLE_UNREAD)?.toIntOrNull() ?: 0) + 1
            trace.event(ApexTraceCat.STATE, "bolus_journal_whole_unread", "times" to unread)
            if (unread < JournalRestartDeep.WHOLE_JOURNAL_TRIES) {
                store.putState(serial, STATE_WHOLE_UNREAD, unread.toString())
                aapsLogger.error(LTag.PUMP, "Apex: the whole bolus journal was not read, time $unread of ${JournalRestartDeep.WHOLE_JOURNAL_TRIES}: nothing is settled until the next heartbeat")
                return null
            }
            aapsLogger.error(LTag.PUMP, "Apex: the whole bolus journal was not read $unread times in a row, a fault: the user is told, the account of boluses starts clean")
            return startClean(serial, journal, second, NoticeKind.JOURNAL_UNLAID, offsetMs, nowMs)
        }
        journal.take(JournalSnapshot.boluses(whole), offsetMs, nowMs)?.let {
            aapsLogger.warn(LTag.PUMP, "Apex: the whole bolus journal of ${whole.entries.size} records shows the last record kept, ${it.records.size} records are new above it")
            trace.event(ApexTraceCat.STATE, "bolus_journal_whole", "records" to whole.entries.size, "new" to it.records.size)
            wholeNotNeeded(serial)
            return it
        }
        aapsLogger.error(LTag.PUMP, "Apex: the whole bolus journal of ${whole.entries.size} records shows nothing of the last record kept, a fault: the user is told, the account of boluses starts clean")
        trace.event(ApexTraceCat.STATE, "bolus_journal_whole_unlaid", "records" to whole.entries.size)
        return startClean(serial, journal, whole, NoticeKind.JOURNAL_UNLAID, offsetMs, nowMs)
    }

    /**
     * The account of boluses starts clean from [answer]: its records are the past, every reference
     * point starts from zero with it, see [ReferencePoints], and the user is told why, [notice].
     */
    private fun startClean(
        serial: String, journal: JournalSnapshot, answer: ApexJournalAnswer<ApexBolusRecord>, notice: NoticeKind, offsetMs: Long, nowMs: Long
    ): JournalSnapshot.Added {
        val added = store.transaction {
            ReferencePoints.drop(store, serial)
            store.putState(serial, STATE_WHOLE_UNREAD, null)
            journal.restart(JournalSnapshot.boluses(answer), offsetMs, nowMs)
        }
        carrier.carry(AapsWrite.Notice(notice, atMs = nowMs), serial)
        return added
    }

    /** The answer lay over the copy: the count of heartbeats the whole journal stayed unread starts over. */
    private fun wholeNotNeeded(serial: String) {
        if (store.state(serial, STATE_WHOLE_UNREAD) != null) store.putState(serial, STATE_WHOLE_UNREAD, null)
    }

    /**
     * When the boluses of this pump were last held against AAPS: the last bolus answer laid over
     * the copy, or the last day of the statistics kept, whichever is later. Null when neither is kept.
     */
    private fun lastHeldMs(serial: String): Long? =
        listOfNotNull(store.snapshot(serial, SnapshotKind.BOLUS)?.readAtMs, store.snapshot(serial, SnapshotKind.DAILY)?.readAtMs).filter { it > 0 }.maxOrNull()

    /** The records the plain path did not expect, see [BolusDeep]. Null when a faulty cell came and the span cannot be closed yet. */
    private fun settleDeep(serial: String, added: JournalSnapshot.Added, move: Lazy<BolusMove.Answer>, nowMs: Long, comparedAtMs: Long): BolusDeep.Settled? {
        val settled = BolusDeep(store, serial).settleFaults(added, { move.value.closes }, notBeforeMs(), nowMs, comparedAtMs)
        if (settled == null) {
            val why = (move.value as? BolusMove.Answer.NotYet)?.why
            aapsLogger.warn(LTag.PUMP, "Apex: a faulty bolus cell came and the span cannot be closed yet ($why), the answer is settled at the next read")
            trace.event(ApexTraceCat.STATE, "bolus_span_not_yet", "why" to why)
            return null
        }
        settled.faultyRowId?.let {
            aapsLogger.error(LTag.PUMP, "Apex: new bolus records are faulty: older than the records before them, out of the pump's bounds, or in a journal the pump started over; row $it takes them all, at our command or the middle of their window, with what the span's bolus move leaves")
            trace.event(ApexTraceCat.STATE, "bolus_faulty_cell", "row" to it)
        }
        return settled
    }

    /**
     * [writes] held against how much went in as boluses over the span, [move], less the rows
     * settled in the span before these, [settledBeforeUnits]. Only the row of the faulty
     * cells, [faultyRowId], takes its amount from the move; every other row stays, and a move that
     * does not agree with them is told in the log. Left as they are when [end] was not read or
     * cannot tell, or the move is more than the reservoir held, which no pump does, see
     * [PlausibleRecordDeep]. The count is asked only for the faulty cells.
     */
    private fun byMove(
        serial: String, writes: List<AapsWrite>, end: BolusSpanEnd?, move: Lazy<BolusMove.Answer>,
        settledBeforeUnits: Double, nowMs: Long, faultyRowId: Long?
    ): BolusByDaysDeep.Result {
        val asRecords = BolusByDaysDeep.Result(writes, change = null)
        if (end == null || end is BolusSpanEnd.Count && faultyRowId == null) return asRecords
        val answer = move.value
        if (answer is BolusMove.Answer.Units && answer.byCount) aapsLogger.info(LTag.PUMP, "Apex: the faulty cells are closed by ${answer.source.text}: ${answer.units} U went in as boluses over the span")
        if (answer is BolusMove.Answer.Never) return if (faultyRowId == null) asRecords else withoutAmount(serial, writes, faultyRowId, answer.why)
        if (answer !is BolusMove.Answer.Units) return asRecords
        val moved = answer.units
        val bounds = PlausibleRecordDeep(store, serial, nowMs)
        if (bounds.aboveReservoir(moved)) {
            aapsLogger.error(LTag.PUMP, "Apex: the bolus move of the span is $moved U, more than the reservoir held, ${bounds.reservoirUnits} U: it is not used for this span")
            trace.event(ApexTraceCat.STATE, "bolus_days_out_of_bounds", "moved" to moved, "reservoir" to bounds.reservoirUnits)
            return if (faultyRowId != null) withoutAmount(serial, writes, faultyRowId, "the move is out of bounds") else asRecords
        }
        if (answer.undatedDay) aapsLogger.debug(LTag.PUMP, "Apex: a day with no date stands among the days of the span, the bolus field is summed over it")
        val rest = moved - settledBeforeUnits
        val source = answer.source.text
        if (faultyRowId == null) {
            val recordUnits = writes.filterIsInstance<AapsWrite.Bolus>().sumOf { it.amountUnits }
            val gap = BolusAccount.onStep(rest - recordUnits)
            if (abs(gap) >= ApexProtocol.DOSE_SCALE / 2) {
                aapsLogger.warn(LTag.PUMP, "Apex: the bolus records give $recordUnits U, $source moved $rest U in the span: the rows are written as the records say, the day table shows the difference")
                trace.event(ApexTraceCat.STATE, "bolus_records_off_move", "records" to recordUnits, "moved" to moved, "before" to settledBeforeUnits)
            }
            return asRecords
        }
        if (answer.byCount && rest < ApexProtocol.DOSE_SCALE / 2) aapsLogger.warn(LTag.PUMP, "Apex: $source leaves $rest U for the boluses of the span: nothing for the faulty cells")
        val result = BolusByDaysDeep(store, serial).correct(writes, rest, faultyRowId)
        result.change?.let {
            aapsLogger.warn(LTag.PUMP, "Apex: $source moved $rest U in the span, the faulty cells take what is left: row ${it.pumpId} is written as ${it.dayUnits} U")
            trace.event(ApexTraceCat.STATE, "bolus_by_days", "id" to it.pumpId, "day" to it.dayUnits, "moved" to moved, "before" to settledBeforeUnits)
        }
        if (result.shortUnits > 0) {
            aapsLogger.warn(LTag.PUMP, "Apex: the other rows give ${result.shortUnits} U more than the bolus move of the span, they are written as they are")
            trace.event(ApexTraceCat.STATE, "bolus_records_over_days", "units" to result.shortUnits, "moved" to moved, "before" to settledBeforeUnits)
        }
        return result
    }

    /**
     * The move can never close this span, for [why]: the faulty cells get no amount, the other
     * records are written as they are. The cells' row is left with nothing, so that the user is told, see [BolusDeep.noticeFor].
     */
    private fun withoutAmount(serial: String, writes: List<AapsWrite>, faultyRowId: Long, why: String): BolusByDaysDeep.Result {
        aapsLogger.warn(LTag.PUMP, "Apex: faulty bolus cells came and the pump's count can never close this span ($why): the cells get no amount, the other records are written")
        trace.event(ApexTraceCat.STATE, "bolus_span_open", "why" to why)
        val row = writes.filterIsInstance<AapsWrite.Bolus>().first { it.pumpId == faultyRowId }
        if (row.amountUnits > 0.0) BolusAccount(store, serial).noteSettled(-row.amountUnits)
        return BolusByDaysDeep.Result(writes.map { if (it === row) row.copy(amountUnits = 0.0) else it }, change = null)
    }

    /** The moment AAPS registered this pump: the core refuses a row dated before it. */
    private fun notBeforeMs(): Long = preferences.get(LongNonKey.ActivePumpChangeTimestamp)

    private companion object {

        /** How many heartbeats in a row the whole journal was asked for and not read. */
        const val STATE_WHOLE_UNREAD = "wholeBolusJournalUnread"
    }
}
