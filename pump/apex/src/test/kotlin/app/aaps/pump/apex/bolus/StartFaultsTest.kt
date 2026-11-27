package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.aaps.NoticeKind
import app.aaps.pump.apex.bolus.JournalBench.Companion.NOW_MS
import app.aaps.pump.apex.bolus.JournalBench.Companion.TWO
import app.aaps.pump.apex.bolus.JournalBench.Companion.at
import app.aaps.pump.apex.bolus.JournalBench.Companion.oldCell
import app.aaps.pump.apex.store.BolusCell
import app.aaps.pump.apex.clock.ApexClock
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The start reconciliation over a journal with a faulty cell on top, 03.11 at a quarter past two: the cells
 * stand at their own stamps, the faulty one is found and takes what the sound records of the day leave of
 * the bolus field. The journal holds the day whole: its oldest record is of 02.11.
 */
class StartFaultsTest {

    private val bench = JournalBench()

    /** A sound record of yesterday, 02.11 18:00, 0.2 U: the oldest of the journal. */
    private val yesterday = BolusRecordFrames.record(TWO - 20 * 3600L, 8, 8)

    /** The journal, newest first: [top], then 0.1 U at 14:05 and 14:03, then yesterday's record. */
    private fun journal(top: BolusCells.Answered) = listOf(top, at(5, 4), at(3, 4), yesterday)

    private fun cells(): List<BolusCell> = bench.store.cells(bench.serial).sortedBy { it.seq }

    /** The newest bolus in AAPS a minute after 14:07: the bound is after 14:05, every record stays in the cells. */
    private fun aapsHasTheTop() {
        bench.newestBolusMs = (TWO + 8 * 60L) * 1000L
    }

    private fun settle(top: BolusCells.Answered, dayRaw: Int): Boolean {
        val records = journal(top)
        bench.thenWhole(records)
        return bench.settle(records, dayRaw = dayRaw)
    }

    @Test
    fun `an old record copied on top and the day short of it - faulty, it takes the rest in the middle of its window, the cells only`() {
        aapsHasTheTop()
        // The bolus field: 0.1 + 0.1 and the 0.3 U the copy hides.
        assertTrue(settle(yesterday, dayRaw = 20))
        val top = cells().last()
        assertTrue(top.faulty)
        assertEquals(0.3, top.units!!, 1e-9)
        assertEquals(((TWO + 5 * 60 + 59) * 1000L + NOW_MS) / 2, top.atMs)
        assertTrue(bench.rows().isEmpty())
        assertTrue(bench.notices().isEmpty())
    }

    @Test
    fun `the records below the copy stand at their own stamps`() {
        aapsHasTheTop()
        assertTrue(settle(yesterday, dayRaw = 20))
        val below = cells().dropLast(1)
        assertEquals(listOf(yesterday, at(3, 4), at(5, 4)).map { it.clockSeconds * 1000L }, below.map { it.atMs })
        assertTrue(below.none { it.faulty })
    }

    @Test
    fun `a record stamped before the one below it, the day holds it, no zone move - its date is faulty, its dose stays, it stands in the window`() {
        aapsHasTheTop()
        // The bolus field: 0.1 + 0.1 + 0.2 of the record on top.
        assertTrue(settle(yesterday, dayRaw = 16))
        val top = cells().last()
        assertTrue(top.faulty)
        assertEquals(0.2, top.units!!, 1e-9)
        assertEquals(((TWO + 5 * 60 + 59) * 1000L + NOW_MS) / 2, top.atMs)
        assertTrue(bench.notices().isEmpty())
    }

    @Test
    fun `a record stamped before the one below it with a zone move logged between - it stays at its stamp`() {
        aapsHasTheTop()
        ApexClock.clockWritten(bench.store, bench.serial, 0L, (TWO - 21 * 3600L) * 1000L, "first write", bolusCount = 0)
        ApexClock.clockWritten(bench.store, bench.serial, 3_600_000L, (TWO + 6 * 60L) * 1000L, "zone", bolusCount = 0)
        assertTrue(settle(yesterday, dayRaw = 16))
        val top = cells().last()
        assertFalse(top.faulty)
        assertEquals(0.2, top.units!!, 1e-9)
    }

    @Test
    fun `a record stamped before the one below it, AAPS holds the rows of those below by their key - sound, those keep their rows, no notice`() {
        bench.newestBolusMs = null
        // The two records of today were stamped while the clock stood an hour ahead: AAPS holds them an hour before their stamps.
        bench.aapsRows = listOf(at(3, 4), at(5, 4)).associate { keyOf(it) to (it.clockSeconds - 3600L) * 1000L }
        val top = BolusRecordFrames.record(TWO - 30 * 60L, 8, 8)
        assertTrue(settle(top, dayRaw = 16))
        assertTrue(cells().none { it.faulty })
        // Yesterday's record and the one on top at their stamps, the two between at their rows.
        val stamps = listOf(yesterday.clockSeconds, TWO - 3600L + 3 * 60 + 59, TWO - 3600L + 5 * 60 + 59, top.clockSeconds)
        assertEquals(stamps.map { it * 1000L }, cells().map { it.atMs })
        // Only the record on top goes to AAPS, at the minute of its stamp; the rows of the two stay as they are.
        assertEquals(listOf(top.clockSeconds / 60 * 60_000L), bench.rows().map { it.timestampMs })
        assertTrue(bench.notices().isEmpty())
    }

    private fun keyOf(answered: BolusCells.Answered) = BolusKey.of(ApexBolusRecord.decode(answered.frame)!!, 0)

