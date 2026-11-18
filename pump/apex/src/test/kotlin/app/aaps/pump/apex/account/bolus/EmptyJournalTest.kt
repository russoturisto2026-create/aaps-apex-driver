package app.aaps.pump.apex.account.bolus

import app.aaps.pump.apex.account.bolus.JournalBench.Companion.at
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A pump whose bolus journal is empty answers with a count of 0: a state, and its first record is a new bolus. */
class EmptyJournalTest {

    private val bench = JournalBench()

    @Test
    fun `an empty journal is settled with nothing, and the first record after it is written as a bolus`() {
        assertTrue(bench.settle(emptyList(), dayRaw = 0, recordCount = 0))
        assertTrue(bench.rows().isEmpty())

        assertTrue(bench.settle(listOf(at(0, 12)), dayRaw = 12))
        assertEquals(0.3, bench.rows().single().amountUnits, 1e-9)
    }
}
