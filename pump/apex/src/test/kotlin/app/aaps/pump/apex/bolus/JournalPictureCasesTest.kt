package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.aaps.NoticeKind
import app.aaps.pump.apex.bolus.JournalBench.Companion.NOW_MS
import app.aaps.pump.apex.bolus.JournalBench.Companion.TWO
import app.aaps.pump.apex.bolus.JournalBench.Companion.at
import app.aaps.pump.apex.bolus.JournalBench.Companion.oldCell
import app.aaps.pump.apex.bolus.JournalBench.Companion.ten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two pictures of a span held against each other at the heartbeat: our pictures and the pump's
 * records, with the bolus field of the daily statistics as the judge. Each test is one answer of the
 * pump after the past of ten records of 0.2 U, the newest a minute before two.
 */
class JournalPictureCasesTest {

    private val bench = JournalBench()
    private val acceptedAt = TWO * 1000L + 5_000L

    init {
        bench.past(ten(), dayRaw = 200)
    }

    @Test
    fun `our stopped bolus, its record one step short of the field - paired, the field moves the record, the user is told`() {
        bench.ours(acceptedAt, 1.0, 0.4, type = "NORMAL")
        assertTrue(bench.settle(listOf(BolusRecordFrames.record(TWO, 40, 15)) + ten().take(9), dayRaw = 216))

        val row = bench.rows().single()
        assertEquals(0.4, row.amountUnits, 1e-9)
        assertEquals(acceptedAt, row.tempId)
        assertEquals(acceptedAt, row.timestampMs)
        assertTrue(row.pumpId != null)
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT), bench.notices().map { it.kind })
    }

    @Test
    fun `our stopped bolus, its record agrees with the field and not with what we saw - the record`() {
        bench.ours(acceptedAt, 1.0, 0.4, type = "NORMAL")
        assertTrue(bench.settle(listOf(BolusRecordFrames.record(TWO, 40, 15)) + ten().take(9), dayRaw = 215))

        assertEquals(0.375, bench.rows().single().amountUnits, 1e-9)
        assertTrue(bench.notices().isEmpty())
    }

    @Test
    fun `our bolus stopped past the driver, its record a step above what we saw - paired, the row keeps what we saw`() {
        val account = BolusAccount(bench.store, bench.serial)
        val action = account.commandAccepted(acceptedAt, 2.0, "NORMAL").first
        account.finished(action, 0.4, frames = 16, cancelPressed = false, cancelAccepted = false, completed = false)
        assertTrue(bench.settle(listOf(BolusRecordFrames.record(TWO, 80, 20)) + ten().take(9), dayRaw = 216))

        val row = bench.rows().single()
        assertEquals(0.4, row.amountUnits, 1e-9)
        assertEquals(acceptedAt, row.tempId)
    }

    @Test
    fun `our whole bolus whose completion frame was lost - not stopped, the row is its record, not the last progress frame`() {
        val account = BolusAccount(bench.store, bench.serial)
        val action = account.commandAccepted(acceptedAt, 0.5, "NORMAL").first
        account.finished(action, 0.45, frames = 18, cancelPressed = false, cancelAccepted = false, completed = false)
        assertTrue(bench.settle(listOf(at(0, 20)) + ten().take(9), dayRaw = 220))

        assertEquals(0.5, bench.rows().single().amountUnits, 1e-9)
    }

    @Test
    fun `our whole bolus, its record agrees, the field one step off - the row as the record says, the step left out, the loop opened`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(0, 20)) + ten().take(9), dayRaw = 221))
        assertEquals(listOf(0.5), bench.rows().map { it.amountUnits })
        val notice = bench.notices().single()
        assertEquals(NoticeKind.JOURNAL_MISMATCH, notice.kind)
        assertEquals(0.025, notice.units, 1e-9)
        assertTrue(AapsWrite.OpenLoop in bench.written)
    }

    @Test
    fun `two of ours, one record - it pairs with the bolus it is the record of, the other has none and the field gives it nothing`() {
        bench.ours(acceptedAt, 0.3, 0.0, seen = false)
        bench.ours(acceptedAt + 20_000L, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(0, 20)) + ten().take(9), dayRaw = 220))

        val rows = bench.rows().sortedBy { it.timestampMs }
        assertEquals(listOf(0.0, 0.5), rows.map { it.amountUnits })
        assertNull(rows[0].pumpId)
        assertTrue(rows[1].pumpId != null)
        assertEquals(listOf(NoticeKind.OURS_NO_RECORD), bench.notices().map { it.kind })
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `ours with nothing seen, one sound record of somebody else that sums - the record as it is, ours gets nothing`() {
        bench.ours(acceptedAt, 1.0, 0.0, seen = false)
        assertTrue(bench.settle(listOf(at(1, 80)) + ten().take(9), dayRaw = 280))

        val rows = bench.rows().sortedBy { it.timestampMs }
        assertEquals(listOf(0.0, 2.0), rows.map { it.amountUnits })
        assertNull(rows[0].pumpId)
        assertTrue(rows[1].pumpId != null)
        assertTrue(AapsWrite.OpenLoop !in bench.written)
        assertTrue(bench.notices().none { it.kind == NoticeKind.JOURNAL_MISMATCH })
    }

    @Test
    fun `ours stopped at nothing, one sound record of somebody else that sums - the record as it is, ours stays at nothing`() {
        bench.ours(acceptedAt, 1.0, 0.0)
        assertTrue(bench.settle(listOf(at(1, 80)) + ten().take(9), dayRaw = 280))

        assertEquals(listOf(0.0, 2.0), bench.rows().sortedBy { it.timestampMs }.map { it.amountUnits })
        assertTrue(bench.notices().isEmpty())
        assertTrue(AapsWrite.OpenLoop !in bench.written)
    }

    @Test
    fun `two of ours and two sound records of somebody else, none pair, the records sum - the records are the truth, ours get nothing`() {
        bench.ours(acceptedAt, 0.5, 0.0, seen = false)
        bench.ours(acceptedAt + 20_000L, 0.7, 0.0, seen = false)
        assertTrue(bench.settle(listOf(at(2, 40), at(1, 80)) + ten().take(8), dayRaw = 320))

        val foreign = bench.rows().filter { it.pumpId != null }
        assertEquals(listOf(1.0, 2.0), foreign.map { it.amountUnits }.sorted())
        assertTrue(bench.rows().filter { it.pumpId == null }.all { it.amountUnits == 0.0 })
        assertTrue(AapsWrite.OpenLoop !in bench.written)
    }

    @Test
    fun `a faulty record of somebody else - its amount is not taken, the field gives it, it stands in the middle of its window`() {
        val nowMs = NOW_MS + 5 * 60_000L
        assertTrue(bench.settle(listOf(oldCell()) + ten().take(9), dayRaw = 212, nowMs = nowMs))

        val row = bench.rows().single()
        assertEquals(0.3, row.amountUnits, 1e-9)
        // The last read was at NOW_MS, this one five minutes later: the middle of the two.
        assertEquals(NOW_MS + 150_000L, row.timestampMs)
        assertEquals(NoticeKind.JOURNAL_FAULT, bench.notices().single().kind)
    }

    @Test
    fun `the first answer with a picture of ours open and no record of it - the picture is closed, its row stays as written`() {
        val fresh = JournalBench()
        fresh.ours(acceptedAt, 0.5, 0.5)
        // The row of our bolus, written when the pump accepted it, is the newest bolus in AAPS.
        fresh.newestBolusMs = acceptedAt
        fresh.thenWhole(ten())
        assertTrue(fresh.settle(ten(), dayRaw = 200))

        assertTrue(fresh.rows().isEmpty())
        assertTrue(fresh.openCommands().isEmpty())
        assertTrue("bolus_start" in fresh.traced())
    }
}
