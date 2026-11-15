package app.aaps.pump.apex.account.bolus

import app.aaps.pump.apex.account.bolus.JournalBench.Companion.TWO
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.at
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.ten
import app.aaps.pump.apex.state.ApexAbilities
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A pump that loses the daily statistics for a while and gets them back must not have the boluses of that time written twice. */
class JournalAbilityChangeTest {

    private val bench = JournalBench()
    private val firmware = "1.1.1"
    private val logger = AAPSLoggerTest()

    @Test
    fun `statistics back after two boluses settled without them - no row for unknown boluses`() {
        // A day kept: the bolus field stands at 200 steps.
        ApexAbilities.store(logger, bench.store, bench.serial, firmware, ApexAbilities())
        bench.past(ten(), dayRaw = 200)

        // The statistics are gone from the pump's answers; two boluses of ours are settled from the journal alone.
        ApexAbilities.store(logger, bench.store, bench.serial, firmware, ApexAbilities(dayRecord = false))
        bench.ours(TWO * 1000L + 5_000L, 0.3, 0.3)
        assertTrue(bench.settle(listOf(at(0, 12)) + ten().take(9), dayRaw = null))
        // As the heartbeat does on a pump without the statistics: nothing compares the settled boluses with a day, so the list is cleared.
        BolusAccount(bench.store, bench.serial).clearSettledSinceHeartbeat()
        bench.ours(TWO * 1000L + 65_000L, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(1, 20), at(0, 12)) + ten().take(8), dayRaw = null))
        // As the heartbeat does on a pump without the statistics: nothing compares the settled boluses with a day, so the list is cleared.
        BolusAccount(bench.store, bench.serial).clearSettledSinceHeartbeat()
        assertEquals(listOf(0.3, 0.5), bench.rows().map { Math.round(it.amountUnits * 1000) / 1000.0 })
        bench.written.clear()

        // The statistics come back, the field moved by those two boluses: 200 + 12 + 20 steps.
        ApexAbilities.store(logger, bench.store, bench.serial, firmware, ApexAbilities())
        assertTrue(bench.settle(listOf(at(1, 20), at(0, 12)) + ten().take(8), dayRaw = 232))

        assertTrue(bench.rows().isEmpty())
        assertTrue(bench.notices().isEmpty())
    }
}
