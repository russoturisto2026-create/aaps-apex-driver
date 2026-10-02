package app.aaps.pump.atc3.manager

import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.atc3.Atc3Const
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
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * That the driver stops hammering a Bluetooth stack that has just refused it.
 *
 * AAPS's queue calls connect once a second for two minutes and holds a partial wake lock the whole
 * time, so the driver is the only thing that can decide not to try. After a link lost with
 * `status=8`, every attempt can take the stack's full thirty seconds to refuse with `status=255`,
 * back to back.
 */
class Atc3ManagerConnectTest : TestBaseWithProfile() {

    @Mock lateinit var atc3BLE: Atc3BLE
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var manager: Atc3Manager

    @BeforeEach
    fun setup() {
        whenever(preferences.get(Atc3StringKey.Atc3SerialNumber)).thenReturn("12345678")
        whenever(preferences.get(Atc3StringKey.Atc3Address)).thenReturn("11:22:33:AA:BB:CC")
        whenever(preferences.get(Atc3BooleanKey.Trace)).thenReturn(false)
        // No change has been made, so there is no second candidate to fall back to.
        whenever(preferences.get(Atc3StringKey.Atc3BtPasswordAlternate)).thenReturn("")
        manager = Atc3Manager(
            aapsLogger, rxBus, preferences, dateUtil, atc3BLE, Atc3Pump(),
            Atc3Trace(aapsLogger, preferences), uiInteraction, Atc3ClockWatch(), rh
        )
    }

    @Test
    fun `while the stack is being given a rest, nothing is asked of it`() {
        whenever(atc3BLE.backoffRemainingMs).thenReturn(5_000L)

        assertThat(manager.connect("Connection needed")).isFalse()
        verify(atc3BLE, never()).connect(any())
    }

    /**
     * The pump refuses everything until it has the password, so a connection that goes out without
     * one is a wasted wakeup at best. It has to be the value stored right now, not one read once at
     * startup: the password can change between two connections.
     */
    @Test
    fun `the transport is given the configured password before every attempt`() {
        whenever(atc3BLE.backoffRemainingMs).thenReturn(0L)
        whenever(atc3BLE.connect(any())).thenReturn(true)
        whenever(preferences.get(Atc3StringKey.Atc3BtPassword)).thenReturn("487613")

        manager.connect("Connection needed")

        verify(atc3BLE).setPassword("487613")
    }

    /**
     * A refused password is a definite answer, so retrying it for ever holds the radio and the wake
     * lock for a question whose answer is already known and can only be read off the pump.
     */
    @Test
    fun `a password the pump keeps refusing is given up on`() {
        whenever(atc3BLE.backoffRemainingMs).thenReturn(0L)
        whenever(atc3BLE.connect(any())).thenReturn(true)
        whenever(preferences.get(Atc3StringKey.Atc3BtPassword)).thenReturn("487613")

        repeat(Atc3Const.AUTH_MAX_ATTEMPTS) {
            assertThat(manager.connect("Connection needed")).isTrue()
            manager.onAuthenticationRejected()
        }

        assertThat(manager.connect("Connection needed")).isFalse()
        verify(atc3BLE, times(Atc3Const.AUTH_MAX_ATTEMPTS)).connect(any())
    }

    /** Entering a different password is a different question, and it gets its own ten tries. */
    @Test
    fun `changing the password starts the count over`() {
        whenever(atc3BLE.backoffRemainingMs).thenReturn(0L)
        whenever(atc3BLE.connect(any())).thenReturn(true)
        whenever(preferences.get(Atc3StringKey.Atc3BtPassword)).thenReturn("487613")
        repeat(Atc3Const.AUTH_MAX_ATTEMPTS) {
            manager.connect("Connection needed")
            manager.onAuthenticationRejected()
        }
        assertThat(manager.connect("Connection needed")).isFalse()

        whenever(preferences.get(Atc3StringKey.Atc3BtPassword)).thenReturn("730473")

        assertThat(manager.connect("Connection needed")).isTrue()
        verify(atc3BLE).setPassword("730473")
    }

    /**
     * A change the pump acknowledged has not always left it holding the value that was asked for,
     * so the first refusal afterwards is spent on the other candidate rather than on the counter.
     * It is spent once: a password that is simply wrong still has to end up given up on.
     */
    @Test
    fun `a refusal straight after a change presents the other candidate instead of counting`() {
        whenever(atc3BLE.backoffRemainingMs).thenReturn(0L)
        whenever(atc3BLE.connect(any())).thenReturn(true)
        whenever(preferences.get(Atc3StringKey.Atc3BtPassword)).thenReturn("123456")
        whenever(preferences.get(Atc3StringKey.Atc3BtPasswordAlternate)).thenReturn("188992")

        manager.connect("Connection needed")
        manager.onAuthenticationRejected()

        verify(preferences).put(Atc3StringKey.Atc3BtPassword, "188992")
        verify(preferences).put(Atc3StringKey.Atc3BtPasswordAlternate, "")
        verify(uiInteraction, never()).addNotification(any(), any(), any())
    }

    @Test
    fun `once the rest is over the link is asked for again`() {
        whenever(atc3BLE.backoffRemainingMs).thenReturn(0L)
        whenever(atc3BLE.connect(any())).thenReturn(true)

        assertThat(manager.connect("Connection needed")).isTrue()
        verify(atc3BLE, times(1)).connect(any())
    }

    @Test
    fun `a pump with no serial is never connected to, whatever the stack says`() {
        // Every request carries the serial, so a connection made without one could not be used.
        whenever(preferences.get(Atc3StringKey.Atc3SerialNumber)).thenReturn("")

        assertThat(manager.connect("Connection needed")).isFalse()
        verify(atc3BLE, never()).connect(any())
    }
}
