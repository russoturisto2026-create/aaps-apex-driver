package app.aaps.pump.apex

import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.command.ApexFailure
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexSettings
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails

/** The pump's maximum bolus and basal follow AAPS's: written when they differ, the user told either way. */
class ApexLimitsTest {

    private val preferences: Preferences = mock(Preferences::class.java)
    private val manager: ApexManager = mock(ApexManager::class.java)
    private val pumpState: ApexPumpState = mock(ApexPumpState::class.java)
    private val notifications: NotificationManager = mock(NotificationManager::class.java)
    private val limits = ApexLimits(AAPSLoggerTest(), mock(ResourceHelper::class.java), preferences, manager, pumpState, notifications, mock(ApexTrace::class.java))

    /** The pump's settings with a maximum bolus of [bolusRaw] and a maximum basal of [basalRaw] steps of 0.025. */
    private fun settings(bolusRaw: Int, basalRaw: Int) = ApexSettings(
        lowBolusSpeed = false, keypadLock = false, autoOff = false, basalPatterns = false, dailyLimitEnabled = false, english = true,
        alarmSignalType = ApexSettings.ALARM_TYPE_VIBRATION, brightnessLevel = 0, autoOffHours = 1, lowInsulinUnits = 20, lowInsulinHalfHours = 2,
        extendedBolusAllowed = false, bgReminder = false, alarmDuration = ApexSettings.ALARM_DURATION_NORMAL, screenTimeoutRaw = 150,
        dailyLimitUnits = 0, maxBasalRaw = basalRaw, maxBolusRaw = bolusRaw
    )

    private fun aaps(bolus: Double, basal: Double) {
        doReturn(bolus).`when`(preferences).get(DoubleKey.SafetyMaxBolus)
        doReturn(basal).`when`(preferences).get(DoubleKey.ApsMaxBasal)
    }

    private fun writes(): List<ApexSettings> = mockingDetails(manager).invocations.filter { it.method.name == "writeSettings" }.map { it.getArgument(0) }
    private fun notices(): Int = mockingDetails(notifications).invocations.count { it.method.name == "post" }

    @Test
    fun `the pump holds AAPS's limits - nothing is written, nobody told`() {
        aaps(6.0, 2.5)
        assertTrue(limits.matches(settings(240, 100)))
        limits.onHeartbeat(settings(240, 100))
        assertTrue(writes().isEmpty())
        assertEquals(0, notices())
    }

    @Test
    fun `the pump differs - AAPS's limits go to it, the rest of the block as it was, the user told`() {
        aaps(5.0, 2.0)
        doReturn(null).`when`(manager).writeSettings(anyArg())
        val pump = settings(240, 100)
        assertFalse(limits.matches(pump))
        limits.onHeartbeat(pump)
        assertEquals(listOf(pump.copy(maxBolusRaw = 200, maxBasalRaw = 80)), writes())
        assertEquals(1, notices())
    }

    @Test
    fun `the write fails - the user told`() {
        aaps(5.0, 2.0)
        doReturn(ApexFailure("the pump refused the settings")).`when`(manager).writeSettings(anyArg())
        limits.onHeartbeat(settings(240, 100))
        assertEquals(1, writes().size)
        assertEquals(1, notices())
    }

    @Test
    fun `the pump takes no commands - nothing is written`() {
        aaps(5.0, 2.0)
        doReturn(true).`when`(pumpState).locked
        limits.onHeartbeat(settings(240, 100))
        assertTrue(writes().isEmpty())
    }

    @Test
    fun `no settings read yet - nothing is written`() {
        aaps(5.0, 2.0)
        limits.onHeartbeat(null)
        assertTrue(writes().isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T
}
