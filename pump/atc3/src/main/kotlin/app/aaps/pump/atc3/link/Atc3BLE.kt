package app.aaps.pump.atc3.link

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
 * The Bluetooth link to the pump: connects, sets the link up, presents the Bluetooth password and
 * passes bytes both ways; it knows nothing of frames.
 *
 * Setting up is a chain of steps, each started by the callback of the one before: MTU, discovery,
 * the authorisation subscription, the password, the data subscription. A connect watchdog covers
 * the whole chain. [linkProtection] is read from the pump's answer to the password alone: the pump
 * accepts a link rather than a client, so being let in without one says nothing.
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

    /** When the link was asked for and when it became usable, for the trace. */
    @Volatile private var connectingSince = 0L

    @Volatile private var readySince = 0L

    /**
     * When anything last arrived from the pump: what tells a held link that went quiet from a live one.
     * Every frame counts, not only the heartbeat.
     */
    @Volatile private var lastHeardFromAt = 0L

    /** When the link came up, milliseconds, 0 while there is none: what the settle wait before the first request is counted from. */
    @Volatile
    var linkUpAtMs = 0L
        private set

    /**
     * Whether discovery has been started on this link: a second MTU callback, from another client on the
     * same link, would otherwise start the setup twice and leave it stuck.
     */
    @Volatile private var discoveryStarted = false

    @Volatile private var servicesResolved = false

    @Volatile private var linkClaimed = false

    /** @return true once per link, for the caller that may start discovery */
    @Synchronized
    internal fun claimDiscovery(): Boolean {
        if (discoveryStarted) return false
        discoveryStarted = true
        return true
    }

    /** @return true once per link, for the caller that may begin the setup: the stack can report one link as connected twice */
    @Synchronized
    internal fun claimLinkUp(): Boolean {
        if (linkClaimed) return false
        linkClaimed = true
        return true
    }

    /** @return true once per link, for the caller that may act on the discovered characteristics */
    @Synchronized
    internal fun claimServices(): Boolean {
        if (servicesResolved) return false
        servicesResolved = true
        return true
    }

    /** True from asking for a link until its end has been reported, so that the end is reported exactly once. */
    private val live = AtomicBoolean(false)

    @Volatile private var watchdog: ScheduledFuture<*>? = null

    /** Attempts in a row that never gave a usable link: what sets the wait before the next. */
    @Volatile private var failures = 0

    @Volatile private var blockedUntil = 0L

    private val watchdogExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Atc3ConnectWatchdog").apply { isDaemon = true }
    }

    /** How long connecting is held off, milliseconds, 0 when it is allowed: a failed stack fails again at once. */
    val backoffRemainingMs: Long get() = (blockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var authWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var authNotifyCharacteristic: BluetoothGattCharacteristic? = null

    /** The Bluetooth password to present, `000000` when none is entered. */
    @Volatile private var password: String = Atc3BtPassword.NONE

    /** How well the current link is protected, as the pump answered while it came up. */
    @Volatile
    var linkProtection: Atc3LinkProtection = Atc3LinkProtection.UNKNOWN
        private set

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

    /** The phone's Bluetooth going off ends the link at once: the stack reports nothing for it. */
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

    @Synchronized
    private fun watchAdapterState() {
        if (adapterStateWatched) return
        runCatching {
            context.registerReceiver(adapterStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            adapterStateWatched = true
        }.onFailure { aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: could not listen for the Bluetooth adapter's state", it) }
    }

    /** The password to present on the next link; blank or malformed is none, and `000000` is presented. */
    fun setPassword(password: String?) {
        this.password = password?.trim()?.takeIf { Atc3BtPassword.isValid(it) } ?: Atc3BtPassword.NONE
    }

    /** @return false when the address is unusable or Bluetooth is not available */
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

    /** Close the link and report its end exactly once, whoever asked for it. */
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
        // Only an attempt that never gave a usable link counts towards the wait.
        val wait = when {
            wasReady       -> 0L
            // The stack answered: it works, and the pump is out of reach. A flat wait.
            stackAnswered  -> BACKOFF_ANSWERED_MS
            // The stack said nothing: it is wedged, and only time helps, more of it each round.
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

    /** The pump's heartbeat; counted like any frame, told apart only in the trace. */
    fun noteHeartbeat() {
        noteHeardFrom()
    }

    fun noteHeardFrom() {
        lastHeardFromAt = System.currentTimeMillis()
    }

    /** How long the pump has been silent, milliseconds, -1 when nothing has arrived yet. What to do about it is decided above. */
    val quietForMs: Long get() = if (lastHeardFromAt == 0L) -1L else System.currentTimeMillis() - lastHeardFromAt

    /** Give up on a connection attempt that is going nowhere, long before the stack or the queue would. */
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
     * Write one payload and wait until the stack reports it done, so that writes go one at a time.
     *
     * @return false when there is no usable link, the write was refused, or it did not complete in time
     */
    fun write(data: ByteArray): Boolean {
        val currentGatt = gatt
        val characteristic = writeCharacteristic
        if (currentGatt == null || characteristic == null || !isConnected) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: write attempted without a ready connection")
            return false
        }
        // Bluetooth off is the link gone, whatever the stack has said; asked here for a missed broadcast.
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

            // The reason the stack gave for a refusal, for the trace.
            var refusedWith = REFUSAL_UNNUMBERED
            val issued = try {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val status = currentGatt.writeCharacteristic(characteristic, data, writeType)
                    if (status == BluetoothStatusCodes.SUCCESS) {
                        true
                    } else {
                        refusedWith = status
                        aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: write refused by the Bluetooth stack, status $status")
                        false
                    }
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

        /** The largest MTU there is: whatever the two ends agree on is used. */
        private const val WANTED_MTU = 517

        /** A refusal from a stack too old to give a reason. */
        private const val REFUSAL_UNNUMBERED = -1

        /** A refusal for a missing permission rather than by the stack. */
        private const val REFUSAL_PERMISSION = -2
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** How long one setup step may go unanswered: long past any real answer, short enough to name the step that failed. */
        private const val GATT_OP_TIMEOUT_MS = 5_000L
        private const val DISCOVER_TIMEOUT_MS = 10_000L
        internal const val OP_MTU = "mtu"
        internal const val OP_DISCOVER = "discover"
        internal const val OP_SUBSCRIBE_AUTH = "subscribe:auth"
        internal const val OP_SUBSCRIBE_DATA = "subscribe:data"
        internal const val OP_WRITE_AUTH = "write:auth"

        /** The wait after an attempt the stack never answered; it doubles from here. */
        private const val BACKOFF_BASE_MS = 5_000L

        /** The wait after an attempt the stack refused: flat, the stack works. */
        private const val BACKOFF_ANSWERED_MS = 15_000L
        private const val BACKOFF_CAP_MS = 60_000L

        /** Keeps the doubling from overflowing. */
        private const val BACKOFF_MAX_SHIFT = 5

        /** The status the pump refuses the data subscription with until it has a password; Android has no constant for it. */
        private const val GATT_WRITE_NOT_PERMITTED = 3
    }

    // GATT operations, one at a time

    /**
     * One request to the Bluetooth stack.
     *
     * @param kind      the name it is traced and completed under
     * @param timeoutMs how long its callback may take
     * @param issue     makes the call, returning whether the stack started it
     */
    private class GattOp(val kind: String, val timeoutMs: Long, val issue: () -> Boolean)

    private val opLock = Any()
    private val opQueue = ArrayDeque<GattOp>()

    /** True once the link the queue served has ended: no step may be issued on a closing link. */
    @Volatile private var opsClosed = false
    private var opInFlight: GattOp? = null
    private var opIssuedAt = 0L
    private var opTimer: ScheduledFuture<*>? = null

    /**
     * Queue one GATT operation. The stack drops an operation issued while another is outstanding,
     * without a callback, so each is issued only when the one before has been answered, and each is
     * answered, timed out or refused, and traced.
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

    /** @return true when [kind] was the operation outstanding, false for a callback nobody was waiting for */
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

    /** Give up on an operation, and on the link with it: a setup that lost a step is ended, named. */
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

    /** Drop everything outstanding and take no more: the link is gone. */
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

    /** What the stack is working on, for tests. */
    internal fun opInFlightKind(): String? = synchronized(opLock) { opInFlight?.kind }

    /** How many operations wait behind it, for tests. */
    internal fun opQueueDepth(): Int = synchronized(opLock) { opQueue.size }

    private fun discoverServices(gatt: BluetoothGatt) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: discovering services")
        // The one setup step that can be slow.
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
        // Release a write that will never complete, so its caller fails at once.
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
                    // Asked before discovery, which starts from onMtuChanged, so that nothing lands in the middle of it.
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

        /** A bigger MTU brings the pump's answers whole; whatever is granted, the setup goes on. */
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!completeOp(OP_MTU, status)) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: MTU is now $mtu")
            } else {
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: MTU request failed with status $status, staying at the default")
            }
            trace.event(Atc3TraceCat.BLE, "mtu", "mtu" to mtu, "status" to status)
            if (!claimDiscovery()) {
                // A second MTU callback for the one request, from another client on the link: discovering again
                // would put everything in flight twice, see [discoveryStarted].
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
            // No authorisation service: firmware older than the password, nothing can protect this link.
            if (authNotify == null || authWriteCharacteristic == null) linkProtection = Atc3LinkProtection.UNSUPPORTED
            // The password first: until the pump has one, it refuses the data subscription.
            if (authNotify != null && authWriteCharacteristic != null) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: characteristics resolved, authorising")
                subscribe(gatt, authNotify, OP_SUBSCRIBE_AUTH)
                return
            }
            // A pump without the service cannot be asking for a password.
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: no authorisation service on this pump, connecting without one")
            trace.event(Atc3TraceCat.BLE, "auth_absent")
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
                // The pump asking for a password, said by name.
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
            watchdog?.cancel(false)
            watchdog = null
            isConnecting = false
            isConnected = true
            readySince = trace.now()
            // A new link has had no heartbeat yet: its silence is counted from now.
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

        /** One notification, by the characteristic that sent it: the password's answer is not a frame. */
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
            // 000000 is the pump's own "no password": a link it guards is not protected.
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
            // The password's outcome arrives as a notification: this write releases no exchange.
            if (characteristic.uuid == GattAttributes.characteristicAuthWriteUuid) {
                // The stack is busy until this write is reported, and the step after the password needs it free.
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

    /** Present the Bluetooth password, from the callback chain; the pump answers with a notification. */
    private fun writePassword(gatt: BluetoothGatt) {
        val characteristic = authWriteCharacteristic
        val configured = password
        if (characteristic == null) {
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
            // A descriptor write the stack does not start gets no callback: the answer here is the whole question.
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
