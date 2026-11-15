package app.aaps.pump.apex.account.basal

import app.aaps.pump.apex.protocol.ApexDailyRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** The period of the basal account, closed by the pump's day record. */
class BasalPeriodTest {

    /** 2026-11-08 00:00 UTC as the day's key, and the day before. */
    private val today = 1_793_836_800L
    private val yesterday = today - 86_400L

    /** A heartbeat at 10:05:02 of that day, phone clock. */
    private val tenOhFive = today * 1000L + 10 * 3_600_000L + 5 * 60_000L + 2_000L
    private val minute = 60_000L

    private fun day(basalRaw: Int, tbrRaw: Int, bolusRaw: Int = 40, key: Long = today) =
        ApexDailyRecord(0, bolusRaw * 0.025, basalRaw * 0.025, tbrRaw * 0.025, key)

    private fun step(start: BasalPeriod.Mark, mode: ExactBasalMode, atMs: Long, vararg days: ApexDailyRecord, broken: Boolean = false, settled: Boolean = true) =
        BasalPeriod.step(start, mode, atMs, Delivered.DayRecord(days.toList(), broken), settled)

    @Test
    fun `the mark survives the store as one line`() {
        val mark = BasalPeriod.Mark(tenOhFive, day(400, 20, bolusRaw = 123))
        val back = BasalPeriod.Mark.decode(mark.encode())
        assertNotNull(back)
        assertEquals(tenOhFive, back!!.atMs)
        assertEquals(today, back.day!!.dayClockSeconds)
        assertEquals(400 * 0.025, back.day!!.basalUnits, 1e-9)
        assertEquals(20 * 0.025, back.day!!.tbrUnits, 1e-9)
        assertEquals(123 * 0.025, back.day!!.bolusUnits, 1e-9)
        assertEquals(null, BasalPeriod.Mark.decode("1;2;3"))
        assertEquals(null, BasalPeriod.Mark.decode(null))
    }

    @Test
    fun `by heartbeats the next heartbeat closes the period, the same heartbeat does not`() {
        val start = BasalPeriod.Mark(tenOhFive, day(400, 20))
        assertEquals(BasalPeriod.Step.Wait, step(start, ExactBasalMode.HEARTBEAT, tenOhFive, day(400, 20)))
        assertEquals(BasalPeriod.Step.Close(3 * 0.025), step(start, ExactBasalMode.HEARTBEAT, tenOhFive + 5 * minute, day(403, 20)))
    }

    @Test
    fun `off never closes`() {
        val start = BasalPeriod.Mark(tenOhFive, day(400, 20))
        assertEquals(BasalPeriod.Step.Wait, step(start, ExactBasalMode.OFF, tenOhFive + 60 * minute, day(440, 20)))
    }

    @Test
    fun `boluses first - an unsettled journal postpones, a broken day record postpones`() {
        val start = BasalPeriod.Mark(tenOhFive, day(400, 20))
        assertEquals(BasalPeriod.Step.Postpone("boluses"), step(start, ExactBasalMode.HEARTBEAT, tenOhFive + 5 * minute, day(403, 20), settled = false))
        assertEquals(BasalPeriod.Step.Postpone("days_broken"), step(start, ExactBasalMode.HEARTBEAT, tenOhFive + 5 * minute, day(403, 20), broken = true))
    }

    @Test
    fun `across the pump's midnight the old day is found below the new one and each day counts whole`() {
        val start = BasalPeriod.Mark(tenOhFive, day(400, 20, key = yesterday))
        // The new day from nothing on top, the old day closed at 410 + 30 below it.
        val close = step(start, ExactBasalMode.HEARTBEAT, tenOhFive + 25 * minute, day(6, 0, bolusRaw = 0), day(410, 30, key = yesterday))
        assertEquals(BasalPeriod.Step.Close((10 + 10 + 6) * 0.025), close)
    }

    @Test
    fun `the day the period began in is gone or began over - the period begins anew`() {
        val start = BasalPeriod.Mark(tenOhFive, day(400, 20))
        assertEquals(BasalPeriod.Step.Restart("kept_day_gone"), step(start, ExactBasalMode.HEARTBEAT, tenOhFive + 5 * minute, day(3, 0, key = today + 86_400L)))
        // The same date, but less than the kept day held, and no day without a date under it: begun over.
        assertEquals(BasalPeriod.Step.Restart("kept_day_gone"), step(start, ExactBasalMode.HEARTBEAT, tenOhFive + 5 * minute, day(3, 0)))
    }

    @Test
    fun `the power was lost - the kept day goes on in the day without a date under the new one`() {
        val start = BasalPeriod.Mark(tenOhFive, day(400, 20))
        val close = step(start, ExactBasalMode.HEARTBEAT, tenOhFive + 5 * minute, day(2, 0, bolusRaw = 0), day(401, 20, key = 0L))
        assertEquals(BasalPeriod.Step.Close(3 * 0.025), close)
    }

    @Test
    fun `the basal of the period is in whole steps of the pump`() {
        val start = BasalPeriod.Mark(tenOhFive, day(400, 20))
        val close = step(start, ExactBasalMode.HEARTBEAT, tenOhFive + 5 * minute, day(407, 20)) as BasalPeriod.Step.Close
        assertEquals(7 * 0.025, close.units, 1e-9)
        assertEquals(7.0, close.units / 0.025, 1e-9)
    }

    // The count kind: a pump that gives no daily statistics.

    private fun countMark(units: Double, pumpDay: Long = today / 86_400L) = BasalPeriod.Mark(tenOhFive, Delivered.Count(units, pumpDay))

