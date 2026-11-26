package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.bolus.JournalBench.Companion.at
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A pump whose bolus journal is empty answers with a count of 0: a state, and its first record is a new bolus. */
class EmptyJournalTest {

    private val bench = JournalBench()

    @Test
    fun `the pump says its journal is empty soon after the cells were laid over - waited out, the records that come back are not new`() {
        bench.past(JournalBench.ten(), dayRaw = 80)
        bench.thenWhole(emptyList())
        assertFalse(bench.settle(emptyList(), dayRaw = 80, recordCount = 0))
        assertTrue(bench.settle(listOf(at(1, 8)) + JournalBench.ten().take(9), dayRaw = 88))
        assertEquals(0.2, bench.rows().single().amountUnits, 1e-9)
    }

    @Test
    fun `cells lost from the base - the answer is not laid over what is left and the pump holds no fewer records, an error of the driver`() {
        bench.past(JournalBench.ten(), dayRaw = 80)
        val cells = bench.store.cells(bench.serial).filter { it.seq != null }.sortedBy { it.seq }
        bench.store.dropCells(bench.serial, cells.takeLast(3).map { it.id })
        bench.thenWhole(listOf(at(1, 8)) + JournalBench.ten(), recordCount = BolusCells.CAPACITY.toInt())
        assertThrows(IllegalStateException::class.java) { bench.settle(listOf(at(1, 8)) + JournalBench.ten().take(9), dayRaw = 88) }
        assertTrue(bench.rows().isEmpty())
    }

    @Test
    fun `an empty journal is settled with nothing, and the first record after it is written as a bolus`() {
        bench.thenWhole(emptyList())
        assertTrue(bench.settle(emptyList(), dayRaw = 0, recordCount = 0))
        assertTrue(bench.rows().isEmpty())

        assertTrue(bench.settle(listOf(at(0, 12)), dayRaw = 12))
        assertEquals(0.3, bench.rows().single().amountUnits, 1e-9)
    }
}
