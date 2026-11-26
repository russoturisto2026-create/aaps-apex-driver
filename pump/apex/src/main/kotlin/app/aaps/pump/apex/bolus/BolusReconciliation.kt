package app.aaps.pump.apex.bolus

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.aaps.AapsCarrier
import app.aaps.pump.apex.aaps.AapsToday
import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.aaps.NoticeKind
import app.aaps.pump.apex.bolus.FaultyCell.Suspicion
import app.aaps.pump.apex.statistics.StatisticsDays
import app.aaps.pump.apex.store.Action
import app.aaps.pump.apex.store.ApexStore
import app.aaps.pump.apex.store.SnapshotKind
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.pulse.PictureReader
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.abs

/**
 * The one reconciliation of boluses, at the heartbeat with no bolus running. The answer is laid over the
 * cells, see [BolusCells]; one that does not lay over them is read whole, see [WholeJournal]. Each new
 * record is paired with our bolus, see [BolusAccount.pair], and the rows must sum to the move of the bolus
 * field of the statistics. A record that looks sound goes as it is, ours or another's; a difference goes
 * only to faulty records, see [suspicion], and to our boluses whose end was not seen whole. What none can
 * take draws a line, see [MismatchLine]. The cells are filled with what went in and whether the row is in AAPS.
 */
