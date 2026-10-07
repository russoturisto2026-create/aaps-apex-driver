package app.aaps.pump.atc3.manager

import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.atc3.clock.Atc3ClockWatch
import app.aaps.pump.atc3.command.Atc3BolusDelivery
import app.aaps.pump.atc3.command.Atc3BolusOutcome
import app.aaps.pump.atc3.exchange.Atc3Exchange
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.link.Atc3BLE
import app.aaps.pump.atc3.link.Atc3BtPassword
import app.aaps.pump.atc3.link.Atc3Connection
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3ResponseFrame
import app.aaps.pump.atc3.protocol.CrcUtil
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * That the stop button ends the watch over a bolus only once the pump has taken the cancel.
 *
 * The watch is what keeps the queue off the pump while a bolus is being delivered, and what
 * counts the progress frames as ours. A watch that ended on the wish to stop alone would leave a
 * bolus the pump did not stop running unwatched.
 */
class Atc3ManagerBolusStopTest : TestBaseWithProfile() {

    @Mock lateinit var atc3BLE: Atc3BLE
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var manager: Atc3Manager
    private lateinit var exchange: Atc3Exchange
    private lateinit var bolusDelivery: Atc3BolusDelivery
    private lateinit var connection: Atc3Connection

    /** Whether the pump takes the cancel; the bolus itself is always accepted. */
    private val cancelAccepted = AtomicBoolean(true)

    @BeforeEach
    fun setup() {
        whenever(preferences.get(Atc3StringKey.Atc3SerialNumber)).thenReturn("12345678")
        whenever(preferences.get(Atc3StringKey.Atc3BtPassword)).thenReturn("487613")
        whenever(atc3BLE.isConnected).thenReturn(true)
        whenever(atc3BLE.write(any())).thenAnswer { invocation ->
            val frame = invocation.arguments[0] as ByteArray
            // The cancel travels with the query group; everything else here is the bolus command.
            if (frame[0] == Atc3Protocol.GROUP_QUERY && !cancelAccepted.get()) return@thenAnswer false
            exchange.onDataReceived(ack())
            true
        }
        val trace = Atc3Trace(aapsLogger, preferences)
        connection = Atc3Connection(aapsLogger, rxBus, preferences, atc3BLE, trace, uiInteraction, rh, mock())
        exchange = Atc3Exchange(aapsLogger, preferences, atc3BLE, trace, connection)
        bolusDelivery = Atc3BolusDelivery(aapsLogger, dateUtil, exchange)
        manager = Atc3Manager(aapsLogger, rxBus, preferences, dateUtil, atc3BLE, Atc3PumpState(), trace, Atc3ClockWatch(), connection, exchange, bolusDelivery)
    }

    /** Start a bolus on a thread of its own, as the command queue does, and wait until the pump took it. */
    private fun bolusUnderWay(): Pair<Thread, AtomicReference<Atc3BolusOutcome?>> {
        val outcome = AtomicReference<Atc3BolusOutcome?>(null)
        val accepted = AtomicBoolean(false)
        val thread = Thread {
            runBlocking {
                outcome.set(bolusDelivery.bolus(1.0, onAccepted = { accepted.set(true) }, onProgress = {}))
            }
        }
        thread.start()
        val deadline = System.currentTimeMillis() + 2_000
        while (!accepted.get() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertThat(accepted.get()).isTrue()
        exchange.onDataReceived(progress(24))
        return thread to outcome
    }

    @Test
    fun `a cancel the pump accepted ends the watch, and the bolus is answered as stopped`() {
        val (thread, outcome) = bolusUnderWay()

        assertThat(bolusDelivery.stopBolus()).isTrue()
        thread.join(2_000)

        assertThat(thread.isAlive).isFalse()
        val delivered = outcome.get() as Atc3BolusOutcome.Delivered
        assertThat(delivered.cancelled).isTrue()
        assertThat(delivered.completed).isFalse()
        assertThat(manager.isBusy).isFalse()
    }

    @Test
    fun `a cancel the pump did not take leaves the bolus watched to its end`() {
        cancelAccepted.set(false)
        val (thread, outcome) = bolusUnderWay()

        assertThat(bolusDelivery.stopBolus()).isFalse()
        Thread.sleep(300)

        // Still watched: the pump is still delivering, and the queue is still held off it.
        assertThat(thread.isAlive).isTrue()
        assertThat(manager.isBusy).isTrue()

        // The pump finishes the bolus as if nothing had been asked.
        exchange.onDataReceived(completed(40))
        thread.join(2_000)

        assertThat(thread.isAlive).isFalse()
        val delivered = outcome.get() as Atc3BolusOutcome.Delivered
        assertThat(delivered.completed).isTrue()
        assertThat(delivered.reportedUnits).isWithin(1e-9).of(1.0)
        // The wish to stop is still what it was, for the judgement of a bolus cut short.
        assertThat(delivered.cancelled).isTrue()
    }

    private fun ack(): ByteArray = control(Atc3Protocol.ObjectType.ACK, 0x00, 0x00)

    private fun progress(raw: Int): ByteArray = control(Atc3Protocol.ObjectType.BOLUS_PROGRESS, (raw and 0xFF).toByte(), (raw shr 8).toByte())

    private fun completed(raw: Int): ByteArray = control(Atc3Protocol.ObjectType.BOLUS_COMPLETED, (raw and 0xFF).toByte(), (raw shr 8).toByte())

    private fun control(objectType: Byte, lo: Byte, hi: Byte): ByteArray {
        val body = byteArrayOf(Atc3ResponseFrame.MARKER, 0x0A, 0x00, Atc3Protocol.MODE_CONTROL, objectType, 0x00, lo, hi)
        return body + CrcUtil.crc16ModbusLeBytes(body)
    }
}
