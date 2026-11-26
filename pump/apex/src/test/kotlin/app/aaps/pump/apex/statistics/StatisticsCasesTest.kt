package app.aaps.pump.apex.statistics

import app.aaps.pump.apex.store.FakeStore
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.protocol.ApexStamp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * The daily statistics of real pumps, a hundred days each, kept and counted by day as the driver does it:
 * - 6.25, read 19.11 from a pump of somebody else: a date stands two or three times in a row, the newest
 *   holding the older ones; the bolus journal agrees with the newest of each date;
 * - 1.1.1, our bench pump, read 04.11: three days with no date, cut by a dead battery. The bolus journal
 *   read 27.10 holds 28 records of 24.10 up to 10:50, 10.6 U, none of 25.10, and 21.2 U of 26.10 from 14:38.
 */
class StatisticsCasesTest {

    @AfterEach
    fun plainStamps() {
        ApexStamp.digits = false
    }

    /** The days of [name] as the driver keeps them, newest first; [digits] for a pump that stamps in decimal digits. */
    private fun kept(name: String, digits: Boolean): List<ApexDailyRecord> {
        ApexStamp.digits = digits
        val frames = File("src/test/resources/statistics/$name").readLines().filter { it.isNotBlank() }
            .map { hex -> ApexResponseFrame(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) }
        val store = FakeStore()
        StatisticsDays(store, "1").keep(ApexJournalAnswer(frames.map { ApexRead(it, ApexDailyRecord.decode(it)!!) }, frames.size), 0L)
        return StatisticsDays(store, "1").kept()
    }

    private fun day(dayOfMonth: Int, month: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
        clear()
        set(2026, month - 1, dayOfMonth)
        timeInMillis / 1000L
    }

    @Test
    fun `6_25 - each date counts its newest record, the older ones under it are copies`() {
        val days = kept("fw625_days.txt", digits = true)
        assertEquals(100, days.size)
        for (date in days.map { it.dayClockSeconds }.distinct()) {
            assertEquals(days.first { it.dayClockSeconds == date }.bolusUnits, StatisticsDays.bolusOfDay(days, date)!!, 1e-9)
        }
        assertEquals(8.0, StatisticsDays.bolusOfDay(days, day(17, 11))!!, 1e-9)
        assertEquals(3.975, StatisticsDays.bolusOfDay(days, day(24, 10))!!, 1e-9)
        assertEquals(18.35, StatisticsDays.bolusOfDay(days, day(3, 10))!!, 1e-9)
    }

    @Test
    fun `1_1_1 - a day with no date is the day after the day below it, the battery dead two days in between`() {
        val days = kept("fw111_days.txt", digits = false)
        assertEquals(10.6, StatisticsDays.bolusOfDay(days, day(24, 10))!!, 1e-9)
        assertNull(StatisticsDays.bolusOfDay(days, day(25, 10)))
        assertEquals(21.2, StatisticsDays.bolusOfDay(days, day(26, 10))!!, 1e-9)
    }

    @Test
    fun `1_1_1 - a day with no date and the power back the same day - both parts are of that day`() {
        val days = kept("fw111_days.txt", digits = false)
        // 27.09: 25.25 U to 06:29 and 1.9 U after; 05.10: 9.825 U to 16:51 and 30.425 U after.
        assertEquals(27.15, StatisticsDays.bolusOfDay(days, day(27, 9))!!, 1e-9)
        assertEquals(40.25, StatisticsDays.bolusOfDay(days, day(5, 10))!!, 1e-9)
    }

    @Test
    fun `1_1_1 - every day with a date and none with no date of its own counts its parts as they are`() {
        val days = kept("fw111_days.txt", digits = false)
        val dates = StatisticsDays.datesOf(days)
        val partsOfLostDays = listOf(day(27, 9), day(5, 10), day(24, 10))
        for (date in days.filter { it.hasDate }.map { it.dayClockSeconds }.distinct() - partsOfLostDays.toSet()) {
            val parts = days.indices.filter { dates[it] == date }
            assertEquals(parts.sumOf { days[it].bolusUnits }, StatisticsDays.bolusOfDay(days, date)!!, 1e-6)
        }
    }
}