@SingleIn(AppScope::class)
@Inject
class BolusReconciliation(
    private val aapsLogger: AAPSLogger,
    private val store: ApexStore,
    manager: ApexManager,
    reader: PictureReader,
    private val carrier: AapsCarrier,
    aapsToday: AapsToday,
    private val preferences: Preferences,
    private val pumpState: ApexPumpState,
    private val trace: ApexTrace
) {

    private val table = WholeJournal(aapsLogger, store, manager, reader, carrier, aapsToday, trace)

    /**
     * What the reconciliation made: the rows and notices, and what each line of the span came to. [told]: the
     * line drawn, see [MismatchLine]; it is told only once the reconciliation is kept, see `Reconciliation`.
     */
    class Settled(val writes: List<AapsWrite>, val lines: List<Line> = emptyList(), val told: List<AapsWrite> = emptyList()) {

        /**
         * One line of the span: the record [seq], our bolus [actionId], what went in and when, and its row, null when none was written;
         * when the reconciliation ran and the bolus field of the statistics for the day of the bolus then.
         */
        class Line(
            val seq: Long?, val actionId: Long?, val units: Double, val atMs: Long, val faulty: Boolean, val row: AapsWrite.Bolus?,
            val settledAtMs: Long? = null, val dayBolusUnits: Double? = null
        ) {

            fun filled(inAaps: Boolean) = BolusCells.Filled(seq, actionId, units, atMs, faulty, inAaps, settledAtMs, dayBolusUnits)
        }
    }

    /** One row of the span and what it may take of a difference, see [FaultyCell.give]. */
    private class Row(var row: AapsWrite.Bolus, val priority: Priority, val ceiling: Double, val suspicion: Suspicion, val seq: Long?, var faulty: Boolean, val actionId: Long? = null) {

        val units: Double get() = row.amountUnits
    }

    /**
     * Who takes a difference first: our bolus in a faulty record, then our bolus whose end we did not see whole,
     * then a faulty record of somebody else; a sound record never.
     */
    private enum class Priority { OURS_FAULTY, OURS_UNSURE, FOREIGN_FAULTY, NEVER }

    /**
     * Settle [answer], read at [nowMs] under the clock offset [offsetMs], against the statistics [days]
     * read after it, and write the rows. Null when nothing is kept: the next answer shows the same records.
     * [allDays]: the first reconciliation since AAPS started, the closed days of [days] were checked and are taken.
     */
    fun settle(
        serial: String, answer: ApexJournalAnswer<ApexBolusRecord>, days: ApexJournalAnswer<ApexDailyRecord>, offsetMs: Long, nowMs: Long,
        comparedAtMs: Long = nowMs, allDays: Boolean = false
    ): Settled? {
        if (answer.entries.isEmpty() && !answer.empty) {
            aapsLogger.warn(LTag.PUMP, "Apex: the bolus answer has no record it could read, taken as silence: nothing is settled")
            return null
        }
        // The rows go to AAPS inside the transaction: cut short, the next answer writes them again over themselves.
        return store.transaction { settleRows(serial, answer, days, offsetMs, nowMs, comparedAtMs, allDays) }
    }

    private fun settleRows(
        serial: String, answer: ApexJournalAnswer<ApexBolusRecord>, days: ApexJournalAnswer<ApexDailyRecord>, offsetMs: Long, nowMs: Long, comparedAtMs: Long, allDays: Boolean
    ): Settled? {
        val journal = BolusCells(store, serial)
        val account = BolusAccount(store, serial)
        val reservoir = reservoirUnits(serial)
        val anyMove = BolusGate(store, serial).bolusMovedSinceKept(days)
        val moved = anyMove?.takeIf { usable(it, reservoir) }
        // Our bolus is held against the statistics: with none read, nothing is laid and the next heartbeat tries.
        if (anyMove == null && journal.readAtMs() != null && account.openActions().isNotEmpty()) {
            aapsLogger.warn(LTag.PUMP, "Apex: a bolus of ours waits and no statistics came to hold it against: nothing is settled, the next heartbeat tries")
            return null
        }
        fun laid(read: ApexJournalAnswer<ApexBolusRecord>) = journal.take(BolusCells.boluses(read), offsetMs, nowMs)
        var count = answer.recordCount
        val added = laid(answer) ?: run {
            val whole = table.read(serial) ?: return null
            count = whole.recordCount
            // More records came than one answer carries: the whole journal lays over the cells, and is reconciled as usual.
            laid(whole) ?: run {
                if (table.emptyTooSoon(journal, whole, nowMs)) return null
                table.check(journal, whole, offsetMs)
                journal.countSeen(count)
                return table.settle(serial, journal, whole, days, offsetMs, nowMs, notBeforeMs(), reservoir, comparedAtMs, allDays) { inBounds(it, reservoir) }
            }
        }
        journal.countSeen(count)
        val keys = journal.keys()
        fun suspicion(record: JournalEntry) =
            ApexBolusRecord.decode(record.frame)?.let { FaultyCell.suspicion(it, record.atMs, added.previousTopAtMs, reservoir, nowMs) } ?: Suspicion.FAULTY
        // Pairing takes the paired boluses out of the list: what stays has no record of its own.
        val open = account.openActions().toMutableList()
        val pairs = account.pair(added.records, open)
        val rows = ArrayList<Row>()
        added.records.zip(pairs).forEach { (record, action) ->
            val bolus = action?.let { ApexBolusRecord.decode(record.frame) } ?: return@forEach
            // A bolus whose end we did not see whole, cancelled or with its frames lost, is not sure: its record may take the difference.
            val sure = action.completed && !action.cancelPressed
            rows += Row(account.row(bolus, action, keys[record.seq], notBeforeMs()), if (sure) Priority.NEVER else Priority.OURS_UNSURE, action.amount, Suspicion.SOUND, record.seq, false, action.id)
        }
        val left = added.records.filterIndexed { i, _ -> pairs[i] == null }.sortedWith(compareByDescending<JournalEntry> { suspicion(it) }.thenBy { it.seq })
        // Sound records that sum to the move are what the pump did: our bolus with no pair takes none of them.
        // When they do not sum, one of them is a faulty cell: our bolus may take it, the most faulty first.
        val soundUnits = rows.sumOf { it.units } + left.filter { suspicion(it) == Suspicion.SOUND }.sumOf { ApexBolusRecord.decode(it.frame)?.totalDeliveredUnits ?: 0.0 }
        val soundSum = moved != null && abs(ApexProtocol.onStep(moved - soundUnits)) < ApexProtocol.HALF_STEP
        val faulty = if (soundSum) left.filter { suspicion(it) != Suspicion.SOUND } else left
        open.forEachIndexed { i, action -> rows += ourRow(action, faulty.getOrNull(i), faulty.getOrNull(i)?.let { suspicion(it) } ?: Suspicion.SOUND, keys, account) }
        val taken = faulty.take(open.size).toSet()
        for (record in left.filter { it !in taken }) {
            val bolus = ApexBolusRecord.decode(record.frame) ?: continue
            val suspicion = suspicion(record)
            val row = account.foreignRow(record, bolus, keys[record.seq], notBeforeMs())
            rows += if (suspicion != Suspicion.SOUND) Row(row.copy(amountUnits = 0.0), Priority.FOREIGN_FAULTY, Double.MAX_VALUE, suspicion, record.seq, true)
            else Row(row, Priority.NEVER, Double.MAX_VALUE, suspicion, record.seq, false)
        }
        val gap = moved?.let { ApexProtocol.onStep(it - rows.sumOf { r -> r.units }) } ?: 0.0
        val unplaced = if (abs(gap) >= ApexProtocol.HALF_STEP) spread(rows, gap, moved!!) else 0.0
        middleOfWindow(rows, added, comparedAtMs)
        val sharedOn = moved != null && rows.any { it.faulty && it.units >= ApexProtocol.HALF_STEP }
        val lines = rows.sortedBy { it.seq ?: Long.MAX_VALUE }.map { it to write(it, sharedOn) }
        val foreignFaulty = rows.filter { it.priority == Priority.FOREIGN_FAULTY }
        val shared = foreignFaulty.firstOrNull { it.units >= ApexProtocol.HALF_STEP }?.let { FaultyCell.sharedNotice(foreignFaulty.size, it.units, it.row.timestampMs) }
        val newestFirst = days.entries.map { it.record }
        val settled = Settled(lines.flatMap { it.second } + listOfNotNull(shared), lines.map { (r, out) ->
            val day = StatisticsDays.bolusOfDay(newestFirst, StatisticsDays.dayOf(r.row.timestampMs, offsetMs))
            Settled.Line(r.seq, r.actionId, r.row.amountUnits, r.row.timestampMs, r.faulty, out.filterIsInstance<AapsWrite.Bolus>().firstOrNull(), comparedAtMs, day)
        })
        val line = if (abs(unplaced) < ApexProtocol.HALF_STEP) emptyList() else MismatchLine(store, serial).draw(added.previousReadAtMs ?: comparedAtMs, comparedAtMs, unplaced, newestFirst)
        val written = settled.writes.map { inBounds(it, reservoir) }
        written.forEach { carrier.carry(it, serial) }
        // A row the bound turned into a notice did not go to AAPS.
        journal.settled(settled.lines.map { line -> line.filled(inAaps = line.row != null && written.any { it === line.row }) })
        journal.prune()
        return Settled(settled.writes, settled.lines, told = line)
    }

    /**
     * Our bolus whose record did not pair. Its record may be a faulty cell, [record]; or there is none. A
     * sound record is never ours here. The row starts from what we saw, see [FaultyCell.startUnits], and the
     * move of the statistics sets it, see [spread]: sound records that sum to the move leave it nothing.
     */
    private fun ourRow(action: Action, record: JournalEntry?, suspicion: Suspicion, keys: Map<Long, Long>, account: BolusAccount): Row {
        val row = AapsWrite.Bolus(record?.let { keys[it.seq] }, maxOf(action.acceptedAtMs, notBeforeMs()), FaultyCell.startUnits(action, account), action.type, tempId = action.tempId)
        return Row(row, Priority.OURS_FAULTY, action.amount, suspicion, record?.seq, suspicion != Suspicion.SOUND, action.id)
    }

    /**
     * [gap] U, the move less the rows, given to the rows by priority, the most suspect first, see [FaultyCell.give].
     * Returns what none could take, U: the rows must sum to the move, else a line is drawn, see [MismatchLine].
     */
    private fun spread(rows: List<Row>, gap: Double, moved: Double): Double {
        aapsLogger.error(LTag.PUMP, "Apex: the bolus rows give ${moved - gap} U, the statistics moved $moved U: the difference goes to the rows")
        trace.event(ApexTraceCat.STATE, "bolus_records_off_move", "rows" to moved - gap, "moved" to moved)
        val takers = rows.filter { it.priority != Priority.NEVER }.sortedWith(compareBy<Row> { it.priority }.thenByDescending { it.suspicion }.thenBy { it.seq ?: Long.MAX_VALUE })
        val was = takers.map { it.units }
        val now = was.toMutableList()
        val left = FaultyCell.give(now, takers.map { it.ceiling }, gap)
        takers.forEachIndexed { i, r ->
            r.row = r.row.copy(amountUnits = now[i])
            // A record the move changed was faulty, and is told.
            if (abs(now[i] - was[i]) < ApexProtocol.HALF_STEP) return@forEachIndexed
            r.faulty = true
            trace.event(ApexTraceCat.STATE, "bolus_by_move", "id" to (r.row.pumpId ?: r.row.tempId), "was" to was[i], "now" to r.units, "moved" to moved)
        }
        if (left == 0.0) return 0.0
        aapsLogger.error(LTag.PUMP, "Apex: the bolus rows do not sum to the move of the statistics, $moved U: $left U is left with no faulty record to take it, a line is drawn")
        trace.event(ApexTraceCat.STATE, "bolus_move_left", "units" to left, "moved" to moved)
        return left
    }

    /** A faulty record of somebody else stands in the middle of the window its place in the journal gives, see [FaultyCell.middle]. */
    private fun middleOfWindow(rows: List<Row>, added: BolusCells.Added, endMs: Long) {
        val known = rows.filter { !it.faulty && it.seq != null }.map { it.seq!! to it.row.timestampMs }
        val lastReadMs = added.previousReadAtMs ?: endMs
        for (r in rows.filter { it.priority == Priority.FOREIGN_FAULTY }) {
            val seq = r.seq ?: continue
            val fromMs = (known.filter { it.first < seq }.map { it.second } + lastReadMs).max()
            val toMs = (known.filter { it.first > seq }.map { it.second } + endMs).min()
            r.row = r.row.copy(timestampMs = maxOf(notBeforeMs(), FaultyCell.middle(fromMs, toMs)))
        }
    }

    /** The row and its notice, see [FaultyCell.notice]: a row of somebody else with nothing is not written. */
    private fun write(r: Row, sharedOn: Boolean): List<AapsWrite> {
        val notice = FaultyCell.notice(r.faulty, r.actionId != null, r.seq != null, r.units, r.row.timestampMs, sharedOn)
        return listOfNotNull(r.row.takeIf { r.units >= ApexProtocol.HALF_STEP || it.tempId != null }, notice)
    }

    /** A bolus row above the reservoir is not written: a notice takes its place. */
    private fun inBounds(write: AapsWrite, reservoir: Double?): AapsWrite {
        if (write !is AapsWrite.Bolus || !FaultyCell.aboveReservoir(write.amountUnits, reservoir)) return write
        aapsLogger.error(LTag.PUMP, "Apex: bolus row ${write.pumpId ?: write.tempId} of ${write.amountUnits} U is more than the reservoir held, $reservoir U: not written")
        trace.event(ApexTraceCat.STATE, "bolus_row_out_of_bounds", "id" to (write.pumpId ?: write.tempId), "units" to write.amountUnits, "reservoir" to reservoir)
        return AapsWrite.Notice(NoticeKind.IMPLAUSIBLE_ROW, units = write.amountUnits, atMs = write.timestampMs)
    }

    /** A move above the reservoir is not used: the rows stand as the records say. */
    private fun usable(moved: Double, reservoir: Double?): Boolean {
        if (!FaultyCell.aboveReservoir(moved, reservoir)) return true
        trace.event(ApexTraceCat.STATE, "bolus_days_out_of_bounds", "moved" to moved, "reservoir" to reservoir)
        return false
    }

    /** The reservoir bound, U: the larger of the last reconciliation and the newest status read; null before the first status. */
    private fun reservoirUnits(serial: String): Double? = listOfNotNull(
        store.snapshot(serial, SnapshotKind.STATUS)?.frames?.firstOrNull()?.let { ApexStatusV1.decode(ApexResponseFrame(it)) }?.reservoirUnits,
        pumpState.statusCard?.status?.reservoirUnits
    ).maxOrNull()

    /** The moment AAPS registered this pump: the core refuses a row dated before it. */
    private fun notBeforeMs(): Long = preferences.get(LongNonKey.ActivePumpChangeTimestamp)
}
