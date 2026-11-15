package app.aaps.pump.apex.account.bolus

import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.NoticeKind
import app.aaps.pump.apex.account.basal.BasalPeriodKeeper
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.NOW_MS
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.TWO
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.at
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.ten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whole journal pictures through the real bolus journal: how many records moved, an answer that
 * does not lay over the copy, a journal the pump starts over. Each test is one answer
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
    fun `ten records moved, nothing of the copy, the boluses last held against AAPS six hours ago - the journal and the reference points start anew, nothing is written`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        bench.store.putState(bench.serial, BasalPeriodKeeper.STATE_PERIOD, "kept")
        val tenNew = (9 downTo 0).map { at(it, 4) }
        bench.thenAnswers(tenNew)
        assertTrue(bench.settle(tenNew, dayRaw = 240, nowMs = NOW_MS + 6 * 3_600_000L))

        assertTrue(bench.rows().isEmpty())
        assertTrue(bench.openCommands().isEmpty())
        assertEquals(0, bench.wholeReads)
        assertEquals(null, bench.store.state(bench.serial, BasalPeriodKeeper.STATE_PERIOD))
        assertEquals(NoticeKind.JOURNAL_CLEAN_START, bench.notices().single().kind)
    }

    @Test
    fun `the insulin in use acts seven hours, the boluses last held against AAPS six hours ago - the whole journal is read`() {
        bench.insulinActsMs = 7 * 3_600_000L
        val late = NOW_MS + 6 * 3_600_000L
        val tenNew = (9 downTo 0).map { BolusRecordFrames.record(late / 1000L - 60L - it * 20L, 3, 3) }
        bench.thenAnswers(tenNew)
        bench.thenWhole(tenNew + ten())
        assertTrue(bench.settle(tenNew, dayRaw = 230, nowMs = late))

        assertEquals(10, bench.rows().size)
        assertTrue(bench.notices().isEmpty())
    }

    @Test
    fun `the whole journal stays unread - two heartbeats settle nothing, the third tells the user and starts clean`() {
        val tenNew = (9 downTo 0).map { at(it, 3) }
        repeat(2) {
            bench.thenAnswers(tenNew)
            assertFalse(bench.settle(tenNew, dayRaw = 230))
        }
        assertTrue(bench.written.isEmpty())

        bench.thenAnswers(tenNew)
        assertTrue(bench.settle(tenNew, dayRaw = 230))
        assertTrue(bench.rows().isEmpty())
        assertEquals(NoticeKind.JOURNAL_UNLAID, bench.notices().single().kind)

        bench.written.clear()
        assertTrue(bench.settle(listOf(at(11, 8)) + tenNew.take(9), dayRaw = 238))
        assertEquals(0.2, bench.rows().single().amountUnits, 1e-9)
    }

    @Test
    fun `the whole journal unread once, then read - the count of unread heartbeats starts over`() {
        val tenNew = (9 downTo 0).map { at(it, 3) }
        bench.thenAnswers(tenNew)
        assertFalse(bench.settle(tenNew, dayRaw = 230))
        bench.thenAnswers(tenNew)
        bench.thenWhole(tenNew + ten())
        assertTrue(bench.settle(tenNew, dayRaw = 230))

        val more = (21 downTo 12).map { at(it, 3) }
        repeat(2) {
            bench.thenAnswers(more)
            assertFalse(bench.settle(more, dayRaw = 260))
        }
        assertTrue(bench.notices().isEmpty())
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
    fun `the whole journal shows nothing of the copy either - the user is told, the journal and the reference points start anew`() {
        bench.store.putState(bench.serial, BasalPeriodKeeper.STATE_PERIOD, "kept")
        val tenNew = (9 downTo 0).map { at(it, 3) }
        bench.thenAnswers(tenNew)
        bench.thenWhole(tenNew + ten(minutesBeforeTwo = 100, raw = 5))
        assertTrue(bench.settle(tenNew, dayRaw = 260))

        assertTrue(bench.rows().isEmpty())
        assertEquals(NoticeKind.JOURNAL_UNLAID, bench.notices().single().kind)
        assertEquals(null, bench.store.state(bench.serial, BasalPeriodKeeper.STATE_PERIOD))

        bench.written.clear()
        assertTrue(bench.settle(listOf(at(11, 8)) + tenNew.take(9), dayRaw = 268))
        assertEquals(0.2, bench.rows().single().amountUnits, 1e-9)
        assertTrue(bench.notices().isEmpty())
    }

    @Test
    fun `a pump without the statistics - ten records moved in one span, the whole journal gives ten rows`() {
        val pump = JournalBench()
        pump.pastByCount(ten(), countUnits = 5.0)
        val tenNew = (9 downTo 0).map { at(it, 3) }
        pump.thenAnswers(tenNew)
        pump.thenWhole(tenNew + ten())
        assertTrue(pump.settleByCount(tenNew, countUnits = 5.75))

        assertEquals(10, pump.rows().size)
        assertEquals(0.75, pump.rows().sumOf { it.amountUnits }, 1e-9)
    }

    @Test
    fun `an answer of another picture, then the right one - the right one is settled`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        bench.thenAnswers(listOf(at(0, 20)) + ten().take(9))
        assertTrue(bench.settle(ten(minutesBeforeTwo = 300, raw = 40), dayRaw = 220))

        assertEquals(0.5, bench.rows().single().amountUnits, 1e-9)
    }

    @Test
    fun `two answers of other pictures that differ - nothing is settled, the next answer settles our bolus`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        bench.thenAnswers(ten(minutesBeforeTwo = 500, raw = 40))
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
    fun `the pump's journal starts over with one record, ours - read again, alike - a fault, ours by the field, the user is told`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        bench.thenAnswers(listOf(at(0, 20)))
        assertTrue(bench.settle(listOf(at(0, 20)), dayRaw = 220))

        val row = bench.rows().single()
        assertEquals(0.5, row.amountUnits, 1e-9)
        assertEquals(acceptedAt, row.timestampMs)
        assertEquals("SMB", row.type)
        assertEquals(listOf(NoticeKind.JOURNAL_FAULT), bench.notices().map { it.kind })
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `the pump's journal starts over with two records and our bolus - one row of the whole move, 0_8, at our command`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        bench.thenAnswers(listOf(at(1, 12), at(0, 20)))
        assertTrue(bench.settle(listOf(at(1, 12), at(0, 20)), dayRaw = 232))

        val row = bench.rows().single()
        assertEquals(0.8, row.amountUnits, 1e-9)
        assertEquals(acceptedAt, row.timestampMs)
    }

    @Test
    fun `the pump's journal starts over and the statistics were not read - nothing is settled, the next answer with them settles it`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        bench.thenAnswers(listOf(at(0, 20)), listOf(at(0, 20)), listOf(at(0, 20)))
        assertFalse(bench.settle(listOf(at(0, 20)), dayRaw = null))
        assertTrue(bench.written.isEmpty())
        assertEquals(1, bench.openCommands().size)

        assertTrue(bench.settle(listOf(at(0, 20)), dayRaw = 220))
        assertEquals(0.5, bench.rows().single().amountUnits, 1e-9)
        assertEquals(acceptedAt, bench.rows().single().timestampMs)
    }

    @Test
    fun `the pump's journal starts over with a record of nobody's and the statistics were not read - nothing is settled, the next answer with them settles it`() {
        bench.thenAnswers(listOf(at(0, 20)), listOf(at(0, 20)), listOf(at(0, 20)))
        assertFalse(bench.settle(listOf(at(0, 20)), dayRaw = null))
        assertTrue(bench.written.isEmpty())

        assertTrue(bench.settle(listOf(at(0, 20)), dayRaw = 220))
        assertEquals(0.5, bench.rows().single().amountUnits, 1e-9)
    }

    @Test
    fun `after the journal started over with one record, the next answer lays over it`() {
        bench.thenAnswers(listOf(at(0, 20)))
        bench.settle(listOf(at(0, 20)), dayRaw = 220)
        bench.written.clear()
        bench.ours(acceptedAt + 60_000L, 0.3, 0.3)
        assertTrue(bench.settle(listOf(at(1, 12), at(0, 20)), dayRaw = 232))

        val row = bench.rows().single()
        assertEquals(0.3, row.amountUnits, 1e-9)
        assertEquals("SMB", row.type)
    }

    @Test
    fun `no new record, the bolus field moved by our 0_5 U - our command is closed, no row is made without a record`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        assertTrue(bench.settle(ten(), dayRaw = 220))

        assertTrue(bench.rows().isEmpty())
        assertTrue(bench.openCommands().isEmpty())
    }

    @Test
    fun `the record that came late is written by itself, once, at its own minute`() {
        bench.ours(acceptedAt, 0.5, 0.5)
        bench.settle(ten(), dayRaw = 220)
        bench.written.clear()
        assertTrue(bench.settle(listOf(at(0, 20)) + ten().take(9), dayRaw = 220))

        val row = bench.rows().single()
        assertEquals(0.5, row.amountUnits, 1e-9)
        assertEquals(TWO * 1000L, row.timestampMs)
    }
}
