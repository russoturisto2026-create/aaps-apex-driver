package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.aaps.NoticeKind
import app.aaps.pump.apex.basal.BasalPeriodKeeper
import app.aaps.pump.apex.bolus.JournalBench.Companion.NOW_MS
import app.aaps.pump.apex.bolus.JournalBench.Companion.TWO
import app.aaps.pump.apex.bolus.JournalBench.Companion.at
import app.aaps.pump.apex.bolus.JournalBench.Companion.ten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whole journal pictures through the real bolus journal: how many records moved, an answer that
 * does not lay over the cells, a journal the pump starts over: it is read whole. Each test is one answer
 * of the pump after the past of ten records of 0.2 U, the newest a minute before two.
 */
class JournalPicturesTest {

    private val bench = JournalBench()
    private val acceptedAt = TWO * 1000L + 5_000L

    init {
        bench.past(ten(), dayRaw = 200)
    }

    @Test
    fun `plain - one new record of our bolus, our row`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(0, 20)) + ten().take(9), dayRaw = 220))

        val row = bench.rows().single()
        assertEquals(0.5, row.amountUnits, 1e-9)
        assertEquals("SMB", row.type)
        assertTrue(bench.notices().isEmpty())
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `stopped right after the row went to AAPS - no mark is kept, the next answer writes the same row over itself`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        bench.faultAfterRow = true
        assertThrows(IllegalStateException::class.java) { bench.settle(listOf(at(0, 20)) + ten().take(9), dayRaw = 220) }
        val first = bench.rows().single()
        assertEquals(1, bench.openCommands().size)

        bench.faultAfterRow = false
        bench.written.clear()
        assertTrue(bench.settle(listOf(at(0, 20)) + ten().take(9), dayRaw = 220))
        val again = bench.rows().single()
        assertEquals(first.pumpId, again.pumpId)
        assertEquals(first.timestampMs, again.timestampMs)
        assertEquals("SMB", again.type)
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `three records moved at once - a bolus on the pump, ours, another on the pump - three rows`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(2, 8), at(1, 20), at(0, 12)) + ten().take(7), dayRaw = 240))

        assertEquals(listOf(0.3, 0.5, 0.2), bench.rows().map { Math.round(it.amountUnits * 1000) / 1000.0 })
        assertEquals(listOf("NORMAL", "SMB", "NORMAL"), bench.rows().map { it.type })
    }

    @Test
    fun `nine records moved, the copy's newest is the last of the answer - nine rows`() {
        val nine = (8 downTo 0).map { at(it, 4) }
        assertTrue(bench.settle(nine + ten().take(1), dayRaw = 236))

        assertEquals(9, bench.rows().size)
        assertEquals(0.9, bench.rows().sumOf { it.amountUnits }, 1e-9)
    }

    @Test
    fun `nothing of the cells, nor in the whole journal - the start reconciliation writes only what is newer than the newest bolus in AAPS`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        bench.store.putState(bench.serial, BasalPeriodKeeper.STATE_PERIOD, "kept")
        val tenNew = (9 downTo 0).map { at(it, 4) }
        bench.thenWhole(tenNew)
        bench.newestBolusMs = (TWO + 4 * 60L) * 1000L
        assertTrue(bench.settle(tenNew, dayRaw = 240))

        // Records from a minute after the newest bolus in AAPS: minutes 5 to 9.
        assertEquals(5, bench.rows().size)
        assertEquals(0.5, bench.rows().sumOf { it.amountUnits }, 1e-9)
        assertTrue(bench.openCommands().isEmpty())
        assertEquals("kept", bench.store.state(bench.serial, BasalPeriodKeeper.STATE_PERIOD))
        assertTrue(bench.notices().isEmpty())
    }

    @Test
    fun `the start reconciliation writes from the later of the newest bolus in AAPS and the time the insulin acts`() {
        val tenNew = (9 downTo 0).map { at(it, 4) }
        bench.thenWhole(tenNew)
        bench.newestBolusMs = (TWO + 2 * 60L) * 1000L
        // Ten minutes of insulin up to a quarter past two: from five past two, later than a minute after the newest bolus.
        bench.insulinActsMs = 10 * 60_000L
        assertTrue(bench.settle(tenNew, dayRaw = 240))
        assertEquals(5, bench.rows().size)
    }

    @Test
    fun `with no profile in AAPS the insulin is taken to act five hours`() {
        val tenNew = (9 downTo 0).map { at(it, 4) }
        bench.thenWhole(tenNew)
        bench.insulinActsMs = null
        // Five hours ago is before every record: all ten go.
        assertTrue(bench.settle(tenNew, dayRaw = 240, nowMs = NOW_MS))
        assertEquals(10, bench.rows().size)
    }

    @Test
    fun `a record older than the insulin acts does not go to AAPS, even with no bolus in AAPS`() {
        val tenNew = (9 downTo 0).map { at(it, 4) }
        bench.thenWhole(tenNew)
        bench.insulinActsMs = 10 * 60_000L
        assertTrue(bench.settle(tenNew, dayRaw = 240))
        assertEquals(5, bench.rows().size)
    }

    @Test
    fun `ten records moved in one span, nothing of the copy - the whole journal is read, ten rows`() {
        bench.store.putState(bench.serial, BasalPeriodKeeper.STATE_PERIOD, "kept")
        val tenNew = (9 downTo 0).map { at(it, 3) }
        bench.thenAnswers(tenNew)
        bench.thenWhole(tenNew + ten())
        assertTrue(bench.settle(tenNew, dayRaw = 230))

        assertEquals(10, bench.rows().size)
        assertEquals(0.75, bench.rows().sumOf { it.amountUnits }, 1e-9)
        assertEquals(1, bench.wholeReads)
        assertEquals("kept", bench.store.state(bench.serial, BasalPeriodKeeper.STATE_PERIOD))
    }

    @Test
    fun `fourteen records moved in one span - the whole journal gives fourteen rows, the next answer none`() {
        val new = (13 downTo 0).map { at(it, 3) }
        bench.thenAnswers(new.take(10))
        bench.thenWhole(new + ten())
        assertTrue(bench.settle(new.take(10), dayRaw = 242))
        assertEquals(14, bench.rows().size)

        bench.written.clear()
        assertTrue(bench.settle(new.take(10), dayRaw = 242))
        assertTrue(bench.rows().isEmpty())
    }

    @Test
    fun `the whole journal is not answered - nothing is settled, the next heartbeat reads it and settles`() {
        val tenNew = (9 downTo 0).map { at(it, 3) }
        bench.thenAnswers(tenNew)
        assertFalse(bench.settle(tenNew, dayRaw = 230))
        assertTrue(bench.rows().isEmpty())

        bench.thenAnswers(tenNew)
        bench.thenWhole(tenNew + ten())
        assertTrue(bench.settle(tenNew, dayRaw = 230))
        assertEquals(10, bench.rows().size)
    }

    @Test
    fun `ten records moved, the journal last read six hours ago, the statistics kept at the last heartbeat - the whole journal is read`() {
        val late = NOW_MS + 6 * 3_600_000L
        assertTrue(bench.settle(ten(), dayRaw = 200, nowMs = late - 300_000L))
        val tenNew = (9 downTo 0).map { BolusRecordFrames.record(late / 1000L - 60L - it * 20L, 3, 3) }
        bench.thenAnswers(tenNew)
        bench.thenWhole(tenNew + ten())
        assertTrue(bench.settle(tenNew, dayRaw = 230, nowMs = late))

        assertEquals(10, bench.rows().size)
    }

    @Test
    fun `the whole journal shows nothing of the cells either - the records newer than AAPS go to it, the next answer lays over them`() {
        val tenNew = (9 downTo 0).map { at(it, 3) }
        bench.thenWhole(tenNew + ten(minutesBeforeTwo = 100, raw = 5))
        bench.newestBolusMs = (TWO - 60L) * 1000L
        assertTrue(bench.settle(tenNew, dayRaw = 260))
        assertEquals(10, bench.rows().size)
        assertEquals(0.75, bench.rows().sumOf { it.amountUnits }, 1e-9)

        bench.written.clear()
        assertTrue(bench.settle(listOf(at(11, 8)) + tenNew.take(9), dayRaw = 268))
        assertEquals(0.2, bench.rows().single().amountUnits, 1e-9)
        assertTrue(bench.notices().isEmpty())
    }

    @Test
    fun `an answer of another picture - the whole journal lays over the cells and is settled`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        bench.thenWhole(listOf(at(0, 20)) + ten())
        assertTrue(bench.settle(ten(minutesBeforeTwo = 300, raw = 40), dayRaw = 220))

        assertEquals(0.5, bench.rows().single().amountUnits, 1e-9)
    }

    @Test
    fun `an answer of another picture and no whole journal - nothing is settled, the next answer settles our bolus`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertFalse(bench.settle(ten(minutesBeforeTwo = 300, raw = 40), dayRaw = 220))
        assertTrue(bench.written.isEmpty())
        assertEquals(1, bench.openCommands().size)

        assertTrue(bench.settle(listOf(at(0, 20)) + ten().take(9), dayRaw = 220))
        assertEquals(0.5, bench.rows().single().amountUnits, 1e-9)
    }

    @Test
    fun `a shorter answer that lays over the copy - one new record`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(0, 20)) + ten().take(4), dayRaw = 220))

        assertEquals(0.5, bench.rows().single().amountUnits, 1e-9)
    }

    @Test
    fun `two boluses alike in one minute - the new one is new, not the copy's newest again`() {
        bench.settle(listOf(at(0, 20)) + ten().take(9), dayRaw = 220)
        bench.written.clear()
        bench.ours(acceptedAt + 20_000L, 0.5, 0.5)
        assertTrue(bench.settle(listOf(at(0, 20), at(0, 20)) + ten().take(8), dayRaw = 240))

        assertEquals(0.5, bench.rows().single().amountUnits, 1e-9)
    }

    @Test
    fun `the pump's journal starts over with one record, ours - the start reconciliation pairs it, ours`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        bench.thenWhole(listOf(at(0, 20)))
        assertTrue(bench.settle(listOf(at(0, 20)), dayRaw = 220))

        val row = bench.rows().single()
        assertEquals(0.5, row.amountUnits, 1e-9)
        assertEquals(acceptedAt, row.timestampMs)
        assertEquals("SMB", row.type)
        assertTrue(bench.notices().isEmpty())
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `the pump's journal starts over with two records and our bolus - ours paired, the other written as it is`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        bench.thenWhole(listOf(at(1, 12), at(0, 20)))
        assertTrue(bench.settle(listOf(at(1, 12), at(0, 20)), dayRaw = 232))

        assertEquals(listOf(0.5, 0.3), bench.rows().map { Math.round(it.amountUnits * 1000) / 1000.0 })
        assertEquals(acceptedAt, bench.rows().first().timestampMs)
        assertTrue(bench.notices().isEmpty())
    }

    @Test
    fun `after the journal started over with one record, the next answer lays over it`() {
        bench.thenWhole(listOf(at(0, 20)))
        bench.settle(listOf(at(0, 20)), dayRaw = 220)
        bench.written.clear()
        bench.ours(acceptedAt + 60_000L, 0.3, 0.3)
        assertTrue(bench.settle(listOf(at(1, 12), at(0, 20)), dayRaw = 232))

        val row = bench.rows().single()
        assertEquals(0.3, row.amountUnits, 1e-9)
        assertEquals("SMB", row.type)
    }

    @Test
    fun `no new record, the bolus field moved by our 0_5 U - ours by what we saw, with no record number, the picture closed`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(ten(), dayRaw = 220))

        val row = bench.rows().single()
        assertEquals(0.5, row.amountUnits, 1e-9)
        assertEquals(null, row.pumpId)
        assertEquals(acceptedAt, row.tempId)
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `no new record and nothing of our bolus seen, the field did not move - the row is nothing, the user is told`() {
        bench.ours(acceptedAt, 0.5, 0.0, seen = false)
        assertTrue(bench.settle(ten(), dayRaw = 200))

        assertEquals(0.0, bench.rows().single().amountUnits, 1e-9)
        assertEquals(listOf(NoticeKind.OURS_NO_RECORD), bench.notices().map { it.kind })
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `no new record and nothing of our bolus seen, the field moved by it - the row of the field, nothing to tell`() {
        bench.ours(acceptedAt, 0.5, 0.0, seen = false)
        assertTrue(bench.settle(ten(), dayRaw = 220))

        assertEquals(0.5, bench.rows().single().amountUnits, 1e-9)
        assertTrue(bench.notices().isEmpty())
    }

    @Test
    fun `a sound record of somebody else off the bolus field - written as it is, a line, the loop opened`() {
        assertTrue(bench.settle(listOf(at(0, 19)) + ten().take(9), dayRaw = 220))
        assertEquals(0.475, bench.rows().single().amountUnits, 1e-9)
        assertEquals(listOf(NoticeKind.JOURNAL_MISMATCH), bench.notices().map { it.kind })
        assertTrue(AapsWrite.OpenLoop in bench.written)
    }

    @Test
    fun `a second line in one day - the user is told it is a serious error`() {
        assertTrue(bench.settle(listOf(at(0, 19)) + ten().take(9), dayRaw = 220))
        bench.written.clear()
        assertTrue(bench.settle(listOf(at(1, 19), at(0, 19)) + ten().take(8), dayRaw = 240))
        assertEquals(listOf(NoticeKind.JOURNAL_MISMATCH_AGAIN), bench.notices().map { it.kind })
        assertTrue(AapsWrite.OpenLoop in bench.written)
    }
}
