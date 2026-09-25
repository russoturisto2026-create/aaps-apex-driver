package app.aaps.pump.atc3.manager

import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.atc3.Atc3Pump
import app.aaps.pump.atc3.ble.Atc3BLE
import app.aaps.pump.atc3.history.Atc3ClockWatch
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import app.aaps.pump.atc3.trace.Atc3Trace
import kotlinx.coroutines.test.runTest

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

    @BeforeEach
    fun setup() {
        // No serial configured, so every request fails to build and no exchange can complete. That
        // is exactly the failing path this test is about.
        whenever(preferences.get(Atc3StringKey.Atc3SerialNumber)).thenReturn("")
        whenever(atc3BLE.write(any())).thenReturn(false)
        manager = Atc3Manager(aapsLogger, rxBus, preferences, dateUtil, atc3BLE, Atc3Pump(), Atc3Trace(aapsLogger, preferences), uiInteraction, Atc3ClockWatch(), rh)
    }

    @Test
    fun `an idle driver is not busy`() = runTest {
        assertThat(manager.isBusy).isFalse()
    }

    @Test
    fun `a read that could not even be sent leaves nothing held`() = runTest {
        assertThat(manager.readStatus()).isFalse()
        assertThat(manager.isBusy).isFalse()
    }

    @Test
    fun `a bolus that could not be sent leaves nothing held`() = runTest {
        val outcome = manager.bolus(units = 1.0, onAccepted = {}, onProgress = {})

        assertThat(outcome).isInstanceOf(Atc3BolusOutcome.NotSent::class.java)
        assertThat(manager.isBusy).isFalse()
    }

    @Test
    fun `a bolus that could not be sent never claims it was accepted`() = runTest {
        var accepted = false
        manager.bolus(units = 1.0, onAccepted = { accepted = true }, onProgress = {})

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
        val reader = Thread { answered.set(manager.readStatus()) }
        reader.start()
        // Let the read get as far as waiting, then drop the link under it.
        Thread.sleep(200)
        manager.onDisconnected()
        reader.join(5_000)

        assertThat(reader.isAlive).isFalse()
        assertThat(answered.get()).isFalse()
        assertThat(manager.isBusy).isFalse()
    }

    @Test
    fun `a failed temporary basal read leaves nothing held`() = runTest {
        assertThat(manager.readActiveTbr()).isFalse()
        assertThat(manager.isBusy).isFalse()
    }
}
