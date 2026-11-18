package app.aaps.pump.apex.account.bolus

import app.aaps.pump.apex.account.bolus.JournalBench.Companion.TWO
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.at
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.ten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The short way of our bolus: the last record read alone confirms it, with no journal and no count,
 * when the record read just before the bolus was the top of the copy kept. The past is ten records
 * of 0.2 U, the newest a minute before two.
 */
class JournalLastRecordTest {

    private val bench = JournalBench()
    private val acceptedAt = TWO * 1000L + 5_000L

    init {
        bench.past(ten(), dayRaw = 200)
    }

    @Test
    fun `the record read before is the top of the copy, a new one is not`() {
        assertTrue(bench.topIs(ten().first()))
        assertFalse(bench.topIs(at(0, 20)))
    }

    @Test
    fun `our new record confirms our bolus - our row, the command closed`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settleOurLast(at(0, 20)))

        val row = bench.rows().single()
        assertEquals(0.5, row.amountUnits, 1e-9)
        assertEquals("SMB", row.type)
        assertTrue(bench.openCommands().isEmpty())
        assertTrue(bench.topIs(at(0, 20)))
    }

    @Test
    fun `the next journal read knows the record already - no second row`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settleOurLast(at(0, 20)))
        bench.written.clear()

        assertTrue(bench.settle(listOf(at(0, 20)) + ten().take(9), dayRaw = 220))
        assertTrue(bench.rows().isEmpty())
    }

    @Test
    fun `the row has the number the journal would give it`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settleOurLast(at(0, 20)))
        val short = bench.rows().single()

        val full = JournalBench().apply { past(ten(), dayRaw = 200) }
        full.ours(acceptedAt, 0.5, 0.5)
        assertTrue(full.settle(listOf(at(0, 20)) + ten().take(9), dayRaw = 220))
        assertEquals(full.rows().single().pumpId, short.pumpId)
        assertEquals(full.rows().single().timestampMs, short.timestampMs)
    }

    @Test
    fun `the last record is still the kept top - nothing is written, the command stays open`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertFalse(bench.settleOurLast(ten().first()))
        assertTrue(bench.written.isEmpty())
        assertEquals(1, bench.openCommands().size)
    }

    @Test
    fun `a new record that asks another dose is not ours - nothing kept, the full reconciliation settles it`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertFalse(bench.settleOurLast(at(0, 12)))
        assertTrue(bench.written.isEmpty())
        assertEquals(1, bench.openCommands().size)
        assertTrue(bench.topIs(ten().first()))
    }

    @Test
    fun `after our bolus an answer that does not lay over the copy is left to the heartbeat - nothing written, not read again`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertFalse(bench.settleAfterOurBolus(listOf(at(0, 20)) + ten(minutesBeforeTwo = 30).take(9)))
        assertTrue(bench.written.isEmpty())
        assertEquals(1, bench.openCommands().size)
        assertTrue(bench.topIs(ten().first()))
    }

    @Test
    fun `an answer with no new record leaves the note - the record at the heartbeat is still ours`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settleAfterOurBolus(ten()))
        assertTrue(bench.written.isEmpty())
        assertEquals(1, bench.openCommands().size)

        assertTrue(bench.settle(listOf(at(0, 20)) + ten().take(9), dayRaw = 220))
        val row = bench.rows().single()
        assertEquals("SMB", row.type)
        assertEquals(TWO * 1000L + 5_000L, row.timestampMs)
        assertTrue(bench.openCommands().isEmpty())
    }
}
