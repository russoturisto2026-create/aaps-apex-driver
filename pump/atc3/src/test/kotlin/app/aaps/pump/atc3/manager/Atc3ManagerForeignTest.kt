package app.aaps.pump.atc3.manager

import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.Atc3Pump
import app.aaps.pump.atc3.ble.Atc3BLE
import app.aaps.pump.atc3.comm.Atc3ResponseFrame
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import app.aaps.pump.atc3.comm.CrcUtil
import app.aaps.pump.atc3.history.Atc3ClockWatch
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * That an answer nobody asked for is written down.
 *
 * The pump replies to what it is asked and to nothing else, so a reply arriving with no exchange
 * armed came from somebody else — another client connected to the same pump. On a shared link
 * Android hands every notification to every subscribed app, and a response frame carries no
 * identity at all: no request id, no sequence, no serial, only the shape of the answer. The driver
 * therefore cannot tell such a frame from its own and does not try to; it counts them, so that a
 * measurement taken on a contaminated link can be recognised as one afterwards.
 *
 * This is written from the other direction too — the frames that legitimately arrive unasked must
 * not be counted, or the number means nothing.
 */
class Atc3ManagerForeignTest : TestBaseWithProfile() {

    @Mock lateinit var atc3BLE: Atc3BLE
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var trace: Atc3Trace

    private lateinit var manager: Atc3Manager

    @BeforeEach
    fun setup() {
        whenever(preferences.get(Atc3StringKey.Atc3SerialNumber)).thenReturn("")
        whenever(atc3BLE.write(any())).thenReturn(false)
        whenever(trace.sessionId).thenReturn(1L)
        manager = Atc3Manager(aapsLogger, rxBus, preferences, dateUtil, atc3BLE, Atc3Pump(), trace, uiInteraction, Atc3ClockWatch(), rh)
    }

    @Test
    fun `a status answer arriving with nothing armed is somebody else's`() {
        manager.onDataReceived(reply(Atc3Const.MODE_HISTORY, Atc3Const.ReadOpcode.STATUS_V1))

        verify(trace).countForeign()
    }

    @Test
    fun `an acknowledgement arriving with nothing armed is somebody else's`() {
        // A control ack carries no request identity whatsoever, which is the whole problem: this
        // frame is byte for byte what an ack of ours would be.
        manager.onDataReceived(reply(Atc3Const.MODE_CONTROL, Atc3Const.ObjectType.ACK))

        verify(trace).countForeign()
    }

    @Test
    fun `a refusal arriving with nothing armed is somebody else's`() {
        manager.onDataReceived(reply(Atc3Const.MODE_CONTROL, Atc3Const.ObjectType.REJECTED))

        verify(trace).countForeign()
    }

    @Test
    fun `bolus progress is not evidence of another client`() {
        // The pump sends these unasked while a bolus runs. Counting them would make the number
        // report a second client every time anyone gives a bolus.
        manager.onDataReceived(reply(Atc3Const.MODE_CONTROL, Atc3Const.ObjectType.BOLUS_PROGRESS))

        verify(trace, never()).countForeign()
    }

    @Test
    fun `bolus completion is not evidence of another client`() {
        manager.onDataReceived(reply(Atc3Const.MODE_CONTROL, Atc3Const.ObjectType.BOLUS_COMPLETED))

        verify(trace, never()).countForeign()
    }

    @Test
    fun `the detail is written once for a connection and then only counted`() {
        repeat(3) { manager.onDataReceived(reply(Atc3Const.MODE_HISTORY, Atc3Const.ReadOpcode.STATUS_V1)) }

        verify(trace, times(3)).countForeign()
        verify(trace).event(eq(Atc3TraceCat.BLE), eq("foreign_answer"), any(), any())
    }

    @Test
    fun `the next connection says it again`() {
        manager.onDataReceived(reply(Atc3Const.MODE_HISTORY, Atc3Const.ReadOpcode.STATUS_V1))
        whenever(trace.sessionId).thenReturn(2L)
        manager.onDataReceived(reply(Atc3Const.MODE_HISTORY, Atc3Const.ReadOpcode.STATUS_V1))

        verify(trace, times(2)).event(eq(Atc3TraceCat.BLE), eq("foreign_answer"), any(), any())
    }

    @Test
    fun `the heartbeat is not evidence of another client`() {
        manager.onDataReceived(heartbeat(Atc3Const.HEARTBEAT_OBJECT))
        verify(atc3BLE).noteHeartbeat()
        verify(trace, never()).countForeign()
    }

    /**
     * The object byte of the heartbeat is the period in minutes, set by whichever client last set
     * it. A period another client left behind is still the pump saying it is there.
     */
    @Test
    fun `a heartbeat on somebody else's period is still the heartbeat`() {
        manager.onDataReceived(heartbeat(0x02))
        verify(atc3BLE).noteHeartbeat()
        verify(trace, never()).countForeign()
    }

    /** The heartbeat frame: eight bytes, declared as six, the way frame id 0xA5 declares itself. */
    private fun heartbeat(period: Byte): ByteArray {
        val body = byteArrayOf(Atc3ResponseFrame.MARKER, 0x06, 0x00, Atc3Const.MODE_HEARTBEAT, period, 0x00)
        return body + CrcUtil.crc16ModbusLeBytes(body)
    }

    /** A valid, CRC correct answer of the given shape, with no payload behind it. */
    private fun reply(frameId: Byte, objectType: Byte): ByteArray {
        val body = byteArrayOf(Atc3ResponseFrame.MARKER, 0x0A, 0x00, frameId, objectType, 0x00, 0x00, 0x00)
        return body + CrcUtil.crc16ModbusLeBytes(body)
    }
}
