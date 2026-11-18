package app.aaps.pump.apex.account.bolus

import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.NoticeKind
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.BEFORE_MS
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.NOW_MS
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.PUMP_DAY
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.TWO
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.at
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.oldCell
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.ten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A faulty cell on a pump without the daily statistics: the pump's count of the day, less the basal
 * of the span, closes it. Each test is one answer after the past of ten records of 0.2 U, with the
 * count of 10 U kept five minutes before, and the rates ordering 0.2 U over the span unless the test says.
 */
class JournalCountFaultTest {

    private val bench = JournalBench()
    private val acceptedAt = TWO * 1000L + 5_000L
    private val later = NOW_MS + 5 * 60_000L

    init {
        bench.pastByCount(ten(), countUnits = 10.0)
        bench.basalOfSpan = 0.2
    }

    private fun amounts(b: JournalBench = bench) = b.rows().map { Math.round(it.amountUnits * 1000) / 1000.0 }

    @Test
    fun `a faulty cell and a sound record - the cell takes the count's move less the basal less the sound record`() {
        assertTrue(bench.settleByCount(listOf(oldCell(), at(0, 12)) + ten().take(8), countUnits = 11.5))

        assertEquals(listOf(0.3, 1.0), amounts())
        assertEquals(AapsWrite.NORMAL, bench.rows().last().type)
        assertEquals(1, bench.basalClosed)
        val notice = bench.notices().single()
        assertEquals(NoticeKind.JOURNAL_FAULT, notice.kind)
        assertEquals(1.0, notice.units, 1e-9)
    }

    @Test
    fun `the basal rows of the span do not stand - nothing written, the answer waits for the next read`() {
        bench.basalStands = false
        assertFalse(bench.settleByCount(listOf(oldCell(), at(0, 12)) + ten().take(8), countUnits = 11.5))

        assertEquals(1, bench.basalClosed)
        assertTrue(bench.written.isEmpty())
        assertTrue(bench.topIs(ten().first()))
    }

