package app.aaps.pump.apex.basal

import app.aaps.pump.apex.basal.BasalPeriodRows.Op
import app.aaps.pump.apex.basal.BasalPeriodRows.Row
import app.aaps.pump.apex.basal.BasalPeriodRows.Running
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rows of a closed period: before the mark one row of the fact; after it only what the status
 * shows running, from the mark for the minutes it has left. Every other row is cut or taken out.
 */
class BasalPeriodRowsTest {

    private val minute = 60_000L
    private val start = 1_793_873_100_000L
    private val end = start + 30 * minute

    private fun row(id: Long, startMs: Long, minutes: Long, rate: Double = 1.0, suspend: Boolean = false) = Row(id, startMs, minutes * minute, rate, true, suspend)
    private fun factRow(units: Double, suspend: Boolean = false) = Op.Place(BasalPeriodRows.factId(start), start, end - start, units * 2.0, true, suspend)

    @Test
    fun `no rows and none running - the fact row alone, at the rate of the period`() {
        val plan = BasalPeriodRows.plan(emptyList(), start, end, 0.275, null)
        assertEquals(listOf<Op>(factRow(0.275)), plan.ops)
        assertEquals(start + 1, plan.fact.pumpId)
        assertNull(plan.running)
    }

    @Test
    fun `a row begun before and ended inside is cut at the start`() {
        val plan = BasalPeriodRows.plan(listOf(row(7, start - 10 * minute, 25, 2.0)), start, end, 0.5, null)
        assertEquals(listOf(Op.Place(7, start - 10 * minute, 10 * minute, 2.0, true, false), factRow(0.5)), plan.ops)
    }

    @Test
    fun `a row begun and ended inside is taken out`() {
        val plan = BasalPeriodRows.plan(listOf(row(7, start + 5 * minute, 15)), start, end, 0.5, null)
        assertEquals(listOf(Op.Remove(7), factRow(0.5)), plan.ops)
    }

    @Test
    fun `none runs at the mark - a row running past it is taken out, its end in AAPS is not used`() {
        val plan = BasalPeriodRows.plan(listOf(row(7, start + 20 * minute, 30, 1.5)), start, end, 0.5, null)
        assertEquals(listOf(Op.Remove(7), factRow(0.5)), plan.ops)
    }

    @Test
    fun `one runs, its row begun inside the period - taken out, a new row from the mark for the minutes the status gives`() {
        val plan = BasalPeriodRows.plan(listOf(row(7, start + 20 * minute, 30, 1.5)), start, end, 0.5, Running(1.5, 17 * minute))
        val after = Op.Place(BasalPeriodRows.continuedId(end), end, 17 * minute, 1.5, true, false)
        assertEquals(listOf(Op.Remove(7), factRow(0.5), after), plan.ops)
        assertEquals(after, plan.running)
        assertEquals(end + 2, after.pumpId)
    }

    @Test
    fun `one runs, its row begun before the period - cut at the start, a new row from the mark`() {
        val plan = BasalPeriodRows.plan(listOf(row(7, start - 10 * minute, 60, 1.5)), start, end, 0.5, Running(1.5, 20 * minute))
        val after = Op.Place(BasalPeriodRows.continuedId(end), end, 20 * minute, 1.5, true, false)
        assertEquals(listOf(Op.Place(7, start - 10 * minute, 10 * minute, 1.5, true, false), factRow(0.5), after), plan.ops)
    }

    @Test
    fun `two rows past the mark - both taken out, after the mark only the rate of the status`() {
        // An old row of ours still ran past the mark in AAPS; another's runs now as the status shows.
        val rows = listOf(row(7, start + 2 * minute, 60, 0.0), row(9, start + 18 * minute, 30, 2.0))
        val plan = BasalPeriodRows.plan(rows, start, end, 0.5, Running(2.0, 15 * minute))
        assertEquals(listOf(Op.Remove(7), Op.Remove(9), factRow(0.5), Op.Place(BasalPeriodRows.continuedId(end), end, 15 * minute, 2.0, true, false)), plan.ops)
    }

    @Test
    fun `a row that begins past the mark is taken out`() {
        val plan = BasalPeriodRows.plan(listOf(row(7, end + 20_000L, 30)), start, end, 0.5, null)
        assertEquals(listOf(Op.Remove(7), factRow(0.5)), plan.ops)
    }

    @Test
    fun `rows before the period and the period's own row are left alone`() {
        val rows = listOf(row(1, start - 60 * minute, 30), row(BasalPeriodRows.factId(start), start, 30))
        val plan = BasalPeriodRows.plan(rows, start, end, 0.5, null)
        assertEquals(listOf<Op>(factRow(0.5)), plan.ops)
    }

    @Test
    fun `a period the pump stood suspended through is a suspension, a period with a dose is not`() {
        val suspended = BasalPeriodRows.plan(listOf(row(7, start - 5 * minute, 40, 0.0, suspend = true)), start, end, 0.0, null)
        assertTrue(suspended.fact.suspend)
        val dosed = BasalPeriodRows.plan(listOf(row(7, start - 5 * minute, 40, 0.0, suspend = true)), start, end, 0.025, null)
        assertTrue(!dosed.fact.suspend)
        val mixed = BasalPeriodRows.plan(listOf(row(7, start - 5 * minute, 10, 0.0, suspend = true), row(8, start + 5 * minute, 10)), start, end, 0.0, null)
        assertTrue(!mixed.fact.suspend)
    }

    @Test
    fun `a five minute period - the fact rate is the dose times twelve`() {
        val plan = BasalPeriodRows.plan(emptyList(), start, start + 5 * minute, 0.05, null)
        assertEquals(0.6, plan.fact.rate, 1e-9)
    }
}
