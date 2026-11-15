package app.aaps.pump.apex.deep

import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.bolus.BolusAccount
import app.aaps.pump.apex.account.store.FakeStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The row of the unknown faulty cells takes what the move of the span leaves; every other row stays as it is. */
class BolusByDaysDeepTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private val account = BolusAccount(store, serial)
    private val byDays = BolusByDaysDeep(store, serial)
    private val nowMs = 1_793_714_400_000L
    private val idBase get() = account.idBase(nowMs)

    /** The row of record number [seq] that delivered [units]. */
    private fun row(seq: Long, units: Double) = AapsWrite.Bolus(idBase + seq, nowMs - 30_000L, units, "NORMAL")

    private fun bolusRows(result: BolusByDaysDeep.Result) = result.writes.filterIsInstance<AapsWrite.Bolus>()

    @Test
    fun `the cells take what the move leaves after the other rows, the other rows stay`() {
        val cells = row(9, 0.0)
        val result = byDays.correct(listOf(row(7, 0.5), cells), restUnits = 0.8, faultyRowId = cells.pumpId)

        assertEquals(listOf(0.5, 0.3), bolusRows(result).map { Math.round(it.amountUnits * 1000) / 1000.0 })
        assertEquals(0.3, result.change!!.dayUnits, 1e-9)
        assertEquals(0.3, account.settledSinceHeartbeat().sum(), 1e-9)
    }

    @Test
    fun `cells the move leaves nothing for keep nothing, for the notice to drop them`() {
        val cells = row(9, 0.0)
        val result = byDays.correct(listOf(row(7, 0.5), cells), restUnits = 0.5, faultyRowId = cells.pumpId)

        assertEquals(listOf(0.5, 0.0), bolusRows(result).map { it.amountUnits })
        assertEquals(0.0, result.change!!.dayUnits, 1e-9)
    }

    @Test
    fun `the other rows beyond the move stay as they are, the excess is told`() {
        val cells = row(9, 0.0)
        val result = byDays.correct(listOf(row(7, 0.5), row(8, 0.3), cells), restUnits = 0.7, faultyRowId = cells.pumpId)

        assertEquals(listOf(0.5, 0.3, 0.0), bolusRows(result).map { it.amountUnits })
        assertEquals(0.1, result.shortUnits, 1e-9)
    }

    @Test
    fun `without the row of the cells nothing is changed and no row is made`() {
        val writes = listOf(row(7, 0.1), row(8, 0.35))
        val result = byDays.correct(writes, restUnits = 0.6, faultyRowId = idBase + 99)

        assertEquals(writes, result.writes)
        assertNull(result.change)
    }
}
