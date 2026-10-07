package app.aaps.pump.atc3.state

import app.aaps.pump.atc3.protocol.Atc3Alarm
import app.aaps.pump.atc3.protocol.Atc3Protocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Atc3PumpStateTest {

    @Test
    fun `an empty reservoir counts as not delivering even while the pump says it is running`() {
        // Under this alarm the pump reports itself running, with a temporary basal counting down,
        // and its delivery counter does not move.
        val pump = Atc3PumpState()
        pump.editStatus { it.copy(suspended = false) }
        pump.editStatus { it.copy(activeAlarmCodes = (listOf(Atc3Alarm.RESERVOIR_EMPTY)).map { a -> a.code }) }
        assertTrue(pump.notDelivering)
        assertFalse(pump.suspended)
    }

    @Test
    fun `two alarms at once still count as not delivering`() {
        // The slots holding 03 01 0d 01: a button error beside the empty reservoir.
        val pump = Atc3PumpState()
        pump.editStatus { it.copy(activeAlarmCodes = (listOf(Atc3Alarm.BUTTON_ERROR, Atc3Alarm.RESERVOIR_EMPTY)).map { a -> a.code }) }
        assertTrue(pump.notDelivering)
    }

    @Test
    fun `the daily dose limit is a stop too`() {
        val pump = Atc3PumpState()
        pump.editStatus { it.copy(activeAlarmCodes = (listOf(Atc3Alarm.DAILY_LIMIT)).map { a -> a.code }) }
        assertTrue(pump.notDelivering)
    }

    @Test
    fun `the other alarms say nothing about delivery`() {
        val pump = Atc3PumpState()
        for (alarm in listOf(Atc3Alarm.BUTTON_ERROR, Atc3Alarm.NO_DELIVERY, Atc3Alarm.LOW_BATTERY, Atc3Alarm.BLOOD_GLUCOSE_REMINDER)) {
            pump.editStatus { it.copy(activeAlarmCodes = (listOf(alarm)).map { a -> a.code }) }
            assertFalse(pump.notDelivering, "$alarm must not be read as a stop")
        }
    }

    @Test
    fun `a pump that stopped itself is not delivering whatever the alarms say`() {
        val pump = Atc3PumpState()
        pump.editStatus { it.copy(suspended = true) }
        pump.editStatus { it.copy(activeAlarmCodes = emptyList()) }
        assertTrue(pump.notDelivering)
    }

    @Test
    fun `a quiet running pump is delivering`() {
        val pump = Atc3PumpState()
        pump.editStatus { it.copy(suspended = false) }
        pump.editStatus { it.copy(activeAlarmCodes = emptyList()) }
        assertFalse(pump.notDelivering)
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
        assertEquals(Atc3Protocol.BASAL_SLOTS, rawProfileB.size)
        assertEquals(1200, rawProfileB.sum())
        // sum(raw) * 0.025 U/h * 0.5 h per slot
        val dailyUnits = rawProfileB.sum() * Atc3Protocol.DOSE_SCALE * 0.5
        assertEquals(15.0, dailyUnits, 1e-9)
        // Slot 38 covers 19:00.
        assertEquals(0.500, rawProfileB[38] * Atc3Protocol.DOSE_SCALE, 1e-9)
        assertEquals("19:00", Atc3PumpState.slotLabel(38))
    }

    @Test
    fun `a profile summing to raw 1280 delivers 16 units a day`() {
        // Raw 1280 over half hour slots is 16.0 U per day.
        assertEquals(16.0, 1280 * Atc3Protocol.DOSE_SCALE * 0.5, 1e-9)
    }

    @Test
    fun `slot labels follow half hour boundaries`() {
        assertEquals("00:00", Atc3PumpState.slotLabel(0))
        assertEquals("00:30", Atc3PumpState.slotLabel(1))
        assertEquals("20:00", Atc3PumpState.slotLabel(40))
        assertEquals("23:30", Atc3PumpState.slotLabel(47))
    }

    @Test
    fun `comparison tolerates differences within one step`() {
        val wanted = DoubleArray(Atc3Protocol.BASAL_SLOTS) { 0.5 }
        val fromPump = DoubleArray(Atc3Protocol.BASAL_SLOTS) { 0.5 }
        fromPump[10] = 0.5 + Atc3Protocol.DOSE_SCALE / 2
        assertTrue(Atc3PumpState.rateArraysMatch(wanted, fromPump, Atc3Protocol.DOSE_SCALE))
    }

    @Test
    fun `comparison reports a slot that differs by more than one step`() {
        val wanted = DoubleArray(Atc3Protocol.BASAL_SLOTS) { 0.5 }
        val fromPump = wanted.copyOf()
        fromPump[40] = 0.7
        assertFalse(Atc3PumpState.rateArraysMatch(wanted, fromPump, Atc3Protocol.DOSE_SCALE))
        assertEquals(listOf(40), Atc3PumpState.differingSlots(wanted, fromPump, Atc3Protocol.DOSE_SCALE))
    }

    @Test
    fun `arrays of different length never match`() {
        assertFalse(Atc3PumpState.rateArraysMatch(DoubleArray(48), DoubleArray(24), Atc3Protocol.DOSE_SCALE))
    }

    @Test
    fun `a status taken once keeps its count and its moment together when the next one arrives`() {
        val pump = Atc3PumpState()
        pump.applyStatus(testStatus().copy(deliveredTodayUnits = 10.0), readAtMs = 1_000L)
        val held = pump.statusCard!!

        pump.applyStatus(testStatus().copy(deliveredTodayUnits = 12.0), readAtMs = 2_000L)

        assertEquals(10.0, held.deliveredTodayUnits)
        assertEquals(1_000L, held.readAtMs)
        assertEquals(12.0, pump.deliveredTodayUnits)
        assertEquals(2_000L, pump.statusReadAtMs)
    }
}
