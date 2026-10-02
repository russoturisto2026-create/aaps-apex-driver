package app.aaps.pump.atc3.manager


import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.atc3.Atc3Pump
import app.aaps.pump.atc3.ble.Atc3BLE
import app.aaps.pump.atc3.history.Atc3ClockWatch
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.comm.Atc3ResponseFrame
import app.aaps.pump.atc3.comm.CrcUtil
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * That an idle queue does not cost the pump a connection.
 *
 * AAPS asks for a disconnection every time its command queue runs dry, which is every few minutes,
 * all day. Taken literally that builds a link hundreds of times a day, and building one is the
 * fragile part, not using it.
 *
 * So the idle ask releases the pump without dropping the link. The rules below are the ones that
 * keep that safe, and each is here because ignoring it would be a way to hold a link that should
 * have been let go.
 */
class Atc3ManagerHoldLinkTest : TestBaseWithProfile() {

    @Mock lateinit var atc3BLE: Atc3BLE
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var manager: Atc3Manager

    @BeforeEach
    fun setup() {
        whenever(preferences.get(Atc3StringKey.Atc3SerialNumber)).thenReturn("12345678")
        whenever(preferences.get(Atc3BooleanKey.HoldLink)).thenReturn(true)
        whenever(atc3BLE.write(any())).thenReturn(false)
        manager = Atc3Manager(
            aapsLogger, rxBus, preferences, dateUtil, atc3BLE, Atc3Pump(),
            Atc3Trace(aapsLogger, preferences), uiInteraction, Atc3ClockWatch(), rh
        )
    }

    @Test
    fun `an empty queue does not cost the pump its link`() {
        whenever(atc3BLE.isConnected).thenReturn(true)

        manager.disconnect("Queue empty")

        verify(atc3BLE, never()).disconnect()
    }

    /**
     * The Bluetooth watchdog exists to toggle the phone's radio when a link cannot be made, and it
     * cannot do that against a link the driver is still holding.
     */
    @Test
    fun `the watchdog still gets the link dropped`() {
        whenever(atc3BLE.isConnected).thenReturn(true)

        manager.disconnect("watchdog")

        verify(atc3BLE).disconnect()
    }

    @Test
    fun `a reason the driver does not recognise is taken at face value`() {
        whenever(atc3BLE.isConnected).thenReturn(true)

        manager.disconnect("stopConnecting")

        verify(atc3BLE).disconnect()
    }

    @Test
    fun `holding can be switched off without a new build`() {
        whenever(atc3BLE.isConnected).thenReturn(true)
        whenever(preferences.get(Atc3BooleanKey.HoldLink)).thenReturn(false)

        manager.disconnect("Queue empty")

        verify(atc3BLE).disconnect()
    }

    /**
     * Nothing to hold. Letting this through would leave the driver thinking it had kept a link that
     * was never there, and the next command would be sent into it.
     */
    @Test
    fun `there is nothing to keep when the link is already down`() {
        whenever(atc3BLE.isConnected).thenReturn(false)

        manager.disconnect("Queue empty")

        verify(atc3BLE).disconnect()
    }

    // The rule that decides whether a silent link gets questioned. The heartbeat cannot be asked
    // for - it is the pump's own initiative every 180 s - so a silence can only be waited out or
    // put to the test, and this is where that choice is made.

    @Test
    fun `a link the pump has just spoken on is not questioned`() {
        assertThat(manager.shouldProbeQuietLink(quietForMs = 1_000, connected = true, busy = false)).isFalse()
    }

    @Test
    fun `a link silent for longer than a missed heartbeat is questioned`() {
        assertThat(manager.shouldProbeQuietLink(quietForMs = 240_000, connected = true, busy = false)).isTrue()
    }

    @Test
    fun `a link still short of the threshold is left alone`() {
        assertThat(manager.shouldProbeQuietLink(quietForMs = 239_999, connected = true, busy = false)).isFalse()
    }

    /**
     * An exchange in flight is itself proof the pump is answering, and a probe sent into the middle
     * of one would be a second conversation on a link that carries one at a time.
     */
    @Test
    fun `a link already carrying an exchange is not questioned`() {
        assertThat(manager.shouldProbeQuietLink(quietForMs = 600_000, connected = true, busy = true)).isFalse()
    }

    @Test
    fun `there is nothing to question when there is no link`() {
        assertThat(manager.shouldProbeQuietLink(quietForMs = 600_000, connected = false, busy = false)).isFalse()
    }

    // The period the heartbeat comes on is the pump's setting, left by whichever client set it
    // last, so the driver sets it once on every link before it starts counting on it.

    @Test
    fun `the heartbeat period is set once on a link that answers`() {
        whenever(atc3BLE.isConnected).thenReturn(true)
        whenever(atc3BLE.write(any())).thenAnswer { manager.onDataReceived(ack()); true }
        manager.onConnected()

        manager.ensureHeartbeatPeriod()
        manager.ensureHeartbeatPeriod()

        verify(atc3BLE, times(1)).write(argThat { this[3] == Atc3Const.MODE_CONTROL && this[4] == Atc3Const.ControlOpcode.SET_HEARTBEAT })
    }

    @Test
    fun `a period the pump did not answer is asked for again`() {
        whenever(atc3BLE.isConnected).thenReturn(true)
        manager.onConnected()

        manager.ensureHeartbeatPeriod()
        manager.ensureHeartbeatPeriod()

        verify(atc3BLE, times(2)).write(any())
    }

    @Test
    fun `a new link asks for the period again`() {
        whenever(atc3BLE.isConnected).thenReturn(true)
        whenever(atc3BLE.write(any())).thenAnswer { manager.onDataReceived(ack()); true }
        manager.onConnected()
        manager.ensureHeartbeatPeriod()
        manager.onConnected()

        manager.ensureHeartbeatPeriod()

        verify(atc3BLE, times(2)).write(any())
    }

    private fun ack(): ByteArray {
        val body = byteArrayOf(Atc3ResponseFrame.MARKER, 0x0A, 0x00, Atc3Const.MODE_CONTROL, Atc3Const.ObjectType.ACK, 0x00, 0x00, 0x00)
        return body + CrcUtil.crc16ModbusLeBytes(body)
    }

    /**
     * Nothing has ever arrived, which the link layer reports as -1 rather than as a huge silence.
     * Read as a number it would look like the longest silence possible and drop a link that has
     * only just come up.
     */
    @Test
    fun `a link that has heard nothing yet is not mistaken for a silent one`() {
        assertThat(manager.shouldProbeQuietLink(quietForMs = -1, connected = true, busy = false)).isFalse()
    }
}
