package app.aaps.pump.atc3.manager

import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.atc3.clock.Atc3ClockWatch
import app.aaps.pump.atc3.command.Atc3BolusDelivery
import app.aaps.pump.atc3.command.Atc3BolusOutcome
import app.aaps.pump.atc3.exchange.Atc3Exchange
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.link.Atc3BLE
import app.aaps.pump.atc3.link.Atc3Connection
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * That the driver never gets stuck saying "busy".
 *
 * This is the one flag whose failure mode has no safety net: the command queue's watchdog only
 * steps in when the pump is disconnected, so a busy flag left set while connected spins the queue
 * for ever and the pump stops taking commands altogether. Every path out of an exchange and out of
 * a bolus therefore has to clear it, including the ones that fail.
 */
class Atc3ManagerBusyTest : TestBaseWithProfile() {

    @Mock lateinit var atc3BLE: Atc3BLE
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var manager: Atc3Manager
    private lateinit var exchange: Atc3Exchange
    private lateinit var bolusDelivery: Atc3BolusDelivery
    private lateinit var connection: Atc3Connection

    @BeforeEach
    fun setup() {
        // No serial configured, so every request fails to build and no exchange can complete. That
        // is exactly the failing path this test is about.
        whenever(preferences.get(Atc3StringKey.Atc3SerialNumber)).thenReturn("")
        whenever(atc3BLE.write(any())).thenReturn(false)
        val trace = Atc3Trace(aapsLogger, preferences)
        connection = Atc3Connection(aapsLogger, rxBus, preferences, atc3BLE, trace, uiInteraction, rh, mock())
        exchange = Atc3Exchange(aapsLogger, preferences, atc3BLE, trace, connection)
        bolusDelivery = Atc3BolusDelivery(aapsLogger, dateUtil, exchange)
        manager = Atc3Manager(aapsLogger, rxBus, preferences, dateUtil, atc3BLE, Atc3PumpState(), trace, Atc3ClockWatch(), connection, exchange, bolusDelivery)
    }

    @Test
    fun `an idle driver is not busy`() = runTest {
        assertThat(manager.isBusy).isFalse()
    }

    @Test
    fun `a read that could not even be sent leaves nothing held`() = runTest {
        assertThat(manager.readStatus()).isNull()
        assertThat(manager.isBusy).isFalse()
    }

    @Test
    fun `a bolus that could not be sent leaves nothing held`() = runTest {
        val outcome = bolusDelivery.bolus(units = 1.0, onAccepted = {}, onProgress = {})

        assertThat(outcome).isInstanceOf(Atc3BolusOutcome.NotSent::class.java)
        assertThat(manager.isBusy).isFalse()
    }

    @Test
    fun `a bolus that could not be sent never claims it was accepted`() = runTest {
        var accepted = false
        bolusDelivery.bolus(units = 1.0, onAccepted = { accepted = true }, onProgress = {})

        // Counting a bolus AAPS never managed to send would park insulin in IOB that never left.
        assertThat(accepted).isFalse()
    }

    @Test
    fun `a failed history read leaves nothing held`() = runTest {
        assertThat(manager.readBolusHistory()).isNull()
        assertThat(manager.isBusy).isFalse()
    }

    @Test
    fun `a dropped link ends the wait at once instead of leaving it to time out`() = runTest {
        // Twenty seconds of waiting for an answer that can no longer come is twenty seconds the
        // command queue spends doing nothing, and it ends in the same failure either way.
        whenever(preferences.get(Atc3StringKey.Atc3SerialNumber)).thenReturn("12345678")
        whenever(atc3BLE.write(any())).thenReturn(true)

        val answered = java.util.concurrent.atomic.AtomicBoolean(true)
        val reader = Thread { answered.set(manager.readStatus() != null) }
        reader.start()
        // Let the read get as far as waiting, then drop the link under it.
        Thread.sleep(200)
        connection.onDisconnected()
        reader.join(5_000)

        assertThat(reader.isAlive).isFalse()
        assertThat(answered.get()).isFalse()
        assertThat(manager.isBusy).isFalse()
    }

    @Test
    fun `a failed temporary basal read leaves nothing held`() = runTest {
        assertThat(manager.readActiveTbr()).isNull()
        assertThat(manager.isBusy).isFalse()
    }
}