    @Test
    fun `a faulty record whose window is after the bound - its row goes to AAPS with the rest, and the user is told`() {
        bench.newestBolusMs = null
        assertTrue(settle(yesterday, dayRaw = 20))
        val faulty = bench.rows().single { it.timestampMs == ((TWO + 5 * 60 + 59) * 1000L + NOW_MS) / 2 }
        assertEquals(0.3, faulty.amountUnits, 1e-9)
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT), bench.notices().map { it.kind })
        // The two sound records of today go too; yesterday's is older than the insulin acts.
        assertEquals(3, bench.rows().size)
    }

    @Test
    fun `more delivered than asked on top - faulty at once, it takes the rest`() {
        aapsHasTheTop()
        assertTrue(settle(BolusRecordFrames.record(TWO + 7 * 60L, 4, 12), dayRaw = 20))
        val top = cells().last()
        assertTrue(top.faulty)
        assertEquals(0.3, top.units!!, 1e-9)
        assertTrue(bench.notices().isEmpty())
    }

    @Test
    fun `the day is short and no record takes the rest - a line from the start of the day, the loop opened`() {
        aapsHasTheTop()
        assertTrue(settle(at(7, 12), dayRaw = 24))
        val notice = bench.notices().single()
        assertEquals(NoticeKind.JOURNAL_MISMATCH, notice.kind)
        assertEquals(0.1, notice.units, 1e-9)
        assertTrue(AapsWrite.OpenLoop in bench.written)
    }


    @Test
    fun `the journal holds no whole day - nothing is checked, a candidate keeps its dose`() {
        aapsHasTheTop()
        val records = listOf(oldCell(), at(5, 4), at(3, 4))
        bench.thenWhole(records)
        assertTrue(bench.settle(records, dayRaw = 200))
        // No day is checked; with no zone move its date is faulty all the same, its dose stays.
        val top = cells().last()
        assertTrue(top.faulty)
        assertEquals(0.675, top.units!!, 1e-9)
        assertEquals(((TWO + 5 * 60 + 59) * 1000L + NOW_MS) / 2, top.atMs)
        assertTrue(bench.notices().isEmpty())
    }

    @Test
    fun `each cell keeps when the start ran and the bolus field of its day`() {
        aapsHasTheTop()
        assertTrue(settle(yesterday, dayRaw = 20))
        assertTrue(cells().all { it.settledAtMs == NOW_MS })
        assertEquals(listOf(null, 0.5, 0.5, 0.5), cells().map { it.dayBolusUnits })
    }

    @Test
    fun `a window across midnight, the day after whole by the statistics - the record stands before the midnight`() {
        aapsHasTheTop()
        // Stamped 02.11 10:00, before yesterday's 18:00 below it; the record above is of 03.11 14:03.
        val records = listOf(at(5, 4), at(3, 4), BolusRecordFrames.record(TWO - 28 * 3600L, 8, 8), yesterday)
        bench.thenWhole(records)
        assertTrue(bench.settle(records, dayRaw = 8))
        val moved = cells().single { it.faulty }
        val fromMs = (TWO - 20 * 3600L + 59) * 1000L
        val midnightMs = (TWO - 14 * 3600L) * 1000L
        assertEquals((fromMs + midnightMs - 1) / 2, moved.atMs)
    }

    @Test
    fun `two faulty records of one day - the first takes the rest, and the user is warned`() {
        aapsHasTheTop()
        val records = listOf(BolusRecordFrames.record(TWO + 7 * 60L, 4, 12), at(5, 4), BolusRecordFrames.record(TWO + 4 * 60L, 4, 12), at(3, 4), yesterday)
        bench.thenWhole(records)
        assertTrue(bench.settle(records, dayRaw = 20))
        val faulty = cells().filter { it.faulty }
        assertEquals(0.3, faulty[0].units!!, 1e-9)
        assertEquals(0.0, faulty[1].units!!, 1e-9)
        assertEquals(listOf(NoticeKind.FAULTS_SHARED), bench.notices().map { it.kind })
        assertEquals(0.3, bench.notices().single().units, 1e-9)
    }

    @Test
    fun `midnight between the journal read and the statistics read - the records of 03_11 are held against the day 03_11, not against the new day`() {
        bench.newestBolusMs = null
        val midnight = TWO + 10 * 3600L
        // 03.11 23:50 and 23:55, 0.1 U each; the statistics, read after midnight, have 04.11 with nothing on top of 03.11 with 0.2 U.
        val records = listOf(BolusRecordFrames.record(midnight - 5 * 60L, 4, 4), BolusRecordFrames.record(midnight - 10 * 60L, 4, 4), yesterday)
        bench.thenWhole(records)
        val days = ApexJournalAnswer(listOf(JournalBench.day(0, 4, 0), JournalBench.day(8, 3, 1)), 2)
        assertTrue(bench.settleWith(records, days, nowMs = (midnight + 30) * 1000L))
        assertTrue(cells().none { it.faulty })
        assertTrue(bench.notices().isEmpty())
        assertEquals(2, bench.rows().size)
    }

    @Test
    fun `a window across midnight, the day after short by its dose - the record stands after the midnight, its dose stays`() {
        aapsHasTheTop()
        val records = listOf(at(5, 4), at(3, 4), BolusRecordFrames.record(TWO - 28 * 3600L, 8, 8), yesterday)
        bench.thenWhole(records)
        // 03.11: 0.1 + 0.1 and the 0.2 U of the record.
        assertTrue(bench.settle(records, dayRaw = 16))
        val moved = cells().single { it.faulty }
        val midnightMs = (TWO - 14 * 3600L) * 1000L
        assertEquals((midnightMs + (TWO + 3 * 60 + 59) * 1000L) / 2, moved.atMs)
        assertEquals(0.2, moved.units!!, 1e-9)
        assertTrue(bench.notices().isEmpty())
    }
}
