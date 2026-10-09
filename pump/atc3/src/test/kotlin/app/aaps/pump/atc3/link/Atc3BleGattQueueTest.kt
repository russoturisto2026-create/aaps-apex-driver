package app.aaps.pump.atc3.link

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever
import java.util.concurrent.Executors

/**
 * That the driver only ever asks the Bluetooth stack for one thing at a time.
 *
 * A GATT connection carries a single operation. One issued while another is outstanding is dropped
 * by the stack **with no callback at all**, so whoever asked waits for an answer that was never
 * coming and the connection simply stops progressing. The subscription that produces `ready`, for
 * one, follows the pump's acceptance of the password while the stack can still be reporting the
 * write that carried it.
 *
 * The operations themselves need a real link, but the ordering does not. Each operation here is a
 * plain function returning what the stack said about starting it, so every rule below is checked
 * without Android in the way.
 */
class Atc3BleGattQueueTest : TestBase() {

    @Mock lateinit var preferences: Preferences

    private lateinit var ops: Atc3GattOps

    /** The steps whose failure ended the link, by name. */
    private val failed = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        // The trace is exercised on its own elsewhere; here it must not get in the way of the
        // ordering being checked.
        whenever(preferences.get(Atc3BooleanKey.Trace)).thenReturn(false)
        ops = Atc3GattOps(aapsLogger, Atc3Trace(aapsLogger, preferences), Executors.newSingleThreadScheduledExecutor()) { kind, _ -> failed += kind }
    }

    @Test
    fun `an operation is issued as soon as it is asked for`() {
        var issued = false

        ops.enqueue(Atc3BLE.OP_MTU, TIMEOUT_MS) { issued = true; true }

        assertThat(issued).isTrue()
        assertThat(ops.inFlightKind()).isEqualTo(Atc3BLE.OP_MTU)
        assertThat(ops.queueDepth()).isEqualTo(0)
    }

    @Test
    fun `a second operation waits rather than going out on top of the first`() {
        var secondIssued = false
        ops.enqueue(Atc3BLE.OP_MTU, TIMEOUT_MS) { true }

        ops.enqueue(Atc3BLE.OP_DISCOVER, TIMEOUT_MS) { secondIssued = true; true }

        assertThat(secondIssued).isFalse()
        assertThat(ops.inFlightKind()).isEqualTo(Atc3BLE.OP_MTU)
        assertThat(ops.queueDepth()).isEqualTo(1)
    }

    @Test
    fun `the one waiting goes out the moment the first is answered`() {
        var secondIssued = false
        ops.enqueue(Atc3BLE.OP_MTU, TIMEOUT_MS) { true }
        ops.enqueue(Atc3BLE.OP_DISCOVER, TIMEOUT_MS) { secondIssued = true; true }

        assertThat(ops.complete(Atc3BLE.OP_MTU, 0)).isTrue()

        assertThat(secondIssued).isTrue()
        assertThat(ops.inFlightKind()).isEqualTo(Atc3BLE.OP_DISCOVER)
        assertThat(ops.queueDepth()).isEqualTo(0)
    }

    /**
     * The pump answers the password before the stack has finished reporting the write that
     * asked. The subscription must not go out on that
     * answer, because the write is still outstanding.
     */
    @Test
    fun `the answer to one operation does not release the next while the first is outstanding`() {
        var subscribeIssued = false
        ops.enqueue(Atc3BLE.OP_WRITE_AUTH, TIMEOUT_MS) { true }
        ops.enqueue(Atc3BLE.OP_SUBSCRIBE_DATA, TIMEOUT_MS) { subscribeIssued = true; true }

        // The pump's verdict arrives, but it is not this operation's completion.
        assertThat(ops.complete(Atc3BLE.OP_SUBSCRIBE_DATA, 0)).isFalse()
        assertThat(subscribeIssued).isFalse()

        // Only the write being reported frees the stack.
        assertThat(ops.complete(Atc3BLE.OP_WRITE_AUTH, 0)).isTrue()
        assertThat(subscribeIssued).isTrue()
    }

    @Test
    fun `a callback nobody was waiting for is refused rather than acted on`() {
        assertThat(ops.complete(Atc3BLE.OP_SUBSCRIBE_DATA, 0)).isFalse()
        assertThat(ops.inFlightKind()).isNull()
    }

    @Test
    fun `a duplicate callback for a finished operation is refused`() {
        ops.enqueue(Atc3BLE.OP_MTU, TIMEOUT_MS) { true }
        assertThat(ops.complete(Atc3BLE.OP_MTU, 0)).isTrue()

        // One MTU request can be reported twice. The second report completes nothing.
        assertThat(ops.complete(Atc3BLE.OP_MTU, 0)).isFalse()
    }

    /**
     * An operation the stack would not start: no callback is
     * coming, so waiting for one costs the whole connection watchdog and says nothing about which
     * step failed. It ends the link instead, and takes the queue with it.
     */
    @Test
    fun `an operation the stack refuses ends the connection instead of waiting for nothing`() {
        var laterIssued = false
        ops.enqueue(Atc3BLE.OP_SUBSCRIBE_DATA, TIMEOUT_MS) { false }
        ops.enqueue(Atc3BLE.OP_DISCOVER, TIMEOUT_MS) { laterIssued = true; true }

        assertThat(ops.inFlightKind()).isNull()
        assertThat(ops.queueDepth()).isEqualTo(0)
        assertThat(laterIssued).isFalse()
        assertThat(failed).containsExactly(Atc3BLE.OP_SUBSCRIBE_DATA)
    }

    @Test
    fun `operations queued for a link that ended do not survive into the next one`() {
        ops.enqueue(Atc3BLE.OP_MTU, TIMEOUT_MS) { true }
        ops.enqueue(Atc3BLE.OP_DISCOVER, TIMEOUT_MS) { true }

        ops.close()

        assertThat(ops.inFlightKind()).isNull()
        assertThat(ops.queueDepth()).isEqualTo(0)
    }

    /**
     * The queue takes no work after the link carrying it has gone: it would be issued on a GATT
     * client that is being closed, and the callback for an operation on a dead link never arrives,
     * so nothing would ever report it.
     */
    @Test
    fun `no operation is taken once the link has ended`() {
        var issued = false
        ops.close()

        ops.enqueue(Atc3BLE.OP_DISCOVER, TIMEOUT_MS) { issued = true; true }

        assertThat(issued).isFalse()
        assertThat(ops.inFlightKind()).isNull()
        assertThat(ops.queueDepth()).isEqualTo(0)
    }

    @Test
    fun `each operation is issued exactly once however many queue behind it`() {
        var mtuIssues = 0
        ops.enqueue(Atc3BLE.OP_MTU, TIMEOUT_MS) { mtuIssues++; true }
        ops.enqueue(Atc3BLE.OP_DISCOVER, TIMEOUT_MS) { true }
        ops.enqueue(Atc3BLE.OP_SUBSCRIBE_AUTH, TIMEOUT_MS) { true }

        ops.complete(Atc3BLE.OP_MTU, 0)
        ops.complete(Atc3BLE.OP_DISCOVER, 0)

        assertThat(mtuIssues).isEqualTo(1)
        assertThat(ops.inFlightKind()).isEqualTo(Atc3BLE.OP_SUBSCRIBE_AUTH)
    }

    private companion object {

        const val TIMEOUT_MS = 5_000L
    }
}
