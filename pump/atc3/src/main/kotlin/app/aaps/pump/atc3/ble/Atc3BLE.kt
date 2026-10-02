package app.aaps.pump.atc3.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.atc3.comm.Atc3BtPassword
import app.aaps.pump.atc3.comm.Atc3LinkProtection
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raw GATT transport for the ATC3 pump.
 *
 * This class knows nothing about the ATC3 frame format. It connects, finds the pump's services,
 * works out which characteristic is used for writing and which delivers notifications, and passes
 * bytes through in both directions.
 *
 * Bringing a link up is a chain of four steps rather than two, because the pump can require a
 * Bluetooth password and refuses the subscription to its notify characteristic until it has been
 * given one: subscribe to the authorisation characteristic, write the password, wait for the pump
 * to accept it, and only then subscribe to the ATC3 characteristic and report the link as ready.
 * Each step is driven by the callback of the one before it, so nothing waits on the stack's own
 * thread, and the connect watchdog covers the whole chain rather than only its first step.
 *
 * With no password configured the first three steps are skipped and the link comes up without
 * authorising, which is what a pump that asks for no password wants — and a pump that lets that
 * happen has told the driver, unambiguously, that it is asking for no password, which is how
 * [linkProtection] is arrived at without ever guessing a value.
 *
 * See [GattAttributes].
 */
