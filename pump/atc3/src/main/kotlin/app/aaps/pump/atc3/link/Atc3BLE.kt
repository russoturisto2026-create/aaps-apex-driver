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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Bluetooth link to the pump: connects, sets the link up, presents the Bluetooth password and
 * passes bytes both ways; it knows nothing of frames.
 *
 * Setting up is a chain of steps, each started by the callback of the one before: MTU, discovery,
 * the authorisation subscription, the password, the data subscription. Where the link is in that
 * chain is [LinkState], and a step is taken only from the state before it, so a callback the stack
 * repeats starts nothing twice. A connect watchdog covers the whole chain. [linkProtection] is read
 * from the pump's answer to the password alone: the pump accepts a link rather than a client, so
 * being let in without one says nothing.
 *
 * Each attempt at a link is a [Link] of its own, with its own callback, setup steps and watchdog.
 * The stack can still report on a link it has been told to close, and a watchdog or a step can fire
 * after its link has ended: whatever does not belong to the current link touches nothing. The link,
 * its state and what the link knows change together under this object's lock, so nothing ever sees
 * a link half ended. The link's start and end are told to [Atc3BleCallback] one at a time and in the
 * order they happened, and a start is told only while its link is still the current one.
 *
 * Why the same check comes back so often: the stack answers on threads of its own, the timers fire on
 * another, and the exchange writes from a third. A link can end between any two lines of code. So each
 * place that acts for a link asks again, under the lock, whether that link is still the current one.
 * Three places close such a gap the stack leaves open: a link that ends while the stack hands it over
 * ([startLink]), while its watchdog is set ([armWatchdog]), and while its start is told.
 */
