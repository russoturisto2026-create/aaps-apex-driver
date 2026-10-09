package app.aaps.pump.atc3.manager

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.clock.Atc3ClockWatch
import app.aaps.pump.atc3.command.Atc3BolusDelivery
import app.aaps.pump.atc3.exchange.Atc3Exchange
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.link.Atc3BLE
import app.aaps.pump.atc3.link.Atc3BtPassword
import app.aaps.pump.atc3.link.Atc3Connection
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
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
    private lateinit var exchange: Atc3Exchange
    private lateinit var bolusDelivery: Atc3BolusDelivery
    private lateinit var connection: Atc3Connection
    private lateinit var trace: Atc3Trace

    @BeforeEach
    fun setup() {
        whenever(preferences.get(Atc3StringKey.Atc3SerialNumber)).thenReturn("12345678")
        // A password is entered: without one the driver asks the pump nothing, which is not what these are about.
        whenever(preferences.get(Atc3StringKey.Atc3BtPassword)).thenReturn("487613")
        whenever(preferences.get(Atc3StringKey.Atc3Address)).thenReturn("11:22:33:AA:BB:CC")
        whenever(preferences.get(Atc3BooleanKey.Trace)).thenReturn(false)
        // No change has been made, so there is no second candidate to fall back to.
        whenever(preferences.get(Atc3StringKey.Atc3BtPasswordAlternate)).thenReturn("")
        whenever(atc3BLE.hasBluetooth).thenReturn(true)
        whenever(atc3BLE.isBluetoothOn).thenReturn(true)
        whenever(atc3BLE.isValidAddress(any())).thenReturn(true)
        trace = Atc3Trace(aapsLogger, preferences)
        connection = Atc3Connection(aapsLogger, rxBus, preferences, atc3BLE, trace, uiInteraction, rh, mock())
        exchange = Atc3Exchange(aapsLogger, preferences, atc3BLE, trace, connection)
        bolusDelivery = Atc3BolusDelivery(aapsLogger, dateUtil, exchange)
        manager = Atc3Manager(aapsLogger, rxBus, preferences, dateUtil, atc3BLE, Atc3PumpState(), trace, Atc3ClockWatch(), connection, exchange, bolusDelivery)
    }

    @Test
    fun `while the stack is being given a rest, nothing is asked of it`() {
        whenever(atc3BLE.backoffRemainingMs).thenReturn(5_000L)

        assertThat(connection.connect("Connection needed")).isFalse()
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

        connection.connect("Connection needed")

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
            assertThat(connection.connect("Connection needed")).isTrue()
            connection.onAuthenticationRejected()
        }

        assertThat(connection.connect("Connection needed")).isFalse()
        verify(atc3BLE, times(Atc3Const.AUTH_MAX_ATTEMPTS)).connect(any())
    }

    /**
     * With no password entered the driver does not bring a link up at all: what the pump lets
     * through says nothing, since it lets anybody onto a link another client has opened.
     */
    @Test
    fun `with no password entered nothing is connected to, and the user is told once`() {
        whenever(atc3BLE.backoffRemainingMs).thenReturn(0L)
        whenever(atc3BLE.connect(any())).thenReturn(true)
        whenever(preferences.get(Atc3StringKey.Atc3BtPassword)).thenReturn("")
        whenever(rh.gs(R.string.atc3_password_missing)).thenReturn("no password entered")

        assertThat(connection.connect("Connection needed")).isFalse()
        assertThat(connection.connect("Connection needed")).isFalse()

        verify(atc3BLE, never()).connect(any())
        verify(uiInteraction, times(1)).addNotification(any(), eq("no password entered"), any())
    }

    /** A link held from before the password was taken out is dropped at the first request. */
    @Test
    fun `a link held when the password is taken out is dropped rather than used`() {
        whenever(preferences.get(Atc3StringKey.Atc3BtPassword)).thenReturn("")

        assertThat(manager.readStatus()).isNull()

        verify(atc3BLE).disconnect()
        verify(atc3BLE, never()).write(any())
    }

    /**
     * The queue asks once a second. Once the driver has given up, each of those asks is refused
     * quietly: no new session is begun for a pump that is not asked anything.
     */
    @Test
    fun `a password given up on is refused without a session for each ask`() {
        whenever(atc3BLE.backoffRemainingMs).thenReturn(0L)
        whenever(atc3BLE.connect(any())).thenReturn(true)
        repeat(Atc3Const.AUTH_MAX_ATTEMPTS) {
            connection.connect("Connection needed")
            connection.onAuthenticationRejected()
        }
        val sessions = trace.sessionId

        repeat(3) { assertThat(connection.connect("Connection needed")).isFalse() }

        assertThat(trace.sessionId).isEqualTo(sessions)
        verify(atc3BLE, times(Atc3Const.AUTH_MAX_ATTEMPTS)).connect(any())
    }

    /**
     * With the phone's Bluetooth off, some phones still let a link to the pump come up and fail it at
     * the first write. Nothing is tried, no session is begun, and the user is told once.
     */
    @Test
    fun `while Bluetooth is off nothing is connected to, and the user is told once`() {
        whenever(atc3BLE.backoffRemainingMs).thenReturn(0L)
        whenever(atc3BLE.connect(any())).thenReturn(true)
        whenever(atc3BLE.isBluetoothOn).thenReturn(false)
        whenever(rh.gs(R.string.atc3_bluetooth_off)).thenReturn("bluetooth off")
        val sessions = trace.sessionId

        assertThat(connection.connect("Connection needed")).isFalse()
        assertThat(connection.connect("Connection needed")).isFalse()

        verify(atc3BLE, never()).connect(any())
        assertThat(trace.sessionId).isEqualTo(sessions)
        verify(uiInteraction, times(1)).addNotification(any(), eq("bluetooth off"), any())
    }

    @Test
    fun `Bluetooth off during a wait is told at once, not when the wait is over`() {
        whenever(atc3BLE.backoffRemainingMs).thenReturn(5_000L)
        whenever(atc3BLE.isBluetoothOn).thenReturn(false)
        whenever(rh.gs(R.string.atc3_bluetooth_off)).thenReturn("bluetooth off")

        connection.connect("Connection needed")

        verify(uiInteraction, times(1)).addNotification(any(), eq("bluetooth off"), any())
    }

    /** Bluetooth on again: the link is asked for, and the next time it goes off the user is told again. */
    @Test
    fun `Bluetooth on again connects, and off again is told again`() {
        whenever(atc3BLE.backoffRemainingMs).thenReturn(0L)
        whenever(atc3BLE.connect(any())).thenReturn(true)
        whenever(rh.gs(R.string.atc3_bluetooth_off)).thenReturn("bluetooth off")

        whenever(atc3BLE.isBluetoothOn).thenReturn(false)
        connection.connect("Connection needed")
        whenever(atc3BLE.isBluetoothOn).thenReturn(true)
        assertThat(connection.connect("Connection needed")).isTrue()
        whenever(atc3BLE.isBluetoothOn).thenReturn(false)
        connection.connect("Connection needed")

        verify(atc3BLE, times(1)).connect(any())
        verify(uiInteraction, times(2)).addNotification(any(), eq("bluetooth off"), any())
    }

    /**
     * The queue asks once a second while it waits. A reason to refuse is logged when it begins, not at
     * every ask, and again when it comes back after an ask that went to the pump.
     */
    @Test
    fun `a refusal is logged once for each time it begins`() {
        val logger = mock<AAPSLogger>()
        val quiet = Atc3Connection(logger, rxBus, preferences, atc3BLE, trace, uiInteraction, rh, mock())
        whenever(atc3BLE.connect(any())).thenReturn(true)
        whenever(atc3BLE.backoffRemainingMs).thenReturn(5_000L)

        repeat(3) { quiet.connect("Connection needed") }
        whenever(atc3BLE.backoffRemainingMs).thenReturn(0L)
        quiet.connect("Connection needed")
        whenever(atc3BLE.backoffRemainingMs).thenReturn(5_000L)
        repeat(3) { quiet.connect("Connection needed") }

        verify(logger, times(2)).debug(eq(LTag.PUMP), eq("ATC3: waiting before trying the link again"))
        verify(logger, times(1)).debug(eq(LTag.PUMP), eq("ATC3: connect, reason Connection needed"))
    }

    @Test
    fun `a missing serial is an error said once, not at every ask`() {
        val logger = mock<AAPSLogger>()
        val quiet = Atc3Connection(logger, rxBus, preferences, atc3BLE, trace, uiInteraction, rh, mock())
        whenever(preferences.get(Atc3StringKey.Atc3SerialNumber)).thenReturn("")

        repeat(3) { assertThat(quiet.connect("Connection needed")).isFalse() }

        verify(logger, times(1)).error(eq(LTag.PUMP), any<String>())
    }

    /** Entering a different password is a different question, and it gets its own ten tries. */
    @Test
    fun `changing the password starts the count over`() {
        whenever(atc3BLE.backoffRemainingMs).thenReturn(0L)
        whenever(atc3BLE.connect(any())).thenReturn(true)
        whenever(preferences.get(Atc3StringKey.Atc3BtPassword)).thenReturn("487613")
        repeat(Atc3Const.AUTH_MAX_ATTEMPTS) {
            connection.connect("Connection needed")
            connection.onAuthenticationRejected()
        }
        assertThat(connection.connect("Connection needed")).isFalse()

        whenever(preferences.get(Atc3StringKey.Atc3BtPassword)).thenReturn("730473")

        assertThat(connection.connect("Connection needed")).isTrue()
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

        connection.connect("Connection needed")
        connection.onAuthenticationRejected()

        verify(preferences).put(Atc3StringKey.Atc3BtPassword, "188992")
        verify(preferences).put(Atc3StringKey.Atc3BtPasswordAlternate, "")
        verify(uiInteraction, never()).addNotification(any(), any(), any())
    }

    @Test
    fun `once the rest is over the link is asked for again`() {
        whenever(atc3BLE.backoffRemainingMs).thenReturn(0L)
        whenever(atc3BLE.connect(any())).thenReturn(true)

        assertThat(connection.connect("Connection needed")).isTrue()
        verify(atc3BLE, times(1)).connect(any())
    }

    @Test
    fun `a pump with no serial is never connected to, whatever the stack says`() {
        // Every request carries the serial, so a connection made without one could not be used.
        whenever(preferences.get(Atc3StringKey.Atc3SerialNumber)).thenReturn("")

        assertThat(connection.connect("Connection needed")).isFalse()
        verify(atc3BLE, never()).connect(any())
    }
}
