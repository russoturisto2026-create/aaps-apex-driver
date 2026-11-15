package app.aaps.pump.apex.state

import app.aaps.pump.apex.protocol.ApexResponseParser
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.protocol.ApexStatusV2
import app.aaps.pump.apex.protocol.CrcUtilTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Which battery the pump state gives: by voltage when Status V2 is there, else by the bars of Status V1. */
class ApexPumpStateBatteryTest {

    private val state = ApexPumpState()

    private fun applyStatusV1() {
        val raw = ApexResponseParser().feed(CrcUtilTest.hexToBytes(CrcUtilTest.STATUS_V1_FRAME))[0]
        state.applyStatus(ApexStatusV1.decode(raw)!!, raw.raw, 1_000L)
    }

    @Test
    fun `there is no battery before any status`() {
        assertNull(state.batteryPercent)
        assertNull(state.batteryPercentFlow.value)
    }

    @Test
    fun `a pump with no Status V2 shows the bars of Status V1`() {
        applyStatusV1()
        assertEquals(100, state.batteryPercent)
        assertEquals(100, state.batteryPercentFlow.value)
    }

    @Test
    fun `Status V2 takes over once it is read`() {
        applyStatusV1()
        state.applyStatusV2(ApexStatusV2(activeInsulinUnits = 0.0, batteryVolts = 1.39))
        assertEquals(56, state.batteryPercent)
        assertEquals(56, state.batteryPercentFlow.value)
    }

    @Test
    fun `Status V2 stays the source when a later Status V1 comes`() {
        state.applyStatusV2(ApexStatusV2(activeInsulinUnits = 0.0, batteryVolts = 1.39))
        applyStatusV1()
        assertEquals(56, state.batteryPercent)
    }

    @Test
    fun `a pump forgotten leaves no battery`() {
        applyStatusV1()
        state.reset()
        assertNull(state.batteryPercent)
    }
}
