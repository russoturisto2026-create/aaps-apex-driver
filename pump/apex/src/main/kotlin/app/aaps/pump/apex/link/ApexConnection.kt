package app.aaps.pump.apex.link

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.ApexConst
import app.aaps.pump.apex.R
import app.aaps.pump.apex.keys.ApexBooleanKey
import app.aaps.pump.apex.keys.ApexStringKey
import app.aaps.pump.apex.protocol.ApexFrame
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * The link to the pump: bringing it up and letting it go, and the Bluetooth password it is brought up
 * under. What travels over it is the [Listener]'s. A held link that went quiet is found by the watch
 * over the heartbeat, see `HeartbeatOverdueDeep`.
 */
@SingleIn(AppScope::class)
@Inject
class ApexConnection(
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val preferences: Preferences,
    private val apexBLE: ApexBLE,
    private val trace: ApexTrace,
    private val notificationManager: NotificationManager,
    private val rh: ResourceHelper,
    private val pairing: ApexPairing
) : ApexBleCallback {

    /** Carries the exchanges over the link, see `ApexExchange`. */
    interface Listener {

        /** The link is up: nothing of the last one is owed on it. */
        fun onLinkUp()

        /** The link is gone: whatever waits for an answer is released now. */
        fun onLinkDown()

        /** One notification, as received. */
        fun onDataReceived(chunk: ByteArray)
    }

    @Volatile var listener: Listener? = null

    val isConnected: Boolean get() = apexBLE.isConnected
    val isConnecting: Boolean get() = apexBLE.isConnecting

    /** True when a serial number is configured: no request can be built without one. */
    val isConfigured: Boolean get() = ApexFrame.isValidSerial(preferences.get(ApexStringKey.ApexSerialNumber))

    /**
     * Refusals of the Bluetooth password in a row, and the password refused: after
     * [ApexConst.AUTH_MAX_ATTEMPTS] the driver stops asking until the password is changed.
     */
    @Volatile private var authFailures = 0
    @Volatile private var authFailedFor: String? = null

    /** Why the link is being closed, for the line that reports what the connection cost. */
    @Volatile private var closeReason: String = "link"

    /** True once the user has been told that no password is entered, until one is. */
    @Volatile private var passwordMissingSaid = false

    /** Why the last ask for a link was refused, null when it went to the pump: what has been said. */
    @Volatile private var refusedFor: Refusal? = null

    /**
     * Why an ask for a link is refused. The queue asks once a second while it waits, so each reason is
     * said once, when it begins, see [tell].
     *
     * @param message what the log says
     * @param isError true for a reason the user has to act on
     */
    private enum class Refusal(val message: String, val isError: Boolean) {

        NO_SERIAL("serial number is not configured, cannot connect", true),
        NO_PASSWORD("no Bluetooth password is entered in AAPS, not working with the pump", true),
        PAIRING_REFUSED("the pump on trial refused its password, not trying again", false),
        NO_BLUETOOTH("the phone has no Bluetooth", true),
        BLUETOOTH_OFF("the phone's Bluetooth is off, not connecting to the pump", true),
        WAITING("waiting before trying the link again", false),
        PASSWORD_GIVEN_UP("the pump refused this Bluetooth password too many times, not trying again until it is changed", true),
        NO_ADDRESS("no pump address configured", true),
        BAD_ADDRESS("the pump address is not a Bluetooth address", true)
    }

    // The password

    /** Whether a Bluetooth password is entered in AAPS: without one the driver does not work with the pump. */
    val isPasswordEntered: Boolean
        get() = (preferences.get(ApexStringKey.ApexBtPassword) as String?)?.let { ApexBtPassword.isValid(it) } == true

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
        if (passwordMissingSaid) return
        passwordMissingSaid = true
        aapsLogger.error(LTag.PUMP, "Apex: ${Refusal.NO_PASSWORD.message}")
        trace.event(ApexTraceCat.SESS, "no_password")
        notificationManager.post(NotificationId.PUMP_ERROR, rh.gs(R.string.apex_password_missing), level = NotificationLevel.URGENT)
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
    }

    // Bringing the link up and letting it go

    /**
     * Why an ask for a link is refused now, null when it may go to the pump. It asks the pump nothing and
     * tells nobody: what is said is decided in [tell].
     */
    private fun refusalNow(password: String, address: String): Refusal? = when {
        !isConfigured                                                      -> Refusal.NO_SERIAL
        !isPasswordEntered                                                 -> Refusal.NO_PASSWORD
        pairing.refused                                                    -> Refusal.PAIRING_REFUSED
        !apexBLE.hasBluetooth                                              -> Refusal.NO_BLUETOOTH
        // Before the wait, so the user hears of it at once.
        !apexBLE.isBluetoothOn                                             -> Refusal.BLUETOOTH_OFF
        // A refused attempt is not a wakeup of the pump and is not traced as one.
        apexBLE.backoffRemainingMs > 0                                     -> Refusal.WAITING
        password == authFailedFor && authFailures >= ApexConst.AUTH_MAX_ATTEMPTS -> Refusal.PASSWORD_GIVEN_UP
        address.isBlank()                                                  -> Refusal.NO_ADDRESS
        !apexBLE.isValidAddress(address)                                   -> Refusal.BAD_ADDRESS
        else                                                               -> null
    }

    /**
     * Say why asks are refused, once, when the reason begins, and take back what was said of the reason
     * it follows. Null is an ask that went to the pump: the next refusal is said again.
     */
    private fun tell(refusal: Refusal?) {
        val before = refusedFor
        if (refusal == before) return
        refusedFor = refusal
        when (before) {
            Refusal.BLUETOOTH_OFF -> notificationManager.dismiss(NotificationId.BLUETOOTH_NOT_ENABLED)
            // The next time it is missing is told again.
            Refusal.NO_PASSWORD   -> passwordMissingSaid = false
            else                  -> {}
        }
        if (refusal == null) return
        trace.event(ApexTraceCat.SESS, "refused", "why" to refusal.name.lowercase())
        when (refusal) {
            // Shared with the exchanges, which are refused for it too: told once for both.
            Refusal.NO_PASSWORD   -> refuseWithoutPassword()
            Refusal.BLUETOOTH_OFF -> {
                aapsLogger.error(LTag.PUMP, "Apex: ${refusal.message}")
                notificationManager.post(NotificationId.BLUETOOTH_NOT_ENABLED, rh.gs(R.string.apex_bluetooth_off), level = NotificationLevel.NORMAL)
            }
            else                  ->
                if (refusal.isError) aapsLogger.error(LTag.PUMP, "Apex: ${refusal.message}")
                else aapsLogger.debug(LTag.PUMP, "Apex: ${refusal.message}")
        }
    }

    /**
     * Ask for a link. The queue asks once a second while it waits: only an ask that goes to the pump is
     * logged, and a refusal once for each reason, see [Refusal].
     */
    fun connect(reason: String): Boolean {
        // Read before every attempt: the user can change it between two connections.
        val password = preferences.get(ApexStringKey.ApexBtPassword)
        // A different password is a different question, with tries of its own.
        if (password != authFailedFor) {
            authFailures = 0
            authFailedFor = password
        }
        val address = preferences.get(ApexStringKey.ApexAddress)
        val refusal = refusalNow(password, address)
        tell(refusal)
        if (refusal != null) {
            rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
            return false
        }
        aapsLogger.debug(LTag.PUMP, "Apex: connect, reason $reason")
        // A link already up is not a new one.
        if (isConnected || isConnecting) {
            trace.event(ApexTraceCat.SESS, "reuse", "reason" to reason, "connected" to isConnected)
        } else {
            trace.sessionOpen(reason)
        }
        closeReason = reason
        apexBLE.setCallback(this)
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTING))
        apexBLE.setPassword(password)
        val started = apexBLE.connect(address)
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
        aapsLogger.debug(LTag.PUMP, "Apex: disconnect, reason $reason")
        if (reason == QUEUE_EMPTY_REASON && preferences.get(ApexBooleanKey.HoldLink) && apexBLE.isConnected) {
            trace.event(
                ApexTraceCat.SESS, "link_kept",
                "reason" to reason,
                "quietMs" to apexBLE.quietForMs
            )
            // Not logged: this happens every few minutes.
            return
        }
        drop(reason)
    }

    /** Drop the link whatever AAPS would rather: found dead, or no password. */
    fun drop(reason: String) {
        closeReason = reason
        apexBLE.disconnect()
    }

    /**
     * What the pump's heartbeat starts: a read through the AAPS queue, so that the pump is asked
     * something at every heartbeat. Set by the driver.
     */
    var askOnHeartbeat: (() -> Unit)?
        get() = apexBLE.onHeartbeat
        set(value) {
            apexBLE.onHeartbeat = value
        }

    // BLE callbacks

    override fun onConnected() {
        trace.event(ApexTraceCat.SESS, "ready")
        // The stored password is the one just accepted: no second reading any more.
        preferences.put(ApexStringKey.ApexBtPasswordAlternate, "")
        listener?.onLinkUp()
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTED))
    }

    override fun onDisconnected() {
        trace.sessionClose(closeReason)
        listener?.onLinkDown()
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
    }

    override fun onDataReceived(chunk: ByteArray) {
        listener?.onDataReceived(chunk)
    }

    override fun onSendError(reason: String) {
        aapsLogger.error(LTag.PUMPBTCOMM, "Apex: send error, $reason")
        trace.event(ApexTraceCat.BLE, "send_error", "why" to reason)
    }

    /** The pump refused the password: said by a notification, since nothing improves by itself and only the pump shows the right one. */
    override fun onAuthenticationRejected() {
        if (pairing.inProgress) {
            // The connection screen says it itself and drops the password.
            aapsLogger.error(LTag.PUMP, "Apex: the pump refused the password entered for pairing")
            trace.event(ApexTraceCat.SESS, "auth_rejected", "pairing" to true)
            pairing.noteRefused()
            rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
            return
        }
        if (tryAlternatePassword()) return
        authFailures++
        val givenUp = authFailures >= ApexConst.AUTH_MAX_ATTEMPTS
        aapsLogger.error(
            LTag.PUMP,
            "Apex: the pump refused the configured Bluetooth password, attempt $authFailures" +
                if (givenUp) ", not trying again until it is changed" else ""
        )
        trace.event(ApexTraceCat.SESS, "auth_rejected", "attempt" to authFailures, "givenUp" to givenUp)
        notificationManager.post(NotificationId.PUMP_ERROR, rh.gs(if (givenUp) R.string.apex_password_given_up else R.string.apex_password_rejected), level = NotificationLevel.URGENT)
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
    }

    /**
     * After a password change, try the other reading once rather than give up: the pump can have
     * taken the payload literally, and the password cannot be read back. The second reading is
     * cleared either way.
     *
     * @return true when a new value was stored and the next connection is to try it
     */
    private fun tryAlternatePassword(): Boolean {
        val alternate = preferences.get(ApexStringKey.ApexBtPasswordAlternate)
        preferences.put(ApexStringKey.ApexBtPasswordAlternate, "")
        if (alternate.isBlank() || alternate == preferences.get(ApexStringKey.ApexBtPassword)) return false
        aapsLogger.warn(LTag.PUMP, "Apex: the pump refused the password its last change asked for, presenting the payload read literally instead")
        trace.event(ApexTraceCat.SESS, "auth_alternate")
        preferences.put(ApexStringKey.ApexBtPassword, alternate)
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
        return true
    }

    companion object {

        /** What AAPS says when its queue has nothing left; matched exactly, see [disconnect]. */
        const val QUEUE_EMPTY_REASON = "Queue empty"
    }
}
