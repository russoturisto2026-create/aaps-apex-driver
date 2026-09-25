package app.aaps.pump.atc3.manager

import android.os.SystemClock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.Atc3Pump
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.ble.Atc3BLE
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import app.aaps.pump.atc3.ble.Atc3BleCallback
import app.aaps.pump.atc3.comm.Atc3AlarmRecord
import app.aaps.pump.atc3.comm.Atc3BasalChangeRecord
import app.aaps.pump.atc3.comm.Atc3BasalProfile
import app.aaps.pump.atc3.comm.Atc3BolusCalculator
import app.aaps.pump.atc3.comm.Atc3BolusHistory
import app.aaps.pump.atc3.comm.Atc3BolusRecord
import app.aaps.pump.atc3.comm.Atc3BtPassword
import app.aaps.pump.atc3.comm.Atc3DailyStats
import app.aaps.pump.atc3.comm.Atc3FinishedTbr
import app.aaps.pump.atc3.comm.Atc3Frame
import app.aaps.pump.atc3.comm.Atc3LinkProtection
import app.aaps.pump.atc3.comm.Atc3RefillRecord
import app.aaps.pump.atc3.comm.Atc3ResponseFrame
import app.aaps.pump.atc3.comm.Atc3ResponseParser
import app.aaps.pump.atc3.comm.Atc3Settings
import app.aaps.pump.atc3.comm.Atc3StatusV1
import app.aaps.pump.atc3.comm.Atc3StatusV2
import app.aaps.pump.atc3.comm.Atc3TbrRecord
import app.aaps.pump.atc3.comm.Atc3TbrShort
import app.aaps.pump.atc3.comm.Atc3TbrStatus
import app.aaps.pump.atc3.comm.Atc3Version
import app.aaps.pump.atc3.events.EventAtc3PumpDataChanged
import app.aaps.pump.atc3.history.Atc3ClockWatch
import app.aaps.pump.atc3.keys.Atc3IntNonKey
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.pump.atc3.keys.Atc3StringKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection and protocol orchestration.
 *
 * Requests are sent and then awaited, so callers on the command queue thread get a definite
 * outcome. Nothing is reported as successful on the strength of an acknowledgement alone: a
 * control operation is confirmed by reading the pump's own state back afterwards.
 */
