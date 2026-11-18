package app.aaps.pump.apex.deep

import app.aaps.pump.apex.account.basal.Delivered
import app.aaps.pump.apex.account.bolus.BolusCountMark
import app.aaps.pump.apex.account.bolus.BolusSpanEnd
import app.aaps.pump.apex.account.store.FakeStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How much went in as boluses over a span of a pump without the daily statistics: the count's move less the basal. */
class BolusMoveDeepTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private var basal: Double? = 0.0
    private val move = BolusMoveDeep(store, serial) { _, _ -> basal }

    private fun keepAt(atMs: Long, units: Double) = BolusCountMark(store, serial).move(end(atMs, units))

    private fun end(atMs: Long, units: Double, day: Long = 20_000L) = BolusSpanEnd.Count(atMs, Delivered.Count(units, day))

    @Test
    fun `the move of the count less the basal, in whole steps`() {
        keepAt(1_000L, 10.0)
        basal = 0.21
        val answer = move.over(end(2_000L, 11.0)) as BolusMoveDeep.Answer.Units
        assertEquals(0.8, answer.units, 1e-9)
        assertTrue(answer.byCount)
    }

    @Test
    fun `more basal than the count moved - zero, not below`() {
        keepAt(1_000L, 10.0)
        basal = 0.5
        assertEquals(0.0, (move.over(end(2_000L, 10.1)) as BolusMoveDeep.Answer.Units).units, 1e-9)
    }

    @Test
    fun `no count kept - the start, nothing can ever close the span`() {
        assertTrue(move.over(end(2_000L, 11.0)) is BolusMoveDeep.Answer.Never)
    }

    @Test
    fun `nothing read - not yet`() {
        assertFalse(move.over(null).closes)
    }

    @Test
    fun `AAPS cannot tell the basal - not yet`() {
        keepAt(1_000L, 10.0)
        basal = null
        assertTrue(move.over(end(2_000L, 11.0)) is BolusMoveDeep.Answer.NotYet)
    }

    @Test
    fun `another pump day, a count that went back, a clock that went back - never`() {
        keepAt(1_000L, 10.0)
        assertTrue(move.over(end(2_000L, 1.0, day = 20_001L)) is BolusMoveDeep.Answer.Never)
        assertTrue(move.over(end(2_000L, 9.0)) is BolusMoveDeep.Answer.Never)
        assertTrue(move.over(end(1_000L, 11.0)) is BolusMoveDeep.Answer.Never)
    }

    @Test
    fun `a count one step lower than the kept one is the same count`() {
        keepAt(1_000L, 10.0)
        assertTrue(move.over(end(2_000L, 9.99)) is BolusMoveDeep.Answer.Units)
    }
}
