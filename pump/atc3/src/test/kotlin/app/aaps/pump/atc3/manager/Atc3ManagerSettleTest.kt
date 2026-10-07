package app.aaps.pump.atc3.manager

import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.clock.Atc3ClockWatch
import app.aaps.pump.atc3.command.Atc3BolusDelivery
import app.aaps.pump.atc3.exchange.Atc3Exchange
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
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The wait that keeps the driver from talking before the pump is listening.
 *
 * The pump ignores a request sent in the first second and a half of a connection, and it ignores it
 * invisibly: the ATT write is acknowledged by the peer exactly as usual and the answer simply never
 * comes. See `Atc3Const.FIRST_REQUEST_SETTLE_MS`.
 *
 * **What is testable here and what is not.** The sleep is not: this project builds its unit tests
 * against a stubbed `android.jar`, so `SystemClock.sleep` returns immediately and a test that timed
 * the exchange would pass whether the wait existed or not. What can be checked is the arithmetic
 * that decides how long to wait, and that every exchange consults the link's age at all — which is
 * the failure that would bring the bug back silently, since a driver that never asks would look
 * exactly like one that waits and is ignored anyway.
 */
class Atc3ManagerSettleTest : TestBaseWithProfile() {

    @Mock lateinit var atc3BLE: Atc3BLE
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var manager: Atc3Manager
    private lateinit var exchange: Atc3Exchange
    private lateinit var bolusDelivery: Atc3BolusDelivery
    private lateinit var connection: Atc3Connection

    /** A fixed instant; the base class already has a `now`, and this one must not be it. */
    private val stamp = 1_700_000_000_000L

    @BeforeEach
    fun setup() {
        // No serial, so the request cannot even be built and the exchange fails at the send. The
        // settle gate runs before that, which is what makes this the cheap way to reach it.
        whenever(preferences.get(Atc3StringKey.Atc3SerialNumber)).thenReturn("")
        // A password is entered: without one the driver asks the pump nothing, which is not what these are about.
        whenever(preferences.get(Atc3StringKey.Atc3BtPassword)).thenReturn("487613")
        whenever(atc3BLE.write(any())).thenReturn(false)
        val trace = Atc3Trace(aapsLogger, preferences)
        connection = Atc3Connection(aapsLogger, rxBus, preferences, atc3BLE, trace, uiInteraction, rh, mock())
        exchange = Atc3Exchange(aapsLogger, preferences, atc3BLE, trace, connection)
        bolusDelivery = Atc3BolusDelivery(aapsLogger, dateUtil, exchange)
        manager = Atc3Manager(aapsLogger, rxBus, preferences, dateUtil, atc3BLE, Atc3PumpState(), trace, Atc3ClockWatch(), connection, exchange, bolusDelivery)
    }

    @Test
    fun `a link that has just come up owes the whole window`() {
        assertThat(Atc3Exchange.settleDelayMs(linkUpAtMs = stamp, now = stamp))
            .isEqualTo(Atc3Const.FIRST_REQUEST_SETTLE_MS)
    }

    @Test
    fun `a link owes only what is left of the window`() {
        val alreadyWaited = 1_200L

        assertThat(Atc3Exchange.settleDelayMs(linkUpAtMs = stamp - alreadyWaited, now = stamp))
            .isEqualTo(Atc3Const.FIRST_REQUEST_SETTLE_MS - alreadyWaited)
    }

    @Test
    fun `a connection whose setup outlasted the window owes nothing`() {
        // Most connections are this one: authorising and subscribing already took longer than the
        // window, so the wait costs nothing at all.
        val old = stamp - Atc3Const.FIRST_REQUEST_SETTLE_MS - 1

        assertThat(Atc3Exchange.settleDelayMs(linkUpAtMs = old, now = stamp)).isEqualTo(0L)
    }

    @Test
    fun `the moment the window closes nothing more is owed`() {
        val exactly = stamp - Atc3Const.FIRST_REQUEST_SETTLE_MS

        assertThat(Atc3Exchange.settleDelayMs(linkUpAtMs = exactly, now = stamp)).isEqualTo(0L)
    }

    @Test
    fun `no link means no wait rather than a wait of the whole window`() {
        // Zero is "there is no link", not "the link came up at the epoch". Reading it as a time
        // would make every exchange without a connection sit out the window before failing.
        assertThat(Atc3Exchange.settleDelayMs(linkUpAtMs = 0L, now = stamp)).isEqualTo(0L)
    }

    @Test
    fun `a clock corrected forward does not park the driver for a minute`() {
        // A phone clock moved while the link is up puts its timestamp in the future. Unclamped this
        // would wait however far the clock jumped, in the middle of an exchange.
        val fromTheFuture = stamp + 60_000L

        assertThat(Atc3Exchange.settleDelayMs(linkUpAtMs = fromTheFuture, now = stamp))
            .isEqualTo(Atc3Const.FIRST_REQUEST_SETTLE_MS)
    }

    @Test
    fun `every exchange asks how old the link is`() {
        // The one thing a mock can prove about the gate: that it is consulted. A driver that
        // stopped asking would go back to firing into the window, and the symptom would be the
        // thirty percent of first requests going unanswered all over again.
        manager.readStatus()

        verify(atc3BLE, atLeastOnce()).linkUpAtMs
    }
}
