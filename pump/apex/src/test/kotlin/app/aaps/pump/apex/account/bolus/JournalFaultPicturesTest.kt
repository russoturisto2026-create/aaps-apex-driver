package app.aaps.pump.apex.account.bolus

import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.NoticeKind
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.TWO
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.at
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.oldCell
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.ten
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.undated
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Faulty records through the real bolus journal: one record after our command is our bolus,
 * whatever it holds, and the user is told. Each test is one answer of the pump after the past of
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
    fun `an old record in our cell - ours, 0_5 U, the user is told`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(oldCell()) + ten().take(9), dayRaw = 220))
        assertOurs(0.5, fault = true)
        assertEquals(0, bench.basalClosed)
    }

    @Test
    fun `a record with no date in our cell - ours, 0_5 U, the user is told`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(undated(20)) + ten().take(9), dayRaw = 220))
        assertOurs(0.5, fault = true)
    }

    @Test
    fun `a record of today with another amount after our command - written as it says, our command is closed without a row`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(0, 12)) + ten().take(9), dayRaw = 220))
        val row = bench.rows().single()
        assertEquals(0.3, row.amountUnits, 1e-9)
        assertEquals(AapsWrite.NORMAL, row.type)
        assertTrue(bench.notices().isEmpty())
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `a record that asks nothing after our command - written as it says, nothing, our command is closed without a row`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(0, 0)) + ten().take(9), dayRaw = 220))
        assertEquals(0.0, bench.rows().single().amountUnits, 1e-9)
        assertTrue(bench.notices().isEmpty())
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `an old record over a record with no date left before - ours, 0_5 U, the user is told`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        bench.settle(listOf(undated(20)) + ten().take(9), dayRaw = 220)
        bench.written.clear()
        val ourAt = acceptedAt + 60_000L
        BolusAccount(bench.store, bench.serial).commandAccepted(ourAt, 0.5, "SMB").also { BolusAccount(bench.store, bench.serial).finished(it, 0.5, completed = true) }
        assertTrue(bench.settle(listOf(oldCell(), undated(20)) + ten().take(8), dayRaw = 240))

        val row = bench.rows().single()
        assertEquals(0.5, row.amountUnits, 1e-9)
        assertEquals(ourAt, row.timestampMs)
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT), bench.notices().map { it.kind })
    }

    @Test
    fun `the field moved more than our bolus in the cell - the cell takes the move, 0_65, at our command`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(oldCell()) + ten().take(9), dayRaw = 226))
        assertOurs(0.65, fault = true)
    }

    @Test
    fun `the field moved other than our stopped bolus reported - the cell takes the move, not the report`() {
        bench.ours(acceptedAt, 0.5, 0.45).let { BolusAccount(bench.store, bench.serial).finished(it, 0.45, completed = false) }
        assertTrue(bench.settle(listOf(oldCell()) + ten().take(9), dayRaw = 226))
        assertOurs(0.65, fault = true)
    }

    @Test
    fun `the cell of our command with no statistics read - nothing is settled, the next answer with them settles it`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertFalse(bench.settle(listOf(oldCell()) + ten().take(9), dayRaw = null))
        assertTrue(bench.written.isEmpty())
        assertEquals(1, bench.openCommands().size)

        assertTrue(bench.settle(listOf(oldCell()) + ten().take(9), dayRaw = 220))
        assertOurs(0.5, fault = true)
    }

    @Test
    fun `a faulty cell of nobody's with no statistics read - nothing is settled, the next answer with them settles it`() {
        assertFalse(bench.settle(listOf(oldCell()) + ten().take(9), dayRaw = null))
        assertTrue(bench.written.isEmpty())

        assertTrue(bench.settle(listOf(oldCell()) + ten().take(9), dayRaw = 220))
        assertEquals(0.5, bench.rows().single().amountUnits, 1e-9)
    }

    @Test
    fun `a sound record of another amount and a bolus on the pump, our one command - two records, as they are, nothing more`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(1, 12), at(0, 16)) + ten().take(8), dayRaw = 232))

        assertEquals(listOf(0.4, 0.3), bench.rows().map { Math.round(it.amountUnits * 1000) / 1000.0 })
        assertEquals(listOf(AapsWrite.NORMAL, AapsWrite.NORMAL), bench.rows().map { it.type })
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
    fun `a record stamped in the future in our cell - ours, 0_5 U by the field, the user is told`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(60, 20)) + ten().take(9), dayRaw = 220))

        assertOurs(0.5, fault = true)
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
    fun `our bolus in a faulty cell, the statistics out of bounds - no row, the user is told the dose is unknown`() {
        bench.status(reservoirUnits = 10.0)
        bench.ours(acceptedAt, 0.5, 12.0)
        assertTrue(bench.settle(listOf(oldCell()) + ten().take(9), dayRaw = 200 + 800))

        assertTrue(bench.rows().isEmpty())
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT_UNKNOWN), bench.notices().map { it.kind })
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `an answer with no record at all is silence - nothing is settled, our command waits`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertFalse(bench.settle(emptyList(), dayRaw = 220))

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
