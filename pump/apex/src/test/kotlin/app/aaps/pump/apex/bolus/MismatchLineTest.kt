package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.aaps.NoticeKind
import app.aaps.pump.apex.bolus.JournalBench.Companion.NOW_MS
import app.aaps.pump.apex.bolus.JournalBench.Companion.TWO
import app.aaps.pump.apex.bolus.JournalBench.Companion.at
import app.aaps.pump.apex.statistics.StatisticsDays
import app.aaps.pump.apex.store.FakeStore
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/** The line drawn when the bolus records do not sum to the statistics, and the day check counted from it. */
class MismatchLineTest {

    private val store = FakeStore()
    private val line = MismatchLine(store, "12345678")

    /** Today 0.1 U at 14:03 and 14:05, 0.3 U at 14:07, and 0.2 U yesterday: the journal holds today whole. */
    private val records = listOf(BolusRecordFrames.record(TWO - 20 * 3600L, 8, 8), at(3, 4), at(5, 4), at(7, 12))

    private fun lines(answered: List<BolusCells.Answered>) = answered.mapIndexed { i, it ->
        val atMs = it.clockSeconds * 1000L
        val bolus = ApexBolusRecord.decode(it.frame)!!
        JournalLine(JournalEntry(i + 1L, it.frame, it.clockSeconds, atMs), bolus, null, faulty = false, candidate = false, units = bolus.totalDeliveredUnits, atMs = atMs)
    }

    private fun check(answered: List<BolusCells.Answered>, dayRaw: Int, mark: MismatchLine.Mark?) =
        WholeJournalDays(AAPSLoggerTest(), mock(ApexTrace::class.java), HashMap()).checkDays(lines(answered), emptyList(), JournalBench.days(dayRaw).entries.map { it.record }, 0L, 0L, mark)

    private fun today() = StatisticsDays.datesOf(JournalBench.days(24).entries.map { it.record }).first()!!

    @Test
    fun `the day short by 0_1 U with no line - 0_1 U is left, from the start of the day`() {
        val checked = check(records, dayRaw = 24, mark = null)
        assertEquals(0.1, checked.leftUnits, 1e-9)
        assertEquals(today() * 1000L, checked.leftFromMs)
    }

    @Test
    fun `the same day after a line drawn at its field - nothing before the line counts, nothing is left`() {
        val checked = check(records, dayRaw = 24, mark = MismatchLine.Mark(NOW_MS, today(), 0.6))
        assertEquals(0.0, checked.leftUnits, 1e-9)
        assertNull(checked.leftFromMs)
    }

    @Test
    fun `after the line a record of 0_2 U and the field 0_2 U above the line - it sums, nothing is left`() {
        val after = records + at(20, 8)
        assertEquals(0.0, check(after, dayRaw = 32, mark = MismatchLine.Mark(NOW_MS, today(), 0.6)).leftUnits, 1e-9)
    }

    @Test
    fun `after the line the field 0_1 U above what came - left from the line on`() {
        val after = records + at(20, 8)
        val checked = check(after, dayRaw = 36, mark = MismatchLine.Mark(NOW_MS, today(), 0.6))
        assertEquals(0.1, checked.leftUnits, 1e-9)
        assertEquals(NOW_MS, checked.leftFromMs)
    }

    @Test
    fun `a line tells the user and opens the loop, the second one in a day is a serious error, a new day starts anew`() {
        val days = JournalBench.days(24).entries.map { it.record }
        val first = line.draw(NOW_MS - 300_000L, NOW_MS, -0.1, days)
        assertEquals(listOf(AapsWrite.Notice(NoticeKind.JOURNAL_MISMATCH, 0.1, NOW_MS, NOW_MS - 300_000L), AapsWrite.OpenLoop), first)
        assertEquals(NOW_MS, line.mark()!!.atMs)
        assertEquals(0.6, line.mark()!!.fieldUnits, 1e-9)
        val second = line.draw(NOW_MS, NOW_MS + 60_000L, 0.2, days)
        assertEquals(NoticeKind.JOURNAL_MISMATCH_AGAIN, (second.first() as AapsWrite.Notice).kind)
        val nextDay = listOf(JournalBench.day(4, 4, 0).record)
        assertEquals(NoticeKind.JOURNAL_MISMATCH, (line.draw(NOW_MS, NOW_MS + 86_400_000L, 0.2, nextDay).first() as AapsWrite.Notice).kind)
    }
}
