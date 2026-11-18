package app.aaps.pump.apex.deep

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.NoticeKind
import app.aaps.pump.apex.account.bolus.BolusSpanEnd
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import kotlin.math.abs

/**
 * The bolus rows of a journal answer held against the pump's own total, at the heartbeat.
 *
 * The problem. A bolus row is made only from a journal record. But the journal does not always
 * show every bolus in time: the pump writes the record of a bolus after the bolus ends, so a bolus
 * of another client that still runs has no record yet; a stopped bolus is often one step short in
 * its record; a record can be missing; a cell can be faulty. And an answer can carry a number the
 * pump cannot have made. The records alone cannot tell any of this, so they are held against the
 * pump's total of the span, see [BolusMoveDeep]. That is how the reconciliation finds what was done
 * outside our commands.
 *
 * When it applies. At each settled answer that came with the pump's total: the daily statistics, or
 * the pump's count less the basal AAPS ordered. The bounds apply to every row.
 *
 * The way out.
 * - Sound records and the move differ by [OFF_MOVE_UNITS] or more: a record is missing or not
 *   written yet. No bolus runs here: that is asked before, see [RunningBolusDeep]. The rows are
 *   written as the records say, and the caller is told: the record of a bolus that just ended may
 *   follow it by a moment, so the journal is read once more, then the pump's numbers, and what the
 *   records still leave is taken as it is: basal on a pump counted by its count, a difference of the day on a pump with
 *   the statistics. A smaller difference is taken at once: a stopped bolus is often one step short.
 * - Faulty cells take what the move leaves after the other rows, see [BolusByDaysDeep].
 * - The move can never close the span: the cells get no amount, the user is told, see [BolusDeep.noticeFor].
 * - A move above the reservoir is not used; a row above the reservoir is not written and the user is
 *   told, see [PlausibleRecordDeep].
 */