@SuppressLint("MissingPermission")
@Singleton
class Atc3BLE @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val context: Context,
    private val trace: Atc3Trace
) {

    @Volatile private var callback: Atc3BleCallback? = null

    /** Held while a link's start or end is told: one at a time, so they arrive in the order they happened. */
    private val reportLock = Any()

    /** Where the link is: from asking for it, through each setup step, to usable, and back to none. */
    enum class LinkState { IDLE, CONNECTING, LINK_UP, DISCOVERING, AUTHORISING, SUBSCRIBING, READY }

    /** One attempt at a link, from asking for it to its end. */
    private inner class Link {

        /** What the stack handed over for it; null in the moment between asking and the stack's answer. */
        @Volatile var gatt: BluetoothGatt? = null
        val callback = LinkCallback(this)

        /** The setup steps, one at a time; a step that fails ends this link, and only this one. */
        val ops = Atc3GattOps(aapsLogger, trace, scheduler) { kind, why -> endConnection("gatt $kind $why", link = this) }
        @Volatile var watchdog: ScheduledFuture<*>? = null
        @Volatile var writeCharacteristic: BluetoothGattCharacteristic? = null
        @Volatile var notifyCharacteristic: BluetoothGattCharacteristic? = null
        @Volatile var authWriteCharacteristic: BluetoothGattCharacteristic? = null
    }

    /** The current link, null when there is none; changes only together with [state]. */
    @Volatile private var current: Link? = null

    @Volatile private var state = LinkState.IDLE

    internal val linkState: LinkState get() = state

    /**
     * Take [link] one step on, from [from] to [to], and do [then] in the same moment: nothing else
     * changes the link meanwhile.
     *
     * @return false when [link] is not the current one or is not at [from]: the step is not the caller's
     */
    @Synchronized
    private fun advance(link: Link, from: LinkState, to: LinkState, then: () -> Unit = {}): Boolean {
        if (current !== link || state != from) return false
        state = to
        then()
        return true
    }

    private fun isCurrent(link: Link): Boolean = current === link

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

    private val backoff = Atc3Backoff()

    /** Runs the connect watchdog and the setup steps' timers; a test puts its own in before the first link. */
    internal var scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Atc3ConnectWatchdog").apply { isDaemon = true }
    }

    /**
     * How long connecting is held off, milliseconds, 0 when it is allowed: a failed stack fails again at once,
     * and a link the driver has just let go of is still up on the radio for a moment.
     */
    val backoffRemainingMs: Long get() = backoff.remainingMs

    /** The Bluetooth password to present, `000000` when none is entered. */
    @Volatile private var password: String = Atc3BtPassword.NONE

    /** How well the current link is protected, as the pump answered while it came up. */
    @Volatile
    var linkProtection: Atc3LinkProtection = Atc3LinkProtection.UNKNOWN
        private set

    /** How long a write may wait for the stack's report; a test shortens it. */
    internal var writeTimeoutMs = WRITE_TIMEOUT_MS

    /** The write waiting for the stack's report, null when none; set and released under the lock. */
    private var writeDone: CountDownLatch? = null

    @Volatile private var writeSucceeded: Boolean = false

    val isConnected: Boolean get() = state == LinkState.READY

    val isConnecting: Boolean get() = state != LinkState.IDLE && state != LinkState.READY

    fun setCallback(callback: Atc3BleCallback?) {
        this.callback = callback
    }

    /** The phone's Bluetooth going off ends the link at once: the stack reports nothing for it. */
    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val adapterState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (adapterState != BluetoothAdapter.STATE_TURNING_OFF && adapterState != BluetoothAdapter.STATE_OFF) return
            if (state == LinkState.IDLE) return
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the phone's Bluetooth is going off, the link is gone")
            trace.event(Atc3TraceCat.BLE, "adapter_off", "state" to adapterState)
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

    /**
     * Whether the phone's Bluetooth is on. Off, some phones still let a link to the pump come up, and
     * then fail it at the first write: the caller tries no link while it is off.
     */
    val isBluetoothOn: Boolean get() = bluetoothAdapter()?.isEnabled == true

    /** Whether the phone has Bluetooth at all. */
    val hasBluetooth: Boolean get() = bluetoothAdapter() != null

    /** Whether [address] is a Bluetooth address a link can be asked for. */
    fun isValidAddress(address: String): Boolean = BluetoothAdapter.checkBluetoothAddress(address)

    /**
     * Ask for a link. Whether to ask at all, and when, is decided by the caller, see [Atc3Connection.connect].
     *
     * @return false when the address is unusable or Bluetooth is not available
     */
    fun connect(address: String): Boolean {
        if (state != LinkState.IDLE) {
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
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: cannot connect to $address", e)
            return false
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: connecting to $address")
        return startLink { linkCallback -> device.connectGatt(context, false, linkCallback, BluetoothDevice.TRANSPORT_LE) }
    }

    /**
     * Begin a link: it is the current one before the stack is asked, so that the stack's first
     * callbacks, which may come before [connectGatt] returns, find it.
     *
     * @param connectGatt asks the stack for the link with the callback given
     * @return false when no attempt is under way: refused, or ended before the stack answered
     */
    internal fun startLink(connectGatt: (BluetoothGattCallback) -> BluetoothGatt?): Boolean {
        val link = synchronized(this) {
            if (state != LinkState.IDLE) return true
            Link().also {
                current = it
                state = LinkState.CONNECTING
                linkProtection = Atc3LinkProtection.UNKNOWN
            }
        }
        connectingSince = trace.now()
        trace.event(Atc3TraceCat.BLE, "connecting")
        armWatchdog(link)
        val handed = try {
            connectGatt(link.callback)
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: missing Bluetooth permission", e)
            refused(link, "no permission")
            return false
        } catch (e: RuntimeException) {
            // Whatever the stack throws, there is no attempt under way to wait for.
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the stack would not connect", e)
            refused(link, "connect refused")
            return false
        }
        val kept = synchronized(this) {
            if (isCurrent(link)) link.gatt = handed
            isCurrent(link)
        }
        if (!kept) {
            // The link ended while the stack was handing it over, and was reported then: what the stack handed is closed here.
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: the link ended before the stack answered, closing what it handed over")
            trace.event(Atc3TraceCat.BLE, "connect_abandoned")
            // It never came up, so the end already set a wait of a failed attempt, longer than a settle.
            close(handed)
            return false
        }
        // No client from the stack is a stack that does not answer: the watchdog ends the attempt.
        if (handed == null) aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the stack gave no link")
        return true
    }

    /** End an attempt the stack would not start; it would not start the next one either, so the next waits. */
    private fun refused(link: Link, reason: String) {
        endConnection(reason, Ending.NOT_STARTED, link)
        val wait = synchronized(this) { backoff.afterRefusal() }
        trace.event(Atc3TraceCat.BLE, "backoff_set", "why" to reason, "answered" to true, "after" to backoff.failures, "ms" to wait)
    }

    fun disconnect() {
        aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: disconnect requested")
        trace.event(Atc3TraceCat.BLE, "disconnect_requested", "connected" to isConnected)
        endConnection("requested")
    }

    /** How a link ended: what the wait before the next attempt is set from. */
    private enum class Ending {

        /** The driver let the link go or gave up on it; the stack answered all along. The radio may hold the link a moment more. */
        LET_GO,

        /** The stack said the link is gone: nothing of it is left on the radio. */
        STACK_DROPPED,

        /** The link did not come up in time. A link that has become ready meanwhile is left alone. */
        NEVER_UP,

        /** The stack stopped answering on a usable link: counted like an attempt that never came up. */
        STACK_SILENT,

        /** The stack would not start the attempt. Nobody is told: the caller learns it from the return value. */
        NOT_STARTED
    }

    /**
     * End the link and report its end exactly once, whoever asked for it.
     *
     * @param link the link the caller belongs to, null for whatever link there is: a watchdog or a step of a link already ended ends nothing
     * @return true when a link was ended here
     */
    private fun endConnection(reason: String, ending: Ending = Ending.LET_GO, link: Link? = null): Boolean {
        // The stack never answered: it is slow or wedged, and the wait grows each time.
        val stackSilent = ending == Ending.NEVER_UP || ending == Ending.STACK_SILENT
        val ended: Link
        val pendingWrite: CountDownLatch?
        val wait: Long
        synchronized(this) {
            if (link != null && current !== link) return false
            if (ending == Ending.NEVER_UP && state == LinkState.READY) return false
            lastHeardFromAt = 0L
            ended = current ?: return false
            val wasReady = state == LinkState.READY
            current = null
            state = LinkState.IDLE
            readySince = 0L
            linkUpAtMs = 0L
            pendingWrite = writeDone
            // Set with the state: a connect that sees the link idle sees the wait too.
            wait = if (ending == Ending.NOT_STARTED) 0L
            else backoff.afterEnd(
                wasReady = wasReady && !stackSilent,
                stackAnswered = !stackSilent,
                released = ended.gatt != null && ending != Ending.STACK_DROPPED
            )
        }
        ended.watchdog?.cancel(false)
        ended.ops.close()
        close(ended.gatt)
        // Release a write that will never complete, so its caller fails at once.
        pendingWrite?.countDown()
        if (ending == Ending.NOT_STARTED) return true
        if (wait > 0L) {
            trace.event(
                Atc3TraceCat.BLE, "backoff_set",
                "why" to reason, "answered" to !stackSilent, "after" to backoff.failures, "ms" to wait
            )
        }
        synchronized(reportLock) { callback?.onDisconnected() }
        return true
    }

    private fun close(gatt: BluetoothGatt?) {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: missing Bluetooth permission on disconnect", e)
        }
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
    private fun armWatchdog(link: Link) {
        link.watchdog = scheduler.schedule({
            // A link that has become ready, or has ended, is not ended again.
            if (!endConnection("connect timeout", Ending.NEVER_UP, link)) return@schedule
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the link did not come up in ${CONNECT_TIMEOUT_MS}ms, gave up")
            trace.event(Atc3TraceCat.BLE, "connect_timeout", "ms" to CONNECT_TIMEOUT_MS)
        }, CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // A link ended while the watchdog was being set did not see it to cancel it.
        if (!isCurrent(link)) link.watchdog?.cancel(false)
    }

    /**
     * Write one payload and wait until the stack reports it done. One write at a time is the caller's
     * to keep: every write goes through the exchange, which runs one at a time.
     *
     * @return false when there is no usable link, the write was refused, or it did not complete in time
     */
    fun write(data: ByteArray): Boolean {
        // The state first: a link seen ready has its client and characteristics set.
        val ready = state == LinkState.READY
        val link = current
        val currentGatt = link?.gatt
        val characteristic = link?.writeCharacteristic
        if (!ready || link == null || currentGatt == null || characteristic == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: write attempted without a ready connection")
            return false
        }
        // Bluetooth off is the link gone, whatever the stack has said; asked here for a missed broadcast.
        if (bluetoothAdapter()?.isEnabled == false) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the phone's Bluetooth is off, the link is gone")
            trace.event(Atc3TraceCat.BLE, "adapter_off", "state" to -1)
            endConnection("bluetooth off", link = link)
            return false
        }
        val latch = CountDownLatch(1)
        try {
            // The link may have ended since it was seen ready: then there is nothing to write on.
            val registered = synchronized(this) {
                if (current !== link || state != LinkState.READY) return@synchronized false
                writeDone = latch
                writeSucceeded = false
                true
            }
            if (!registered) {
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the link ended before the write could be made")
                return false
            }

            val writeType =
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val status = issueWrite(currentGatt, characteristic, data, writeType)
            val issued = status == BluetoothStatusCodes.SUCCESS
            val issuedAt = trace.now()

            if (!issued) {
                trace.event(
                    Atc3TraceCat.BLE, "write",
                    "bytes" to data.size, "ok" to false, "why" to "refused", "status" to status
                )
                return false
            }
            if (!latch.await(writeTimeoutMs, TimeUnit.MILLISECONDS)) {
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: write did not complete in time, ending the link")
                trace.event(Atc3TraceCat.BLE, "write", "bytes" to data.size, "ok" to false, "why" to "timeout")
                // Its report may still come, and the stack's report names no write: on this link it would
                // be taken for the next write's. Ended, the link passes it over.
                endConnection("write timeout", Ending.STACK_SILENT, link)
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
            synchronized(this) { if (writeDone === latch) writeDone = null }
        }
    }

    /** The stack reported the data write of [link] done. */
    private fun writeReported(link: Link, succeeded: Boolean) {
        val latch = synchronized(this) {
            if (current !== link) return
            writeSucceeded = succeeded
            writeDone
        }
        latch?.countDown()
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
        internal const val CONNECT_TIMEOUT_MS = 15_000L

        /** How long one setup step may go unanswered: long past any real answer, short enough to name the step that failed. */
        private const val GATT_OP_TIMEOUT_MS = 5_000L
        private const val DISCOVER_TIMEOUT_MS = 10_000L
        internal const val OP_MTU = "mtu"
        internal const val OP_DISCOVER = "discover"
        internal const val OP_SUBSCRIBE_AUTH = "subscribe:auth"
        internal const val OP_SUBSCRIBE_DATA = "subscribe:data"
        internal const val OP_WRITE_AUTH = "write:auth"

        /** The status the pump refuses the data subscription with until it has a password; Android has no constant for it. */
        private const val GATT_WRITE_NOT_PERMITTED = 3
    }

    /** The stack's callbacks for one link: what arrives for any other link is passed over. */
    private inner class LinkCallback(private val link: Link) : BluetoothGattCallback() {

        /** True when this callback's link is still the current one; said in the trace when it is not. */
        private fun mine(what: String): Boolean {
            if (isCurrent(link)) return true
            trace.event(Atc3TraceCat.BLE, "stale_callback", "what" to what)
            return false
        }

        private fun enqueue(kind: String, timeoutMs: Long = GATT_OP_TIMEOUT_MS, issue: () -> Boolean) =
            link.ops.enqueue(kind, timeoutMs, issue)

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!mine("state $newState")) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED    -> {
                    if (!advance(link, LinkState.CONNECTING, LinkState.LINK_UP) { linkUpAtMs = System.currentTimeMillis() }) {
                        aapsLogger.debug(
                            LTag.PUMPBTCOMM,
                            "ATC3: connected again on a link already being set up, leaving it alone"
                        )
                        trace.event(Atc3TraceCat.BLE, "gatt_connected_again", "ms" to trace.since(linkUpAtMs))
                        return
                    }
                    aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: connected, asking for a larger MTU")
                    trace.event(Atc3TraceCat.BLE, "gatt_connected", "ms" to trace.since(connectingSince))
                    // Asked before discovery, which starts from onMtuChanged, so that nothing lands in the middle of it.
                    enqueue(OP_MTU) { gatt.requestMtu(WANTED_MTU) }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: disconnected, status $status")
                    trace.event(
                        Atc3TraceCat.BLE, "gatt_disconnected",
                        "status" to status,
                        "linkMs" to if (readySince == 0L) -1 else trace.since(readySince)
                    )
                    endConnection("stack status $status", Ending.STACK_DROPPED, link)
                }
            }
        }

        /** A bigger MTU brings the pump's answers whole; whatever is granted, the setup goes on. */
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!mine("mtu")) return
            if (!link.ops.complete(OP_MTU, status)) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: MTU is now $mtu")
            } else {
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: MTU request failed with status $status, staying at the default")
            }
            trace.event(Atc3TraceCat.BLE, "mtu", "mtu" to mtu, "status" to status)
            // A repeated MTU callback is refused by the step queue above; this fails only for a link that ended meanwhile.
            if (!advance(link, LinkState.LINK_UP, LinkState.DISCOVERING)) return
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: discovering services")
            // The one setup step that can be slow.
            enqueue(OP_DISCOVER, DISCOVER_TIMEOUT_MS) { gatt.discoverServices() }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!mine("discovered")) return
            if (!link.ops.complete(OP_DISCOVER, status)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: service discovery failed with status $status")
                trace.event(Atc3TraceCat.BLE, "discovery_failed", "status" to status)
                endConnection("discovery status $status", link = link)
                return
            }
            val notify = gatt.getService(GattAttributes.serviceNotifyUuid)?.getCharacteristic(GattAttributes.characteristicNotifyUuid)
            val write = gatt.getService(GattAttributes.serviceWriteUuid)?.getCharacteristic(GattAttributes.characteristicWriteUuid)
            val authWrite = gatt.getService(GattAttributes.serviceAuthUuid)?.getCharacteristic(GattAttributes.characteristicAuthWriteUuid)
            val authNotify = gatt.getService(GattAttributes.serviceAuthUuid)?.getCharacteristic(GattAttributes.characteristicAuthNotifyUuid)
            val discovered = advance(link, LinkState.DISCOVERING, LinkState.AUTHORISING) {
                link.notifyCharacteristic = notify
                link.writeCharacteristic = write
                link.authWriteCharacteristic = authWrite
            }
            if (!discovered) return
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
                endConnection("characteristics missing", link = link)
                return
            }
            // The password first: until the pump has one, it refuses the data subscription.
            if (authNotify != null && authWrite != null) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: characteristics resolved, authorising")
                subscribe(gatt, authNotify, OP_SUBSCRIBE_AUTH)
                return
            }
            // No authorisation service: firmware older than the password, nothing can protect this link,
            // and a pump without the service cannot be asking for a password.
            if (!advance(link, LinkState.AUTHORISING, LinkState.SUBSCRIBING) { linkProtection = Atc3LinkProtection.UNSUPPORTED }) return
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: no authorisation service on this pump, connecting without one")
            trace.event(Atc3TraceCat.BLE, "auth_absent")
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: characteristics resolved, subscribing")
            subscribe(gatt, notify, OP_SUBSCRIBE_DATA)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!mine("descriptor")) return
            val subscribed = descriptor.characteristic?.uuid
            val kind =
                if (subscribed == GattAttributes.characteristicAuthNotifyUuid) OP_SUBSCRIBE_AUTH
                else OP_SUBSCRIBE_DATA
            if (!link.ops.complete(kind, status)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // The pump asking for a password, said by name.
                if (subscribed == GattAttributes.characteristicNotifyUuid && status == GATT_WRITE_NOT_PERMITTED) {
                    aapsLogger.error(
                        LTag.PUMPBTCOMM,
                        "ATC3: the pump refused to enable notifications, it is asking for a Bluetooth password"
                    )
                    trace.event(Atc3TraceCat.BLE, "auth", "ok" to false, "why" to "not_permitted")
                    endConnection("password required", link = link)
                    callback?.onAuthenticationRejected()
                    return
                }
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: subscribing to notifications failed, status $status")
                endConnection("subscribe status $status", link = link)
                return
            }
            if (subscribed == GattAttributes.characteristicAuthNotifyUuid) {
                writePassword(gatt)
                return
            }
            val ready = advance(link, LinkState.SUBSCRIBING, LinkState.READY) {
                readySince = trace.now()
                // A new link has had no heartbeat yet: its silence is counted from now.
                noteHeardFrom()
            }
            if (!ready) return
            link.watchdog?.cancel(false)
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: ready")
            trace.event(Atc3TraceCat.BLE, "ready", "ms" to trace.since(connectingSince))
            // Told only while the link is still the one that came up: an end in between is told instead.
            synchronized(reportLock) {
                if (isCurrent(link) && state == LinkState.READY) callback?.onConnected()
            }
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
            if (!mine("notification")) return
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
                endConnection("password refused", link = link)
                callback?.onAuthenticationRejected()
                return
            }
            // 000000 is the pump's own "no password": a link it guards is not protected.
            val unprotected = password == Atc3BtPassword.NONE
            val accepted = advance(link, LinkState.AUTHORISING, LinkState.SUBSCRIBING) {
                linkProtection = if (unprotected) Atc3LinkProtection.UNPROTECTED else Atc3LinkProtection.PROTECTED
            }
            if (!accepted) return
            trace.event(Atc3TraceCat.BLE, "auth", "ok" to true, "protected" to !unprotected)
            val notify = link.notifyCharacteristic
            if (notify == null) {
                endConnection("characteristics missing", link = link)
                return
            }
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: password accepted, subscribing")
            subscribe(gatt, notify, OP_SUBSCRIBE_DATA)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (!mine("write")) return
            // The password's outcome arrives as a notification: this write releases no exchange.
            if (characteristic.uuid == GattAttributes.characteristicAuthWriteUuid) {
                // The stack is busy until this write is reported, and the step after the password needs it free.
                if (!link.ops.complete(OP_WRITE_AUTH, status)) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: could not send the Bluetooth password, status $status")
                    trace.event(Atc3TraceCat.BLE, "auth", "ok" to false, "why" to "write status $status")
                    endConnection("password write status $status", link = link)
                }
                return
            }
            val succeeded = status == BluetoothGatt.GATT_SUCCESS
            if (!succeeded) {
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: characteristic write failed, status $status")
                callback?.onSendError("write status $status")
            }
            writeReported(link, succeeded)
        }

        /** Present the Bluetooth password, from the callback chain; the pump answers with a notification. */
        private fun writePassword(gatt: BluetoothGatt) {
            val characteristic = link.authWriteCharacteristic
            val configured = password
            if (characteristic == null) {
                endConnection("characteristics missing", link = link)
                return
            }
            val value = runCatching { Atc3BtPassword.authValue(configured) }.getOrElse {
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the configured Bluetooth password is unusable, ${it.message}")
                endConnection("password unusable", link = link)
                callback?.onAuthenticationRejected()
                return
            }
            enqueue(OP_WRITE_AUTH) {
                val status = issueWrite(gatt, characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (status != BluetoothStatusCodes.SUCCESS) trace.event(Atc3TraceCat.BLE, "auth_write", "ok" to false, "status" to status)
                status == BluetoothStatusCodes.SUCCESS
            }
        }

        private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, kind: String) {
            val descriptor = characteristic.getDescriptor(GattAttributes.characteristicConfigDescriptor)
            if (descriptor == null) {
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: notification descriptor missing")
                endConnection("notification descriptor missing", link = link)
                return
            }
            val value =
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

            enqueue(kind) {
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

    /**
     * Ask the stack to write [value], for the data and the password alike.
     *
     * @return [BluetoothStatusCodes.SUCCESS] when the stack took the write, otherwise why it did not:
     * its status, [REFUSAL_UNNUMBERED] from a stack too old to give one, or [REFUSAL_PERMISSION]
     */
    private fun issueWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, writeType: Int): Int {
        val status = try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, value, writeType)
            } else {
                characteristic.writeType = writeType
                characteristic.value = value
                if (gatt.writeCharacteristic(characteristic)) BluetoothStatusCodes.SUCCESS else REFUSAL_UNNUMBERED
            }
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: missing Bluetooth permission on a write", e)
            return REFUSAL_PERMISSION
        }
        if (status != BluetoothStatusCodes.SUCCESS) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: a write to ${characteristic.uuid} was refused by the Bluetooth stack, status $status")
        }
        return status
    }
}
