package app.aaps.pump.apex.aaps

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.RM
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.math.abs

/** The bolus rows as they reach AAPS: the row of our order at once, then set by its temporary number. */
class AapsCarrierTest {

    private val pumpSync: PumpSync = mock {
        onBlocking { addBolusWithTempId(any(), any(), any(), any(), any(), any()) } doReturn true
        onBlocking { syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any()) } doReturn true
    }

    private val loop = mock<Loop>()
    private val profile = mock<EffectiveProfile>()
    private val profileFunction = mock<ProfileFunction> { onBlocking { getProfile() } doReturn profile }
    private val carrier = AapsCarrier(AAPSLoggerTest(), mock<ResourceHelper>(), pumpSync, mock<NotificationManager>(), mock<DateUtil>(), mock<ApexTrace>(), lazy { loop }, profileFunction)

    @Test
    fun `the account could not be settled - a closed loop is opened`() {
        runBlocking { whenever(loop.runningMode()).thenReturn(RM.Mode.CLOSED_LOOP) }
        runBlocking { whenever(loop.handleRunningModeChange(any(), any(), any(), any(), any(), any())).thenReturn(true) }
        carrier.carry(AapsWrite.OpenLoop, "12345678")
        runBlocking { verify(loop).handleRunningModeChange(eq(RM.Mode.OPEN_LOOP), eq(Action.OPEN_LOOP_MODE), eq(Sources.Pump), any(), any(), eq(profile)) }
    }

    @Test
    fun `the loop is already open or off - nothing is changed`() {
        runBlocking { whenever(loop.runningMode()).thenReturn(RM.Mode.OPEN_LOOP) }
        carrier.carry(AapsWrite.OpenLoop, "12345678")
        runBlocking { verify(loop, never()).handleRunningModeChange(any(), any(), any(), any(), any(), any()) }
    }
    private val serial = "12345678"

    /** The amount handed to AAPS is [units]. */
    private fun units(units: Double): PumpInsulin = argThat { abs(cU - units) < 1e-9 }

    private fun syncByTempGives(done: Boolean) = runBlocking {
        whenever(pumpSync.syncBolusWithTempId(any(), any(), any(), anyOrNull(), anyOrNull(), any(), any())).thenReturn(done)
    }

    @Test
    fun `the row of our order goes in under its temporary number`() {
        carrier.carry(AapsWrite.EarlyBolus(tempId = 7L, timestampMs = 1_000L, amountUnits = 0.5, type = "SMB"), serial)
        runBlocking { verify(pumpSync).addBolusWithTempId(eq(1_000L), units(0.5), eq(7L), eq(BS.Type.SMB), eq(PumpType.APEX), eq(serial)) }
    }

    @Test
    fun `a final row of ours sets the row of the order, with the pump's number`() {
        syncByTempGives(true)
        carrier.carry(AapsWrite.Bolus(pumpId = 42L, timestampMs = 1_000L, amountUnits = 0.4, type = "SMB", tempId = 7L), serial)
        runBlocking {
            verify(pumpSync).syncBolusWithTempId(eq(1_000L), units(0.4), eq(7L), eq(BS.Type.SMB), eq(42L), eq(PumpType.APEX), eq(serial))
            verify(pumpSync, never()).syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())
        }
    }

    @Test
    fun `the row of the order is not there - the row is made by the pump's number`() {
        syncByTempGives(false)
        carrier.carry(AapsWrite.Bolus(pumpId = 42L, timestampMs = 1_000L, amountUnits = 0.4, type = "SMB", tempId = 7L), serial)
        runBlocking { verify(pumpSync).syncBolusWithPumpId(eq(1_000L), units(0.4), eq(BS.Type.SMB), eq(42L), eq(PumpType.APEX), eq(serial)) }
    }

    @Test
    fun `no row of the order and no record - a bolus that gave something is made anew, one that gave nothing is not`() {
        syncByTempGives(false)
        carrier.carry(AapsWrite.Bolus(pumpId = null, timestampMs = 1_000L, amountUnits = 0.3, type = "SMB", tempId = 7L), serial)
        carrier.carry(AapsWrite.Bolus(pumpId = null, timestampMs = 2_000L, amountUnits = 0.0, type = "SMB", tempId = 8L), serial)
        runBlocking {
            verify(pumpSync).addBolusWithTempId(eq(1_000L), units(0.3), eq(7L), eq(BS.Type.SMB), eq(PumpType.APEX), eq(serial))
            verify(pumpSync, never()).addBolusWithTempId(eq(2_000L), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `a row of somebody else goes in by the pump's number`() {
        carrier.carry(AapsWrite.Bolus(pumpId = 42L, timestampMs = 1_000L, amountUnits = 0.4, type = "NORMAL"), serial)
        runBlocking {
            verify(pumpSync).syncBolusWithPumpId(eq(1_000L), units(0.4), eq(BS.Type.NORMAL), eq(42L), eq(PumpType.APEX), eq(serial))
            verify(pumpSync, never()).syncBolusWithTempId(any(), any(), any(), anyOrNull(), anyOrNull(), any(), any())
        }
    }
}