    @Test
    fun `two faulty cells in one span - one row with the whole rest`() {
        assertTrue(bench.settleByCount(listOf(oldCell(), at(-2, 8)) + ten().take(8), countUnits = 11.0))

        assertEquals(listOf(0.8), amounts())
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT), bench.notices().map { it.kind })
    }

    @Test
    fun `nothing is left after the basal - no row, the user is told`() {
        bench.basalOfSpan = 0.3
        assertTrue(bench.settleByCount(listOf(oldCell()) + ten().take(9), countUnits = 10.2))

        assertTrue(bench.rows().isEmpty())
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT_UNKNOWN), bench.notices().map { it.kind })
    }

    @Test
    fun `our bolus in the cell - the count's move less the basal, at our command`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settleByCount(listOf(oldCell()) + ten().take(9), countUnits = 11.0))

        assertEquals(listOf(0.8), amounts())
        assertEquals(acceptedAt, bench.rows().single().timestampMs)
        assertEquals("SMB", bench.rows().single().type)
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT), bench.notices().map { it.kind })
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `our bolus stopped short in the cell - the count's move less the basal, at our command`() {
        bench.ours(acceptedAt, 0.5, 0.45)
        assertTrue(bench.settleByCount(listOf(oldCell()) + ten().take(9), countUnits = 11.0))

        assertEquals(listOf(0.8), amounts())
        assertEquals(acceptedAt, bench.rows().single().timestampMs)
    }

    @Test
    fun `a bolus settled after our command is not counted twice - the cell gets only what is left`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settleAfterOurBolus(listOf(at(0, 20)) + ten().take(9)))
        assertEquals(listOf(0.5), amounts())
        bench.written.clear()

        assertTrue(bench.settleByCount(listOf(oldCell(), at(0, 20)) + ten().take(8), countUnits = 11.2))
        assertEquals(listOf(0.5), amounts())
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT), bench.notices().map { it.kind })
    }

    @Test
    fun `a sound answer is held against the count - when AAPS cannot tell the basal the rows stand as the records say`() {
        bench.basalOfSpan = null
        assertTrue(bench.settleByCount(listOf(at(0, 12)) + ten().take(9), countUnits = 15.0))

        assertEquals(listOf(0.3), amounts())
        assertTrue(bench.notices().isEmpty())
        assertEquals(1, bench.basalClosed)
    }

    @Test
    fun `a sound answer that explains the count less the basal is a reconciliation that succeeded`() {
        bench.basalOfSpan = 0.2
        assertTrue(bench.settleByCount(listOf(at(0, 12)) + ten().take(9), countUnits = 10.5))
        assertEquals(listOf(0.3), amounts())
        assertEquals(NOW_MS, BolusCountMark(bench.store, bench.serial).get()!!.atMs)
    }

    @Test
    fun `a sound answer that leaves the count unexplained keeps no count, so that a bolus that runs is asked about`() {
        bench.basalOfSpan = 0.2
        assertTrue(bench.settleByCount(listOf(at(0, 12)) + ten().take(9), countUnits = 15.0))
        assertEquals(listOf(0.3), amounts())
        assertEquals(BEFORE_MS, BolusCountMark(bench.store, bench.serial).get()!!.atMs)
    }

    @Test
    fun `no count kept - the start - a cell of nobody's gets no amount and the user is told`() {
        val fresh = JournalBench()
        fresh.past(ten(), dayRaw = 200)
        fresh.basalOfSpan = 0.2
        assertTrue(fresh.settleByCount(listOf(oldCell()) + ten().take(9), countUnits = 10.0, atMs = NOW_MS))
        assertTrue(fresh.rows().none { it.amountUnits > 0.0 })
        assertTrue(fresh.notices().isNotEmpty())
    }

    @Test
    fun `no count kept - the start - the cell of our command gets no amount, the user is told, the command is closed`() {
        val fresh = JournalBench()
        fresh.past(ten(), dayRaw = 200)
        fresh.basalOfSpan = 0.2
        fresh.ours(acceptedAt, 0.5, 0.5)
        assertTrue(fresh.settleByCount(listOf(oldCell()) + ten().take(9), countUnits = 10.0, atMs = NOW_MS))
        assertTrue(fresh.rows().none { it.amountUnits > 0.0 })
        assertTrue(fresh.notices().isNotEmpty())
        assertTrue(fresh.openCommands().isEmpty())
    }

    @Test
    fun `AAPS cannot tell the basal - the answer is taken back, the next heartbeat settles it over the longer span`() {
        bench.basalOfSpan = null
        assertFalse(bench.settleByCount(listOf(oldCell()) + ten().take(9), countUnits = 10.5))
        assertTrue(bench.written.isEmpty())
        assertEquals(BEFORE_MS, BolusCountMark(bench.store, bench.serial).get()!!.atMs)

        bench.basalOfSpan = 0.4
        assertTrue(bench.settleByCount(listOf(oldCell()) + ten().take(9), countUnits = 11.0, atMs = later))
        assertEquals(listOf(0.6), amounts())
        assertEquals(later, BolusCountMark(bench.store, bench.serial).get()!!.atMs)
    }

    @Test
    fun `the answer after our own bolus has no count - the cell of our command waits for the heartbeat`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertFalse(bench.settleAfterOurBolus(listOf(oldCell()) + ten().take(9)))
        assertTrue(bench.written.isEmpty())
        assertEquals(1, bench.openCommands().size)

        assertTrue(bench.settleByCount(listOf(oldCell()) + ten().take(9), countUnits = 11.0))
        assertEquals(listOf(0.8), amounts())
    }

    @Test
    fun `the answer after our own bolus has no count - a cell of nobody's waits for the heartbeat`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertFalse(bench.settleAfterOurBolus(listOf(oldCell(), at(0, 20)) + ten().take(8)))
        assertTrue(bench.written.isEmpty())

        assertTrue(bench.settleByCount(listOf(oldCell(), at(0, 20)) + ten().take(8), countUnits = 11.0))
        assertEquals(listOf(0.5, 0.3), amounts())
    }

    @Test
    fun `the pump's day changed - the other records are written, the cell has no row, the user is told, the mark moves`() {
        assertTrue(bench.settleByCount(listOf(oldCell(), at(0, 12)) + ten().take(8), countUnits = 3.0, pumpDay = PUMP_DAY + 1))

        assertEquals(listOf(0.3), amounts())
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT_UNKNOWN), bench.notices().map { it.kind })
        val mark = BolusCountMark(bench.store, bench.serial).get()!!
        assertEquals(PUMP_DAY + 1, mark.count.pumpDay)
        assertEquals(NOW_MS, mark.atMs)
    }

    @Test
    fun `the count went back - the other records are written, the cell has no row, the user is told`() {
        assertTrue(bench.settleByCount(listOf(oldCell(), at(0, 12)) + ten().take(8), countUnits = 9.0))

        assertEquals(listOf(0.3), amounts())
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT_UNKNOWN), bench.notices().map { it.kind })
    }

    @Test
    fun `the pump's day changed over our bolus in the cell - no dose can be had, no row, the user is told`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settleByCount(listOf(oldCell()) + ten().take(9), countUnits = 3.0, pumpDay = PUMP_DAY + 1))

        assertTrue(bench.rows().isEmpty())
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT_UNKNOWN), bench.notices().map { it.kind })
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `the pump's day changed over our bolus stopped short - no row for it, the user is told`() {
        bench.ours(acceptedAt, 0.5, 0.45)
        assertTrue(bench.settleByCount(listOf(oldCell()) + ten().take(9), countUnits = 3.0, pumpDay = PUMP_DAY + 1))

        assertTrue(bench.rows().isEmpty())
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT_UNKNOWN), bench.notices().map { it.kind })
        assertTrue(bench.openCommands().isEmpty())
    }
}
