package app.aaps.pump.atc3.ble

import android.content.Context
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.keys.Atc3BooleanKey
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

    @Test
    fun `service discovery is started once however many MTU callbacks arrive`() {
        // Two MTU callbacks can arrive for one request. Discovery started from each would put two
        // of everything in flight for the rest of the setup — two discoveries, two subscriptions
        // to the authorisation characteristic, two password writes — and a GATT connection carries
        // one operation at a time. The subscription that produces "ready" could then be dropped by
        // the stack with no callback at all, and the connection would sit out its whole watchdog.
        assertThat(ble.claimDiscovery()).isTrue()

        assertThat(ble.claimDiscovery()).isFalse()
        assertThat(ble.claimDiscovery()).isFalse()
    }

    @Test
    fun `discovered characteristics are acted on once`() {
        assertThat(ble.claimServices()).isTrue()

        assertThat(ble.claimServices()).isFalse()
    }

    @Test
    fun `the setup of one link is begun once however many connected callbacks arrive`() {
        // Seen on the bench while the phone's Bluetooth was being switched off: one connection
        // attempt and two connected callbacks 26 ms apart. Setup begun again from the second asked
        // for the MTU while service discovery from the first was still in flight; a GATT
        // connection carries one operation at a time, so the answers stopped belonging to what was
        // waiting for them and discovery was never answered at all.
        assertThat(ble.claimLinkUp()).isTrue()

        assertThat(ble.claimLinkUp()).isFalse()
        assertThat(ble.claimLinkUp()).isFalse()
    }

    @Test
    fun `a fresh transport has claimed nothing`() {
        // The claims are per connection, so a new one has to be able to take them again. What
        // clears them is the same state reset that clears the characteristics and the link stamp.
        val second = Atc3BLE(aapsLogger, context, Atc3Trace(aapsLogger, preferences))

        assertThat(second.claimDiscovery()).isTrue()
        assertThat(second.claimServices()).isTrue()
        assertThat(second.claimLinkUp()).isTrue()
    }
}
