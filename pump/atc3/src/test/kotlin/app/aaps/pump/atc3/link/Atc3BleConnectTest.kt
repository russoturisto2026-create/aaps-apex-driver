package app.aaps.pump.atc3.link

import android.content.Context
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.pump.atc3.manager.Atc3Manager
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * That an ending is reported once, and only when there was something to end.
 *
 * Closing the GATT client suppresses the stack's own disconnected callback, so the driver has to
 * announce the endings it causes itself. The trap in doing that is announcing one that never
 * happened: [app.aaps.pump.atc3.manager.Atc3Manager.onDisconnected] releases anyone waiting for an
 * answer and tells AAPS the pump has gone, and a spurious one would cut short an exchange on a
 * link that is perfectly alive.
 */
class Atc3BleConnectTest : TestBase() {

    @Mock lateinit var context: Context
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var callback: Atc3BleCallback

    private lateinit var ble: Atc3BLE

    @BeforeEach
    fun setup() {
        whenever(preferences.get(Atc3BooleanKey.Trace)).thenReturn(false)
        ble = Atc3BLE(aapsLogger, context, Atc3Trace(aapsLogger, preferences))
        ble.setCallback(callback)
    }

    @Test
    fun `a driver that never connected is not allowed to say the link dropped`() {
        ble.disconnect()

        verify(callback, never()).onDisconnected()
    }

    @Test
    fun `asking twice does not report two endings`() {
        ble.disconnect()
        ble.disconnect()

        verify(callback, never()).onDisconnected()
    }

    @Test
    fun `nothing is held back before anything has failed`() {
        assertThat(ble.backoffRemainingMs).isEqualTo(0L)
    }

    @Test
    fun `an address that cannot be connected to is refused rather than attempted`() {
        // No Bluetooth address configured is the state a fresh install is in, and the queue calls
        // connect once a second regardless. Answering true would leave AAPS waiting for a link
        // that was never asked for.
        assertThat(ble.connect("")).isFalse()
        assertThat(ble.isConnecting).isFalse()
    }

    @Test
    fun `a failed attempt does not leave the driver believing it is connecting`() {
        // The queue polls isConnecting once a second and holds a wake lock while it does, so a
        // flag left set after a refused attempt costs the phone two minutes of held processor.
        ble.connect("11:22:33:AA:BB:CC")

        assertThat(ble.isConnecting).isFalse()
        assertThat(ble.isConnected).isFalse()
    }
}
