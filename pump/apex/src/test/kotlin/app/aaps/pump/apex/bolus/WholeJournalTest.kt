package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.bolus.JournalBench.Companion.at
import app.aaps.pump.apex.aaps.NoticeKind
import app.aaps.pump.apex.bolus.JournalBench.Companion.NOW_MS
import app.aaps.pump.apex.statistics.StatisticsDays
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A journal that does not lay over the cells is new when the pump holds fewer records than it did, or when
 * every record is newer than the cells: the pump wrote more boluses than its journal holds since the last read.
 */
class WholeJournalTest {

    private val bench = JournalBench()

    /** Three records of after two, none of them in the cells. */
    private val others = listOf(at(9, 4), at(7, 4), at(5, 4))

    /** A record of yesterday, 02.11 18:00, 0.2 U: with it the journal holds today whole. */
    private val yesterday = BolusRecordFrames.record(JournalBench.TWO - 20 * 3600L, 8, 8)

    @Test
    fun `the pump holds fewer records than before - its journal started anew, its records are new`() {
        bench.past(JournalBench.ten(), dayRaw = 80)
        bench.thenWhole(others)
        assertTrue(bench.settle(others, dayRaw = 92))
        assertEquals(3, bench.rows().size)
        assertEquals(0.3, bench.rows().sumOf { it.amountUnits }, 1e-9)
    }

    @Test
    fun `the journal does not lay over the cells and the pump holds as many records, a full journal - an error of the driver, nothing is written`() {
        bench.past(JournalBench.ten(), dayRaw = 80)
        val tenOthers = others + JournalBench.ten(minutesBeforeTwo = 30).take(7)
        bench.thenWhole(tenOthers, recordCount = BolusCells.CAPACITY.toInt())
        assertThrows(IllegalStateException::class.java) { bench.settle(tenOthers, dayRaw = 92) }
        assertTrue(bench.rows().isEmpty())
    }

    @Test
    fun `more boluses than the journal holds since the last read, every record newer than the cells - all new, written, the next answer lays over them`() {
        bench.past(JournalBench.ten(), dayRaw = 80)
        val capacity = BolusCells.CAPACITY.toInt()
        val all = (capacity downTo 1).map { BolusRecordFrames.record(JournalBench.TWO + 6L * it, 4, 4) }
        bench.thenWhole(all, recordCount = capacity)
        assertTrue(bench.settle(all.take(10), dayRaw = 80 + 4 * capacity, recordCount = capacity))
        assertEquals(capacity, bench.rows().size)
        assertTrue(AapsWrite.OpenLoop !in bench.written)

        bench.written.clear()
        assertTrue(bench.settle(listOf(at(14, 8)) + all.take(9), dayRaw = 88 + 4 * capacity, recordCount = capacity))
        assertEquals(0.2, bench.rows().single().amountUnits, 1e-9)
    }

    /** The journal of [records] read whole with the statistics: today 03.11 at [todayRaw], the closed day 02.11 at [closedRaw]. */
    private fun wholeJournal(records: List<BolusCells.Answered>, todayRaw: Int, closedRaw: Int, allDays: Boolean): Boolean {
        bench.thenWhole(records)
        val days = ApexJournalAnswer(listOf(JournalBench.day(todayRaw, 3, 0), JournalBench.day(closedRaw, 2, 1)), 2)
        return bench.settleWith(records, days, NOW_MS, allDays)
    }

    private fun closedDayKept(): Double = StatisticsDays(bench.store, bench.serial).kept()[1].bolusUnits

    @Test
    fun `the journal read whole at a heartbeat keeps the closed days of the copy, the first reconciliation since AAPS started takes them`() {
        bench.newestBolusMs = null
        assertTrue(wholeJournal(others + yesterday, todayRaw = 12, closedRaw = 40, allDays = true))
        assertEquals(1.0, closedDayKept(), 1e-9)
        // The pump started its journal anew: two records, fewer than four; one of yesterday keeps 02.11 in the copy.
        // Today is whole in the journal, so its records sum to its bolus field.
        val lateYesterday = BolusRecordFrames.record(JournalBench.TWO - 19 * 3600L, 8, 8)
        assertTrue(wholeJournal(listOf(at(11, 16), lateYesterday), todayRaw = 16, closedRaw = 99, allDays = false))
        assertEquals(1.0, closedDayKept(), 1e-9)
        bench.store.putState(bench.serial, BolusCells.COUNT_KEY, "5")
        assertTrue(wholeJournal(listOf(at(12, 20), lateYesterday), todayRaw = 20, closedRaw = 99, allDays = true))
        assertEquals(2.475, closedDayKept(), 1e-9)
    }

    @Test
    fun `our bolus with no record in the journal read whole, the field of its day did not move by it - nothing, the user is told`() {
        bench.newestBolusMs = null
        bench.ours(JournalBench.TWO * 1000L + 10 * 60_000L, 0.5, 0.0, seen = false)
        assertTrue(wholeJournal(others + yesterday, todayRaw = 12, closedRaw = 40, allDays = true))
        assertEquals(0.0, bench.rows().single { it.tempId != null }.amountUnits, 1e-9)
        assertEquals(listOf(NoticeKind.OURS_NO_RECORD), bench.notices().map { it.kind })
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `our bolus with no record in the journal read whole, the field of its day holds it - it stays, nothing to tell`() {
        bench.newestBolusMs = null
        bench.ours(JournalBench.TWO * 1000L + 10 * 60_000L, 0.5, 0.0, seen = false)
        assertTrue(wholeJournal(others + yesterday, todayRaw = 32, closedRaw = 40, allDays = true))
        assertTrue(bench.rows().none { it.tempId != null })
        assertTrue(bench.notices().isEmpty())
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `a faulty record of the journal read whole above the reservoir - not written, the user is told`() {
        bench.newestBolusMs = null
        bench.status(reservoirUnits = 50.0)
        // More given than asked: faulty at once; the day leaves it 99.8 U.
        val records = listOf(BolusRecordFrames.record(JournalBench.TWO + 7 * 60L, 4, 12), at(5, 4), at(3, 4), yesterday)
        bench.thenWhole(records)
        assertTrue(bench.settle(records, dayRaw = 4000))
        assertTrue(bench.rows().none { it.amountUnits > 50.0 })
        assertTrue(bench.notices().any { it.kind == NoticeKind.IMPLAUSIBLE_ROW })
    }
}
