package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.aaps.NoticeKind
import app.aaps.pump.apex.bolus.JournalBench.Companion.TWO
import app.aaps.pump.apex.bolus.JournalBench.Companion.at
import app.aaps.pump.apex.bolus.JournalBench.Companion.oldCell
import app.aaps.pump.apex.bolus.JournalBench.Companion.ten
import app.aaps.pump.apex.bolus.JournalBench.Companion.undated
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Faulty records through the real bolus journal. A record that asks and gives what our bolus did is
 * its record, whatever its stamp. One record left against our picture is our bolus by what we saw,
 * and the user is told when it looks faulty. Each test is one answer of the pump after the past of
 * ten records of 0.2 U, the newest a minute before two, and our bolus of 0.5 U accepted at two.
 */
class JournalFaultPicturesTest {

    private val bench = JournalBench()
    private val acceptedAt = TWO * 1000L + 5_000L

    init {
        bench.past(ten(), dayRaw = 200)
    }

    private fun assertOurs(units: Double, fault: Boolean) {
        val row = bench.rows().single()
        assertEquals(units, row.amountUnits, 1e-9)
        assertEquals(acceptedAt, row.timestampMs)
        assertEquals("SMB", row.type)
        assertEquals(if (fault) listOf(NoticeKind.JOURNAL_FAULT) else emptyList<NoticeKind>(), bench.notices().map { it.kind })
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `a row of more than the reservoir held is not written - the user is told`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        bench.status(reservoirUnits = 0.3)
        assertTrue(bench.settle(listOf(at(0, 20)) + ten().take(9), dayRaw = 220))
        assertTrue(bench.rows().isEmpty())
        assertEquals(listOf(NoticeKind.IMPLAUSIBLE_ROW), bench.notices().map { it.kind })
    }

    @Test
    fun `an old record in our cell - ours, 0_5 U, the user is told`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(oldCell()) + ten().take(9), dayRaw = 220))
        assertOurs(0.5, fault = true)
    }

    @Test
    fun `a record with no date the same at every read is a faulty cell - ours takes it, 0_5 U as the field says, the user is told`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(undated(20)) + ten().take(9), dayRaw = 220))
        assertOurs(0.5, fault = true)
    }

    @Test
    fun `a record of today with another amount against our picture - ours by what we saw, the field agrees`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(0, 12)) + ten().take(9), dayRaw = 220))
        assertOurs(0.5, fault = false)
    }

    @Test
    fun `a record of nothing delivered the same at every read is a faulty cell - ours takes it, 0_5 U as the field says, the user is told`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(0, 0)) + ten().take(9), dayRaw = 220))
        assertOurs(0.5, fault = true)
    }

    @Test
    fun `an old record over a record with no date left before - ours, 0_5 U, the user is told`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        bench.settle(listOf(undated(20)) + ten().take(9), dayRaw = 220)
        bench.written.clear()
        val ourAt = acceptedAt + 60_000L
        bench.ours(ourAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(oldCell(), undated(20)) + ten().take(8), dayRaw = 240))

        val row = bench.rows().single()
        assertEquals(0.5, row.amountUnits, 1e-9)
        assertEquals(ourAt, row.timestampMs)
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT), bench.notices().map { it.kind })
    }

    @Test
    fun `the field moved more than our whole bolus in the cell - ours up to its order, the rest left out, the loop opened`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(oldCell()) + ten().take(9), dayRaw = 226))
        assertEquals(listOf(0.5), bench.rows().map { it.amountUnits })
        assertEquals(0.15, bench.notices().single { it.kind == NoticeKind.JOURNAL_MISMATCH }.units, 1e-9)
        assertTrue(AapsWrite.OpenLoop in bench.written)
    }

    @Test
    fun `the field moved other than our stopped bolus reported - ours takes the move up to what we ordered`() {
        bench.ours(acceptedAt, 0.5, 0.45)
        assertTrue(bench.settle(listOf(oldCell()) + ten().take(9), dayRaw = 219))
        assertOurs(0.475, fault = true)
    }

    @Test
    fun `two records of other amounts and our one picture - the older is ours by what we saw, the other somebody else's, the field agrees`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(1, 12), at(0, 16)) + ten().take(8), dayRaw = 232))

        assertEquals(listOf(0.5, 0.3), bench.rows().map { Math.round(it.amountUnits * 1000) / 1000.0 })
        assertEquals(listOf("SMB", AapsWrite.NORMAL), bench.rows().map { it.type })
    }

    @Test
    fun `our record right and a faulty cell from a bolus on the pump - ours as recorded, the cell takes the rest`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(oldCell(), at(0, 20)) + ten().take(8), dayRaw = 232))

        assertEquals(listOf(0.5), bench.rows().filter { it.type == "SMB" }.map { it.amountUnits })
        assertEquals(0.3, bench.rows().single { it.type == AapsWrite.NORMAL }.amountUnits, 1e-9)
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT), bench.notices().map { it.kind })
    }

    @Test
    fun `a record stamped in the future that asks and gives what our bolus did - ours, at our command`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(60, 20)) + ten().take(9), dayRaw = 220))

        assertOurs(0.5, fault = false)
    }

    @Test
    fun `a record of more than the reservoir held next to our record - a faulty cell that takes the rest, not a row of 20 U`() {
        bench.status(reservoirUnits = 10.0)
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(1, 800), at(0, 20)) + ten().take(8), dayRaw = 232))

        assertEquals(listOf(0.5, 0.3), bench.rows().map { Math.round(it.amountUnits * 1000) / 1000.0 })
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT), bench.notices().map { it.kind })
    }

    @Test
    fun `the bolus field moved by more than the reservoir held - the statistics are not used, the row is as the record says`() {
        bench.status(reservoirUnits = 10.0)
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(0, 20)) + ten().take(9), dayRaw = 200 + 800))

        assertOurs(0.5, fault = false)
    }

    @Test
    fun `our bolus in a faulty cell, the statistics out of bounds - ours by what we saw, the user is told`() {
        bench.status(reservoirUnits = 10.0)
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(oldCell()) + ten().take(9), dayRaw = 200 + 800))

        assertOurs(0.5, fault = true)
        assertTrue("bolus_days_out_of_bounds" in bench.traced())
    }

    @Test
    fun `an answer that declares records and has none read is silence - nothing is settled, our command waits`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertFalse(bench.settle(emptyList(), dayRaw = 220, recordCount = 128))

        assertTrue(bench.rows().isEmpty())
        assertEquals(1, bench.openCommands().size)
    }

    @Test
    fun `a faulty cell with no command of ours - a row of the field's move, at the read, the user is told`() {
        assertTrue(bench.settle(listOf(oldCell()) + ten().take(9), dayRaw = 212))

        val row = bench.rows().single()
        assertEquals(0.3, row.amountUnits, 1e-9)
        assertEquals(AapsWrite.NORMAL, row.type)
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT), bench.notices().map { it.kind })
    }
}