class BolusReconcileDeep(
    private val aapsLogger: AAPSLogger,
    private val store: ApexStore,
    private val trace: ApexTrace,
    private val serial: String,
    /** The reservoir of the newest status read, U; null when none was read. */
    private val newestReservoir: () -> Double?
) {

    /**
     * What [check] made of the rows, and [offMove]: the records and the move differ by [OFF_MOVE_UNITS]
     * or more; [gapUnits] is that difference, the move less the records.
     */
    class Checked(val result: BolusByDaysDeep.Result, val offMove: Boolean, val gapUnits: Double = 0.0)

    /** The bounds for records read at [nowMs], see [PlausibleRecordDeep]. */
    fun bounds(nowMs: Long): PlausibleRecordDeep = PlausibleRecordDeep(store, serial, nowMs, newestReservoir())

    /** [rows] without a bolus row above the reservoir bound: such a row is not written, a notice takes its place. */
    fun inBounds(rows: List<AapsWrite>, nowMs: Long): List<AapsWrite> {
        val bounds = bounds(nowMs)
        return rows.map { row ->
            if (row !is AapsWrite.Bolus || !bounds.aboveReservoir(row.amountUnits)) return@map row
            aapsLogger.error(LTag.PUMP, "Apex: bolus row ${row.pumpId} of ${row.amountUnits} U is more than the reservoir held, ${bounds.reservoirUnits} U: not written")
            trace.event(ApexTraceCat.STATE, "bolus_row_out_of_bounds", "id" to row.pumpId, "units" to row.amountUnits, "reservoir" to bounds.reservoirUnits)
            AapsWrite.Notice(NoticeKind.IMPLAUSIBLE_ROW, units = row.amountUnits, atMs = row.timestampMs)
        }
    }

    /**
     * The records the plain path did not expect, see [BolusDeep]. [move] gives the dose of a faulty
     * cell; [notBeforeMs] is the moment AAPS registered this pump. Null when a faulty cell came and the
     * span cannot be closed yet: the answer is taken back, the next one shows the same records.
     */
    fun settleFaults(added: JournalSnapshot.Added, move: Lazy<BolusMoveDeep.Answer>, notBeforeMs: Long, nowMs: Long, comparedAtMs: Long): BolusDeep.Settled? {
        val settled = BolusDeep(store, serial).settleFaults(added, { move.value.closes }, notBeforeMs, nowMs, comparedAtMs, newestReservoir())
        if (settled == null) {
            val why = (move.value as? BolusMoveDeep.Answer.NotYet)?.why
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
     * does not agree with them is told to the caller, see the rules above. Left as they are when [end]
     * was not read or cannot tell, or the move is more than the reservoir held. [readAgain] true for
     * the answer read once more, see `Reconciliation`: what the records leave is taken as it is.
     */
    fun check(
        writes: List<AapsWrite>, end: BolusSpanEnd?, move: Lazy<BolusMoveDeep.Answer>,
        settledBeforeUnits: Double, nowMs: Long, faultyRowId: Long?, readAgain: Boolean
    ): Checked {
        val asRecords = Checked(BolusByDaysDeep.Result(writes, change = null), offMove = false)
        if (end == null) return asRecords
        val answer = move.value
        if (answer is BolusMoveDeep.Answer.Units && answer.byCount) aapsLogger.info(LTag.PUMP, "Apex: the faulty cells are closed by ${answer.source.text}: ${answer.units} U went in as boluses over the span")
        if (answer is BolusMoveDeep.Answer.Never) return if (faultyRowId == null) asRecords else Checked(withoutAmount(writes, faultyRowId, answer.why), offMove = false)
        if (answer !is BolusMoveDeep.Answer.Units) return asRecords
        val moved = answer.units
        val bounds = bounds(nowMs)
        if (bounds.aboveReservoir(moved)) {
            aapsLogger.error(LTag.PUMP, "Apex: the bolus move of the span is $moved U, more than the reservoir held, ${bounds.reservoirUnits} U: it is not used for this span")
            trace.event(ApexTraceCat.STATE, "bolus_days_out_of_bounds", "moved" to moved, "reservoir" to bounds.reservoirUnits)
            return if (faultyRowId != null) Checked(withoutAmount(writes, faultyRowId, "the move is out of bounds"), offMove = false) else asRecords
        }
        if (answer.undatedDay) aapsLogger.debug(LTag.PUMP, "Apex: a day with no date stands among the days of the span, the bolus field is summed over it")
        val rest = moved - settledBeforeUnits
        val source = answer.source.text
        if (faultyRowId == null) {
            val recordUnits = writes.filterIsInstance<AapsWrite.Bolus>().sumOf { it.amountUnits }
            val gap = ApexProtocol.onStep(rest - recordUnits)
            val offMove = abs(gap) > OFF_MOVE_UNITS - ApexProtocol.DOSE_SCALE / 2
            if (offMove && !readAgain) {
                aapsLogger.warn(LTag.PUMP, "Apex: the bolus records give $recordUnits U, $source moved $rest U in the span: the rows are written as the records say; the journal is read again")
                trace.event(ApexTraceCat.STATE, "bolus_records_off_move", "records" to recordUnits, "moved" to moved, "before" to settledBeforeUnits, "read_again" to true)
            } else if (abs(gap) >= ApexProtocol.DOSE_SCALE / 2) {
                val left = if (answer.byCount) "the rest is basal" else "the day table shows the difference"
                aapsLogger.warn(LTag.PUMP, "Apex: the bolus records give $recordUnits U, $source moved $rest U in the span${if (readAgain) " in the journal read again" else ""}: the rows are written as the records say, $left")
                trace.event(ApexTraceCat.STATE, "bolus_records_off_move", "records" to recordUnits, "moved" to moved, "before" to settledBeforeUnits, "read_again" to false, "after_read_again" to readAgain)
            }
            return Checked(asRecords.result, offMove && !readAgain, gap)
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
        return Checked(result, offMove = false)
    }

    /**
     * The move can never close this span, for [why]: the faulty cells get no amount, the other
     * records are written as they are. The cells' row is left with nothing, so that the user is told, see [BolusDeep.noticeFor].
     */
    private fun withoutAmount(writes: List<AapsWrite>, faultyRowId: Long, why: String): BolusByDaysDeep.Result {
        aapsLogger.warn(LTag.PUMP, "Apex: faulty bolus cells came and the pump's count can never close this span ($why): the cells get no amount, the other records are written")
        trace.event(ApexTraceCat.STATE, "bolus_span_open", "why" to why)
        val row = writes.filterIsInstance<AapsWrite.Bolus>().first { it.pumpId == faultyRowId }
        return BolusByDaysDeep.Result(writes.map { if (it === row) row.copy(amountUnits = 0.0) else it }, change = null)
    }

    private companion object {

        /**
         * The bolus records and the bolus field of the daily statistics differ by this much or more, U:
         * the journal is read again. Less is kept as it is: a stopped bolus is often one step short in its record.
         */
        const val OFF_MOVE_UNITS = 0.1

    }
}
