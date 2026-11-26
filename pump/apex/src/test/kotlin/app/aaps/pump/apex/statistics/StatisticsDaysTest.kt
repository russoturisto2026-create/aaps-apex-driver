package app.aaps.pump.apex.statistics

import app.aaps.pump.apex.bolus.BolusCells
import app.aaps.pump.apex.bolus.BolusRecordFrames.record
import app.aaps.pump.apex.store.FakeStore
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.protocol.ApexResponseFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.TimeZone

/** The driver's copy of the daily statistics: laid over by place, the top day active, no day older than the bolus cells. */
class StatisticsDaysTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private val days = StatisticsDays(store, serial)

    /** A day as the pump answers it, raw doses in steps of 0.025 U; [dayOfMonth] 0 is a day with no date. */
    private class Day(val bolus: Int, val basal: Int, val tbr: Int, val dayOfMonth: Int)

    private fun answer(vararg newestFirst: Day): ApexJournalAnswer<ApexDailyRecord> =
        ApexJournalAnswer(newestFirst.mapIndexed { index, it -> read(index, it) }, newestFirst.size)

    private fun read(index: Int, day: Day): ApexRead<ApexDailyRecord> {
        val raw = ByteArray(ApexDailyRecord.RECORD_SIZE)
        raw[0] = ApexResponseFrame.MARKER
        raw[1] = 0x12
        raw[2] = 10
        raw[3] = 0xA3.toByte()
        raw[4] = ApexProtocol.HistoryOpcode.LATEST_DAILY_STATS
        raw[5] = index.toByte()
        raw[6] = (day.bolus and 0xFF).toByte(); raw[7] = (day.bolus shr 8).toByte()
        raw[8] = (day.basal and 0xFF).toByte(); raw[9] = (day.basal shr 8).toByte()
        raw[10] = (day.tbr and 0xFF).toByte(); raw[11] = (day.tbr shr 8).toByte()
        if (day.dayOfMonth > 0) {
            raw[12] = 26; raw[13] = 10; raw[14] = day.dayOfMonth.toByte()
        }
        // The CRC changes with the position: the copy must not hold it against the answer.
        raw[16] = index.toByte(); raw[17] = (index * 7).toByte()
        val frame = ApexResponseFrame(raw)
        return ApexRead(frame, ApexDailyRecord.decode(frame)!!)
    }

    /** The pump's wall clock of [dayOfMonth] October 2026 at [hour], as a key. */
    private fun clockOf(dayOfMonth: Int, hour: Int): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(2026, Calendar.OCTOBER, dayOfMonth, hour, 0, 0)
        return calendar.timeInMillis / 1000L
    }

    private fun dates() = days.kept().map { if (it.hasDate) (it.dayClockSeconds - clockOf(1, 0)) / 86_400L + 1 else 0L }

    @Test
    fun `a new day on top keeps the days under it, today grown is still one day`() {
        days.keep(answer(Day(40, 400, 0, 30), Day(80, 1248, 0, 29), Day(60, 1248, 0, 28)), 1L)
        days.keep(answer(Day(10, 20, 0, 31), Day(120, 1248, 0, 30), Day(80, 1248, 0, 29)), 2L)
        assertEquals(listOf(31L, 30L, 29L, 28L), dates())
        assertEquals(0.25, days.active()!!.bolusUnits, 1e-9)
    }

    @Test
    fun `a copy of today appears under it - a heartbeat keeps today over the copy's closed days, the copy is not counted`() {
        days.keep(answer(Day(40, 400, 0, 30), Day(80, 1248, 0, 29)), 1L)
        // The pump kept today twice: the upper day holds the lower in every field.
        val twice = answer(Day(100, 1248, 0, 30), Day(40, 410, 0, 30), Day(80, 1248, 0, 29))
        assertTrue(StatisticsDays.isCopy(twice.entries.map { it.record }, 1))
        days.keep(twice, 2L)
        assertEquals(listOf(30L, 29L), dates())
        assertEquals(listOf(2.5, 2.0), StatisticsDays.counted(days.kept()).map { it.bolusUnits })
    }

    @Test
    fun `a heartbeat does not take the closed days of its answer - a closed day read broken leaves the copy as it was`() {
        days.keep(answer(Day(40, 400, 0, 30), Day(80, 1248, 0, 29), Day(60, 1248, 0, 28)), 1L)
        days.keep(answer(Day(44, 410, 0, 30), Day(0, 0, 0, 0), Day(60, 1248, 0, 28)), 2L)
        assertEquals(listOf(30L, 29L, 28L), dates())
        assertEquals(listOf(1.1, 2.0, 1.5), days.kept().map { it.bolusUnits })
    }

    @Test
    fun `the first heartbeat after midnight takes the new day and yesterday at its end`() {
        days.keep(answer(Day(40, 400, 0, 30), Day(80, 1248, 0, 29)), 1L)
        days.keep(answer(Day(4, 10, 0, 31), Day(48, 1248, 0, 30), Day(0, 0, 0, 0)), 2L)
        assertEquals(listOf(31L, 30L, 29L), dates())
        assertEquals(listOf(0.1, 1.2, 2.0), days.kept().map { Math.round(it.bolusUnits * 1000) / 1000.0 })
    }

    @Test
    fun `the start takes the closed days of its answer too, and keeps the older days of the copy`() {
        days.keep(answer(Day(40, 400, 0, 30), Day(80, 1248, 0, 29), Day(60, 1248, 0, 28), Day(20, 1248, 0, 27)), 1L)
        days.keep(answer(Day(44, 410, 0, 30), Day(84, 1248, 0, 29)), 2L, closedToo = true)
        assertEquals(listOf(30L, 29L, 28L, 27L), dates())
        assertEquals(listOf(1.1, 2.1, 1.5, 0.5), days.kept().map { it.bolusUnits })
    }

    @Test
    fun `today that lost its date after a power loss is laid over as the day it was`() {
        days.keep(answer(Day(40, 400, 0, 30), Day(80, 1248, 0, 29)), 1L)
        days.keep(answer(Day(4, 20, 0, 30), Day(44, 410, 0, 0), Day(80, 1248, 0, 29)), 2L)
        assertEquals(listOf(30L, 0L, 29L), dates())
        assertEquals(listOf(0.1, 1.1, 2.0), StatisticsDays.counted(days.kept()).map { it.bolusUnits })
    }

    @Test
    fun `an answer that does not lay over the copy takes its place`() {
        days.keep(answer(Day(40, 400, 0, 20), Day(80, 1248, 0, 19)), 1L)
        days.keep(answer(Day(10, 20, 0, 31), Day(120, 1248, 0, 30)), 2L)
        assertEquals(listOf(31L, 30L), dates())
    }

    @Test
    fun `no day older than the oldest record of the bolus cells is kept, a day with no date under the last one stays`() {
        BolusCells(store, serial).restart(listOf(record(clockOf(30, 10), 20, 20), record(clockOf(29, 22), 20, 20)), 0L, clockOf(31, 0) * 1000L)
        days.keep(answer(Day(40, 400, 0, 31), Day(80, 1248, 0, 30), Day(60, 1248, 0, 29), Day(30, 300, 0, 0), Day(60, 1248, 0, 28), Day(60, 1248, 0, 27)), 1L)
        assertEquals(listOf(31L, 30L, 29L, 0L), dates())
    }

    @Test
    fun `the clock gone back over midnight makes parts of one date, each counted`() {
        val parts = answer(Day(8, 4, 0, 29), Day(8, 0, 0, 28), Day(180, 6, 0, 29), Day(600, 50, 0, 28)).entries.map { it.record }
        assertFalse((parts.indices).any { StatisticsDays.isCopy(parts, it) })
        assertEquals(4, StatisticsDays.counted(parts).size)
    }

    @Test
    fun `nothing is kept before the first answer`() {
        assertNull(days.active())
        assertTrue(days.kept().isEmpty())
    }

    @Test
    fun `the move since the day kept - the basal and temporary basal fields and the time, none on another date`() {
        days.keep(answer(Day(40, 400, 0, 30), Day(80, 1248, 0, 29)), 1_000L)
        val moved = days.movedSinceKept(answer(Day(40, 404, 2, 30)).entries.first().record, 301_000L)!!
        assertEquals(0.1, moved.basalUnits, 1e-9)
        assertEquals(0.05, moved.tbrUnits, 1e-9)
        assertEquals(300_000L, moved.ms)
        assertNull(days.movedSinceKept(answer(Day(4, 10, 0, 31)).entries.first().record, 301_000L))
    }
}
