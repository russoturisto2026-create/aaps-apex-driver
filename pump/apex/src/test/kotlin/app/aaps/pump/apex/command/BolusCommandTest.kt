package app.aaps.pump.apex.command

import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.apex.R
import app.aaps.pump.apex.aaps.AapsCarrier
import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.bolus.BolusAccount
import app.aaps.pump.apex.bolus.JournalBench.Companion.TWO
import app.aaps.pump.apex.store.FakeStore
import app.aaps.pump.apex.pulse.HeartbeatTick
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Our bolus with nothing read around it: the row goes to AAPS the moment the pump accepts it, the
 * picture keeps what the pump showed, and the row shows the last frame of a cancelled bolus at once.
 */
class BolusCommandTest {

    private val delivery: ApexBolusDelivery = mock { on { takeNotedHeartbeat() } doReturn null }
    private val heartbeatTick: HeartbeatTick = mock()
    private val carrier: AapsCarrier = mock()
    private val rh: ResourceHelper = mock { on { gs(R.string.apex_refused_by_pump) } doReturn "refused" }
    private val store = FakeStore()
    private val command = BolusCommand(AAPSLoggerTest(), mock<BolusProgressData>(), rh, delivery, store, carrier, mock<ApexPumpState>(), heartbeatTick, mock<ApexTrace>())
    private val acceptedAt = TWO * 1000L + 5_000L

    /** The pump accepts the bolus at [acceptedAt], shows [progress] and ends with [outcome]. */
    private fun pumpGives(outcome: ApexBolusOutcome, vararg progress: Double) = runBlocking {
        whenever(delivery.bolus(any(), any(), any(), any(), any())).doSuspendableAnswer {
            if (outcome is ApexBolusOutcome.Delivered) it.getArgument<suspend (Long) -> Unit>(2).invoke(acceptedAt)
            progress.forEach { units -> it.getArgument<suspend (Double) -> Unit>(3).invoke(units) }
            outcome
        }
    }

    private fun written(): List<AapsWrite> = argumentCaptor<AapsWrite>().also { verify(carrier, atLeast(0)).carry(it.capture(), eq("serial")) }.allValues

    @Test
    fun `a bolus given whole - one row at once at the ordered dose, the picture waits for the heartbeat`() {
        pumpGives(ApexBolusOutcome.Delivered(0.5, completed = true, frames = 10), 0.25, 0.5)
        val result = runBlocking { command.deliver(DetailedBolusInfo().apply { insulin = 0.5 }, "serial") }

        assertTrue(result.success)
        assertEquals(listOf<AapsWrite>(AapsWrite.EarlyBolus(acceptedAt, acceptedAt, 0.5, "NORMAL")), written())
        val picture = BolusAccount(store, "serial").openActions().single()
        assertEquals(0.5, picture.seenUnits!!, 1e-9)
        assertEquals(10, picture.frames)
        assertTrue(picture.completed && picture.lastWordSeen)
    }

    @Test
    fun `a bolus cancelled after frames - the row shows the last frame at once, the cancel is in the picture`() {
        pumpGives(ApexBolusOutcome.Delivered(0.3, cancelled = true, frames = 6, cancelPressed = true), 0.3)
        val result = runBlocking { command.deliver(DetailedBolusInfo().apply { insulin = 1.0 }, "serial") }

        assertTrue(result.success)
        val rows = written()
        assertEquals(2, rows.size)
        assertEquals(AapsWrite.Bolus(null, acceptedAt, 0.3, "NORMAL", tempId = acceptedAt), rows[1])
        val picture = BolusAccount(store, "serial").openActions().single()
        assertTrue(picture.cancelPressed && picture.cancelAccepted)
    }

    @Test
    fun `a bolus over by its time with no frame seen - the row stays at the ordered dose, nothing seen is in the picture`() {
        pumpGives(ApexBolusOutcome.Delivered(0.0))
        runBlocking { command.deliver(DetailedBolusInfo().apply { insulin = 0.5 }, "serial") }

        assertEquals(1, written().size)
        assertTrue(BolusAccount(store, "serial").openActions().single().seenNothing)
    }

    @Test
    fun `a bolus the pump refuses - no row, no picture`() {
        pumpGives(ApexBolusOutcome.Refused)
        val result = runBlocking { command.deliver(DetailedBolusInfo().apply { insulin = 0.5 }, "serial") }

        assertFalse(result.success)
        assertFalse(result.enacted)
        verify(carrier, never()).carry(any(), any())
        assertTrue(BolusAccount(store, "serial").openActions().isEmpty())
        verify(heartbeatTick, times(0)).run(any())
    }
}
