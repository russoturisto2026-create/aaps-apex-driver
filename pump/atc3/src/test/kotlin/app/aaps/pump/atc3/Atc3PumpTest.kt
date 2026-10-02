package app.aaps.pump.atc3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import app.aaps.pump.atc3.comm.Atc3Alarm
import org.junit.jupiter.api.Test

class Atc3PumpTest {

    @Test
    fun `an empty reservoir counts as not delivering even while the pump says it is running`() {
        // Under this alarm the pump reports itself running, with a temporary basal counting down,
        // and its delivery counter does not move.
        val pump = Atc3Pump()
        pump.suspended = false
        pump.activeAlarms = listOf(Atc3Alarm.RESERVOIR_EMPTY)
        assertTrue(pump.notDelivering)
        assertFalse(pump.suspended)
    }

    @Test
    fun `two alarms at once still count as not delivering`() {
        // The slots holding 03 01 0d 01: a button error beside the empty reservoir.
        val pump = Atc3Pump()
        pump.activeAlarms = listOf(Atc3Alarm.BUTTON_ERROR, Atc3Alarm.RESERVOIR_EMPTY)
        assertTrue(pump.notDelivering)
    }

    @Test
    fun `the daily dose limit is a stop too`() {
        val pump = Atc3Pump()
        pump.activeAlarms = listOf(Atc3Alarm.DAILY_LIMIT)
        assertTrue(pump.notDelivering)
    }

    @Test
    fun `the other alarms say nothing about delivery`() {
        val pump = Atc3Pump()
        for (alarm in listOf(Atc3Alarm.BUTTON_ERROR, Atc3Alarm.NO_DELIVERY, Atc3Alarm.LOW_BATTERY, Atc3Alarm.BLOOD_GLUCOSE_REMINDER)) {
            pump.activeAlarms = listOf(alarm)
            assertFalse(pump.notDelivering, "$alarm must not be read as a stop")
        }
    }

    @Test
    fun `a pump that stopped itself is not delivering whatever the alarms say`() {
        val pump = Atc3Pump()
        pump.suspended = true
        pump.activeAlarms = emptyList()
        assertTrue(pump.notDelivering)
    }

    @Test
    fun `a quiet running pump is delivering`() {
        val pump = Atc3Pump()
        pump.suspended = false
        pump.activeAlarms = emptyList()
        assertFalse(pump.notDelivering)
    }

    @Test
    fun `rates convert to the pump raw scale`() {
        // Raw 20 is 0.500 U/h.
        val raw = Atc3Pump.ratesToRaw(doubleArrayOf(0.500, 0.700, 0.400, 0.200))
        assertEquals(20, raw[0])
        assertEquals(28, raw[1])
        assertEquals(16, raw[2])
        assertEquals(8, raw[3])
    }

    @Test
    fun `a profile of 48 raw rates sums to its daily total`() {
        // 48 raw rates summing to raw 1200, which is 15.0 U per day.
        val rawProfileB = intArrayOf(
            12, 12, 20, 20, 24, 24, 28, 28, 44, 44, 48, 48,
            40, 40, 32, 32, 24, 24, 24, 24, 24, 24, 20, 20,
            20, 20, 20, 20, 20, 20, 28, 28, 32, 32, 32, 32,
            24, 24, 20, 20, 20, 20, 20, 20, 16, 16, 8, 8
        )
        assertEquals(Atc3Const.BASAL_SLOTS, rawProfileB.size)
        assertEquals(1200, rawProfileB.sum())
        // sum(raw) * 0.025 U/h * 0.5 h per slot
        val dailyUnits = rawProfileB.sum() * Atc3Const.DOSE_SCALE * 0.5
        assertEquals(15.0, dailyUnits, 1e-9)
        // Slot 38 covers 19:00.
        assertEquals(0.500, rawProfileB[38] * Atc3Const.DOSE_SCALE, 1e-9)
        assertEquals("19:00", Atc3Pump.slotLabel(38))
    }

    @Test
    fun `a profile summing to raw 1280 delivers 16 units a day`() {
        // Raw 1280 over half hour slots is 16.0 U per day.
        assertEquals(16.0, 1280 * Atc3Const.DOSE_SCALE * 0.5, 1e-9)
    }

    @Test
    fun `slot labels follow half hour boundaries`() {
        assertEquals("00:00", Atc3Pump.slotLabel(0))
        assertEquals("00:30", Atc3Pump.slotLabel(1))
        assertEquals("20:00", Atc3Pump.slotLabel(40))
        assertEquals("23:30", Atc3Pump.slotLabel(47))
    }

    @Test
    fun `comparison tolerates differences within one step`() {
        val wanted = DoubleArray(Atc3Const.BASAL_SLOTS) { 0.5 }
        val fromPump = DoubleArray(Atc3Const.BASAL_SLOTS) { 0.5 }
        fromPump[10] = 0.5 + Atc3Const.DOSE_SCALE / 2
        assertTrue(Atc3Pump.rateArraysMatch(wanted, fromPump, Atc3Const.DOSE_SCALE))
    }

    @Test
    fun `comparison reports a slot that differs by more than one step`() {
        val wanted = DoubleArray(Atc3Const.BASAL_SLOTS) { 0.5 }
        val fromPump = wanted.copyOf()
        fromPump[40] = 0.7
        assertFalse(Atc3Pump.rateArraysMatch(wanted, fromPump, Atc3Const.DOSE_SCALE))
        assertEquals(listOf(40), Atc3Pump.differingSlots(wanted, fromPump, Atc3Const.DOSE_SCALE))
    }

    @Test
    fun `arrays of different length never match`() {
        assertFalse(Atc3Pump.rateArraysMatch(DoubleArray(48), DoubleArray(24), Atc3Const.DOSE_SCALE))
    }
}
