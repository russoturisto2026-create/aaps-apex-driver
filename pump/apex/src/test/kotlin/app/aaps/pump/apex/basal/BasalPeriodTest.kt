package app.aaps.pump.apex.basal

import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.statistics.Delivered
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

    private fun step(start: BasalPeriod.Mark, atMs: Long, vararg days: ApexDailyRecord, broken: Boolean = false) =
        BasalPeriod.step(start, atMs, Delivered(days.toList(), broken))

    @Test
    fun `the mark survives the store as one line`() {
        val mark = BasalPeriod.Mark(tenOhFive, day(400, 20, bolusRaw = 123))
        val back = BasalPeriod.Mark.decode(mark.encode())
        assertNotNull(back)
        assertEquals(tenOhFive, back!!.atMs)
        assertEquals(today, back.day.dayClockSeconds)
        assertEquals(400 * 0.025, back.day.basalUnits, 1e-9)
        assertEquals(20 * 0.025, back.day.tbrUnits, 1e-9)
        assertEquals(123 * 0.025, back.day.bolusUnits, 1e-9)
        assertEquals(null, BasalPeriod.Mark.decode("1;2;3"))
        assertEquals(null, BasalPeriod.Mark.decode(null))
    }

    @Test
    fun `by heartbeats the next heartbeat closes the period, the same heartbeat does not`() {
        val start = BasalPeriod.Mark(tenOhFive, day(400, 20))
        assertEquals(BasalPeriod.Step.Wait, step(start, tenOhFive, day(400, 20)))
        assertEquals(BasalPeriod.Step.Close(3 * 0.025), step(start, tenOhFive + 5 * minute, day(403, 20)))
    }

    @Test
    fun `off never closes`() {
        val start = BasalPeriod.Mark(tenOhFive, day(400, 20))
    }

    @Test
    fun `a broken day record postpones`() {
        val start = BasalPeriod.Mark(tenOhFive, day(400, 20))
        assertEquals(BasalPeriod.Step.Postpone("days_broken"), step(start, tenOhFive + 5 * minute, day(403, 20), broken = true))
    }

    @Test
    fun `across the pump's midnight the old day is found below the new one and each day counts whole`() {
        val start = BasalPeriod.Mark(tenOhFive, day(400, 20, key = yesterday))
        // The new day from nothing on top, the old day closed at 410 + 30 below it.
        val close = step(start, tenOhFive + 25 * minute, day(6, 0, bolusRaw = 0), day(410, 30, key = yesterday))
        assertEquals(BasalPeriod.Step.Close((10 + 10 + 6) * 0.025), close)
    }

    @Test
    fun `the day the period began in is gone or began over - the period begins anew`() {
        val start = BasalPeriod.Mark(tenOhFive, day(400, 20))
        assertEquals(BasalPeriod.Step.Restart("kept_day_gone"), step(start, tenOhFive + 5 * minute, day(3, 0, key = today + 86_400L)))
        // The same date, but less than the kept day held, and no day without a date under it: begun over.
        assertEquals(BasalPeriod.Step.Restart("kept_day_gone"), step(start, tenOhFive + 5 * minute, day(3, 0)))
    }

    @Test
    fun `the power was lost - the kept day goes on in the day without a date under the new one`() {
        val start = BasalPeriod.Mark(tenOhFive, day(400, 20))
        val close = step(start, tenOhFive + 5 * minute, day(2, 0, bolusRaw = 0), day(401, 20, key = 0L))
        assertEquals(BasalPeriod.Step.Close(3 * 0.025), close)
    }

    @Test
    fun `the basal of the period is in whole steps of the pump`() {
        val start = BasalPeriod.Mark(tenOhFive, day(400, 20))
        val close = step(start, tenOhFive + 5 * minute, day(407, 20)) as BasalPeriod.Step.Close
        assertEquals(7 * 0.025, close.units, 1e-9)
        assertEquals(7.0, close.units / 0.025, 1e-9)
    }

    @Test
    fun `the stored line of the day record kind is read as before`() {
        val back = BasalPeriod.Mark.decode("$tenOhFive;$today;40;400;20")!!
        assertEquals(tenOhFive, back.atMs)
        assertEquals(today, back.day.dayClockSeconds)
        assertEquals(400 * 0.025, back.day.basalUnits, 1e-9)
        assertEquals(null, BasalPeriod.Mark.decode("$tenOhFive;X;40;400"))
        assertEquals("$tenOhFive;$today;40;400;20", back.encode())
    }

    @Test
    fun `a mark of the count kind kept by an older driver is no mark`() {
        assertEquals(null, BasalPeriod.Mark.decode("$tenOhFive;C;493;20000"))
    }
}
