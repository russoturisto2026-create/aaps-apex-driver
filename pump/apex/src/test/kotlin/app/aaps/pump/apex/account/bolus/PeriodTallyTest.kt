package app.aaps.pump.apex.account.bolus

import app.aaps.pump.apex.account.basal.BasalPeriod
import app.aaps.pump.apex.account.basal.BasalPeriodKeeper
import app.aaps.pump.apex.account.basal.Delivered
import app.aaps.pump.apex.account.basal.PeriodBoluses
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.TWO
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.at
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.ten
import app.aaps.pump.apex.protocol.ApexDailyRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The bolus rows written are added to the boluses of the period under way only when that period is counted from the pump's count. */
class PeriodTallyTest {

    private val bench = JournalBench()
    private val periodAtMs = TWO * 1000L - 60_000L

    init {
        bench.past(ten(), dayRaw = 200)
    }

    private fun begin(mark: BasalPeriod.Mark?) = bench.store.putState(bench.serial, BasalPeriodKeeper.STATE_PERIOD, mark?.encode())

    private fun tally() = PeriodBoluses(bench.store, bench.serial).sum()

    private fun settleOneRecord() {
        assertTrue(bench.settle(listOf(at(0, 20)) + ten().take(9), dayRaw = 220))
        assertEquals(1, bench.rows().size)
    }

    @Test
    fun `under a mark of the count kind the row written is added`() {
        begin(BasalPeriod.Mark(periodAtMs, Delivered.Count(10.0, 1L)))
        settleOneRecord()
        assertEquals(0.5, tally(), 1e-9)
    }

    @Test
    fun `under a mark of the day record kind nothing is added`() {
        begin(BasalPeriod.Mark(periodAtMs, ApexDailyRecord(0, 1.0, 10.0, 0.0, 0L)))
        settleOneRecord()
        assertEquals(0.0, tally(), 1e-9)
    }

    @Test
    fun `with no period under way nothing is added`() {
        begin(null)
        settleOneRecord()
        assertEquals(0.0, tally(), 1e-9)
    }
}