    private fun countStep(
        start: BasalPeriod.Mark, units: Double, boluses: Double = 0.0, pumpDay: Long = today / 86_400L,
        duringBolus: Boolean = false, settled: Boolean = true, atMs: Long = tenOhFive + 5 * minute
    ) = BasalPeriod.step(start, ExactBasalMode.HEARTBEAT, atMs, Delivered.Count(units, pumpDay), settled, duringBolus, boluses)

    @Test
    fun `the count kind - the move of the count is the basal`() {
        assertEquals(BasalPeriod.Step.Close(0.2), countStep(countMark(10.0), 10.2))
    }

    @Test
    fun `the count kind - the boluses of the period are taken off, in whole steps`() {
        val close = countStep(countMark(10.0), 13.2, boluses = 3.0) as BasalPeriod.Step.Close
        assertEquals(8.0, close.units / 0.025, 1e-9)
        assertEquals(0.0, close.overUnits, 1e-9)
    }

    @Test
    fun `the count kind - boluses above the move of the count give no basal, never a negative one, and the excess is told`() {
        val close = countStep(countMark(10.0), 12.9, boluses = 3.0) as BasalPeriod.Step.Close
        assertEquals(0.0, close.units, 1e-9)
        assertEquals(0.1, close.overUnits, 1e-9)
    }

    @Test
    fun `the count kind - another pump day or a count that went back begins the period anew`() {
        assertEquals(BasalPeriod.Step.Restart("pump_day_changed"), countStep(countMark(10.0), 0.3, pumpDay = today / 86_400L + 1))
        assertEquals(BasalPeriod.Step.Restart("count_went_back"), countStep(countMark(10.0), 9.5))
    }

    @Test
    fun `the count kind - during our bolus the period is postponed, a day record period stays as it is`() {
        assertEquals(BasalPeriod.Step.Postpone("bolus_runs"), countStep(countMark(10.0), 11.0, duringBolus = true))
        val start = BasalPeriod.Mark(tenOhFive, day(400, 20))
        val close = BasalPeriod.step(start, ExactBasalMode.HEARTBEAT, tenOhFive + 5 * minute, Delivered.DayRecord(listOf(day(403, 20))), settled = true, duringBolus = true)
        assertEquals(BasalPeriod.Step.Close(3 * 0.025), close)
    }

    @Test
    fun `the count kind - waits at its own heartbeat, postpones on an unsettled journal, restarts when the kind changed`() {
        assertEquals(BasalPeriod.Step.Wait, BasalPeriod.step(countMark(10.0), ExactBasalMode.HEARTBEAT, tenOhFive, Delivered.Count(10.1, today / 86_400L), settled = true))
        assertEquals(BasalPeriod.Step.Postpone("boluses"), countStep(countMark(10.0), 10.2, settled = false))
        assertEquals(BasalPeriod.Step.Restart("day_record_kind_changed"), countStep(BasalPeriod.Mark(tenOhFive, day(400, 20)), 10.2))
        assertEquals(BasalPeriod.Step.Restart("count_kind_changed"), step(countMark(10.0), ExactBasalMode.HEARTBEAT, tenOhFive + 5 * minute, day(403, 20)))
    }

    @Test
    fun `the mark of the count kind survives the store, the pump day comes from the clock`() {
        val mark = countMark(12.325)
        val back = BasalPeriod.Mark.decode(mark.encode())!!
        val count = back.delivered as Delivered.Count
        assertEquals(tenOhFive, back.atMs)
        assertEquals(12.325, count.units, 1e-9)
        assertEquals(today / 86_400L, count.pumpDay)
        assertEquals(null, back.day)
        assertEquals(today / 86_400L, Delivered.Count.of(1.0, today + 3_600L).pumpDay)
        assertEquals(today / 86_400L - 1, Delivered.Count.of(1.0, today - 1L).pumpDay)
    }

    @Test
    fun `the stored line of the day record kind is read as before`() {
        val back = BasalPeriod.Mark.decode("$tenOhFive;$today;40;400;20")!!
        assertEquals(tenOhFive, back.atMs)
        assertEquals(today, back.day!!.dayClockSeconds)
        assertEquals(400 * 0.025, back.day!!.basalUnits, 1e-9)
        assertEquals(null, BasalPeriod.Mark.decode("$tenOhFive;X;40;400"))
        assertEquals("$tenOhFive;$today;40;400;20", back.encode())
    }

    @Test
    fun `the count kind - a count that moved less than half a step gives no basal and tells the boluses above it`() {
        val close = countStep(countMark(10.0), 10.01, boluses = 0.1) as BasalPeriod.Step.Close
        assertEquals(0.0, close.units, 1e-9)
        assertEquals(0.1, close.overUnits, 1e-9)
    }

    @Test
    fun `a period of the count kind begins only at a count with no bolus still to be written`() {
        val count = Delivered.Count(10.0, today / 86_400L)
        assertEquals(null, BasalPeriod.cannotBegin(count, settled = true, duringBolus = false))
        assertEquals("journal_unsettled", BasalPeriod.cannotBegin(count, settled = false, duringBolus = false))
        assertEquals("bolus_runs", BasalPeriod.cannotBegin(count, settled = true, duringBolus = true))
        // The day record kind counts the basal by its own fields: no bolus can stand in its way.
        val days = Delivered.DayRecord(listOf(day(400, 20)))
        assertEquals(null, BasalPeriod.cannotBegin(days, settled = false, duringBolus = true))
    }
}
