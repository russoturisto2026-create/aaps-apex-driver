package app.aaps.pump.apex

import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.command.ApexBolusDelivery
import app.aaps.pump.apex.command.ApexClockWrite
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.state.ApexPumpState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The clock write: after the phone's time moved the temporary basal ends, the journals are settled, the write, the same rate again. */
class ApexPumpClockTest {

    private val serial = "12345678"
    private val nowMs = 1_760_000_000_000L

    /** The offset the pump's clock stood in before the phone's zone moved an hour. */
    private val movedFrom get() = clock.phoneOffsetMs() - 3_600_000L
    private val manager = mock<ApexManager> { on { writeClock(any()) } doReturn ApexClockWrite(nowMs, null) }
    private val pumpState = ApexPumpState()
    private val store = FakeStore()
    private val clock = ApexPumpClock(
        mock<AAPSLogger>(), store, pumpState, manager, mock<ApexBolusDelivery>()
    )

    /** The pump runs 1.0 U/h ordered for 30 min, 10 of them gone, or nothing; the phone's zone just moved. */
    private fun running(tbrActive: Boolean) {
        val status = ApexStatusV1(
            activeProfileIndex = 0, clockSeconds = nowMs / 1000, reservoirUnits = 100.0, deliveredTodayUnits = 10.0, lastStop = null,
            tbrElapsedMinutes = 10, scheduledBasalRate = 0.5, suspended = false, locked = false, tbrActive = tbrActive, tbrRate = 1.0, tbrMode = 1,
            activeAlarmCodes = emptyList(), tbrDurationMinutes = 30, batteryBars = 4
        )
        pumpState.applyStatus(status, ByteArray(0), nowMs)
        clock.onPhoneTimeChanged(TimeChangeType.TimezoneChanged)
    }

    @Test
    fun `the temporary basal ends, the journals are settled, the clock is written, then the same rate again for what was left, rounded up to a quarter hour`() {
        running(tbrActive = true)
        val steps = ArrayList<String>()
        var again: Pair<Double, Int>? = null
        val written = clock.writeIfDue(
            serial, movedFrom, nowMs,
            { steps.add("end"); null }, { steps.add("journals"); true }, { rate, minutes -> again = rate to minutes; null }
        )
        assertTrue(written)
        assertEquals(listOf("end", "journals"), steps)
        verify(manager).writeClock(any())
        assertEquals(1.0 to 30, again)
    }

    @Test
    fun `nothing ran, the journals are settled and nothing is ordered again`() {
        running(tbrActive = false)
        var journals = false
        var again: Pair<Double, Int>? = null
        assertTrue(clock.writeIfDue(serial, movedFrom, nowMs, { null }, { journals = true; true }, { rate, minutes -> again = rate to minutes; null }))
        assertTrue(journals)
        verify(manager).writeClock(any())
        assertNull(again)
    }

    @Test
    fun `journals not settled after the cancel hold the write, and nothing is ordered`() {
        running(tbrActive = true)
        var again = false
        assertFalse(clock.writeIfDue(serial, movedFrom, nowMs, { null }, { false }, { _, _ -> again = true; null }))
        verify(manager, never()).writeClock(any())
        assertFalse(again)
    }

    @Test
    fun `a temporary basal that would not end holds the write, and the journals are not read`() {
        running(tbrActive = true)
        var journals = false
        assertFalse(clock.writeIfDue(serial, movedFrom, nowMs, { "no answer" }, { journals = true; true }, { _, _ -> null }))
        assertFalse(journals)
        verify(manager, never()).writeClock(any())
    }

    @Test
    fun `the first write, with the time where it was, is a clean write`() {
        val status = ApexStatusV1(
            activeProfileIndex = 0, clockSeconds = nowMs / 1000, reservoirUnits = 100.0, deliveredTodayUnits = 10.0, lastStop = null,
            tbrElapsedMinutes = 10, scheduledBasalRate = 0.5, suspended = false, locked = false, tbrActive = true, tbrRate = 1.0, tbrMode = 1,
            activeAlarmCodes = emptyList(), tbrDurationMinutes = 30, batteryBars = 4
        )
        pumpState.applyStatus(status, ByteArray(0), nowMs)
        var touched = false
        assertTrue(clock.writeIfDue(serial, clock.phoneOffsetMs(), nowMs, { touched = true; null }, { touched = true; true }, { _, _ -> touched = true; null }))
        assertFalse(touched)
        verify(manager).writeClock(any())
    }

    @Test
    fun `the heartbeat shows the clock off by more than half a second, and only then the clock is written`() {
        ApexClock.clockWritten(store, serial, clock.phoneOffsetMs(), nowMs, "first write", 0L)
        whenever(manager.pumpClockErrorMs()).thenReturn(400L)
        assertFalse(clock.writeIfDue(serial, clock.phoneOffsetMs(), nowMs + 60_000L, { null }, { true }, { _, _ -> null }))
        verify(manager, never()).writeClock(any())
        whenever(manager.pumpClockErrorMs()).thenReturn(-600L)
        assertTrue(clock.writeIfDue(serial, clock.phoneOffsetMs(), nowMs + 60_000L, { null }, { true }, { _, _ -> null }))
        verify(manager).writeClock(any())
    }

    @Test
    fun `the phone's time set, in the same zone, is a clean write without the package`() {
        running(tbrActive = true)
        var touched = false
        assertTrue(clock.writeIfDue(serial, clock.phoneOffsetMs(), nowMs, { touched = true; null }, { touched = true; true }, { _, _ -> touched = true; null }))
        assertFalse(touched)
        verify(manager).writeClock(any())
    }
}
