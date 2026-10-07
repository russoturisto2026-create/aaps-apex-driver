package app.aaps.pump.atc3.link

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.protocol.Atc3Frame
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The link to the pump: bringing it up and letting it go, the Bluetooth password it is brought up
 * under, and the watch over a held link that went quiet. What travels over it is the [Listener]'s.
 */
@Singleton
class Atc3Connection @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val preferences: Preferences,
    private val atc3BLE: Atc3BLE,
    private val trace: Atc3Trace,
    private val uiInteraction: UiInteraction,
    private val rh: ResourceHelper,
    private val pairing: Atc3Pairing
) : Atc3BleCallback {

    /** Carries the exchanges over the link, see [app.aaps.pump.atc3.exchange.Atc3Exchange]. */
    interface Listener {

        /** Whether the pump is busy with something, which proves the link by itself. */
        val isBusy: Boolean

        /** The link is up: nothing of the last one is owed on it. */
        fun onLinkUp()

        /** The link is gone: whatever waits for an answer is released now. */
        fun onLinkDown()

        /** One notification, as received. */
        fun onDataReceived(chunk: ByteArray)
    }

    @Volatile var listener: Listener? = null

    val isConnected: Boolean get() = atc3BLE.isConnected
    val isConnecting: Boolean get() = atc3BLE.isConnecting

    /** How well the last link was protected, as the pump answered while it came up. */
    val linkProtection: Atc3LinkProtection get() = atc3BLE.linkProtection

    /** True when a serial number is configured: no request can be built without one. */
    val isConfigured: Boolean get() = Atc3Frame.isValidSerial(preferences.get(Atc3StringKey.Atc3SerialNumber))

    /**
     * Refusals of the Bluetooth password in a row, and the password refused: after
     * [Atc3Const.AUTH_MAX_ATTEMPTS] the driver stops asking until the password is changed.
     */
    @Volatile private var authFailures = 0
    @Volatile private var authFailedFor: String? = null

    /** Why the link is being closed, for the line that reports what the connection cost. */
    @Volatile private var closeReason: String = "link"

    /** True once the user has been told that no password is entered, until one is. */
    private var passwordMissingSaid = false

    // The password

    /** Whether a Bluetooth password is entered in AAPS: without one the driver does not work with the pump. */
    val isPasswordEntered: Boolean
        get() = (preferences.get(Atc3StringKey.Atc3BtPassword) as String?)?.let { Atc3BtPassword.isValid(it) } == true

    /** Whether the driver may work with the pump: a password is entered. When none is, the user is told once. */
    fun checkPassword(): Boolean {
        if (isPasswordEntered) {
            passwordMissingSaid = false
            return true
        }
        refuseWithoutPassword()
        return false
    }

    /**
     * No password entered, no work with the pump, not even to find out whether it asks for one: the pump
     * lets in a link rather than a client. A pump with no password is entered as `000000`.
     */
    private fun refuseWithoutPassword() {
        aapsLogger.error(LTag.PUMP, "ATC3: no Bluetooth password is entered in AAPS, not working with the pump")
        if (passwordMissingSaid) return
        passwordMissingSaid = true
        trace.event(Atc3TraceCat.SESS, "no_password")
        uiInteraction.addNotification(Notification.PUMP_ERROR, rh.gs(R.string.atc3_password_missing), Notification.URGENT)
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
    }

    // Bringing the link up and letting it go

    fun connect(reason: String): Boolean {
        aapsLogger.debug(LTag.PUMP, "ATC3: connect, reason $reason")
        if (!isConfigured) {
            aapsLogger.error(LTag.PUMP, "ATC3: serial number is not configured, cannot connect")
            return false
        }
        if (!checkPassword()) return false
        if (pairing.refused) {
            aapsLogger.debug(LTag.PUMP, "ATC3: the pump on trial refused its password, not trying again")
            rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
            return false
        }
        // A refused attempt is not a wakeup of the pump and is not traced as one.
        val waiting = atc3BLE.backoffRemainingMs
        if (waiting > 0) {
            aapsLogger.debug(LTag.PUMP, "ATC3: still waiting ${waiting}ms before trying the link again")
            rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
            return false
        }
        // A link already up is not a new one.
        if (isConnected || isConnecting) {
            trace.event(Atc3TraceCat.SESS, "reuse", "reason" to reason, "connected" to isConnected)
        } else {
            trace.sessionOpen(reason)
        }
        closeReason = reason
        atc3BLE.setCallback(this)
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTING))
        // Told before every attempt: the user can change the password between two connections.
        val password = preferences.get(Atc3StringKey.Atc3BtPassword)
        // A different password is a different question, with tries of its own.
        if (password != authFailedFor) {
            authFailures = 0
            authFailedFor = password
        }
        if (authFailures >= Atc3Const.AUTH_MAX_ATTEMPTS) {
            aapsLogger.error(
                LTag.PUMP,
                "ATC3: not connecting, the pump refused this Bluetooth password $authFailures times"
            )
            trace.event(Atc3TraceCat.SESS, "auth_locked", "after" to authFailures)
            rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
            return false
        }
        atc3BLE.setPassword(password)
        val started = atc3BLE.connect(preferences.get(Atc3StringKey.Atc3Address))
        if (!started) rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
        return started
    }

    /**
     * Let go of the pump, and decide whether that means letting go of the link: AAPS asks for a
     * disconnection every time its queue runs dry, and setting a link up is the fragile part. An idle
     * queue releases the pump and the link is held; any other reason, unrecognised ones included,
     * drops it. A held link is watched through the pump's heartbeat.
     */
    fun disconnect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "ATC3: disconnect, reason $reason")
        if (reason == QUEUE_EMPTY_REASON && preferences.get(Atc3BooleanKey.HoldLink) && atc3BLE.isConnected) {
            trace.event(
                Atc3TraceCat.SESS, "link_kept",
                "reason" to reason,
                "quietMs" to atc3BLE.quietForMs
            )
            // Not logged: this happens every few minutes.
            return
        }
        drop(reason)
    }

    /** Drop the link whatever AAPS would rather: found dead, or no password. */
    fun drop(reason: String) {
        stopLivenessWatch()
        closeReason = reason
        atc3BLE.disconnect()
    }

    // Watching a held link for silence

    private val livenessExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Atc3Liveness").apply { isDaemon = true }
    }

    @Volatile private var livenessWatch: ScheduledFuture<*>? = null

    /**
     * How a quiet link is questioned: by a command on the AAPS queue, so that the question takes its
     * turn among the exchanges. Set by the driver.
     */
    @Volatile var askForProbe: (() -> Unit)? = null

    /**
     * Whether a quiet link is to be questioned now.
     *
     * @param quietForMs how long the pump has been silent, -1 when it has never been heard
     * @param connected  whether there is a link to question
     * @param busy       whether an exchange is running, which proves the link by itself
     */
    fun shouldProbeQuietLink(quietForMs: Long, connected: Boolean, busy: Boolean): Boolean =
        connected && !busy && quietForMs >= QUIET_BEFORE_PROBE_MS

    /**
     * Notice a quiet link and have the pump asked whether it is still there, through [askForProbe]:
     * a missed heartbeat cannot be asked for again, a read can. Asked again while the silence lasts;
     * the queue keeps one such question at a time.
     */
    @Synchronized
    private fun startLivenessWatch() {
        if (livenessWatch != null) return
        livenessWatch = livenessExecutor.scheduleWithFixedDelay({
            runCatching {
                if (!atc3BLE.isConnected) {
                    stopLivenessWatch()
                    return@runCatching
                }
                val quiet = atc3BLE.quietForMs
                if (!shouldProbeQuietLink(quiet, atc3BLE.isConnected, listener?.isBusy == true)) return@runCatching
                val ask = askForProbe ?: return@runCatching

                trace.event(Atc3TraceCat.BLE, "quiet", "ms" to quiet)
                aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: the pump has been quiet for ${quiet}ms, having it asked something")
                ask()
            }.onFailure { aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the liveness check failed", it) }
        }, LIVENESS_CHECK_MS, LIVENESS_CHECK_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun stopLivenessWatch() {
        livenessWatch?.cancel(false)
        livenessWatch = null
    }

    // BLE callbacks

    override fun onConnected() {
        trace.event(Atc3TraceCat.SESS, "ready")
        // Watched for as long as there is a link.
        startLivenessWatch()
        // The stored password is the one just accepted: no second candidate any more.
        preferences.put(Atc3StringKey.Atc3BtPasswordAlternate, "")
        listener?.onLinkUp()
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTED))
    }

    override fun onDisconnected() {
        stopLivenessWatch()
        trace.sessionClose(closeReason)
        listener?.onLinkDown()
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
    }

    override fun onDataReceived(chunk: ByteArray) {
        listener?.onDataReceived(chunk)
    }

    override fun onSendError(reason: String) {
        aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: send error, $reason")
        trace.event(Atc3TraceCat.BLE, "send_error", "why" to reason)
    }

    /** The pump refused the password: said by a notification, since nothing improves by itself and only the pump shows the right one. */
    override fun onAuthenticationRejected() {
        if (pairing.inProgress) {
            // The connection screen says it itself and drops the password.
            aapsLogger.error(LTag.PUMP, "ATC3: the pump refused the password entered for pairing")
            trace.event(Atc3TraceCat.SESS, "auth_rejected", "pairing" to true)
            pairing.noteRefused()
            rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
            return
        }
        if (tryAlternatePassword()) return
        authFailures++
        val givenUp = authFailures >= Atc3Const.AUTH_MAX_ATTEMPTS
        aapsLogger.error(
            LTag.PUMP,
            "ATC3: the pump refused the configured Bluetooth password, attempt $authFailures" +
                if (givenUp) ", not trying again until it is changed" else ""
        )
        trace.event(Atc3TraceCat.SESS, "auth_rejected", "attempt" to authFailures, "givenUp" to givenUp)
        uiInteraction.addNotification(
            Notification.PUMP_ERROR,
            rh.gs(if (givenUp) R.string.atc3_password_given_up else R.string.atc3_password_rejected),
            Notification.URGENT
        )
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
    }

    /**
     * After a password change, try the other candidate once rather than give up: the pump can have
     * taken the other reading, and the password cannot be read back. The fallback is cleared either way.
     *
     * @return true when a new value was stored and the next connection is to try again
     */
    private fun tryAlternatePassword(): Boolean {
        val alternate = preferences.get(Atc3StringKey.Atc3BtPasswordAlternate)
        preferences.put(Atc3StringKey.Atc3BtPasswordAlternate, "")
        if (alternate.isBlank() || alternate == preferences.get(Atc3StringKey.Atc3BtPassword)) return false
        aapsLogger.warn(
            LTag.PUMP,
            "ATC3: the pump refused the password its last change asked for, presenting the payload value instead"
        )
        trace.event(Atc3TraceCat.SESS, "auth_alternate")
        preferences.put(Atc3StringKey.Atc3BtPassword, alternate)
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
        return true
    }

    companion object {

        /** What AAPS says when its queue has nothing left; matched exactly, see [disconnect]. */
        const val QUEUE_EMPTY_REASON = "Queue empty"

        /** How long a held link may be silent before the pump is asked: one missed heartbeat and a minute. */
        private const val QUIET_BEFORE_PROBE_MS = 240_000L
        private const val LIVENESS_CHECK_MS = 60_000L
    }
}
