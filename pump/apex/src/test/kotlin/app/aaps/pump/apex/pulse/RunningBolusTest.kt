package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.command.ApexBolusDelivery
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexSettings
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Our bolus on the air puts the reconciliation off at once; else the count of Status V1 tells: it stood
 * one pause, no bolus; it moved two pauses in a row, a bolus; under a temporary basal of 0, one move is a bolus.
 */
class RunningBolusTest {

    private val manager: ApexManager = mock()
    private val delivery: ApexBolusDelivery = mock()
    private val counts = ArrayDeque<Double?>()
    private val pauses = ArrayList<Long>()
    private val running = RunningBolus(AAPSLoggerTest(), manager, delivery, mock<ApexTrace>()).apply { pause = { pauses.add(it) } }

    init {
        doAnswer { counts.removeFirst()?.let { units -> card(units) } }.whenever(manager).readStatus()
    }

    private fun card(units: Double, slow: Boolean? = false, tbrRate: Double? = null): ApexPumpState.StatusCard {
        val status: ApexStatusV1 = mock {
            on { tbrActive } doReturn (tbrRate != null)
            on { this.tbrRate } doReturn (tbrRate ?: 0.0)
        }
        val settings: ApexSettings? = slow?.let { s -> mock { on { lowBolusSpeed } doReturn s } }
        return mock {
            on { deliveredTodayUnits } doReturn units
            on { this.status } doReturn status
            on { this.settings } doReturn settings
        }
    }

    @Test
    fun `our bolus on the air - a bolus runs, the pump is not read`() {
        whenever(delivery.bolusInProgress).thenReturn(true)
        assertNull(running.quietStatus(card(10.0), "test"))
        verify(manager, never()).readStatus()
    }

    @Test
    fun `the count stood one pause - no bolus, one read`() {
        counts.addAll(listOf(10.0))
        assertNotNull(running.quietStatus(card(10.0), "test"))
        assertEquals(listOf(RunningBolus.PAUSE_MS), pauses)
    }

    @Test
    fun `the count moved, then stood - a basal portion, no bolus, the last status read goes on`() {
        counts.addAll(listOf(10.05, 10.05))
        assertEquals(10.05, running.quietStatus(card(10.0), "test")!!.deliveredTodayUnits, 1e-9)
        assertEquals(2, pauses.size)
    }

    @Test
    fun `the count moved at both pauses - a bolus runs`() {
        counts.addAll(listOf(10.05, 10.1))
        assertNull(running.quietStatus(card(10.0), "test"))
    }

    @Test
    fun `under a temporary basal of 0 one move is a bolus`() {
        counts.addAll(listOf(10.05))
        assertNull(running.quietStatus(card(10.0, tbrRate = 0.0), "test"))
        assertEquals(1, pauses.size)
    }

    @Test
    fun `the slow bolus speed, or settings not known, waits the longer pause`() {
        counts.addAll(listOf(10.0, 10.0))
        running.quietStatus(card(10.0, slow = true), "test")
        running.quietStatus(card(10.0, slow = null), "test")
        assertEquals(listOf(RunningBolus.SLOW_PAUSE_MS, RunningBolus.SLOW_PAUSE_MS), pauses)
    }

    @Test
    fun `an unanswered read is taken as a bolus that runs`() {
        counts.addAll(listOf(null))
        assertNull(running.quietStatus(card(10.0), "test"))
    }
}