@Singleton
class Atc3Manager @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val atc3BLE: Atc3BLE,
    private val atc3Pump: Atc3Pump,
    private val trace: Atc3Trace,
    private val uiInteraction: UiInteraction,
    private val clockWatch: Atc3ClockWatch,
    private val rh: ResourceHelper
) : Atc3BleCallback {

    private val parser = Atc3ResponseParser()

    /** Profiles collected from the current read, keyed by pump profile index. */
    private val collectedProfiles = HashMap<Int, DoubleArray>()

    private val waitLock = Any()
    private var pendingMatch: ((Atc3ResponseFrame) -> Boolean)? = null
    private var pendingLatch: CountDownLatch? = null

    /** Recognises the frames that make up the answer being waited for, when it comes in a burst. */
    private var pendingBurst: ((Atc3ResponseFrame) -> Boolean)? = null
    private var burstFrames: Int = 0
    private var burstLastFrameAt: Long = 0L

    /** The connection a foreign answer was last reported for, so it is said once and then counted. */
    private var foreignReportedFor: Long = -1L

    /** Tells this exchange's answer from a late one belonging to a finished exchange. */
    private val answerGate = Atc3AnswerGate()

    /**
     * Consecutive refusals of the Bluetooth password, and the password they were refusals of.
     *
     * A wrong password does not come right by being tried again, and the pump is the only place the
     * right one can be read, so after [Atc3Const.AUTH_MAX_ATTEMPTS] the driver stops asking rather
     * than holding the radio and the wake lock for a question with a known answer. Changing the
     * password in the settings is what starts it over: that is a different question.
     *
     * Not remembered across restarts on purpose. A driver that came back up already given up would
     * have no way to say why, and starting over costs ten cheap refusals at worst.
     */
    @Volatile private var authFailures = 0
    @Volatile private var authFailedFor: String? = null


    /** Set when the link dropped while an exchange was waiting, so the wait can fail rather than pass. */
    @Volatile private var linkLost: Boolean = false

    /**
     * Why the link is being closed, for the line that reports what the connection cost.
     *
     * The stack reports a drop without saying who asked for it, so whoever asked leaves the reason
     * here on the way past. A drop nobody asked for keeps whatever the connection was opened for,
     * which is the right thing to see in the report.
     */
    @Volatile private var closeReason: String = "link"

    /**
     * Holds an exchange from the moment a request goes out until its answer has arrived.
     *
     * The pump works strictly in request and answer pairs, so exactly one exchange may be in
     * progress at a time. Sending anything while an earlier request is still unanswered breaks the
     * conversation: the Bluetooth stack refuses the second write outright.
     */
    private val exchangeLock = ReentrantLock()

    /** Set when the pump answered the last control command with a refusal. */
    @Volatile private var rejected: Boolean = false

    val isConnected: Boolean get() = atc3BLE.isConnected
    val isConnecting: Boolean get() = atc3BLE.isConnecting

    /** How well the last link was protected, as the pump itself answered while it came up. */
    val linkProtection: Atc3LinkProtection get() = atc3BLE.linkProtection

    /**
     * True while the pump is in the middle of something and will not take another command.
     *
     * Both of these are guaranteed to clear, and that matters more than it looks: the command
     * queue's own watchdog only rescues it when the pump is **not** connected
     * (`QueueWorker`: the elapsed-time check sits behind `!pump.isConnected()`), so a flag that
     * stuck while connected would spin the queue for ever and leave the pump uncontrollable.
     * [exchangeLock] is released in the `finally` of [exchange], and [bolusInProgress] in the
     * `finally` of [bolus].
     *
     * Connecting is not reported as busy. It is the one state where saying "not ready" breaks
     * AAPS's keepalive.
     */
    val isBusy: Boolean get() = exchangeLock.isLocked || bolusInProgress

    /** True when a valid serial number is configured, without which no request can be built. */
    val isConfigured: Boolean get() = runCatching { identity() }.isSuccess

    /**
     * The words AAPS uses when it has nothing left to send.
     *
     * Matched exactly on purpose; see [disconnect] for why anything else is taken at face value.
     */
    private val QUEUE_EMPTY_REASON = "Queue empty"

    private val livenessExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Atc3Liveness").apply { isDaemon = true }
    }

    @Volatile private var livenessWatch: ScheduledFuture<*>? = null

    /** How many exchanges in a row have reached nothing at all. See [noteReachability]. */
    @Volatile private var unreachable = 0

    /**
     * Should a silent link be questioned right now?
     *
     * Separated from the scheduling around it so the rule can be read, and tested, on its own.
     *
     * @param quietForMs how long the pump has been silent, -1 when it has never been heard
     * @param connected  whether there is a link to question
     * @param busy       whether an exchange is already running, which proves the link by itself
     */
    internal fun shouldProbeQuietLink(quietForMs: Long, connected: Boolean, busy: Boolean): Boolean =
        connected && !busy && quietForMs >= QUIET_BEFORE_PROBE_MS

    /**
     * Ask a silent pump whether it is still there, and only then decide about the link.
     *
     * The heartbeat cannot be requested - it is the pump's own initiative every 180 s - so a
     * silence cannot be retried, only waited out. What can be retried is a question of ours, and
     * that is what a silence is worth: one cheap read. If it is answered the link was fine and a
     * beat was merely lost; if it is not, the link is gone and holding on to it would leave AAPS
     * believing its commands were reaching a pump that is not listening.
     *
     * Asking rather than assuming is also why the threshold is one missed beat and not two: a false
     * alarm costs a single read, so the link can be questioned early, and silence is noticed within
     * about four minutes.
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
                if (!shouldProbeQuietLink(quiet, atc3BLE.isConnected, isBusy)) return@runCatching

                trace.event(Atc3TraceCat.BLE, "quiet", "ms" to quiet)
                aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: the pump has been quiet for ${quiet}ms, asking it something")
                val answered = readStatus()
                trace.event(Atc3TraceCat.BLE, "probe", "ok" to answered, "quietMs" to quiet)
                if (answered) {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: the pump answered, the link is alive")
                    return@runCatching
                }
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the pump did not answer, dropping the link")
                trace.event(Atc3TraceCat.BLE, "link_lost", "quietMs" to quiet)
                closeReason = "no answer on a quiet link"
                atc3BLE.disconnect()
                stopLivenessWatch()
            }.onFailure { aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the liveness check failed", it) }
        }, LIVENESS_CHECK_MS, LIVENESS_CHECK_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun stopLivenessWatch() {
        livenessWatch?.cancel(false)
        livenessWatch = null
    }

    fun connect(reason: String): Boolean {
        aapsLogger.debug(LTag.PUMP, "ATC3: connect, reason $reason")
        if (!isConfigured) {
            aapsLogger.error(LTag.PUMP, "ATC3: serial number is not configured, cannot connect")
            return false
        }
        // A refused attempt costs the pump nothing, so it is not a wakeup and is not counted as
        // one. Opening a connection here would leave a session in the trace that never happened.
        val waiting = atc3BLE.backoffRemainingMs
        if (waiting > 0) {
            aapsLogger.debug(LTag.PUMP, "ATC3: still waiting ${waiting}ms before trying the link again")
            rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
            return false
        }
        // A connection that is already up is not a new one, and counting it as one would make the
        // report show twice the wakeups the pump actually had.
        if (isConnected || isConnecting) {
            trace.event(Atc3TraceCat.SESS, "reuse", "reason" to reason, "connected" to isConnected)
        } else {
            trace.sessionOpen(reason)
        }
        closeReason = reason
        atc3BLE.setCallback(this)
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTING))
        // The pump refuses everything until it has been given its Bluetooth password, so the
        // transport is told the current one before every attempt rather than once at startup: the
        // user can change it between two connections and the next one has to use the new value.
        val password = preferences.get(Atc3StringKey.Atc3BtPassword)
        // A different password is a different question, so it gets its own ten tries.
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
     * Let go of the pump, and decide whether that means letting go of the link.
     *
     * AAPS asks for a disconnection whenever its command queue runs dry, which is every few
     * minutes, all day. Obeyed literally, that sets a link up hundreds of times a day, and setting
     * one up is the fragile part, not using it: a link can come up and still fail to finish its
     * setup.
     *
     * So an idle queue releases the pump without dropping the link. Everything else still drops
     * it: the Bluetooth watchdog needs the link genuinely gone to do its work, a failure has to be
     * able to clear the state behind it, and anything the driver does not recognise is treated as a
     * real disconnection rather than assumed harmless. If AAPS ever words its idle reason
     * differently, this falls back to an ordinary disconnection instead of quietly holding a link
     * nobody asked it to hold.
     *
     * What keeps a held link honest is the pump's own heartbeat, see Atc3BLE.noteHeartbeat.
     */
    fun disconnect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "ATC3: disconnect, reason $reason")
        if (reason == QUEUE_EMPTY_REASON && preferences.get(Atc3BooleanKey.HoldLink) && atc3BLE.isConnected) {
            trace.event(
                Atc3TraceCat.SESS, "link_kept",
                "reason" to reason,
                "quietMs" to atc3BLE.quietForMs
            )
            // Deliberately not logged: this happens every few minutes for as long as the driver
            // runs, and the trace line above already records it for anyone who wants it.
            return
        }
        stopLivenessWatch()
        closeReason = reason
        atc3BLE.disconnect()
    }

    // Reads

    /**
     * Read Status V1, the frame most of what the driver knows comes from.
     *
     * Status V2 is a separate request, [readStatusV2]: the two are different objects and the pump
     * answers them one at a time, like everything else here.
     */
    fun readStatus(): Boolean = sendAndWait(Atc3Const.ReadOpcode.STATUS_V1) {
        it.frameId == Atc3Const.MODE_HISTORY && it.objectType == Atc3Const.ReadOpcode.STATUS_V1
    }

    /**
     * Read the pump's firmware and protocol versions.
     *
     * Worth one exchange per connection because the firmware version is what says whether this pump
     * can be asked for a Bluetooth password at all, and a pump that cannot is one whose link nobody
     * can protect. Everything else in the driver works without it.
     */
    fun readVersion(): Boolean = sendAndWait(Atc3Const.ReadOpcode.VERSION) {
        it.frameId == Atc3Const.MODE_HISTORY && it.objectType == Atc3Const.ReadOpcode.VERSION
    }

    /**
     * Read Status V2, which is where the battery voltage lives.
     *
     * Nothing else in it is decoded, but the battery is the one thing a driver cannot get any other
     * way and the one the user asks about first.
     */
    fun readStatusV2(): Boolean = sendAndWait(Atc3Const.ReadOpcode.STATUS_V2) {
        it.frameId == Atc3Const.MODE_HISTORY && it.objectType == Atc3Const.ObjectType.STATUS_V2
    }

    /**
     * The temporary basal the pump reported on the last [readActiveTbr], or null when none was
     * running or the read failed.
     */
    @Volatile var activeTbr: Atc3TbrStatus? = null
        private set

    /**
     * Read the running temporary basal, `35/A3/0A`, for the one thing Status V1 does not carry:
     * when it started.
     *
     * Worth an exchange only while the status says one is running. A temporary basal somebody
     * started on the pump would otherwise be recorded as beginning when the driver first noticed
     * it, which at a fifteen minute poll interval loses a quarter of an hour of altered basal.
     *
     * **Only call this when Status V1 says a temporary basal is running.** The object does not stop
     * answering when one ends: it keeps returning the last temporary basal in full, byte for byte,
     * while Status V1 reports none active. So a full 28 byte answer is not proof that anything is
     * running, and this function cannot tell the difference; its callers check the flag first.
     *
     * @return true when a record arrived
     */
    fun readActiveTbr(): Boolean {
        activeTbr = null
        val answered = sendAndWait(Atc3Const.ReadOpcode.TBR_ACTIVE) {
            it.frameId == Atc3Const.MODE_HISTORY && it.objectType == Atc3Const.ObjectType.TBR_ACTIVE
        }
        return answered && activeTbr != null
    }

    /** Alarm records collected by the current read, guarded by [waitLock]. */
    private val alarmBurst = ArrayList<Atc3AlarmRecord>()
    private var alarmBurstArmed = false

    /**
     * Read the pump's alarm history, `55/A3/03`.
     *
     * This is the only place an occlusion is reported: the pressure figure the pump draws on its
     * own screen is never sent. What is being raised at this moment is in Status V1 instead, and
     * neither of them says anything about delivery — see [app.aaps.pump.atc3.comm.Atc3Alarm].
     *
     * The base object is asked for rather than its `0x23` alias, because an alias stops at ten
     * frames whatever count it declares, and the history can hold more than ten records.
     *
     * @return every record the pump holds, newest first, or null when the read failed
     */
    fun readAlarmHistory(): List<Atc3AlarmRecord>? {
        synchronized(waitLock) {
            alarmBurst.clear()
            alarmBurstArmed = true
        }
        val isAlarmRecord = { frame: Atc3ResponseFrame ->
            frame.frameId == Atc3Const.MODE_HISTORY && frame.objectType == Atc3Const.ObjectType.ALARM_RECORD
        }
        val answered = try {
            exchange(
                "alarm history",
                match = { isAlarmRecord(it) && it.isLastRecord },
                burstOf = isAlarmRecord
            ) {
                send(
                    Atc3Const.GROUP_QUERY, Atc3Const.MODE_HISTORY, Atc3Const.HistoryOpcode.ALARM_HISTORY,
                    Atc3Frame.NO_PARAMETER
                )
            }
        } finally {
            synchronized(waitLock) { alarmBurstArmed = false }
        }
        if (!answered) return null
        return synchronized(waitLock) { ArrayList(alarmBurst) }
    }

    /** Temporary basal records collected by the current read, guarded by [waitLock]. */
    private val tbrRecordBurst = ArrayList<Atc3TbrRecord>()
    private var tbrRecordBurstArmed = false

    /**
     * Read the pump's temporary basal history, `35/A3/27`.
     *
     * The journal of the basal rate: one record per temporary basal that has finished, each saying
     * what it actually delivered. It answers the one question the reservoir arithmetic cannot —
     * what rate was really running between two polls — because a temporary basal started and
     * cancelled while the driver was not looking leaves no trace in any other object.
     *
     * The answer stops at [Atc3Const.ALIAS_BURST_FRAMES] frames however many records the pump
     * declares, in common with every alias, so the burst is finished by counting rather than by
     * waiting for a record that flags itself last.
     *
     * @return the records as the pump sends them, or null when the read failed
     */
    fun readTbrHistory(): List<Atc3TbrRecord>? {
        synchronized(waitLock) {
            tbrRecordBurst.clear()
            tbrRecordBurstArmed = true
        }
        val isTbrRecord = { frame: Atc3ResponseFrame ->
            frame.frameId == Atc3Const.MODE_HISTORY && frame.objectType == Atc3Const.ObjectType.TBR_RECORD
        }
        val answered = try {
            exchange(
                "temporary basal history",
                match = { isTbrRecord(it) && it.isLastRecord },
                burstOf = isTbrRecord,
                burstFrameCap = Atc3Const.ALIAS_BURST_FRAMES
            ) {
                send(
                    Atc3Const.GROUP_CONTROL, Atc3Const.MODE_HISTORY, Atc3Const.HistoryOpcode.TBR_HISTORY,
                    Atc3Const.PARAMETER_LATEST
                )
            }
        } finally {
            synchronized(waitLock) { tbrRecordBurstArmed = false }
        }
        if (!answered) return null
        return synchronized(waitLock) { ArrayList(tbrRecordBurst) }
    }

    /** Basal change records collected by the current read, guarded by [waitLock]. */
    private val basalChangeBurst = ArrayList<Atc3BasalChangeRecord>()
    private var basalChangeBurstArmed = false

    /**
     * Read the pump's basal change history, `55/A3/02`.
     *
     * One record per moment the effective schedule changed, each carrying the whole schedule as it
     * stood afterwards. The only object that shows an edit to the rates of the profile in use:
     * Status V1 gives the active index and `0x08` gives what the profiles hold now, so between them
     * a changed rate inside the profile already selected is invisible.
     *
     * The base object is asked for rather than its `0x22` alias, because an alias stops at ten
     * frames however many records exist. Records are 110 bytes, which makes this the most expensive
     * read the driver has.
     *
     * @return the records, or null when the read failed
     */
    fun readBasalChangeHistory(): List<Atc3BasalChangeRecord>? {
        synchronized(waitLock) {
            basalChangeBurst.clear()
            basalChangeBurstArmed = true
        }
        val isBasalChange = { frame: Atc3ResponseFrame ->
            frame.frameId == Atc3Const.MODE_HISTORY &&
                frame.objectType == Atc3Const.ObjectType.BASAL_CHANGE_RECORD
        }
        val answered = try {
            exchange(
                "basal change history",
                match = { isBasalChange(it) && it.isLastRecord },
                burstOf = isBasalChange
            ) {
                send(
                    Atc3Const.GROUP_QUERY, Atc3Const.MODE_HISTORY,
                    Atc3Const.HistoryOpcode.BASAL_CHANGE_HISTORY, Atc3Frame.NO_PARAMETER
                )
            }
        } finally {
            synchronized(waitLock) { basalChangeBurstArmed = false }
        }
        if (!answered) return null
        return synchronized(waitLock) { ArrayList(basalChangeBurst) }
    }

    /** Reservoir refill records collected by the current read, guarded by [waitLock]. */
    private val refillBurst = ArrayList<Atc3RefillRecord>()
    private var refillBurstArmed = false

    /**
     * Read the pump's reservoir refill history, `55/A3/04`.
     *
     * The only place a refill date is reported, and the only place a catheter prime is recorded at
     * all — a prime's insulin is in neither the bolus history nor the daily statistics, and it does
     * leave the reservoir. It is also what tells a real refill from a plunger drawn back, which the
     * reservoir figure on its own cannot.
     *
     * An empty history answers with the ten byte frame every object uses for "nothing stored", and
     * that is a successful read of no records rather than a failure.
     *
     * @return the records, or null when the read failed
     */
    fun readRefillHistory(): List<Atc3RefillRecord>? {
        synchronized(waitLock) {
            refillBurst.clear()
            refillBurstArmed = true
        }
        val isRefill = { frame: Atc3ResponseFrame ->
            frame.frameId == Atc3Const.MODE_HISTORY && frame.objectType == Atc3Const.ObjectType.REFILL_RECORD
        }
        val answered = try {
            exchange(
                "reservoir refill history",
                match = { isRefill(it) && it.isLastRecord },
                burstOf = isRefill
            ) {
                send(
                    Atc3Const.GROUP_QUERY, Atc3Const.MODE_HISTORY, Atc3Const.HistoryOpcode.REFILL_HISTORY,
                    Atc3Frame.NO_PARAMETER
                )
            }
        } finally {
            synchronized(waitLock) { refillBurstArmed = false }
        }
        if (!answered) return null
        return synchronized(waitLock) { ArrayList(refillBurst) }
    }

    /** The last finished temporary basal, from the last [readFinishedTbr], or null. */
    @Volatile var finishedTbr: Atc3FinishedTbr? = null
        private set

    /**
     * Read the last temporary basal that finished, `35/A3/0B`.
     *
     * Status V1 says no temporary basal is running; this says when the one before it ended and
     * which of three ways ended it. The end clock is what keeps a temporary basal from being
     * recorded as having run until the poll that noticed it was over, and the result byte is the
     * only signal that one was stopped by hand.
     *
     * @return true when a record was decoded
     */
    fun readFinishedTbr(): Boolean {
        finishedTbr = null
        val answered = sendAndWait(Atc3Const.ReadOpcode.TBR_FINISHED) {
            it.frameId == Atc3Const.MODE_HISTORY && it.objectType == Atc3Const.ObjectType.TBR_FINISHED
        }
        return answered && finishedTbr != null
    }

    /** The short form of the last temporary basal command, from the last [readTbrShort], or null. */
    @Volatile var tbrShort: Atc3TbrShort? = null
        private set

    /**
     * Read the last temporary basal command in short form, `35/A3/09`.
     *
     * Two things object `0x0A` does not give: the rate a percentage temporary basal works out to,
     * and how many minutes of it have gone. Neither is needed to run the loop, both are worth
     * having when a percentage temporary basal set on the keypad has to be understood.
     *
     * It answers whether or not anything is running, so it can never be used to decide that
     * question — Status V1 offset 53 is the authority.
     *
     * @return true when a record was decoded
     */
    fun readTbrShort(): Boolean {
        tbrShort = null
        val answered = sendAndWait(Atc3Const.ReadOpcode.TBR_SHORT) {
            it.frameId == Atc3Const.MODE_HISTORY && it.objectType == Atc3Const.ObjectType.TBR_SHORT
        }
        return answered && tbrShort != null
    }

    /** The pump's own bolus calculator settings, from the last [readBolusCalculator], or null. */
    @Volatile var bolusCalculator: Atc3BolusCalculator? = null
        private set

    /**
     * Read the pump's own bolus calculator settings, `35/A3/07`.
     *
     * Not needed to run the loop: AAPS carries its own carb ratio, sensitivity and target. Worth
     * having as a second opinion on those three, held by the pump rather than the phone, which is
     * the only way to notice the two have drifted apart.
     *
     * The frame is 254 bytes against a notification payload of at most 248, so it always arrives
     * split and is put back together by declared length before it reaches the decoder.
     *
     * @return true when the settings were decoded
     */
    fun readBolusCalculator(): Boolean {
        bolusCalculator = null
        val answered = sendAndWait(Atc3Const.ReadOpcode.BOLUS_CALCULATOR) {
            it.frameId == Atc3Const.MODE_HISTORY && it.objectType == Atc3Const.ObjectType.BOLUS_CALCULATOR
        }
        return answered && bolusCalculator != null
    }

    /** Daily totals collected by the current read, guarded by [waitLock]. */
    private val dailyStats = ArrayList<Atc3DailyStats>()
    private var dailyStatsArmed = false

    /**
     * Read the pump's own daily totals, `55/A3/06`.
     *
     * The periodic daily record `35/A3/26` carries the same totals and is not used.
     *
     * @return one record per day the pump holds, or null when the read failed
     */
    fun readDailyStats(): List<Atc3DailyStats>? {
        synchronized(waitLock) {
            dailyStats.clear()
            dailyStatsArmed = true
        }
        val isDailyRecord = { frame: Atc3ResponseFrame ->
            frame.frameId == Atc3Const.MODE_HISTORY && frame.objectType == Atc3Const.ObjectType.DAILY_STATS
        }
        val answered = try {
            exchange(
                "daily statistics",
                match = { isDailyRecord(it) && it.isLastRecord },
                burstOf = isDailyRecord
            ) {
                send(
                    Atc3Const.GROUP_QUERY, Atc3Const.MODE_HISTORY, Atc3Const.HistoryOpcode.DAILY_STATS_SCREEN,
                    Atc3Frame.NO_PARAMETER
                )
            }
        } finally {
            synchronized(waitLock) { dailyStatsArmed = false }
        }
        if (!answered) return null
        return synchronized(waitLock) { ArrayList(dailyStats) }
    }

    /**
     * Read the stored basal profiles. Returns true when the pump sent at least one.
     *
     * **The pump does not always send as many as it says it has.** Its frames declare a record
     * count of 8, and on a link left at the default MTU it answers with five, indices 0 to 4, and
     * then falls silent; see Atc3BLE.onMtuChanged. Waiting for the declared count would then fail
     * the read every time, leaving [Atc3Pump.pumpProfiles] unset, so that
     * [app.aaps.pump.atc3.Atc3PumpPlugin.isThisProfileSet] would compare against nothing and
     * [writeBasalProfile] could not confirm its own write.
     *
     * So the burst ends the way the daily statistics burst does — when the pump goes quiet — and
     * what arrived is published whatever its size. The record count is kept only as a shortcut for
     * finishing early on a pump that does send everything it promises.
     */
    fun readBasalProfiles(): Boolean {
        synchronized(collectedProfiles) { collectedProfiles.clear() }
        val isProfile = { frame: Atc3ResponseFrame ->
            frame.frameId == Atc3Const.MODE_HISTORY && frame.objectType == Atc3Const.ReadOpcode.BASAL_PROFILES
        }
        val answered = exchange(
            "read 0x%02X".format(Atc3Const.ReadOpcode.BASAL_PROFILES),
            match = {
                isProfile(it) &&
                    synchronized(collectedProfiles) { collectedProfiles.size >= it.recordCount && it.recordCount > 0 }
            },
            burstOf = isProfile
        ) {
            sendRead(Atc3Const.ReadOpcode.BASAL_PROFILES)
        }
        if (!answered) return false
        return publishProfiles()
    }

    /**
     * Hand the profiles that arrived to [atc3Pump], and say whether there were any.
     *
     * A profile the pump did not send is left as an empty one rather than dropped, so an index
     * always means the same slot: the driver's own profile is slot
     * [Atc3Const.DRIVER_PROFILE_INDEX] and it must not shift because a later slot went missing.
     */
    private fun publishProfiles(): Boolean {
        val profiles = synchronized(collectedProfiles) { HashMap(collectedProfiles) }
        if (profiles.isEmpty()) {
            aapsLogger.error(LTag.PUMPCOMM, "ATC3: the pump sent no basal profiles")
            return false
        }
        val count = maxOf(profiles.keys.max() + 1, Atc3Const.PROFILE_COUNT)
        atc3Pump.pumpProfiles = Array(count) { index -> profiles[index] ?: DoubleArray(Atc3Const.BASAL_SLOTS) }
        atc3Pump.lastConnection = dateUtil.now()
        aapsLogger.debug(LTag.PUMPCOMM, "ATC3: ${profiles.size} basal profile(s) read of $count slots")
        return true
    }

    // Basal profile

    /**
     * Put [rates] into the pump and prove that it took effect.
     *
     * The write always lands in the active profile, so the driver's own slot is made active first.
     * Success is only reported once the pump reports that slot as active and returns the same 48
     * rates, compared with [tolerance], normally one basal step.
     *
     * @return null on success, otherwise a message describing what did not match
     */
    fun writeBasalProfile(rates: DoubleArray, tolerance: Double): String? {
        require(rates.size == Atc3Const.BASAL_SLOTS) { "expected ${Atc3Const.BASAL_SLOTS} rates" }

        if (!readStatus()) return "pump did not report its status"

        val target = Atc3Const.DRIVER_PROFILE_INDEX
        if (atc3Pump.activeProfileIndex != target) {
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: pump is on profile ${atc3Pump.activeProfileIndex}, selecting $target before writing"
            )
            if (!switchToProfile(target)) {
                return "pump stayed on profile ${atc3Pump.activeProfileIndex} instead of $target"
            }
        }

        val payload = ByteArray(2 * Atc3Const.BASAL_SLOTS)
        val raw = Atc3Pump.ratesToRaw(rates)
        for (slot in raw.indices) {
            payload[2 * slot] = (raw[slot] and 0xFF).toByte()
            payload[2 * slot + 1] = ((raw[slot] shr 8) and 0xFF).toByte()
        }
        if (!sendControlAndWait(Atc3Const.ControlOpcode.WRITE_BASAL_PROFILE, payload)) {
            return "pump did not acknowledge the basal profile"
        }

        // An acknowledgement is not proof. Read the profiles back and compare. The pump needs a
        // moment before the stored profile reflects the write, so allow a few attempts.
        var lastProblem = "pump did not return the basal profiles"
        repeat(Atc3Const.EFFECT_POLL_ATTEMPTS) {
            SystemClock.sleep(Atc3Const.EFFECT_POLL_INTERVAL_MS)
            if (!readBasalProfiles()) return@repeat
            val stored = atc3Pump.pumpProfiles?.getOrNull(target)
            if (stored == null) {
                lastProblem = "pump did not return profile $target"
                return@repeat
            }
            val differing = Atc3Pump.differingSlots(rates, stored, tolerance)
            if (differing.isEmpty()) return null
            lastProblem = "pump stored different rates: " + differing.joinToString(", ", limit = 5) {
                "${Atc3Pump.slotLabel(it)} wanted ${rates[it]} got ${stored[it]}"
            }
            aapsLogger.debug(LTag.PUMP, "ATC3: read back does not match yet, $lastProblem")
        }
        return lastProblem
    }

    // Bolus

    /** Amount the pump has reported as delivered for the bolus currently running, units. */
    @Volatile var bolusDelivered: Double = 0.0
        private set

    /** Set once the pump reports the running bolus as finished. */
    @Volatile private var bolusFinished: Boolean = false

    /** Set once a progress frame of the running bolus has arrived. */
    @Volatile private var bolusSawProgress: Boolean = false

    /** True between asking for a bolus and learning what became of it. */
    @Volatile private var bolusInProgress: Boolean = false

    /** Set when the user asked for the running bolus to stop. */
    @Volatile private var bolusCancelRequested: Boolean = false

    /** When the pump last reported delivery progress, for spotting a bolus that has stopped. */
    @Volatile private var lastProgressAt: Long = 0L

    /** Records of the history burst being read, guarded by [waitLock]. */
    private val bolusBurst = ArrayList<Atc3BolusRecord>()

    /** True only while a history read of ours is in flight, see [readBolusHistory]. */
    private var bolusBurstArmed = false

    /** The count byte the records of that burst carried. */
    private var bolusRecordCount = -1

    /**
     * Deliver a bolus and follow it to the end.
     *
     * The pump answers with an acknowledgement, then a stream of progress frames carrying the
     * running total, then a completion frame. A cancelled bolus simply stops and no completion
     * frame arrives, so the amount reported here is what the pump last said, not proof.
     *
     * [onAccepted] fires the moment the pump accepts the command and before a single unit can have
     * been delivered. That is when AAPS starts counting the bolus, so that insulin is never lost if
     * everything after this point fails.
     *
     * Reading the pump's history, before or after, is deliberately not done here: each read is an
     * exchange of its own and the caller sequences them.
     */
    // Suspend, and so are the two callbacks: what they do on the far side is the driver's history
    // code, which is suspend throughout. The waiting inside is still the blocking kind.
    suspend fun bolus(units: Double, onAccepted: suspend (Long) -> Unit, onProgress: suspend (Double) -> Unit): Atc3BolusOutcome {
        val raw = Math.round(units / Atc3Const.DOSE_SCALE).toInt()
        bolusDelivered = 0.0
        bolusFinished = false
        bolusSawProgress = false
        bolusCancelRequested = false

        val payload = byteArrayOf((raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte(), 0x00)
        bolusInProgress = true
        var acceptedAt = 0L
        try {
            if (!sendControlAndWait(Atc3Const.ControlOpcode.BOLUS, payload)) {
                return if (rejected) Atc3BolusOutcome.Refused else Atc3BolusOutcome.NotSent
            }
            acceptedAt = dateUtil.now()
            onAccepted(acceptedAt)
            // The stall timer starts here: whatever happened before the pump accepted the command
            // must not come out of the time it is allowed to take before its first progress frame.
            lastProgressAt = System.currentTimeMillis()

            var lastReported = -1.0
            while (!bolusFinished && !bolusCancelRequested) {
                if (bolusDelivered != lastReported) {
                    lastReported = bolusDelivered
                    onProgress(bolusDelivered)
                }
                // A bolus that stops for any reason simply goes quiet: no completion frame is
                // sent. Silence is the signal to stop watching and go and ask the pump.
                if (System.currentTimeMillis() - lastProgressAt > BOLUS_STALL_MS) {
                    aapsLogger.debug(LTag.PUMP, "ATC3: no progress for ${BOLUS_STALL_MS}ms, checking the pump")
                    break
                }
                SystemClock.sleep(BOLUS_POLL_MS)
            }
            // The completion frame usually carries a little more than the last progress frame did,
            // so report once more or the dialog is left showing a bolus that never quite finished.
            if (bolusDelivered != lastReported) onProgress(bolusDelivered)
        } finally {
            bolusInProgress = false
        }
        return Atc3BolusOutcome.Delivered(
            reportedUnits = bolusDelivered,
            cancelled = bolusCancelRequested,
            completed = bolusFinished,
            sawProgress = bolusSawProgress,
            acceptedAtMs = acceptedAt
        )
    }

    /**
     * Read the history again and again until [done] is satisfied.
     *
     * The pump writes a bolus record only once the bolus is over, and not immediately, so the
     * record that confirms what was delivered has to be waited for. Each attempt is a separate
     * exchange, one at a time, as everything else here is.
     *
     * @return the last history read that succeeded, or null when none did
     */
    fun readBolusHistoryUntil(attempts: Int, done: (Atc3BolusHistory) -> Boolean): Atc3BolusHistory? {
        var last: Atc3BolusHistory? = null
        repeat(attempts) { attempt ->
            SystemClock.sleep(Atc3Const.EFFECT_POLL_INTERVAL_MS)
            val history = readBolusHistory()
            if (history != null) {
                last = history
                if (done(history)) return history
            }
            aapsLogger.debug(LTag.PUMP, "ATC3: the bolus is not in the pump's history yet, check ${attempt + 1}")
        }
        return last
    }

    /**
     * Stop a running bolus. The pump keeps whatever it has already delivered.
     *
     * Only sent while a bolus is actually running. With nothing running the command can make the
     * pump write a bolus record of its own into the stored history, so it is not sent idly.
     */
    fun stopBolus(): Boolean {
        if (!bolusInProgress) {
            aapsLogger.warn(LTag.PUMP, "ATC3: no bolus is running, not sending a cancel")
            return true
        }
        bolusCancelRequested = true
        val sent = sendControlAndWait(
            Atc3Const.ControlOpcode.CANCEL_BOLUS,
            byteArrayOf(0x00, 0x00),
            group = Atc3Const.GROUP_QUERY
        )
        if (!sent) {
            // The bolus is still running as far as anyone knows, so do not pretend it was stopped.
            bolusCancelRequested = false
            aapsLogger.error(LTag.PUMP, "ATC3: the pump did not accept the cancel")
        }
        return sent
    }

    /**
     * Read the pump's recent boluses with the periodic search, `35/A3/21/01`.
     *
     * The pump answers with a burst of records, newest first, and the read only counts as finished
     * once that burst has gone quiet. Every record is kept: the nine beyond the newest are the only
     * place a bolus given on the pump itself or from another device ever appears.
     *
     * This answer **stops at ten frames** whatever its count byte says. When more have accumulated
     * than the search will return, [readFullBolusHistory] is the way to get at the rest.
     */
    fun readBolusHistory(): Atc3BolusHistory? =
        readBolusRecords(
            "bolus history",
            Atc3Const.ObjectType.LATEST_BOLUS,
            // The full history has no such cap and does flag its last record, so only the periodic
            // search is finished by counting.
            burstFrameCap = Atc3Const.PERIODIC_BOLUS_FRAMES
        ) {
            send(
                Atc3Const.GROUP_CONTROL, Atc3Const.MODE_HISTORY, Atc3Const.HistoryOpcode.LATEST_BOLUS,
                Atc3Const.PARAMETER_LATEST
            )
        }

    /**
     * Read every bolus the pump still holds, `55/A3/01`.
     *
     * The periodic search above stops at ten frames whatever its count says. This one does not: it
     * sends as many frames as its count names, in one burst, with no paging. The records are laid
     * out exactly as object `0x21`, so nothing downstream changes.
     *
     * It costs more frames than the periodic search, so it is asked for only when the driver can
     * see that records have aged out, see
     * [app.aaps.pump.atc3.history.Atc3BolusReconciler.recordsMissing].
     */
    fun readFullBolusHistory(): Atc3BolusHistory? =
        readBolusRecords("full bolus history", Atc3Const.ObjectType.BOLUS_RECORD) {
            send(
                Atc3Const.GROUP_QUERY, Atc3Const.MODE_HISTORY, Atc3Const.HistoryOpcode.BOLUS_HISTORY,
                Atc3Frame.NO_PARAMETER
            )
        }

    /**
     * Carry out one bolus history read and collect the burst of records it answers with.
     *
     * The collector is armed only around this exchange. Another client connected to the pump can
     * poll the same objects on its own schedule and Android delivers its answers here too, so an
     * unarmed burst is somebody else's and is dropped rather than mistaken for ours.
     *
     * @param objectType the object the records of this particular request arrive under
     */
    private fun readBolusRecords(
        what: String,
        objectType: Byte,
        burstFrameCap: Int = 0,
        sender: () -> Boolean
    ): Atc3BolusHistory? {
        synchronized(waitLock) {
            bolusBurst.clear()
            bolusRecordCount = -1
            bolusBurstArmed = true
        }
        val isBolusRecord = { frame: Atc3ResponseFrame ->
            frame.frameId == Atc3Const.MODE_HISTORY && frame.objectType == objectType
        }
        val answered = try {
            exchange(
                what,
                // Shortcut for an answer whose record count really is the number of frames coming.
                match = { isBolusRecord(it) && it.isLastRecord },
                burstOf = isBolusRecord,
                burstFrameCap = burstFrameCap,
                sender = sender
            )
        } finally {
            synchronized(waitLock) { bolusBurstArmed = false }
        }
        if (!answered) return null
        return synchronized(waitLock) { Atc3BolusHistory(ArrayList(bolusBurst), bolusRecordCount) }
    }

    private fun handleBolusProgress(frame: Atc3ResponseFrame) {
        if (!frame.has(2, 2)) return
        if (!bolusInProgress) {
            // Another client may be running a bolus of its own on the same pump.
            aapsLogger.debug(LTag.PUMPCOMM, "ATC3: bolus progress while no bolus of ours is running, ignored")
            return
        }
        bolusDelivered = frame.u16le(2) * Atc3Const.DOSE_SCALE
        bolusSawProgress = true
        lastProgressAt = System.currentTimeMillis()
        aapsLogger.debug(LTag.PUMPCOMM, "ATC3: bolus progress $bolusDelivered U")
    }

    private fun handleBolusCompleted(frame: Atc3ResponseFrame) {
        if (!bolusInProgress) {
            aapsLogger.debug(LTag.PUMPCOMM, "ATC3: bolus completion while no bolus of ours is running, ignored")
            return
        }
        if (frame.has(2, 2)) bolusDelivered = frame.u16le(2) * Atc3Const.DOSE_SCALE
        bolusFinished = true
        aapsLogger.debug(LTag.PUMPCOMM, "ATC3: bolus finished at $bolusDelivered U")
    }

    // Temporary basal

    /**
     * Start an absolute temporary basal and prove from the pump's status that it is running.
     *
     * The payload is laid out as [Atc3Const.TbrPayload]; the duration byte counts quarter hours.
     *
     * The acknowledgement instant and the duration actually sent are reported back, because that
     * is what AAPS's own record of the temporary basal has to be built from: the pump is told a
     * number of quarter hours, which is not always the number of minutes the loop asked for.
     */
    fun setTempBasal(rate: Double, durationMinutes: Int): Atc3TbrResult {
        val rateRaw = Math.round(rate / Atc3Const.DOSE_SCALE).toInt()
        val durationUnits = Math.round(durationMinutes.toDouble() / Atc3Const.TbrPayload.DURATION_UNIT_MINUTES).toInt()
        if (durationUnits <= 0) {
            return Atc3TbrResult(0L, rate, 0, "duration $durationMinutes is shorter than the pump's smallest step")
        }

        val payload = byteArrayOf(
            Atc3Const.TbrPayload.MODE_ABSOLUTE,
            durationUnits.toByte(),
            (rateRaw and 0xFF).toByte(),
            ((rateRaw shr 8) and 0xFF).toByte()
        )
        val wantedMinutes = durationUnits * Atc3Const.TbrPayload.DURATION_UNIT_MINUTES
        var acceptedAt = 0L
        val failure = commandUntilConfirmed(
            what = "a temporary basal of $rate U/h for $wantedMinutes min",
            send = {
                val sent = sendControlAndWait(Atc3Const.ControlOpcode.START_TBR, payload)
                if (sent && acceptedAt == 0L) acceptedAt = dateUtil.now()
                sent
            },
            // The duration is checked as well as the rate, and what the pump reports is what is
            // returned rather than the duration computed here: the field is in the same status
            // frame, it is the duration the temporary basal was started for, and it does not count
            // down, so comparing it is valid at any moment of the run.
            confirmed = {
                readStatus() && atc3Pump.tbrActive &&
                    Math.abs(atc3Pump.tbrRate - rate) <= Atc3Const.DOSE_SCALE &&
                    atc3Pump.tbrDurationMinutes == wantedMinutes
            }
        )
        // Where the pump was never reached at all there is no moment of acceptance to report, and
        // saying "now" would date the record to a temporary basal that never started.
        if (failure != null && acceptedAt == 0L) return Atc3TbrResult(0L, rate, wantedMinutes, failure)
        return Atc3TbrResult(acceptedAt, atc3Pump.tbrRate, atc3Pump.tbrDurationMinutes, failure)
    }

    /**
     * The moment the pump first acknowledged the last [cancelTempBasal], or 0 when it never did.
     * That is where the temporary basal ended.
     */
    @Volatile var lastTbrCancelAckMs: Long = 0L
        private set

    /**
     * Cancel a running temporary basal and prove from the pump's status that it stopped.
     *
     * @return null on success, otherwise a message describing what did not match
     */
    fun cancelTempBasal(): String? {
        lastTbrCancelAckMs = 0L
        return commandUntilConfirmed(
            what = "cancelling the temporary basal",
            // Repeating this one is free of consequence: cancelling twice leaves the pump exactly
            // where cancelling once did.
            send = {
                val sent = sendControlAndWait(Atc3Const.ControlOpcode.CANCEL_TBR, ByteArray(0))
                if (sent && lastTbrCancelAckMs == 0L) lastTbrCancelAckMs = dateUtil.now()
                sent
            },
            confirmed = { readStatus() && !atc3Pump.tbrActive }
        )
    }

    /**
     * Make [index] the active profile and confirm it from the pump's own status.
     *
     * The acknowledgement arrives quickly but the pump's status lags behind it, so the status is
     * re-read a few times before giving up. Checking only once would report a successful switch as
     * a failure.
     */
    fun switchToProfile(index: Int): Boolean {
        if (!sendControlAndWait(Atc3Const.ControlOpcode.SWITCH_PROFILE, byteArrayOf(index.toByte()))) {
            aapsLogger.error(LTag.PUMP, "ATC3: no acknowledgement for selecting profile $index")
            return false
        }
        repeat(Atc3Const.EFFECT_POLL_ATTEMPTS) { attempt ->
            SystemClock.sleep(Atc3Const.EFFECT_POLL_INTERVAL_MS)
            if (readStatus() && atc3Pump.activeProfileIndex == index) {
                aapsLogger.debug(LTag.PUMP, "ATC3: profile $index active after ${attempt + 1} checks")
                return true
            }
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: waiting for profile $index, pump reports ${atc3Pump.activeProfileIndex}, check ${attempt + 1}"
            )
        }
        return false
    }

    // Delivery state

    /**
     * Stop or resume the pump and prove from its own status that it did.
     *
     * The pump marks a stop by putting `0xFFFF` where the scheduled basal rate goes, so the state
     * is read back rather than assumed, like every other control command here.
     *
     * @return null on success, otherwise a message describing what did not happen
     */
    fun setSuspended(suspended: Boolean): String? {
        val payload = byteArrayOf(if (suspended) 1 else 0)
        if (!sendControlAndWait(Atc3Const.ControlOpcode.SUSPEND, payload)) {
            return if (rejected) "the pump refused to change its delivery state"
            else "the pump did not answer"
        }
        repeat(Atc3Const.EFFECT_POLL_ATTEMPTS) {
            SystemClock.sleep(Atc3Const.EFFECT_POLL_INTERVAL_MS)
            if (readStatus() && atc3Pump.suspended == suspended) return null
            aapsLogger.debug(LTag.PUMP, "ATC3: waiting for the pump to be ${if (suspended) "stopped" else "running"}")
        }
        return "the pump still reports itself as ${if (atc3Pump.suspended) "stopped" else "running"}"
    }

    // Clock

    /**
     * Put the phone's time into the pump.
     *
     * The pump keeps a plain wall clock with no notion of a timezone, so this is the only way its
     * time ever changes by itself. The command carries the seconds too. Whether it took is what the
     * pump answers to it: acknowledged is done, refused or silent is not.
     *
     * @return null on success, otherwise a message describing what did not happen
     */
    fun writeClock(nowMs: Long): String? {
        if (!sendControlAndWait(Atc3Const.ControlOpcode.SET_CLOCK, Atc3StatusV1.encodeClock(nowMs))) {
            return if (rejected) "the pump refused the clock" else "the pump did not acknowledge the clock"
        }
        // What the bolus history said about the clock was said about the one just replaced.
        clockWatch.forget()
        return null
    }

    // Settings

    /**
     * Put [wanted] into the pump and prove that it took effect.
     *
     * `35/A1/32` replaces the whole settings block, so [wanted] has to be a complete set built
     * from what the pump last reported, not a single field. Success is only reported once Status
     * V1 comes back carrying the same values, with the one exception of the alarm signal duration,
     * which the pump never reports; see [Atc3Settings.mirroredFieldsMatch].
     *
     * @return null on success, otherwise a message describing what did not match
     */
    fun writeSettings(wanted: Atc3Settings): String? {
        if (!sendControlAndWait(Atc3Const.ControlOpcode.WRITE_SETTINGS, wanted.toPayload())) {
            return if (rejected) "the pump refused the settings" else "the pump did not acknowledge the settings"
        }
        // Remembered only once the pump has accepted the block, so a refused write does not leave
        // the driver believing it set a duration the pump never took.
        preferences.put(Atc3IntNonKey.AlarmDuration, wanted.alarmDuration)

        var lastProblem = "the pump did not report its settings back"
        repeat(Atc3Const.EFFECT_POLL_ATTEMPTS) {
            SystemClock.sleep(Atc3Const.EFFECT_POLL_INTERVAL_MS)
            if (!readStatus()) return@repeat
            val stored = atc3Pump.settings ?: return@repeat
            if (wanted.mirroredFieldsMatch(stored)) return null
            lastProblem = "the pump stored different settings"
            aapsLogger.debug(LTag.PUMP, "ATC3: settings read back does not match yet, wanted $wanted got $stored")
        }
        return lastProblem
    }

    /**
     * Replace the pump's Bluetooth password.
     *
     * There is no way to read the password back: the pump only shows it on its own status screen,
     * so an acknowledgement is all the confirmation that exists. The caller is responsible for
     * storing the new value, and for the fact that the link will drop straight afterwards: the pump
     * restarts its Bluetooth stack once it has accepted the change.
     *
     * @return null on success, otherwise a message describing what went wrong
     */
    fun setBtPassword(value: Int): String? {
        if (!Atc3BtPassword.isSettable(value)) return "out of range"
        if (!sendControlAndWait(Atc3Const.ControlOpcode.SET_BT_PASSWORD, Atc3BtPassword.changePayload(value))) {
            return if (rejected) "the pump refused the new password" else "the pump did not acknowledge the new password"
        }
        return null
    }

    // Sending

    private fun sendRead(opcode: Byte, parameter: Byte = Atc3Frame.NO_PARAMETER): Boolean =
        send(Atc3Const.GROUP_CONTROL, Atc3Const.MODE_HISTORY, opcode, parameter)

    private fun sendAndWait(opcode: Byte, match: (Atc3ResponseFrame) -> Boolean): Boolean =
        exchange("read 0x%02X".format(opcode), match) {
            sendRead(opcode)
        }

    /**
     * Send a control command and wait for the pump to accept or refuse it.
     *
     * A refusal arrives as object [Atc3Const.ObjectType.REJECTED] and is a definite answer, so it
     * ends the wait at once rather than being left to time out.
     */
    private fun sendControlAndWait(
        opcode: Byte,
        payload: ByteArray,
        group: Byte = Atc3Const.GROUP_CONTROL
    ): Boolean {
        rejected = false
        val answered = exchange(
            "control 0x%02X".format(opcode),
            {
                it.frameId == Atc3Const.MODE_CONTROL &&
                    (it.objectType == Atc3Const.ObjectType.ACK || it.objectType == Atc3Const.ObjectType.REJECTED)
            }
        ) {
            send(group, Atc3Const.MODE_CONTROL, opcode, Atc3Frame.NO_PARAMETER, payload)
        }
        if (answered && rejected) {
            aapsLogger.error(LTag.PUMP, "ATC3: pump refused command 0x%02X".format(opcode))
            trace.event(Atc3TraceCat.EXCH, "refused", "opcode" to "0x%02X".format(opcode))
            return false
        }
        return answered
    }

    /** True when the last control command came back refused rather than accepted. */
    fun wasRefused(): Boolean = rejected

    private var heartbeatPeriodSet = false

    /**
     * Put the pump's heartbeat on the period the link watch expects, once per link.
     *
     * The pump keeps the period across links and takes it from whichever client last set it, so it
     * is set here rather than assumed. Either answer settles it for this link: a refusal comes from
     * a locked pump, which refuses every control command, and asking again on every tick would not
     * change that. No answer at all leaves it to be asked again.
     */
    fun ensureHeartbeatPeriod() {
        if (heartbeatPeriodSet || !isConnected) return
        val ok = sendControlAndWait(
            Atc3Const.ControlOpcode.SET_HEARTBEAT,
            byteArrayOf(Atc3Const.HEARTBEAT_PERIOD_MINUTES.toByte(), 0x00)
        )
        heartbeatPeriodSet = ok || rejected
        trace.event(Atc3TraceCat.SESS, "heartbeat_period", "minutes" to Atc3Const.HEARTBEAT_PERIOD_MINUTES, "ok" to ok)
        if (!ok) aapsLogger.warn(LTag.PUMP, "ATC3: the heartbeat period was not set, " + if (rejected) "the pump refused it" else "no answer")
    }

    /**
     * Send a command and keep at it until the pump's own state says it took, or the budget runs out.
     *
     * **Silence is not "not done".** The pump answers most of what it is asked and occasionally
     * does not, having carried the command out all the same. So a command that goes unanswered is
     * not repeated blind: the state is read first, because a status read costs a quarter of a
     * second and a blind repeat costs whatever the first one already did — a temporary basal
     * restarted from a new moment, its record in AAPS out of step with the pump by that much.
     *
     * So it is one loop: send, wait, look, and send again only if looking says it did not take.
     *
     * **A refusal ends it at once.** The pump has considered the command and declined it; the same
     * command with the same parameters will be declined again, and the refusal carries no reason
     * for the driver to act on. Only silence and a state that has not changed are worth repeating.
     *
     * The whole thing is bounded by [Atc3Const.COMMAND_BUDGET_MS] as well as by the attempt counts,
     * because the queue behind it is serial: everything else AAPS wants to do waits on this, and a
     * command that will not take must not hold the loop's next decision behind it.
     *
     * @param send carries the command to the pump; false when it was refused or went unanswered
     * @param confirmed reads the pump's state and says whether it is now what was asked for
     * @return null when the pump's state confirmed the command, otherwise what went wrong
     */
    private fun commandUntilConfirmed(
        what: String,
        send: () -> Boolean,
        confirmed: () -> Boolean
    ): String? {
        val deadline = SystemClock.elapsedRealtime() + Atc3Const.COMMAND_BUDGET_MS
        var sends = 0
        var lastFailure = "the pump did not answer"
        while (sends < Atc3Const.COMMAND_SEND_ATTEMPTS && SystemClock.elapsedRealtime() < deadline) {
            // Nothing sent on a link that is gone reaches the pump, and nothing read from it says
            // whether the command took, so sends and reads there would only burn the budget. The
            // command fails at once and the queue reconnects for the next one.
            if (!atc3BLE.isConnected) return "the link is down"
            sends++
            if (!send()) {
                if (rejected) return "the pump refused $what"
                // Unanswered. It may well have been carried out, so the next thing is to look
                // rather than to send again.
                lastFailure = "the pump did not answer"
            }
            var checks = 0
            while (checks < Atc3Const.COMMAND_CHECK_ATTEMPTS && SystemClock.elapsedRealtime() < deadline) {
                checks++
                if (!atc3BLE.isConnected) return "the link is down"
                SystemClock.sleep(Atc3Const.EFFECT_POLL_INTERVAL_MS)
                if (confirmed()) {
                    if (sends > 1) {
                        trace.event(Atc3TraceCat.EXCH, "command_repeated", "what" to what, "sends" to sends)
                    }
                    return null
                }
                lastFailure = "the pump did not do what $what asked"
            }
            aapsLogger.debug(LTag.PUMP, "ATC3: $what has not taken after $sends sends, $lastFailure")
        }
        trace.event(
            Atc3TraceCat.EXCH, "command_gave_up",
            "what" to what,
            "sends" to sends,
            "budget" to (SystemClock.elapsedRealtime() >= deadline)
        )
        return lastFailure
    }

    /**
     * Carry out one request and answer pair, start to finish, with nothing else in between.
     *
     * Anything that fails leaves no half finished state behind: the wait is disarmed and the
     * receive buffer is dropped, so the next exchange starts clean rather than picking up the tail
     * of an abandoned answer.
     */
    private fun exchange(
        what: String,
        match: (Atc3ResponseFrame) -> Boolean,
        burstOf: ((Atc3ResponseFrame) -> Boolean)? = null,
        burstFrameCap: Int = 0,
        sender: () -> Boolean
    ): Boolean {
        val queuedAt = trace.now()
        exchangeLock.lock()
        val startedAt = trace.now()
        try {
            settleAfterConnecting()
            val latch = arm(match, burstOf)
            if (!sender()) {
                aapsLogger.error(LTag.PUMPCOMM, "ATC3: could not send $what")
                abort()
                finished(what, false, "not_sent", queuedAt, startedAt)
                return false
            }
            val answered = if (burstOf == null) await(latch, what) else awaitBurst(latch, what, burstFrameCap)
            if (!answered) {
                abort()
                finished(what, false, if (linkLost) "link_lost" else "no_answer", queuedAt, startedAt)
                return false
            }
            finished(what, true, "ok", queuedAt, startedAt)
            return true
        } finally {
            exchangeLock.unlock()
        }
    }

    /**
     * Write down one finished exchange.
     *
     * Two durations, because they answer different questions. `waitMs` is how long this request sat
     * behind another one, which is what says whether the driver is asking the pump for too much in
     * one connection; `ms` is what the pump itself took.
     */
    private fun finished(what: String, ok: Boolean, outcome: String, queuedAt: Long, startedAt: Long) {
        trace.countExchange(ok)
        noteReachability(outcome)
        trace.event(
            Atc3TraceCat.EXCH, "done",
            "what" to what,
            "ok" to ok,
            "outcome" to outcome,
            "waitMs" to startedAt - queuedAt,
            "ms" to trace.since(startedAt)
        )
    }

    /**
     * Count exchanges that reached nobody, and give up on the link when enough of them do.
     *
     * When the phone's Bluetooth goes away briefly, the stack can report no disconnection at all,
     * and the driver would go on believing it is connected until the pump's heartbeat had been
     * missing long enough to be questioned, minutes later. Meanwhile every write is refused by the
     * stack within milliseconds, each recorded as `not_sent`.
     *
     * So a run of exchanges that got no answer whatsoever ends the link, and the two signals are
     * complements rather than one waiting on the other: failed exchanges catch a dead link while
     * the driver is talking, the heartbeat catches one while it is quiet.
     *
     * Only exchanges that ended with nothing at all are counted. A command the pump declined
     * arrives here as a completed exchange - the refusal is the answer - so it clears the run,
     * which is right: an answer of any kind proves the link.
     */
    private fun noteReachability(outcome: String) {
        if (outcome == "ok") {
            unreachable = 0
            return
        }
        unreachable++
        if (unreachable < UNREACHABLE_BEFORE_DROP || !atc3BLE.isConnected) return
        aapsLogger.error(
            LTag.PUMPCOMM,
            "ATC3: $unreachable exchanges in a row reached nothing, the link is dead"
        )
        val run = unreachable
        trace.event(Atc3TraceCat.BLE, "link_dead", "after" to run, "why" to outcome)
        unreachable = 0
        stopLivenessWatch()
        closeReason = "no answer to $run exchanges"
        atc3BLE.disconnect()
    }

    /** Drop anything left over from a failed exchange. */
    private fun abort() {
        disarm()
        parser.reset()
    }

    companion object {

        /**
         * How long the pump may be silent on a held link before it is asked whether it is there.
         *
         * One missed heartbeat plus a minute: the beat comes every 180 s, the check runs once a
         * minute, and the pump does not keep to the interval in every single case. Two missed beats
         * would be the right rule if the answer were to drop the link outright; asking costs one
         * small read, so it is cheaper to ask early. See Atc3Manager.startLivenessWatch.
         */
        private const val QUIET_BEFORE_PROBE_MS = 240_000L

        /** How often a held link is examined. Cheap against a four minute rule. */
        private const val LIVENESS_CHECK_MS = 60_000L

        /**
         * How many exchanges must reach nothing before the link is given up on.
         *
         * Three, not one. A single failure can be a write refused because the stack was momentarily
         * busy, and throwing a good link away costs a full setup to get it back. Three in a row is
         * not a coincidence, and the pump is meanwhile unreachable either way, so there is nothing
         * left to protect by waiting.
         */
        private const val UNREACHABLE_BEFORE_DROP = 3

        /** How often to report bolus progress while the pump is delivering, milliseconds. */
        private const val BOLUS_POLL_MS = 500L

        /**
         * How long the pump may stay silent before its bolus is taken to be over, milliseconds.
         *
         * Progress frames arrive about once a second while insulin is flowing, and a bolus that
         * stops for any reason simply goes quiet rather than announcing itself. Silence is
         * therefore the signal to stop watching and read the pump's own record, which is the only
         * trustworthy account of what was delivered anyway.
         */
        private const val BOLUS_STALL_MS = 15_000L

        /**
         * How much of the settle window is still owed, milliseconds, or zero when nothing is.
         *
         * Separate from the sleeping on purpose. The arithmetic is the part that can be wrong —
         * a link timestamp that is never set, a window measured from the wrong moment, a sign
         * slipped — and it is the part a unit test can actually check, because the sleep itself
         * cannot: this project builds its unit tests against a stubbed `android.jar`, where
         * `SystemClock.sleep` returns at once. A test that timed the wait would pass whether or not
         * the wait existed.
         *
         * Never longer than the window itself. The phone clock can be corrected while the link is
         * up, which puts the link's timestamp in the future and would otherwise turn this into a
         * wait of however far the clock jumped, in the middle of an exchange.
         *
         * @param linkUpAtMs when the GATT link came up, or 0 when there is no link
         */
        fun settleDelayMs(linkUpAtMs: Long, now: Long): Long {
            if (linkUpAtMs == 0L) return 0L
            return (Atc3Const.FIRST_REQUEST_SETTLE_MS - (now - linkUpAtMs))
                .coerceIn(0L, Atc3Const.FIRST_REQUEST_SETTLE_MS)
        }

        /** How often to look at a burst that is still arriving, milliseconds. */
        private const val BURST_POLL_MS = 100L

        /**
         * How long a burst of answer frames may be quiet before it counts as finished, milliseconds.
         *
         * Frames of one answer arrive tens of milliseconds apart, so this sits well clear of the
         * gaps inside a burst while costing only half a second per read.
         */
        private const val BURST_IDLE_MS = 500L

        /**
         * How long the line must be quiet after a complete burst before anything else is sent.
         *
         * Not for deciding that the answer has ended — the matching frame does that — but for
         * letting the Bluetooth stack finish with the notifications it is still holding. Without it
         * the next write is refused with 201, a write already in flight, and the exchange fails for
         * a reason that has nothing to do with the pump.
         */
        private const val BURST_SETTLE_MS = 200L
    }

    private fun send(group: Byte, mode: Byte, opcode: Byte, parameter: Byte, payload: ByteArray = ByteArray(0)): Boolean {
        val identity = runCatching { identity() }.getOrElse {
            aapsLogger.error(LTag.PUMP, "ATC3: cannot build request, ${it.message}")
            return false
        }
        return atc3BLE.write(Atc3Frame.buildRequest(group, mode, opcode, identity, parameter, payload))
    }

    private fun arm(match: (Atc3ResponseFrame) -> Boolean, burst: ((Atc3ResponseFrame) -> Boolean)?): CountDownLatch {
        val latch = CountDownLatch(1)
        synchronized(waitLock) {
            pendingMatch = match
            pendingLatch = latch
            pendingBurst = burst
            burstFrames = 0
            burstLastFrameAt = 0L
            linkLost = false
            answerGate.armed(singleAnswer = burst == null)
        }
        return latch
    }

    private fun disarm() {
        synchronized(waitLock) {
            pendingMatch = null
            pendingLatch = null
            pendingBurst = null
            answerGate.disarmed()
        }
    }

    private fun await(latch: CountDownLatch, what: String): Boolean {
        val released = latch.await(Atc3Const.COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        disarm()
        // A dropped link releases the wait as well, and that is a failure, not an answer.
        if (released && linkLost) {
            aapsLogger.error(LTag.PUMPCOMM, "ATC3: link lost while waiting for $what")
            return false
        }
        if (!released) aapsLogger.error(LTag.PUMPCOMM, "ATC3: no answer to $what")
        return released
    }

    /**
     * Whether a burst is finished because it has brought everything it was ever going to bring.
     *
     * Only an answer with a known fixed size can be finished this way, and only the periodic bolus
     * search has one - [Atc3Const.PERIODIC_BOLUS_FRAMES]. A cap of zero means the answer has no
     * such size and only its own last-record flag, or silence, can end it.
     *
     * The comparison is `>=` rather than `==` on purpose: a frame arriving past the cap must not
     * leave the wait unfinished for ever. Getting this boundary wrong in the other direction is the
     * dangerous one - declaring the answer complete one frame early would send the next request
     * while the pump is still talking, and the Bluetooth stack would refuse it.
     */
    internal fun burstCompleteByCount(frames: Int, cap: Int): Boolean = cap > 0 && frames >= cap

    /**
     * Wait for an answer that arrives as a burst of frames, one per record, and then for the line
     * to go quiet before letting anyone speak.
     *
     * The record count in those frames cannot be trusted to say how many frames are coming: the
     * `+0x20` alias objects, the periodic bolus search among them, carry a count well above the ten
     * frames they actually send, so waiting for record number count-1 waits for a frame that is
     * never sent. The frames of one answer arrive back to back, up to about 50 ms apart, one per
     * notification, and then the pump falls silent. The burst is therefore over once it has gone
     * quiet, and the record count is only used as a shortcut where it does agree.
     *
     * **The last expected frame is not the end of the wait.** The Bluetooth stack may still be
     * working through the train of notifications, and a write sent then is refused with 201, a
     * write already in flight. So after a match the wait goes on until [BURST_SETTLE_MS] have
     * passed with nothing arriving, which costs a fifth of a second on every burst read.
     */
    private fun awaitBurst(latch: CountDownLatch, what: String, burstFrameCap: Int = 0): Boolean {
        // Measured on the monotonic clock, not the wall clock. The phone's clock can be corrected
        // while the link is up, and a jump forward would declare the burst finished before the
        // settle period this loop exists to enforce, while a jump back would hold the exchange
        // lock until real time caught up. The single answer wait is already immune because it
        // delegates to CountDownLatch.await; this one has to say so itself.
        val deadline = SystemClock.elapsedRealtime() + Atc3Const.COMMAND_TIMEOUT_MS
        var matched = false
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!matched && latch.await(BURST_POLL_MS, TimeUnit.MILLISECONDS)) {
                matched = true
                if (linkLost) {
                    disarm()
                    aapsLogger.error(LTag.PUMPCOMM, "ATC3: link lost while waiting for $what")
                    return false
                }
            } else if (matched) {
                SystemClock.sleep(BURST_POLL_MS)
                if (linkLost) {
                    disarm()
                    aapsLogger.error(LTag.PUMPCOMM, "ATC3: link lost while waiting for $what")
                    return false
                }
            }
            // An answer can be complete without carrying the flag that says so. The periodic bolus
            // search stops at a fixed number of frames while the count byte keeps counting every
            // record the pump holds, so with more than that stored the flag never appears and the
            // read would sit out the longer of the two silences every time. Reaching the cap says
            // the same thing the flag would have said.
            if (!matched) {
                val frames = synchronized(waitLock) { burstFrames }
                if (burstCompleteByCount(frames, burstFrameCap)) {
                    matched = true
                    trace.event(Atc3TraceCat.EXCH, "burst_capped", "what" to what, "frames" to frames)
                }
            }
            val silence = if (matched) BURST_SETTLE_MS else BURST_IDLE_MS
            val quiet = synchronized(waitLock) {
                burstFrames > 0 && SystemClock.elapsedRealtime() - burstLastFrameAt >= silence
            }
            if (quiet) {
                disarm()
                return true
            }
        }
        disarm()
        // A burst that matched and then never fell quiet has still been answered: everything the
        // request asked for arrived, and what did not come is the pause after it. Failing the
        // exchange there would throw away a complete answer.
        if (matched) return true
        aapsLogger.error(LTag.PUMPCOMM, "ATC3: no answer to $what")
        return false
    }

    /**
     * Hold the first request of a connection back until the pump is listening.
     *
     * A request sent too soon after the link comes up is simply ignored: the ATT write is
     * acknowledged by the stack, nothing below this layer notices, and the answer never arrives, so
     * the exchange sits out its whole timeout for nothing. See [Atc3Const.FIRST_REQUEST_SETTLE_MS].
     *
     * Sleeping here rather than in [connect] is deliberate: the wait is only owed by whoever
     * actually talks first, and it costs nothing at all on a connection whose setup already took
     * longer than the window, which is most of them.
     */
    private fun settleAfterConnecting() {
        val owed = settleDelayMs(atc3BLE.linkUpAtMs, System.currentTimeMillis())
        if (owed <= 0L) return
        aapsLogger.debug(LTag.PUMPCOMM, "ATC3: the link is ${owed}ms too young to be asked anything, waiting")
        trace.event(Atc3TraceCat.EXCH, "settle", "ms" to owed)
        SystemClock.sleep(owed)
    }

    private fun identity(): ByteArray =
        Atc3Frame.identityOf(preferences.get(Atc3StringKey.Atc3SerialNumber))

    // BLE callbacks

    override fun onConnected() {
        trace.event(Atc3TraceCat.SESS, "ready")
        heartbeatPeriodSet = false
        // Watch the link for as long as there is a link, rather than only once AAPS goes idle.
        // A silence matters whenever it happens, and tying the watch to the link is also what keeps
        // it from outliving one.
        startLivenessWatch()
        // The password that is stored is the one the pump just accepted, so there is no longer a
        // second candidate to fall back to.
        preferences.put(Atc3StringKey.Atc3BtPasswordAlternate, "")
        parser.reset()
        // Nothing owed on the last link is owed on this one, and the pump may have had its battery
        // out in between, so the clock this connection measures staleness against starts again.
        answerGate.connected()
        synchronized(collectedProfiles) { collectedProfiles.clear() }
        // A different pump inherits nothing. Everything the driver holds about a pump -- its stored
        // profiles, its firmware, its settings, its reservoir, its alarms -- was read from the pump
        // that was here before, and none of it is true of this one. The profile cache is the
        // dangerous one: it is re-read only when the active index changes, so a new pump reporting
        // the same index would be compared against the old pump's rates.
        val serial = preferences.get(Atc3StringKey.Atc3SerialNumber)
        if (atc3Pump.serialNumber.isNotEmpty() && atc3Pump.serialNumber != serial) {
            aapsLogger.debug(LTag.PUMP, "ATC3: a different pump answered, forgetting what was known of the last one")
            trace.event(Atc3TraceCat.SESS, "pump_changed", "was" to atc3Pump.serialNumber, "now" to serial)
            atc3Pump.reset()
        }
        atc3Pump.serialNumber = serial
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTED))
    }

    override fun onDisconnected() {
        stopLivenessWatch()
        trace.sessionClose(closeReason)
        parser.reset()
        // Release anything waiting, otherwise a command would sit until its timeout for no reason.
        // The flag is what tells the waiter that this was a dropped link and not an answer.
        synchronized(waitLock) {
            linkLost = true
            pendingLatch?.countDown()
        }
        disarm()
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
    }

    /**
     * The pump would not accept the password, so this link never became usable.
     *
     * Worth a notification rather than a log line: nothing about it gets better on its own, and
     * without one the driver would simply look unreachable while the queue retried for ever. The
     * message says where to read the current value, because the pump is the only place it exists.
     */
    override fun onAuthenticationRejected() {
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
     * After a password change, present the other candidate rather than giving up on the pump.
     *
     * A change that the pump acknowledged has not always left it holding the value that was asked
     * for, and the driver cannot read the password back: the pump never sends it. So a refusal that
     * comes straight after a change is taken as "it took the other one" once, and only once — the
     * fallback is cleared whichever way it goes, so a genuinely wrong password still ends up in
     * [onAuthenticationRejected] on the connection after this one.
     *
     * @return true when a new value was stored and the next connection should simply try again
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

    override fun onSendError(reason: String) {
        aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: send error, $reason")
        trace.event(Atc3TraceCat.BLE, "send_error", "why" to reason)
    }

    override fun onDataReceived(chunk: ByteArray) {
        val corruptBefore = parser.crcErrors
        for (frame in parser.feed(chunk)) {
            val late = lateAnswer(frame)
            if (late != null) {
                aapsLogger.warn(
                    LTag.PUMPCOMM,
                    "ATC3: frame 0x%02X object 0x%02X is not this exchange's answer (%s), discarded"
                        .format(frame.frameId, frame.objectType ?: 0, late)
                )
                trace.event(
                    Atc3TraceCat.BLE, "late_answer",
                    "frame" to "0x%02X".format(frame.frameId),
                    "object" to "0x%02X".format(frame.objectType ?: 0),
                    "why" to late
                )
                // Discarding the frame and accounting for it are two different facts, and both are
                // wanted. A stale status arrives whether or not anything asked for one, and when
                // nothing did it is somebody else's read on a shared link -- exactly what the
                // foreign counter exists to notice. Refusing it silently would let a second client
                // stop being counted the moment its answers began to look stale.
                synchronized(waitLock) { if (pendingMatch == null) noteIfForeign(frame) }
                continue
            }
            handleFrame(frame)
            synchronized(waitLock) {
                if (pendingBurst?.invoke(frame) == true) {
                    burstFrames++
                    burstLastFrameAt = SystemClock.elapsedRealtime()
                }
                if (pendingMatch?.invoke(frame) == true) {
                    answerGate.answerAccepted()
                    pendingLatch?.countDown()
                } else if (pendingMatch == null) noteIfForeign(frame)
            }
        }
        // A frame the pump sent and we could not read is not a harmless curiosity: it is an answer
        // somebody is waiting for, and the wait will end in a timeout with no other trace of why.
        if (parser.crcErrors > corruptBefore) {
            aapsLogger.error(
                LTag.PUMPCOMM,
                "ATC3: ${parser.crcErrors - corruptBefore} frame(s) failed their CRC and were dropped, " +
                    "${parser.crcErrors} since this connection began"
            )
        }
    }

    /**
     * Notice an answer that nothing was waiting for.
     *
     * The pump answers what it is asked and nothing else, so a reply arriving with no exchange
     * armed was somebody else's - another client connected to the same pump. Notifications on
     * a shared link reach every app subscribed to them, and a response frame carries no identity at
     * all: no request id, no sequence, no serial, only the shape of the answer. That is why this
     * counts rather than filters. There is nothing to filter on, and the driver already treats a
     * foreign answer as its own wherever the shapes agree.
     *
     * Only replies are counted. Bolus progress, bolus completion and extended bolus progress arrive
     * unasked by design and are not evidence of anyone else.
     *
     * Nothing here changes what the driver does with the frame. This is the missing record, not a
     * defence: the defence is not running two clients against one pump.
     */
    private fun noteIfForeign(frame: Atc3ResponseFrame) {
        val isReply = when (frame.frameId) {
            Atc3Const.MODE_HISTORY -> true
            Atc3Const.MODE_CONTROL ->
                frame.objectType == Atc3Const.ObjectType.ACK || frame.objectType == Atc3Const.ObjectType.REJECTED

            else                   -> false
        }
        if (!isReply) return
        trace.countForeign()
        // Once per connection with the detail, then only the count, so a burst of somebody else's
        // history does not bury the connection it happened in.
        if (foreignReportedFor != trace.sessionId) {
            foreignReportedFor = trace.sessionId
            aapsLogger.warn(
                LTag.PUMPCOMM,
                "ATC3: an answer arrived that nothing asked for, frame 0x%02X object 0x%02X - another client is on this link"
                    .format(frame.frameId, frame.objectType ?: 0)
            )
            trace.event(
                Atc3TraceCat.BLE, "foreign_answer",
                "frame" to "0x%02X".format(frame.frameId),
                "object" to "0x%02X".format(frame.objectType ?: 0)
            )
        }
    }

    /**
     * Why this frame is not the answer to the exchange in front of us, or null when it may be.
     *
     * A response carries no identity, so a reply to an exchange that has already given up could be
     * taken for the reply to the next one of the same shape. See [Atc3AnswerGate] for
     * what is known about the pump that is not in the frame's shape.
     *
     * A frame refused here is not handed to [handleFrame] at all. Applying it and then declining to
     * count it as the answer would be the worse of both: the state would already have been
     * overwritten by the time anyone decided the frame was stale.
     */
    private fun lateAnswer(frame: Atc3ResponseFrame): String? {
        // This asks the armed matcher a question onDataReceived is about to ask it again, and that
        // is safe because of an invariant every matcher in this file holds: each is a pure
        // predicate over frameId, objectType and isLastRecord, so calling it twice on one frame
        // costs nothing and decides nothing. A matcher that ever came to hold state -- counting
        // frames, remembering what it has seen -- would break this, and would have to be given its
        // answer once and passed along rather than asked twice.
        val second = synchronized(waitLock) {
            answerGate.isSecondAnswer(pendingMatch?.invoke(frame) == true)
        }
        if (second) return "second answer to one request"
        return null
    }

    private fun handleFrame(frame: Atc3ResponseFrame) {
        // The heartbeat first, because it can land in the middle of anything and is an answer to
        // nothing. Reading it as one is the mistake to avoid: it can arrive inside the read window of an opcode that answers nothing at all
        // and make that opcode look as though it answered.
        if (frame.frameId == Atc3Const.MODE_HEARTBEAT && frame.raw.size == Atc3Const.HEARTBEAT_FRAME_SIZE) {
            // By size, not by object byte: the object byte is the period somebody set, and a period
            // another client left behind must still count as the pump being there.
            atc3BLE.noteHeartbeat()
            trace.event(Atc3TraceCat.BLE, "heartbeat", "period" to frame.objectType?.toInt())
            return
        }
        if (frame.frameId == Atc3Const.MODE_CONTROL) {
            when (frame.objectType) {
                Atc3Const.ObjectType.REJECTED                -> {
                    rejected = true
                    aapsLogger.error(LTag.PUMPCOMM, "ATC3: pump refused the command")
                }

                Atc3Const.ObjectType.BOLUS_PROGRESS          -> handleBolusProgress(frame)
                Atc3Const.ObjectType.BOLUS_COMPLETED         -> handleBolusCompleted(frame)

                Atc3Const.ObjectType.EXTENDED_BOLUS_PROGRESS -> {
                    // The driver never asks for an extended bolus, so this frame only arrives when
                    // one was started elsewhere. It is not progress of ours and must not be counted
                    // as such.
                    val delivered = if (frame.has(2, 2)) frame.u16le(2) * Atc3Const.DOSE_SCALE else 0.0
                    aapsLogger.debug(LTag.PUMPCOMM, "ATC3: extended bolus progress $delivered U, not ours")
                }

                else                                         ->
                    aapsLogger.debug(LTag.PUMPCOMM, "ATC3: control answer, object ${frame.objectType}")
            }
            return
        }
        if (frame.frameId != Atc3Const.MODE_HISTORY) {
            aapsLogger.debug(LTag.PUMPCOMM, "ATC3: unhandled frame $frame")
            return
        }
        when (frame.objectType) {
            Atc3Const.ReadOpcode.STATUS_V1      -> handleStatusV1(frame)
            Atc3Const.ReadOpcode.BASAL_PROFILES -> handleBasalProfile(frame)

            Atc3Const.ReadOpcode.VERSION        ->
                Atc3Version.decode(frame)?.let {
                    atc3Pump.version = it
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: firmware ${it.firmwareText}, protocol ${it.protocolText}"
                    )
                    rxBus.send(EventAtc3PumpDataChanged())
                } ?: aapsLogger.error(LTag.PUMPCOMM, "ATC3: handshake answer too short, size ${frame.raw.size}")

            Atc3Const.ObjectType.LATEST_BOLUS,
            Atc3Const.ObjectType.BOLUS_RECORD   ->
                Atc3BolusRecord.decode(frame)?.let {
                    val ours = synchronized(waitLock) {
                        if (bolusBurstArmed) {
                            bolusBurst.add(it)
                            bolusRecordCount = frame.recordCount
                        }
                        bolusBurstArmed
                    }
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: bolus record ${it.index}, requested ${it.requestedUnits} delivered ${it.deliveredUnits}" +
                            (if (it.carriesExtendedPart) ", extended ${it.extendedRequestedUnits} delivered ${it.extendedDeliveredUnits}" else "") +
                            (if (ours) "" else ", not ours")
                    )
                }
            Atc3Const.ObjectType.ALARM_RECORD    ->
                Atc3AlarmRecord.decode(frame)?.let {
                    synchronized(waitLock) { if (alarmBurstArmed) alarmBurst.add(it) }
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: alarm record ${it.index}, code ${it.code} ${it.alarm ?: "unknown"} at ${it.timestamp}"
                    )
                }

            Atc3Const.ObjectType.DAILY_STATS    ->
                Atc3DailyStats.decode(frame)?.let {
                    synchronized(waitLock) { if (dailyStatsArmed) dailyStats.add(it) }
                }

            Atc3Const.ObjectType.TBR_ACTIVE     ->
                // A short answer means nothing is running; the decoder tells them apart by size,
                // because the count byte can read zero on a frame carrying a full record.
                Atc3TbrStatus.decode(frame)?.let {
                    activeTbr = it
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: last temporary basal command ${it.amountAsked} since ${it.startTimestamp}, " +
                            "${it.deliveredUnits} U so far"
                    )
                }

            Atc3Const.ReadOpcode.STATUS_V2      ->
                Atc3StatusV2.decode(frame)?.let {
                    atc3Pump.applyStatusV2(it)
                    aapsLogger.debug(LTag.PUMPCOMM, "ATC3: battery ${it.batteryVolts} V, pump reckons ${it.activeInsulinUnits} U on board")
                    rxBus.send(EventAtc3PumpDataChanged())
                }

            Atc3Const.ObjectType.TBR_RECORD     ->
                Atc3TbrRecord.decode(frame)?.let {
                    synchronized(waitLock) { if (tbrRecordBurstArmed) tbrRecordBurst.add(it) }
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: temporary basal record ${it.index}, ${it.amountAsked} for " +
                            "${it.durationMinutes} min from ${it.startTimestamp}, ${it.deliveredUnits} U given"
                    )
                }

            Atc3Const.ObjectType.TBR_FINISHED   ->
                // A pump with nothing finished answers short; the decoder tells them apart by size,
                // as it does for the running temporary basal above.
                Atc3FinishedTbr.decode(frame)?.let {
                    finishedTbr = it
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: last finished temporary basal ${it.amountAsked}, ${it.resultText}, " +
                            "${it.startTimestamp} to ${it.endTimestamp}, ${it.deliveredUnits} U given"
                    )
                }

            Atc3Const.ObjectType.BASAL_CHANGE_RECORD ->
                Atc3BasalChangeRecord.decode(frame)?.let {
                    synchronized(waitLock) { if (basalChangeBurstArmed) basalChangeBurst.add(it) }
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: basal change record ${it.index} at ${it.timestamp}, " +
                            "${it.dailyTotalUnits} U a day"
                    )
                }

            Atc3Const.ObjectType.REFILL_RECORD  ->
                Atc3RefillRecord.decode(frame)?.let {
                    synchronized(waitLock) { if (refillBurstArmed) refillBurst.add(it) }
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: refill record ${it.index} at ${it.timestamp}, ${it.typeText}, ${it.amountUnits} U"
                    )
                }

            Atc3Const.ObjectType.TBR_SHORT      ->
                Atc3TbrShort.decode(frame)?.let {
                    tbrShort = it
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: temporary basal short form ${it.rateUnitsPerHour} U/h, " +
                            "${it.elapsedMinutes} of ${it.durationMinutes} min gone"
                    )
                }

            Atc3Const.ObjectType.BOLUS_CALCULATOR ->
                Atc3BolusCalculator.decode(frame)?.let {
                    bolusCalculator = it
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: bolus calculator ${if (it.enabled) "on" else "off"}, " +
                            "insulin active ${it.activeInsulinMinutes} min, " +
                            "${it.carbRatioInForce.size} carb ratio blocks, " +
                            "${it.sensitivityInForce.size} sensitivity blocks, " +
                            "${it.targetInForce.size} target blocks"
                    )
                }

            else                                ->
                aapsLogger.debug(LTag.PUMPCOMM, "ATC3: unhandled object in $frame")
        }
    }

    private fun handleStatusV1(frame: Atc3ResponseFrame) {
        val status = Atc3StatusV1.decode(frame)
        if (status == null) {
            aapsLogger.error(LTag.PUMPCOMM, "ATC3: Status V1 frame too short, size ${frame.raw.size}")
            return
        }
        atc3Pump.applyStatus(status, dateUtil.now())
        // The same frame carries the pump's own settings, which is the only place they are
        // readable. Without them the settings screens have nothing to build a write from.
        Atc3Settings.decode(frame, preferences.get(Atc3IntNonKey.AlarmDuration))?.let {
            atc3Pump.settings = it
        } ?: aapsLogger.error(LTag.PUMPCOMM, "ATC3: Status V1 too short for the settings block")
        aapsLogger.debug(
            LTag.PUMPCOMM,
            (if (status.suspended) "ATC3: THE PUMP IS SUSPENDED, " else "ATC3: ") +
                "status profile=${status.activeProfileIndex} reservoir=${status.reservoirUnits} " +
                // The temporary basal fields are printed in full because the duration is the one
                // the pump actually took, which is not always the one it was asked for, and
                // nothing else in the driver states it. It is the duration the temporary basal was
                // started for and it does not count down. The elapsed time is a separate field,
                // mirrored by object 0x09.
                "basal=${status.scheduledBasalRate} tbr=${status.tbrActive} " +
                "tbrRate=${status.tbrRate} tbrMode=${status.tbrMode} tbrFor=${status.tbrDurationMinutes}"
        )
        rxBus.send(EventAtc3PumpDataChanged())
    }

    private fun handleBasalProfile(frame: Atc3ResponseFrame) {
        val profile = Atc3BasalProfile.decode(frame)
        if (profile == null) {
            aapsLogger.error(LTag.PUMPCOMM, "ATC3: malformed basal profile frame, size ${frame.raw.size}")
            return
        }
        synchronized(collectedProfiles) { collectedProfiles[profile.index] = profile.rates }
        aapsLogger.debug(
            LTag.PUMPCOMM,
            "ATC3: profile ${profile.index} of ${frame.recordCount}, ${profile.dailyUnits} U/day"
        )
        // Publishing is left to readBasalProfiles, which knows when the answer is over. Doing it
        // here needed the pump to send every profile it claims to have, and it does not.
    }
}
