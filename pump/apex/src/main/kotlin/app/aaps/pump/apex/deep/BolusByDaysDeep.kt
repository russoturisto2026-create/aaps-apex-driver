package app.aaps.pump.apex.deep

import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.bolus.BolusAccount
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.protocol.ApexProtocol

/**
 * The dose of the faulty cells of a span. Their records do not say what went
 * in; the move of the span does: the bolus field of the daily statistics, or the pump's count less
 * the basal, from the read compared before to this one. The row of those cells, see [BolusDeep],
 * takes what the move leaves after the records that can be read.
 *
 * Every other row stays as its record says.
 * A sound record that does not agree with the move is the pump's matter, not the driver's: nobody
 * knows what really went in, and the day table of the screen shows the difference. No row is ever
 * made for insulin no record stands for.
 */
class BolusByDaysDeep(private val store: ApexStore, private val serial: String) {

    /** What the move changed: the row of the cells, its units before and by the move; 0 by the move when it is not written. */
    class Change(val pumpId: Long, val recordUnits: Double, val dayUnits: Double)

    /** [shortUnits]: how far the other rows went beyond the move, U, when they did. */
    class Result(val writes: List<AapsWrite>, val change: Change?, val shortUnits: Double = 0.0)

    /**
     * [writes] of the span with the row of the faulty cells, [faultyRowId], given what the move
     * leaves: [restUnits] is the move of the span less the rows settled in it before these. A row
     * left with nothing stays with nothing: [BolusDeep.noticeFor] drops it and tells the user. The
     * account's list of settled rows follows the change.
     */
    fun correct(writes: List<AapsWrite>, restUnits: Double, faultyRowId: Long): Result {
        val rows = writes.filterIsInstance<AapsWrite.Bolus>()
        val row = rows.firstOrNull { it.pumpId == faultyRowId } ?: return Result(writes, change = null)
        val othersUnits = rows.filter { it !== row }.sumOf { it.amountUnits }
        val dayUnits = ApexProtocol.onStep(maxOf(0.0, restUnits - othersUnits))
        BolusAccount(store, serial).noteSettled(dayUnits - row.amountUnits)
        val changed = writes.map { if (it === row) row.copy(amountUnits = dayUnits) else it }
        return Result(changed, Change(row.pumpId, row.amountUnits, dayUnits), shortUnits = maxOf(0.0, ApexProtocol.onStep(othersUnits - restUnits)))
    }
}
