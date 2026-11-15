package app.aaps.pump.apex.account.bolus

import app.aaps.pump.apex.account.NoticeKind
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.NOW_MS
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.PUMP_DAY
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.TWO
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.at
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.oldCell
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.ten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A faulty cell on a pump without the daily statistics when the count cannot close the span: the
 * move of the reservoir stands in its place. Each test is one answer after the past of ten records
 * of 0.2 U, with the count of 10 U and the reservoir of 200 U kept five minutes before, and the
 * rates ordering 0.2 U over the span.
 */
class JournalReservoirFaultTest {

    private val bench = JournalBench()
    private val acceptedAt = TWO * 1000L + 5_000L

    init {
        bench.pastByCount(ten(), countUnits = 10.0, reservoirUnits = 200.0)
        bench.basalOfSpan = 0.2
    }

    private fun amounts() = bench.rows().map { Math.round(it.amountUnits * 1000) / 1000.0 }

    @Test
    fun `the pump's day changed, the reservoir known - the cell takes the reservoir's move less the basal less the sound record`() {
        assertTrue(bench.settleByCount(listOf(oldCell(), at(0, 12)) + ten().take(8), countUnits = 3.0, pumpDay = PUMP_DAY + 1, reservoirUnits = 198.5))

        assertEquals(listOf(0.3, 1.0), amounts())
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT), bench.notices().map { it.kind })
    }

    @Test
    fun `the count went back, the reservoir known - the same rest`() {
        assertTrue(bench.settleByCount(listOf(oldCell(), at(0, 12)) + ten().take(8), countUnits = 9.0, reservoirUnits = 198.5))

        assertEquals(listOf(0.3, 1.0), amounts())
    }

    @Test
    fun `the count can be used - it wins, the reservoir is not looked at`() {
        assertTrue(bench.settleByCount(listOf(oldCell(), at(0, 12)) + ten().take(8), countUnits = 11.5, reservoirUnits = 150.0))

        assertEquals(listOf(0.3, 1.0), amounts())
    }

    @Test
    fun `the pump's day changed, our bolus in the cell - the reservoir's move less the basal, at our command`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settleByCount(listOf(oldCell()) + ten().take(9), countUnits = 3.0, pumpDay = PUMP_DAY + 1, reservoirUnits = 199.0))

        assertEquals(listOf(0.8), amounts())
        assertEquals(acceptedAt, bench.rows().single().timestampMs)
    }

    @Test
    fun `the reservoir went up - no row for the cell, the other records are written, the user is told, the mark moves`() {
        assertTrue(bench.settleByCount(listOf(oldCell(), at(0, 12)) + ten().take(8), countUnits = 3.0, pumpDay = PUMP_DAY + 1, reservoirUnits = 300.0))

        assertEquals(listOf(0.3), amounts())
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT_UNKNOWN), bench.notices().map { it.kind })
        val mark = BolusCountMark(bench.store, bench.serial).get()!!
        assertEquals(300.0, mark.reservoirUnits!!, 1e-9)
        assertEquals(NOW_MS, mark.atMs)
    }

    @Test
    fun `the reservoir nowhere known at the mark - nothing closes the span`() {
        val fresh = JournalBench()
        fresh.basalOfSpan = 0.2
        fresh.pastByCount(ten(), countUnits = 10.0)
        assertTrue(fresh.settleByCount(listOf(oldCell(), at(0, 12)) + ten().take(8), countUnits = 3.0, pumpDay = PUMP_DAY + 1, reservoirUnits = 198.5))

        assertEquals(listOf(0.3), fresh.rows().map { Math.round(it.amountUnits * 1000) / 1000.0 })
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT_UNKNOWN), fresh.notices().map { it.kind })
    }
}