@SuppressLint("MissingPermission")
@Singleton
class Atc3BLE @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val context: Context,
    private val trace: Atc3Trace
) {

    private var callback: Atc3BleCallback? = null
    private var gatt: BluetoothGatt? = null

    /** When the link was asked for and when it became usable, for the trace's own arithmetic. */
    @Volatile private var connectingSince = 0L

    @Volatile private var readySince = 0L

    /**
     * When anything last arrived from the pump.
     *
     * The pump sends a heartbeat every 180 s whether or not anything else is happening, so a link
     * held open between exchanges is not an unwatched one. This is what makes holding it safe
     * rather than hopeful: without a signal from the far side, a driver reporting itself connected
     * on a link that had quietly died would let AAPS believe its commands were reaching a pump that
     * is not listening, and a temporary basal that was never delivered is worse than one that
     * visibly failed.
     *
     * Every frame counts, not only the heartbeat. An answer to a read proves the pump is there just
     * as well as an unprompted beat does, and while anything is being asked, answers are all there
     * is.
     */
    @Volatile private var lastHeardFromAt = 0L

    /**
     * When the GATT link came up, milliseconds, zero while there is none.
     *
     * Not for the trace: this is what the settle wait before the first request of a connection is
     * measured from. See Atc3Const.FIRST_REQUEST_SETTLE_MS, which explains why there is one.
     */
    @Volatile
    var linkUpAtMs = 0L
        private set

    /**
     * Whether service discovery has already been started for this connection.
     *
     * A second MTU callback for one request starts the whole setup a second time: two discoveries,
     * two subscriptions to the authorisation characteristic, two password writes. A GATT connection
     * carries one operation at a time, and one issued while another is outstanding is dropped
     * without a callback, so the subscription to the data characteristic — the last step, the one
     * that produces `ready` — would never complete and the connection would sit out its whole
     * watchdog.
     *
     * **The duplicate comes from another client connected to the same pump.** Notifications on a
     * shared link reach every app subscribed to them. On a link that is ours alone this claim never
     * fires.
     *
     * The general form of the rule lives in the operation queue, which lets nothing be issued while
     * anything is outstanding whatever produced it. This remains as the specific guard for this
     * one repeat.
     */
    @Volatile private var discoveryStarted = false

    /** Whether the characteristics of this connection have already been resolved and acted on. */
    @Volatile private var servicesResolved = false

    /** Whether the setup of this connection has already been begun by a connected callback. */
    @Volatile private var linkClaimed = false

    /**
     * Take the right to start service discovery, once per connection.
     *
     * @return true for the caller that may discover, false for every repeat of the same callback
     */
    @Synchronized
    internal fun claimDiscovery(): Boolean {
        if (discoveryStarted) return false
        discoveryStarted = true
        return true
    }

    /**
     * Take the right to begin the setup of a connection, once per connection.
     *
     * The stack can report one link as connected more than once. Setup begun again from the
     * second report asks for the MTU while service discovery from the first is still in flight,
     * and a GATT connection carries one operation at a time: the answers no longer belong to what
     * is waiting for them, discovery is never answered, and the connection spends its whole
     * watchdog before anything is retried.
     *
     * @return true for the caller that may begin, false for every repeat
     */
    @Synchronized
    internal fun claimLinkUp(): Boolean {
        if (linkClaimed) return false
        linkClaimed = true
        return true
    }

    /** Take the right to act on discovered characteristics, once per connection. */
    @Synchronized
    internal fun claimServices(): Boolean {
        if (servicesResolved) return false
        servicesResolved = true
        return true
    }

    /**
     * True from the moment a connection is asked for until its end has been reported.
     *
     * The stack reports the end of a link it dropped itself, but says nothing when the link is
     * closed from this side, so without this the driver would hear about only some of the endings.
     * Whoever gets to the ending first flips it; the other finds it already flipped.
     */
    private val live = AtomicBoolean(false)

    /** Fires when a connection attempt has taken far longer than one ever legitimately does. */
    @Volatile private var watchdog: ScheduledFuture<*>? = null

    /** Consecutive attempts that never produced a usable link, which is what sets the backoff. */
    @Volatile private var failures = 0

    @Volatile private var blockedUntil = 0L

    private val watchdogExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Atc3ConnectWatchdog").apply { isDaemon = true }
    }

    /**
     * How long connecting is refused for, milliseconds, zero when it is allowed.
     *
     * After the stack has failed to connect, connecting again straight away fails again: after a
     * link lost with `status=8`, attempts can each take the stack's full thirty seconds to refuse
     * with `status=255`, one after another. Waiting costs nothing when the pump is there, because
     * then the first attempt succeeds; it stops the driver holding the radio and the wake lock busy
     * when it is not.
     */
    val backoffRemainingMs: Long get() = (blockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var authWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var authNotifyCharacteristic: BluetoothGattCharacteristic? = null

    /**
     * The Bluetooth password to present, or null to bring the link up without authorising.
     *
     * Set before every connection by whoever knows the configuration; the transport itself holds no
     * settings. Null means present nothing at all, and the link comes up without authorising.
     */
    @Volatile private var password: String? = null

    /**
     * How well the current link is protected, as the pump itself answered while it came up.
     *
     * Read off what the pump did rather than off what is configured, because only the pump knows
     * whether it is asking for a password. It says so in two ways, both of them unambiguous: a pump
     * with no authorisation service cannot be asking for one at all, and a pump that lets an
     * unauthorised client subscribe to its notify characteristic is not asking for one either — that
     * write is exactly what it refuses when it is.
     */
    @Volatile
    var linkProtection: Atc3LinkProtection = Atc3LinkProtection.UNKNOWN
        private set

    /** Lets exactly one write be outstanding at a time. */
    private val writeGate = Semaphore(1)

    @Volatile private var writeDone: CountDownLatch? = null

    @Volatile private var writeSucceeded: Boolean = false

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var isConnecting: Boolean = false
        private set

    fun setCallback(callback: Atc3BleCallback?) {
        this.callback = callback
    }

    /**
     * The phone's Bluetooth going off ends the link, and says so at once.
     *
     * The stack reports nothing for a link lost that way: no disconnected callback, only writes
     * refused one by one afterwards, which may not reach the three in a row the manager waits
     * for. The driver would then believe for minutes that it is connected to a pump it cannot
     * reach. The adapter's own broadcast is the one signal there is, so it is listened to.
     */
    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state != BluetoothAdapter.STATE_TURNING_OFF && state != BluetoothAdapter.STATE_OFF) return
            if (!live.get()) return
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the phone's Bluetooth is going off, the link is gone")
            trace.event(Atc3TraceCat.BLE, "adapter_off", "state" to state)
            endConnection("bluetooth off")
        }
    }

    @Volatile private var adapterStateWatched = false

    /** Listen for the adapter going off, once, from the first connection on. */
    @Synchronized
    private fun watchAdapterState() {
        if (adapterStateWatched) return
        runCatching {
            context.registerReceiver(adapterStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            adapterStateWatched = true
        }.onFailure { aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: could not listen for the Bluetooth adapter's state", it) }
    }

    /**
     * Say which Bluetooth password to present while bringing the next link up.
     *
     * A blank or malformed value is taken as "no password", which brings the link up without
     * authorising.
     * That is the honest thing to do with nothing configured: presenting a made up password would
     * turn a pump that asks for none into one that refuses us.
     */
    fun setPassword(password: String?) {
        this.password = password?.trim()?.takeIf { Atc3BtPassword.isValid(it) }
    }

    /**
     * Connect to a pump by Bluetooth address.
     *
     * @return false when the address is unusable or Bluetooth is unavailable
     */
    fun connect(address: String): Boolean {
        if (address.isBlank()) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: no pump address configured")
            return false
        }
        if (isConnected || isConnecting) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: connect ignored, already connected or connecting")
            trace.event(Atc3TraceCat.BLE, "connect_skipped", "connected" to isConnected)
            return true
        }
        val adapter = bluetoothAdapter() ?: run {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: no Bluetooth adapter")
            return false
        }
        watchAdapterState()
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: invalid pump address $address")
            return false
        }
        val waiting = backoffRemainingMs
        if (waiting > 0) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: not connecting yet, ${waiting}ms of backoff left")
            trace.event(Atc3TraceCat.BLE, "backoff", "ms" to waiting, "after" to failures)
            return false
        }
        return try {
            val device = adapter.getRemoteDevice(address)
            linkProtection = Atc3LinkProtection.UNKNOWN
            isConnecting = true
            live.set(true)
            opsClosed = false
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: connecting to $address")
            connectingSince = trace.now()
            trace.event(Atc3TraceCat.BLE, "connecting")
            armWatchdog()
            true
        } catch (e: SecurityException) {
            isConnecting = false
            live.set(false)
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: missing Bluetooth permission", e)
            false
        } catch (e: IllegalArgumentException) {
            isConnecting = false
            live.set(false)
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: cannot connect to $address", e)
            false
        }
    }

    fun disconnect() {
        aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: disconnect requested")
        trace.event(Atc3TraceCat.BLE, "disconnect_requested", "connected" to isConnected)
        endConnection("requested")
    }

    /**
     * Close the link, whoever asked for the ending, and report it exactly once.
     *
     * Closing the GATT client suppresses the stack's own disconnected callback, so a link closed
     * from this side would otherwise end in silence: nothing would tell AAPS the pump had gone,
     * nothing would release a caller still waiting for an answer that can no longer come, and
     * nothing would measure what the connection had cost.
     */
    private fun endConnection(reason: String, stackAnswered: Boolean = true) {
        watchdog?.cancel(false)
        watchdog = null
        val wasReady = readySince != 0L
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: missing Bluetooth permission on disconnect", e)
        }
        clearConnectionState()
        if (!live.compareAndSet(true, false)) return
        // Only an attempt that never produced a usable link counts towards the backoff. A link
        // that worked and was then closed says nothing about the state of the stack, however it
        // ended, and must not slow down the connection after it.
        val wait = when {
            wasReady       -> 0L
            // The stack answered, so the stack is working and the pump is simply not reachable.
            // The two are told apart because after an outage the stack goes from saying nothing at
            // all to refusing within seconds, and a backoff grown to its full minute would then be
            // the only thing still holding the link back.
            stackAnswered  -> BACKOFF_ANSWERED_MS
            // Silence means the stack itself is wedged. Nothing but time helps, so give it more
            // of it each round.
            else           -> (BACKOFF_BASE_MS shl failures.coerceAtMost(BACKOFF_MAX_SHIFT))
                .coerceAtMost(BACKOFF_CAP_MS)
        }
        failures = if (wasReady || stackAnswered) 0 else failures + 1
        blockedUntil = if (wait == 0L) 0L else System.currentTimeMillis() + wait
        if (wait > 0L) {
            trace.event(
                Atc3TraceCat.BLE, "backoff_set",
                "why" to reason, "answered" to stackAnswered, "after" to failures, "ms" to wait
            )
        }
        callback?.onDisconnected()
    }

    /**
     * The pump has spoken of its own accord, which is the strongest thing a held link can be told.
     *
     * Kept separate from ordinary traffic only so the trace can say which it was; both count the
     * same towards [quietForMs].
     */
    fun noteHeartbeat() {
        noteHeardFrom()
    }

    /** Anything arriving from the pump proves the link, whether it was asked for or not. */
    fun noteHeardFrom() {
        lastHeardFromAt = System.currentTimeMillis()
    }

    /**
     * How long the pump has been silent, milliseconds, or -1 when nothing has ever arrived.
     *
     * What to do about a long silence is not decided here. This layer knows when the pump last
     * spoke; the driver above it knows how to ask the pump something, which is what a silence
     * deserves before the link is given up on. See Atc3Manager's liveness check.
     */
    val quietForMs: Long get() = if (lastHeardFromAt == 0L) -1L else System.currentTimeMillis() - lastHeardFromAt

    /**
     * Give up on a connection attempt that is going nowhere.
     *
     * Without this the driver waits on the stack, and the stack takes thirty seconds to refuse an
     * attempt it cannot make — or, when `connectGatt` never calls back at all, says nothing ever.
     * AAPS's queue polls `isConnecting` once a second and holds a partial wake lock throughout, so
     * an attempt that hangs costs the phone two minutes of held processor before the queue's own
     * limit ends it. A healthy link is ready in about a tenth of a second, so this bound is more
     * than a hundred times what a connection needs.
     */
    private fun armWatchdog() {
        watchdog?.cancel(false)
        watchdog = watchdogExecutor.schedule({
            if (isConnected) return@schedule
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the link did not come up in ${CONNECT_TIMEOUT_MS}ms, giving up")
            trace.event(Atc3TraceCat.BLE, "connect_timeout", "ms" to CONNECT_TIMEOUT_MS)
            endConnection("connect timeout", stackAnswered = false)
        }, CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Write one payload to the pump and wait until the stack reports the write as done.
     *
     * Android carries out one GATT operation at a time: issuing a write while a previous one is
     * still outstanding is refused. Waiting for the completion callback here keeps writes strictly
     * one after another, which is also what the pump itself requires, since it works in request
     * and answer pairs.
     *
     * @return false when there is no usable connection, the write was refused, or it did not
     *         complete in time
     */
    fun write(data: ByteArray): Boolean {
        val currentGatt = gatt
        val characteristic = writeCharacteristic
        if (currentGatt == null || characteristic == null || !isConnected) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: write attempted without a ready connection")
            return false
        }
        // The adapter off is the link gone, whatever the stack has said about it so far; see
        // adapterStateReceiver. Asked here as well, for a broadcast that was missed.
        if (bluetoothAdapter()?.isEnabled == false) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the phone's Bluetooth is off, the link is gone")
            trace.event(Atc3TraceCat.BLE, "adapter_off", "state" to -1)
            endConnection("bluetooth off")
            return false
        }
        writeGate.acquire()
        try {
            val latch = CountDownLatch(1)
            writeDone = latch
            writeSucceeded = false

            val writeType =
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            // Why the stack refused, kept for the trace as well as the log. A refusal names its
            // reason on Android 13 and later — a write already in flight answers 201, a link that
            // has gone answers 4 — and without the number a refusal is undiagnosable.
            var refusedWith = REFUSAL_UNNUMBERED
            val issued = try {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val status = currentGatt.writeCharacteristic(characteristic, data, writeType)
                    if (status != BluetoothStatusCodes.SUCCESS) {
                        refusedWith = status
                        aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: write refused by the Bluetooth stack, status $status")
                        false
                    } else true
                } else {
                    characteristic.writeType = writeType
                    characteristic.value = data
                    val ok = currentGatt.writeCharacteristic(characteristic)
                    if (!ok) aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: write refused by the Bluetooth stack")
                    ok
                }
            } catch (e: SecurityException) {
                refusedWith = REFUSAL_PERMISSION
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: missing Bluetooth permission on write", e)
                false
            }
            val issuedAt = trace.now()

            if (!issued) {
                trace.event(
                    Atc3TraceCat.BLE, "write",
                    "bytes" to data.size, "ok" to false, "why" to "refused", "status" to refusedWith
                )
                return false
            }
            if (!latch.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: write did not complete in time")
                trace.event(Atc3TraceCat.BLE, "write", "bytes" to data.size, "ok" to false, "why" to "timeout")
                return false
            }
            trace.event(
                Atc3TraceCat.BLE, "write",
                "bytes" to data.size,
                "ok" to writeSucceeded,
                "ms" to trace.since(issuedAt)
            )
            if (writeSucceeded) trace.countOut(data.size)
            return writeSucceeded
        } finally {
            writeDone = null
            writeGate.release()
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    companion object {

        /**
         * MTU to ask the pump for, the largest ATT allows.
         *
         * The pump grants 251 of it, which makes most of its answers arrive in one notification
         * each. The number itself does not have to be right:
         * whatever the two ends agree on is used, and asking for the maximum simply avoids a second
         * negotiation.
         */
        private const val WANTED_MTU = 517

        /** A refusal from a stack too old to give a reason, so the trace says so rather than 0. */
        private const val REFUSAL_UNNUMBERED = -1

        /** A refusal that was not the stack's at all: the permission was missing. */
        private const val REFUSAL_PERMISSION = -2

        /** How long to wait for the stack to report a write as done, milliseconds. */
        private const val WRITE_TIMEOUT_MS = 5_000L

        /** How long a connection attempt may take before the driver gives up on it, milliseconds. */
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /**
         * How long any one GATT operation may go unanswered.
         *
         * Every step of the setup is answered in single or double digit milliseconds, so five
         * seconds is far past anything that is going to arrive late. Its purpose
         * is to name the step that failed rather than to be tight: without it a lost callback
         * shows up only as the whole connection timing out, which says nothing about where it
         * stopped.
         */
        private const val GATT_OP_TIMEOUT_MS = 5_000L

        /** Discovery talks to the pump repeatedly and is allowed to take longer. */
        private const val DISCOVER_TIMEOUT_MS = 10_000L

        internal const val OP_MTU = "mtu"
        internal const val OP_DISCOVER = "discover"
        internal const val OP_SUBSCRIBE_AUTH = "subscribe:auth"
        internal const val OP_SUBSCRIBE_DATA = "subscribe:data"
        internal const val OP_WRITE_AUTH = "write:auth"

        /** The wait after an attempt the stack never answered, milliseconds; it doubles from here. */
        private const val BACKOFF_BASE_MS = 5_000L

        /**
         * The wait after an attempt the stack refused outright, milliseconds.
         *
         * Flat rather than growing: the stack is working, so trying again is cheap and might well
         * succeed the moment the pump is back in range. It exists only to stop the queue turning
         * that into an attempt every second.
         */
        private const val BACKOFF_ANSWERED_MS = 15_000L

        /** As long as that wait is allowed to grow, milliseconds. */
        private const val BACKOFF_CAP_MS = 60_000L

        /** Keeps the doubling from overflowing after a very long outage. */
        private const val BACKOFF_MAX_SHIFT = 5

        /**
         * GATT status the pump returns when it wants a Bluetooth password and has not had one.
         *
         * `BluetoothGatt` has no constant for it; this is the ATT error code 0x03 the pump answers
         * the descriptor write with, which Android passes through unchanged.
         */
        private const val GATT_WRITE_NOT_PERMITTED = 3
    }


    // GATT operations, one at a time

    /**
     * One request to the Bluetooth stack, and what it is waiting to hear back.
     *
     * @param kind      the name it is traced and completed under, see [enqueueOp]
     * @param timeoutMs how long its callback may take before the connection is given up on
     * @param issue     performs the call, returning what the stack said about starting it
     */
    private class GattOp(val kind: String, val timeoutMs: Long, val issue: () -> Boolean)

    private val opLock = Any()
    private val opQueue = ArrayDeque<GattOp>()

    /**
     * Whether the queue has been shut because the link it was serving ended.
     *
     * Without this, a step of the setup could still be handed to the stack after the connection
     * carrying it had been torn down - and it would be issued, on a GATT client that is being
     * closed. Nothing good comes of that and nothing would report it, because the callback for an
     * operation on a dead link never arrives. A new connection opens the queue again.
     */
    @Volatile private var opsClosed = false
    private var opInFlight: GattOp? = null
    private var opIssuedAt = 0L
    private var opTimer: ScheduledFuture<*>? = null

    /**
     * Send one GATT operation at a time, and account for every one of them.
     *
     * A GATT connection carries a single operation. One issued while another is outstanding is
     * dropped by the stack **with no callback at all**, so the code that asked waits for an answer
     * that was never going to come, and the only symptom is a connection that stops progressing.
     * The subscription that produces `ready`, for one, follows the pump's acceptance of the
     * password while the stack can still be finishing the write that carried it.
     *
     * Ordering is therefore not left to whichever callback happens to arrive first. Every operation
     * goes through here, is issued only once the previous one has been reported, and is answered,
     * timed out or refused explicitly.
     *
     * Everything is written down, because a dropped operation leaves no other evidence:
     * - `op_queued` when it is asked for, with what is ahead of it
     * - `op_sent` with whether the stack accepted the request at all - a refusal here means no
     *   callback is coming
     * - `op_done` with the status and how long it took
     * - `op_timeout` when its callback never arrived
     * - `op_stray` for a callback nobody was waiting for, which is how a duplicate from the stack
     *   or an answer meant for another client shows itself
     */
    internal fun enqueueOp(kind: String, timeoutMs: Long = GATT_OP_TIMEOUT_MS, issue: () -> Boolean) {
        if (opsClosed) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: not asking for $kind, the link it belonged to has ended")
            trace.event(Atc3TraceCat.BLE, "op_rejected", "kind" to kind)
            return
        }
        synchronized(opLock) {
            opQueue.addLast(GattOp(kind, timeoutMs, issue))
            trace.event(
                Atc3TraceCat.BLE, "op_queued",
                "kind" to kind,
                "waiting" to opQueue.size,
                "inflight" to (opInFlight?.kind ?: "-")
            )
        }
        pumpOps()
    }

    /** Issue the next operation, if the stack is free to take one. */
    private fun pumpOps() {
        val op = synchronized(opLock) {
            if (opInFlight != null) return
            val next = opQueue.removeFirstOrNull() ?: return
            opInFlight = next
            opIssuedAt = trace.now()
            next
        }
        val accepted = try {
            op.issue()
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: missing Bluetooth permission issuing ${op.kind}", e)
            false
        }
        trace.event(Atc3TraceCat.BLE, "op_sent", "kind" to op.kind, "ok" to accepted)
        if (!accepted) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the stack would not start ${op.kind}")
            failOp(op, "refused")
            return
        }
        armOpTimer(op)
    }

    /**
     * Report that the operation named [kind] has been answered.
     *
     * @return true when it was the one outstanding, false for a callback nobody was waiting for
     */
    internal fun completeOp(kind: String, status: Int): Boolean {
        val op = synchronized(opLock) {
            val current = opInFlight
            if (current == null || current.kind != kind) {
                trace.event(
                    Atc3TraceCat.BLE, "op_stray",
                    "kind" to kind,
                    "expected" to (current?.kind ?: "-"),
                    "status" to status
                )
                return false
            }
            opTimer?.cancel(false)
            opTimer = null
            opInFlight = null
            current
        }
        trace.event(
            Atc3TraceCat.BLE, "op_done",
            "kind" to op.kind,
            "status" to status,
            "ms" to trace.since(opIssuedAt)
        )
        pumpOps()
        return true
    }

    /**
     * Give up on an operation, and on the connection with it.
     *
     * A setup step that was refused or never answered leaves the link in a state nobody can
     * describe, so it is ended rather than carried on with. The connection watchdog would catch it
     * eventually; this catches it at the step that failed and says which one it was.
     */
    private fun failOp(op: GattOp, why: String) {
        val waitedMs = trace.since(opIssuedAt)
        synchronized(opLock) {
            opTimer?.cancel(false)
            opTimer = null
            opInFlight = null
            opQueue.clear()
        }
        trace.event(Atc3TraceCat.BLE, "op_failed", "kind" to op.kind, "why" to why, "ms" to waitedMs)
        endConnection("gatt ${op.kind} $why")
    }

    private fun armOpTimer(op: GattOp) {
        val timer = watchdogExecutor.schedule({
            val outstanding = synchronized(opLock) { opInFlight === op }
            if (outstanding) {
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: ${op.kind} was never answered in ${op.timeoutMs}ms")
                trace.event(Atc3TraceCat.BLE, "op_timeout", "kind" to op.kind, "ms" to op.timeoutMs)
                failOp(op, "timeout")
            }
        }, op.timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(opLock) {
            if (opInFlight === op) opTimer = timer else timer.cancel(false)
        }
    }

    /** Drop everything outstanding, and take no more, because the link it belonged to is gone. */
    private fun cancelOps() {
        opsClosed = true
        synchronized(opLock) {
            opTimer?.cancel(false)
            opTimer = null
            val dropped = opQueue.size + if (opInFlight != null) 1 else 0
            if (dropped > 0) {
                trace.event(
                    Atc3TraceCat.BLE, "op_cancelled",
                    "n" to dropped,
                    "inflight" to (opInFlight?.kind ?: "-")
                )
            }
            opQueue.clear()
            opInFlight = null
        }
    }

    /** What the stack is currently working on, for tests and for the trace. */
    internal fun opInFlightKind(): String? = synchronized(opLock) { opInFlight?.kind }

    /** How many operations are waiting behind it. */
    internal fun opQueueDepth(): Int = synchronized(opLock) { opQueue.size }

    private fun discoverServices(gatt: BluetoothGatt) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: discovering services")
        // Discovery talks to the pump several times over and is the one setup step that can
        // legitimately be slow, so it is given longer than the rest before it is called lost.
        enqueueOp(OP_DISCOVER, DISCOVER_TIMEOUT_MS) { gatt.discoverServices() }
    }

    private fun clearConnectionState() {
        cancelOps()
        lastHeardFromAt = 0L
        isConnected = false
        isConnecting = false
        readySince = 0L
        linkUpAtMs = 0L
        discoveryStarted = false
        servicesResolved = false
        linkClaimed = false
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        authWriteCharacteristic = null
        authNotifyCharacteristic = null
        // Release a write that will now never complete, so its caller fails at once.
        writeDone?.countDown()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED    -> {
                    if (!claimLinkUp()) {
                        aapsLogger.debug(
                            LTag.PUMPBTCOMM,
                            "ATC3: connected again on a link already being set up, leaving it alone"
                        )
                        trace.event(Atc3TraceCat.BLE, "gatt_connected_again", "ms" to trace.since(linkUpAtMs))
                        return
                    }
                    linkUpAtMs = System.currentTimeMillis()
                    aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: connected, asking for a larger MTU")
                    trace.event(Atc3TraceCat.BLE, "gatt_connected", "ms" to trace.since(connectingSince))
                    // Ask before discovering services, and discover from onMtuChanged, because a
                    // request sent later can land in the middle of the first exchange.
                    enqueueOp(OP_MTU) { gatt.requestMtu(WANTED_MTU) }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: disconnected, status $status")
                    trace.event(
                        Atc3TraceCat.BLE, "gatt_disconnected",
                        "status" to status,
                        "linkMs" to if (readySince == 0L) -1 else trace.since(readySince)
                    )
                    endConnection("stack status $status")
                }
            }
        }

        /**
         * The pump's answers only arrive whole on a link that negotiated a bigger MTU.
         *
         * Asked for 517, the pump grants 251, so a 104 byte basal profile frame travels in one
         * notification and all eight arrive in a third of a second. At the default MTU of 23 the
         * same answer comes as thirty-odd notifications of 20 bytes, and the pump stops after five
         * whole frames and part of a sixth.
         *
         * Whatever the stack ends up granting is fine; this is an optimisation of the link, not a
         * requirement of the protocol, so a refusal is logged and the connection goes on.
         */
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // Whatever the stack granted is fine - a bigger MTU optimises the link, it is not a
            // requirement of the protocol - so the outcome is recorded and the setup carries on.
            if (!completeOp(OP_MTU, status)) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: MTU is now $mtu")
            } else {
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: MTU request failed with status $status, staying at the default")
            }
            trace.event(Atc3TraceCat.BLE, "mtu", "mtu" to mtu, "status" to status)
            if (!claimDiscovery()) {
                // A second MTU callback for the one request, from another client on the same link.
                // Discovering again from here would put two of everything in flight, see
                // [discoveryStarted].
                trace.event(Atc3TraceCat.BLE, "mtu_repeat")
                return
            }
            discoverServices(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!completeOp(OP_DISCOVER, status)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: service discovery failed with status $status")
                trace.event(Atc3TraceCat.BLE, "discovery_failed", "status" to status)
                endConnection("discovery status $status")
                return
            }
            if (!claimServices()) {
                trace.event(Atc3TraceCat.BLE, "discovery_repeat")
                return
            }
            // The two characteristics live in two different services, see GattAttributes.
            notifyCharacteristic = gatt
                .getService(GattAttributes.serviceNotifyUuid)
                ?.getCharacteristic(GattAttributes.characteristicNotifyUuid)
            writeCharacteristic = gatt
                .getService(GattAttributes.serviceWriteUuid)
                ?.getCharacteristic(GattAttributes.characteristicWriteUuid)

            val notify = notifyCharacteristic
            val write = writeCharacteristic
            if (notify == null || write == null) {
                aapsLogger.error(
                    LTag.PUMPBTCOMM,
                    "ATC3: expected characteristics not found, notify=${notify?.uuid} write=${write?.uuid}"
                )
                for (service in gatt.services) {
                    for (characteristic in service.characteristics) {
                        aapsLogger.debug(
                            LTag.PUMPBTCOMM,
                            "ATC3: discovered ${service.uuid} / ${characteristic.uuid} properties 0x%02X"
                                .format(characteristic.properties)
                        )
                    }
                }
                endConnection("characteristics missing")
                return
            }
            authWriteCharacteristic = gatt
                .getService(GattAttributes.serviceAuthUuid)
                ?.getCharacteristic(GattAttributes.characteristicAuthWriteUuid)
            authNotifyCharacteristic = gatt
                .getService(GattAttributes.serviceAuthUuid)
                ?.getCharacteristic(GattAttributes.characteristicAuthNotifyUuid)

            val authNotify = authNotifyCharacteristic
            // A pump without the authorisation service has firmware older than the password
            // feature, so nothing can protect its link and nothing the user does here will change
            // that.
            if (authNotify == null || authWriteCharacteristic == null) linkProtection = Atc3LinkProtection.UNSUPPORTED
            // Authorising has to come first: with a password set, the pump answers the write that
            // enables notifications on the ATC3 characteristic with Write Not Permitted until it
            // has accepted one.
            if (password != null && authNotify != null && authWriteCharacteristic != null) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: characteristics resolved, authorising")
                subscribe(gatt, authNotify, OP_SUBSCRIBE_AUTH)
                return
            }
            if (password != null) {
                // Configured to authorise but the pump has no such service. Carrying on is right:
                // a pump that does not offer it cannot be asking for a password either.
                aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: no authorisation service on this pump, connecting without one")
                trace.event(Atc3TraceCat.BLE, "auth_absent")
            }
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: characteristics resolved, subscribing")
            subscribe(gatt, notify, OP_SUBSCRIBE_DATA)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val subscribed = descriptor.characteristic?.uuid
            val kind =
                if (subscribed == GattAttributes.characteristicAuthNotifyUuid) OP_SUBSCRIBE_AUTH
                else OP_SUBSCRIBE_DATA
            if (!completeOp(kind, status)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // The ATC3 characteristic answering GATT_WRITE_NOT_PERMITTED is the pump saying it
                // wants a password, not a broken link, so it is worth saying so by name.
                if (subscribed == GattAttributes.characteristicNotifyUuid && status == GATT_WRITE_NOT_PERMITTED) {
                    aapsLogger.error(
                        LTag.PUMPBTCOMM,
                        "ATC3: the pump refused to enable notifications, it is asking for a Bluetooth password"
                    )
                    trace.event(Atc3TraceCat.BLE, "auth", "ok" to false, "why" to "not_permitted")
                    endConnection("password required")
                    callback?.onAuthenticationRejected()
                    return
                }
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: subscribing to notifications failed, status $status")
                endConnection("subscribe status $status")
                return
            }
            if (subscribed == GattAttributes.characteristicAuthNotifyUuid) {
                writePassword(gatt)
                return
            }
            // Subscribing here is the write a pump asking for a password refuses. Getting it
            // through without having presented one is the pump saying it asks for none.
            if (password == null && linkProtection == Atc3LinkProtection.UNKNOWN) {
                linkProtection = Atc3LinkProtection.UNPROTECTED
            }
            watchdog?.cancel(false)
            watchdog = null
            isConnecting = false
            isConnected = true
            readySince = trace.now()
            // A link that has just come up has not been proved by a heartbeat yet, and the first
            // one is up to three minutes away. Counting the silence from here rather than from zero
            // is what stops the check firing on a link that is merely new.
            noteHeardFrom()
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: ready")
            trace.event(Atc3TraceCat.BLE, "ready", "ms" to trace.since(connectingSince))
            callback?.onConnected()
        }

        @Deprecated("Deprecated in Android 13, kept for older devices")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { received(gatt, characteristic, it) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            received(gatt, characteristic, value)
        }

        /**
         * One notification, routed by which characteristic sent it.
         *
         * The two carry unrelated things: the authorisation answer is a single byte with its own
         * meaning, and feeding it to the frame parser would leave a stray byte in front of the
         * next answer.
         */
        private fun received(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == GattAttributes.characteristicAuthNotifyUuid) {
                onAuthAnswer(gatt, value)
                return
            }
            trace.countIn(value.size)
            noteHeardFrom()
            callback?.onDataReceived(value)
        }

        private fun onAuthAnswer(gatt: BluetoothGatt, value: ByteArray) {
            val answer = value.firstOrNull()
            if (answer != Atc3BtPassword.ACCEPTED) {
                aapsLogger.error(
                    LTag.PUMPBTCOMM,
                    "ATC3: the pump refused the Bluetooth password, answer ${answer?.let { "0x%02X".format(it) } ?: "none"}"
                )
                trace.event(Atc3TraceCat.BLE, "auth", "ok" to false, "answer" to answer?.toInt())
                endConnection("password refused")
                callback?.onAuthenticationRejected()
                return
            }
            // 000000 is the pump's own way of having no password: setting it is how its menu is
            // switched off. A link it guards is no more protected than one it does not.
            val unprotected = password == Atc3BtPassword.NONE
            linkProtection = if (unprotected) Atc3LinkProtection.UNPROTECTED else Atc3LinkProtection.PROTECTED
            trace.event(Atc3TraceCat.BLE, "auth", "ok" to true, "protected" to !unprotected)
            val notify = notifyCharacteristic
            if (notify == null) {
                endConnection("characteristics missing")
                return
            }
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: password accepted, subscribing")
            subscribe(gatt, notify, OP_SUBSCRIBE_DATA)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // The password is not an ATC3 request and nobody is waiting on it here: its outcome
            // arrives as a notification. Letting it fall through would release a protocol write
            // that has not happened.
            if (characteristic.uuid == GattAttributes.characteristicAuthWriteUuid) {
                // Nobody waits on this write as an exchange - the pump's verdict on the password
                // arrives as a notification - but the stack is not free until the write has been
                // reported, and the subscription that follows that verdict needs it free.
                if (!completeOp(OP_WRITE_AUTH, status)) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: could not send the Bluetooth password, status $status")
                    trace.event(Atc3TraceCat.BLE, "auth", "ok" to false, "why" to "write status $status")
                    endConnection("password write status $status")
                }
                return
            }
            writeSucceeded = status == BluetoothGatt.GATT_SUCCESS
            if (!writeSucceeded) {
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: characteristic write failed, status $status")
                callback?.onSendError("write status $status")
            }
            writeDone?.countDown()
        }
    }

    /**
     * Present the Bluetooth password.
     *
     * Sent from inside the GATT callback that reported the subscription, so it is already on the
     * stack's own thread and one operation at a time is guaranteed by the chain itself; nothing
     * here waits. The answer arrives as a notification, and the connect watchdog covers a pump
     * that never sends one.
     */
    private fun writePassword(gatt: BluetoothGatt) {
        val characteristic = authWriteCharacteristic
        val configured = password
        if (characteristic == null || configured == null) {
            endConnection("characteristics missing")
            return
        }
        val value = runCatching { Atc3BtPassword.authValue(configured) }.getOrElse {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the configured Bluetooth password is unusable, ${it.message}")
            endConnection("password unusable")
            callback?.onAuthenticationRejected()
            return
        }
        enqueueOp(OP_WRITE_AUTH) { issueAuthWrite(gatt, characteristic, value) }
    }

    private fun issueAuthWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean =
        try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status =
                    gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (status != BluetoothStatusCodes.SUCCESS) {
                    // Named for the same reason the data write names it: a refusal without its
                    // number is undiagnosable afterwards, and 201 — a write already in flight —
                    // means something quite different from 4, a link that has gone.
                    aapsLogger.error(
                        LTag.PUMPBTCOMM, "ATC3: an authorisation write was refused by the Bluetooth stack, status $status"
                    )
                    trace.event(Atc3TraceCat.BLE, "auth_write", "ok" to false, "status" to status)
                }
                status == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = value
                val ok = gatt.writeCharacteristic(characteristic)
                if (!ok) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: an authorisation write was refused by the Bluetooth stack")
                    trace.event(Atc3TraceCat.BLE, "auth_write", "ok" to false, "status" to REFUSAL_UNNUMBERED)
                }
                ok
            }
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: missing Bluetooth permission on an authorisation write", e)
            trace.event(Atc3TraceCat.BLE, "auth_write", "ok" to false, "status" to REFUSAL_PERMISSION)
            false
        }

    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, kind: String) {
        val descriptor = characteristic.getDescriptor(GattAttributes.characteristicConfigDescriptor)
        if (descriptor == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: notification descriptor missing")
            endConnection("notification descriptor missing")
            return
        }
        val value =
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        enqueueOp(kind) {
            gatt.setCharacteristicNotification(characteristic, true)
            // What this call answers is the whole question. A descriptor write the stack declines
            // to start produces no callback at all; ignored, the connection would sit out its
            // entire watchdog with nothing said about why.
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }
    }
}
