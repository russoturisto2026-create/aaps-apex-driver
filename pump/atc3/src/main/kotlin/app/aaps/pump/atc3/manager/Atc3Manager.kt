package app.aaps.pump.atc3.manager

import android.os.SystemClock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.clock.Atc3ClockWatch
import app.aaps.pump.atc3.command.Atc3BolusDelivery
import app.aaps.pump.atc3.command.Atc3Failure
import app.aaps.pump.atc3.command.Atc3TbrCancel
import app.aaps.pump.atc3.command.Atc3TbrResult
import app.aaps.pump.atc3.events.EventAtc3PumpDataChanged
import app.aaps.pump.atc3.exchange.Atc3Answer
import app.aaps.pump.atc3.exchange.Atc3Exchange
import app.aaps.pump.atc3.keys.Atc3IntNonKey
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.link.Atc3BLE
import app.aaps.pump.atc3.link.Atc3BtPassword
import app.aaps.pump.atc3.link.Atc3Connection
import app.aaps.pump.atc3.protocol.Atc3AlarmRecord
import app.aaps.pump.atc3.protocol.Atc3BasalProfile
import app.aaps.pump.atc3.protocol.Atc3BolusHistory
import app.aaps.pump.atc3.protocol.Atc3BolusRecord
import app.aaps.pump.atc3.protocol.Atc3DailyStats
import app.aaps.pump.atc3.protocol.Atc3FinishedTbr
import app.aaps.pump.atc3.protocol.Atc3Frame
import app.aaps.pump.atc3.protocol.Atc3Payload
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3RefillRecord
import app.aaps.pump.atc3.protocol.Atc3ResponseFrame
import app.aaps.pump.atc3.protocol.Atc3Settings
import app.aaps.pump.atc3.protocol.Atc3StatusV1
import app.aaps.pump.atc3.protocol.Atc3StatusV2
import app.aaps.pump.atc3.protocol.Atc3TbrRecord
import app.aaps.pump.atc3.protocol.Atc3TbrStatus
import app.aaps.pump.atc3.protocol.Atc3Version
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the driver asks of the pump: reads, control commands and the bolus, each carried by
 * [Atc3Exchange] one request and its answer at a time, and what the pump's frames say about
 * itself put into [Atc3PumpState].
 *
 * Callers on the command queue thread get a definite outcome. Nothing is reported as successful
 * on the strength of an acknowledgement alone: a control operation is confirmed by reading the
 * pump's own state back afterwards.
 */
@Singleton
class Atc3Manager @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val atc3BLE: Atc3BLE,
    private val pumpState: Atc3PumpState,
    private val trace: Atc3Trace,
    private val clockWatch: Atc3ClockWatch,
    private val connection: Atc3Connection,
    private val exchange: Atc3Exchange,
    private val bolusDelivery: Atc3BolusDelivery
) : Atc3Exchange.FrameSink {

    init {
        exchange.frameSink = this
    }

    /** Profiles collected from the current read, keyed by pump profile index. */
    private val collectedProfiles = HashMap<Int, DoubleArray>()

    private val isConnected: Boolean get() = atc3BLE.isConnected

    /** True while the pump will not take another command, see [Atc3Exchange.isBusy]. Connecting is not busy: that breaks AAPS's keepalive. */
    val isBusy: Boolean get() = exchange.isBusy

    override val deliveryRunning: Boolean get() = bolusDelivery.bolusInProgress

    /**
     * Ask a silent pump whether it is still there, on the queue's thread, and drop the link when it
     * does not answer. The silence is looked at again first: another exchange may have proved the link.
     *
     * @return false when the pump was asked and did not answer, and the link has been dropped
     */
    fun probeQuietLink(): Boolean {
        val quiet = atc3BLE.quietForMs
        if (!connection.shouldProbeQuietLink(quiet, atc3BLE.isConnected, isBusy)) return true
        val answered = askStatus() != null
        trace.event(Atc3TraceCat.BLE, "probe", "ok" to answered, "quietMs" to quiet)
        if (answered) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: the pump answered, the link is alive")
            return true
        }
        aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the pump did not answer, dropping the link")
        trace.event(Atc3TraceCat.BLE, "link_lost", "quietMs" to quiet)
        connection.drop("no answer on a quiet link")
        return false
    }

    // Reads

    /** Read Status V1, what most of what the driver knows comes from. */
    fun readStatus(): Atc3PumpState.StatusCard? =
        askStatus()?.takeIf { Atc3StatusV1.decode(it) != null }?.let { pumpState.statusCard }

    /** Ask for the status: the frame that answered, decodable or not. */
    private fun askStatus(): Atc3ResponseFrame? = exchange.sendAndWait(Atc3Protocol.ReadOpcode.STATUS_V1) {
        it.frameId == Atc3Protocol.MODE_HISTORY && it.objectType == Atc3Protocol.ReadOpcode.STATUS_V1
    }

    /** Read the firmware version, which says whether the pump can be given a Bluetooth password. */
    fun readVersion(): Atc3Version? = exchange.sendAndWait(Atc3Protocol.ReadOpcode.VERSION) {
        it.frameId == Atc3Protocol.MODE_HISTORY && it.objectType == Atc3Protocol.ReadOpcode.VERSION
    }?.takeIf { Atc3Version.decode(it) != null }?.let { pumpState.version }

    /** Read Status V2, for the battery. */
    fun readStatusV2(): Atc3StatusV2? = exchange.sendAndWait(Atc3Protocol.ReadOpcode.STATUS_V2) {
        it.frameId == Atc3Protocol.MODE_HISTORY && it.objectType == Atc3Protocol.ObjectType.STATUS_V2
    }?.takeIf { Atc3StatusV2.decode(it) != null }?.let { pumpState.statusV2 }

    /**
     * Read the last temporary basal command, for its start. Called only while Status V1 says one runs:
     * the answer stays the same after it ends.
     *
     * @return the record, or null when none arrived
     */
    fun readActiveTbr(): Atc3TbrStatus? = exchange.sendAndWait(Atc3Protocol.ReadOpcode.TBR_ACTIVE) {
        it.frameId == Atc3Protocol.MODE_HISTORY && it.objectType == Atc3Protocol.ObjectType.TBR_ACTIVE
    }?.let { Atc3TbrStatus.decode(it) }

    /** @return every alarm record the pump holds, newest first, or null when the read failed */
    fun readAlarmHistory(): List<Atc3AlarmRecord>? =
        readRecords(
            "alarm history", Atc3Protocol.ObjectType.ALARM_RECORD,
            Atc3Protocol.GROUP_QUERY, Atc3Protocol.HistoryOpcode.ALARM_HISTORY, Atc3Frame.NO_PARAMETER,
            decode = Atc3AlarmRecord::decode
        )?.items

    /** @return the finished temporary basals, newest first, or null when the read failed */
    fun readTbrHistory(): List<Atc3TbrRecord>? =
        readRecords(
            "temporary basal history", Atc3Protocol.ObjectType.TBR_RECORD,
            Atc3Protocol.GROUP_CONTROL, Atc3Protocol.HistoryOpcode.TBR_HISTORY, Atc3Protocol.PARAMETER_LATEST,
            burstFrameCap = Atc3Protocol.ALIAS_ANSWER_FRAMES,
            decode = Atc3TbrRecord::decode
        )?.items

    /** @return the refills and primes the pump holds, or null when the read failed */
    fun readRefillHistory(): List<Atc3RefillRecord>? =
        readRecords(
            "reservoir refill history", Atc3Protocol.ObjectType.REFILL_RECORD,
            Atc3Protocol.GROUP_QUERY, Atc3Protocol.HistoryOpcode.REFILL_HISTORY, Atc3Frame.NO_PARAMETER,
            decode = Atc3RefillRecord::decode
        )?.items

    /** Read the last temporary basal that finished, for when and how it ended. */
    fun readFinishedTbr(): Atc3FinishedTbr? = exchange.sendAndWait(Atc3Protocol.ReadOpcode.TBR_FINISHED) {
        it.frameId == Atc3Protocol.MODE_HISTORY && it.objectType == Atc3Protocol.ObjectType.TBR_FINISHED
    }?.let { Atc3FinishedTbr.decode(it) }

    /** @return one record per day the pump holds, or null when the read failed */
    fun readDailyStats(): List<Atc3DailyStats>? =
        readRecords(
            "daily statistics", Atc3Protocol.ObjectType.DAILY_STATS,
            Atc3Protocol.GROUP_QUERY, Atc3Protocol.HistoryOpcode.DAILY_STATS_SCREEN, Atc3Frame.NO_PARAMETER,
            decode = Atc3DailyStats::decode
        )?.items

    /**
     * Read the stored basal profiles; true when at least one came. The pump can send fewer than it
     * declares, so the read ends when the pump goes quiet and what arrived is kept.
     */
    fun readBasalProfiles(selectedIndex: Int?): Boolean {
        synchronized(collectedProfiles) { collectedProfiles.clear() }
        val isProfile = { frame: Atc3ResponseFrame ->
            frame.frameId == Atc3Protocol.MODE_HISTORY && frame.objectType == Atc3Protocol.ReadOpcode.BASAL_PROFILES
        }
        val answered = exchange.exchange(
            "read 0x%02X".format(Atc3Protocol.ReadOpcode.BASAL_PROFILES),
            match = {
                isProfile(it) &&
                    synchronized(collectedProfiles) { collectedProfiles.size >= it.recordCount && it.recordCount > 0 }
            },
            burstOf = isProfile
        ) {
            exchange.sendRead(Atc3Protocol.ReadOpcode.BASAL_PROFILES)
        }
        if (!answered) return false
        return publishProfiles(selectedIndex)
    }

    /** Hand the profiles that came to [pumpState]; a profile not sent stays an empty slot, so an index keeps its slot. */
    private fun publishProfiles(selectedIndex: Int?): Boolean {
        val profiles = synchronized(collectedProfiles) { HashMap(collectedProfiles) }
        if (profiles.isEmpty()) {
            aapsLogger.error(LTag.PUMPCOMM, "ATC3: the pump sent no basal profiles")
            return false
        }
        val count = maxOf(profiles.keys.max() + 1, Atc3Protocol.PROFILE_COUNT)
        pumpState.profiles = Atc3PumpState.Profiles(
            Array(count) { index -> profiles[index] ?: DoubleArray(Atc3Protocol.BASAL_SLOTS) },
            selectedIndex
        )
        pumpState.lastConnection = dateUtil.now()
        aapsLogger.debug(LTag.PUMPCOMM, "ATC3: ${profiles.size} basal profile(s) read of $count slots")
        return true
    }

    // Basal profile

    /**
     * Put [rates] into the driver's profile slot and prove it took: the slot made active, the same
     * rates read back within [tolerance].
     *
     * @return null on success, else what did not match
     */
    fun writeBasalProfile(rates: DoubleArray, tolerance: Double): String? {
        require(rates.size == Atc3Protocol.BASAL_SLOTS) { "expected ${Atc3Protocol.BASAL_SLOTS} rates" }

        val card = readStatus() ?: return "pump did not report its status"

        val target = Atc3Const.DRIVER_PROFILE_INDEX
        if (card.status.activeProfileIndex != target) {
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: pump is on profile ${card.status.activeProfileIndex}, selecting $target before writing"
            )
            if (!switchToProfile(target)) {
                return "pump stayed on profile ${pumpState.activeProfileIndex} instead of $target"
            }
        }

        if (exchange.sendControlAndWait(Atc3Protocol.ControlOpcode.WRITE_BASAL_PROFILE, Atc3Payload.basalProfile(rates)) != Atc3Answer.ACCEPTED) {
            return "pump did not acknowledge the basal profile"
        }

        // Read back, a few times: the stored profile follows the acknowledgement with a delay.
        var lastProblem = "pump did not return the basal profiles"
        repeat(Atc3Const.EFFECT_POLL_ATTEMPTS) {
            SystemClock.sleep(Atc3Const.EFFECT_POLL_INTERVAL_MS)
            if (!readBasalProfiles(target)) return@repeat
            val stored = pumpState.profiles?.rates?.getOrNull(target)
            if (stored == null) {
                lastProblem = "pump did not return profile $target"
                return@repeat
            }
            val differing = Atc3PumpState.differingSlots(rates, stored, tolerance)
            if (differing.isEmpty()) return null
            lastProblem = "pump stored different rates: " + differing.joinToString(", ", limit = 5) {
                "${Atc3PumpState.slotLabel(it)} wanted ${rates[it]} got ${stored[it]}"
            }
            aapsLogger.debug(LTag.PUMP, "ATC3: read back does not match yet, $lastProblem")
        }
        return lastProblem
    }

    /** Read the recent boluses; when records have aged out of this answer, [readFullBolusHistory] has the rest. */
    fun readBolusHistory(): Atc3BolusHistory? =
        readRecords(
            "bolus history", Atc3Protocol.ObjectType.LATEST_BOLUS,
            Atc3Protocol.GROUP_CONTROL, Atc3Protocol.HistoryOpcode.LATEST_BOLUS, Atc3Protocol.PARAMETER_LATEST,
            burstFrameCap = Atc3Protocol.ALIAS_ANSWER_FRAMES,
            decode = Atc3BolusRecord::decode
        )?.let { Atc3BolusHistory(it.items, it.recordCount) }

    /** Read every bolus the pump holds, for when records have aged out of [readBolusHistory]. */
    fun readFullBolusHistory(): Atc3BolusHistory? =
        readRecords(
            "full bolus history", Atc3Protocol.ObjectType.BOLUS_RECORD,
            Atc3Protocol.GROUP_QUERY, Atc3Protocol.HistoryOpcode.BOLUS_HISTORY, Atc3Frame.NO_PARAMETER,
            decode = Atc3BolusRecord::decode
        )?.let { Atc3BolusHistory(it.items, it.recordCount) }

    /** Records decoded from one burst, and the record count the last of them declared. */
    private class Records<T>(val items: List<T>, val recordCount: Int)

    /**
     * One read answered by a burst of [objectType] records, each decoded with [decode]; only frames of
     * this read are taken, not another client's.
     *
     * @param burstFrameCap the most frames the answer can bring, for an answer that ends by its count
     * @return the records in arrival order, or null when the read failed
     */
    private fun <T : Any> readRecords(
        what: String,
        objectType: Byte,
        group: Byte,
        opcode: Byte,
        parameter: Byte,
        burstFrameCap: Int = 0,
        decode: (Atc3ResponseFrame) -> T?
    ): Records<T>? {
        val isRecord = { frame: Atc3ResponseFrame -> frame.frameId == Atc3Protocol.MODE_HISTORY && frame.objectType == objectType }
        val frames = exchange.exchangeForBurst(what, isRecord, burstFrameCap) {
            exchange.send(group, Atc3Protocol.MODE_HISTORY, opcode, parameter)
        } ?: return null
        val decoded = frames.mapNotNull { frame -> decode(frame)?.let { frame to it } }
        return Records(decoded.map { it.second }, decoded.lastOrNull()?.first?.recordCount ?: -1)
    }

    // Temporary basal

    /**
     * Start an absolute temporary basal and prove from the status that it runs. Reports the moment of
     * acceptance and the duration the pump took, whole quarter hours, which AAPS's row is built from.
     */
    fun setTempBasal(rate: Double, durationMinutes: Int): Atc3TbrResult {
        val durationUnits = Math.round(durationMinutes.toDouble() / Atc3Protocol.TbrPayload.DURATION_UNIT_MINUTES).toInt()
        if (durationUnits <= 0) {
            return Atc3TbrResult(0L, rate, 0, "duration $durationMinutes is shorter than the pump's smallest step")
        }

        val payload = Atc3Payload.absoluteTbr(rate, durationUnits)
        val wantedMinutes = durationUnits * Atc3Protocol.TbrPayload.DURATION_UNIT_MINUTES
        var acceptedAt = 0L
        val failure = commandUntilConfirmed(
            what = "a temporary basal of $rate U/h for $wantedMinutes min",
            send = {
                val answer = exchange.sendControlAndWait(Atc3Protocol.ControlOpcode.START_TBR, payload)
                if (answer == Atc3Answer.ACCEPTED && acceptedAt == 0L) acceptedAt = dateUtil.now()
                answer
            },
            // The duration the status reports is the one it was started for, and does not count down.
            confirmed = {
                readStatus()?.let {
                    it.tbrActive && Math.abs(it.tbrRate - rate) <= Atc3Protocol.DOSE_SCALE && it.tbrDurationMinutes == wantedMinutes
                } == true
            }
        )
        // Never reached: no moment of acceptance to report.
        if (failure != null && acceptedAt == 0L) return Atc3TbrResult(0L, rate, wantedMinutes, failure)
        val card = pumpState.statusCard
        return Atc3TbrResult(acceptedAt, card?.tbrRate ?: 0.0, card?.tbrDurationMinutes ?: 0, failure)
    }

    /** Cancel the running temporary basal and prove from the status that it stopped. */
    fun cancelTempBasal(): Atc3TbrCancel {
        var acceptedAt = 0L
        val failure = commandUntilConfirmed(
            what = "cancelling the temporary basal",
            // Cancelling twice leaves the pump where cancelling once did.
            send = {
                val answer = exchange.sendControlAndWait(Atc3Protocol.ControlOpcode.CANCEL_TBR, ByteArray(0))
                if (answer == Atc3Answer.ACCEPTED && acceptedAt == 0L) acceptedAt = dateUtil.now()
                answer
            },
            confirmed = { readStatus()?.tbrActive == false }
        )
        return Atc3TbrCancel(acceptedAt, failure)
    }

    /** Make [index] the active profile and confirm it from the status, which follows the acknowledgement with a delay. */
    fun switchToProfile(index: Int): Boolean {
        if (exchange.sendControlAndWait(Atc3Protocol.ControlOpcode.SWITCH_PROFILE, byteArrayOf(index.toByte())) != Atc3Answer.ACCEPTED) {
            aapsLogger.error(LTag.PUMP, "ATC3: no acknowledgement for selecting profile $index")
            return false
        }
        repeat(Atc3Const.EFFECT_POLL_ATTEMPTS) { attempt ->
            SystemClock.sleep(Atc3Const.EFFECT_POLL_INTERVAL_MS)
            val card = readStatus()
            if (card?.status?.activeProfileIndex == index) {
                aapsLogger.debug(LTag.PUMP, "ATC3: profile $index active after ${attempt + 1} checks")
                return true
            }
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: waiting for profile $index, pump reports ${card?.status?.activeProfileIndex}, check ${attempt + 1}"
            )
        }
        return false
    }

    // Delivery state

    /**
     * Stop or resume the pump and prove it from the status.
     *
     * @return null on success, else what did not happen
     */
    fun setSuspended(suspended: Boolean): Atc3Failure? {
        when (exchange.sendControlAndWait(Atc3Protocol.ControlOpcode.SUSPEND, Atc3Payload.suspend(suspended))) {
            Atc3Answer.REFUSED  -> return Atc3Failure("the pump refused to change its delivery state", refused = true)
            Atc3Answer.NONE     -> return Atc3Failure("the pump did not answer")
            Atc3Answer.ACCEPTED -> Unit
        }
        repeat(Atc3Const.EFFECT_POLL_ATTEMPTS) {
            SystemClock.sleep(Atc3Const.EFFECT_POLL_INTERVAL_MS)
            if (readStatus()?.status?.suspended == suspended) return null
            aapsLogger.debug(LTag.PUMP, "ATC3: waiting for the pump to be ${if (suspended) "stopped" else "running"}")
        }
        return Atc3Failure("the pump still reports itself as ${if (pumpState.suspended) "stopped" else "running"}")
    }

    // Clock

    /**
     * Put the phone's time into the pump; done when the pump acknowledges it.
     *
     * @return null on success, else what did not happen
     */
    fun writeClock(nowMs: Long): String? {
        when (exchange.sendControlAndWait(Atc3Protocol.ControlOpcode.SET_CLOCK, Atc3StatusV1.encodeClock(nowMs))) {
            Atc3Answer.REFUSED  -> return "the pump refused the clock"
            Atc3Answer.NONE     -> return "the pump did not acknowledge the clock"
            Atc3Answer.ACCEPTED -> Unit
        }
        // What the bolus history said about the clock was about the one just replaced.
        clockWatch.forget()
        return null
    }

    // Settings

    /**
     * Write the whole settings block [wanted], built from what the pump last reported, and prove it
     * from the status, see [Atc3Settings.mirroredFieldsMatch].
     *
     * @return null on success, else what did not match
     */
    fun writeSettings(wanted: Atc3Settings): Atc3Failure? {
        when (exchange.sendControlAndWait(Atc3Protocol.ControlOpcode.WRITE_SETTINGS, wanted.toPayload())) {
            Atc3Answer.REFUSED  -> return Atc3Failure("the pump refused the settings", refused = true)
            Atc3Answer.NONE     -> return Atc3Failure("the pump did not acknowledge the settings")
            Atc3Answer.ACCEPTED -> Unit
        }
        // Remembered only once accepted: a refused write sets nothing.
        preferences.put(Atc3IntNonKey.AlarmDuration, wanted.alarmDuration)

        var lastProblem = "the pump did not report its settings back"
        repeat(Atc3Const.EFFECT_POLL_ATTEMPTS) {
            SystemClock.sleep(Atc3Const.EFFECT_POLL_INTERVAL_MS)
            val stored = readStatus()?.settings ?: return@repeat
            if (wanted.mirroredFieldsMatch(stored)) return null
            lastProblem = "the pump stored different settings"
            aapsLogger.debug(LTag.PUMP, "ATC3: settings read back does not match yet, wanted $wanted got $stored")
        }
        return Atc3Failure(lastProblem)
    }

    /**
     * Replace the pump's Bluetooth password; the acknowledgement is all the confirmation there is. The
     * caller stores the new value before the link, which the pump drops after a change, comes back.
     *
     * @return null on success, else what went wrong
     */
    fun setBtPassword(value: Int): Atc3Failure? {
        if (!Atc3BtPassword.isSettable(value)) return Atc3Failure("out of range")
        return when (exchange.sendControlAndWait(Atc3Protocol.ControlOpcode.SET_BT_PASSWORD, Atc3BtPassword.changePayload(value))) {
            Atc3Answer.REFUSED  -> Atc3Failure("the pump refused the new password", refused = true)
            Atc3Answer.NONE     -> Atc3Failure("the pump did not acknowledge the new password")
            Atc3Answer.ACCEPTED -> null
        }
    }

    private var heartbeatPeriodSet = false

    /** Put the pump's heartbeat on the period the link watch expects, once per link; any answer settles it. */
    fun ensureHeartbeatPeriod() {
        if (heartbeatPeriodSet || !isConnected) return
        val answer = exchange.sendControlAndWait(
            Atc3Protocol.ControlOpcode.SET_HEARTBEAT,
            byteArrayOf(Atc3Const.HEARTBEAT_PERIOD_MINUTES.toByte(), 0x00)
        )
        val ok = answer == Atc3Answer.ACCEPTED
        heartbeatPeriodSet = answer != Atc3Answer.NONE
        trace.event(Atc3TraceCat.SESS, "heartbeat_period", "minutes" to Atc3Const.HEARTBEAT_PERIOD_MINUTES, "ok" to ok)
        if (!ok) aapsLogger.warn(LTag.PUMP, "ATC3: the heartbeat period was not set, " + if (answer == Atc3Answer.REFUSED) "the pump refused it" else "no answer")
    }

    /**
     * Send a command and repeat it until the pump's state shows it took, within
     * [Atc3Const.COMMAND_BUDGET_MS]. An unanswered command may have been carried out, so the state is
     * read before it is sent again; a refusal ends it at once.
     *
     * @param send carries the command; its answer
     * @param confirmed reads the pump's state and says whether it is what was asked for
     * @return null when confirmed, else what went wrong
     */
    private fun commandUntilConfirmed(
        what: String,
        send: () -> Atc3Answer,
        confirmed: () -> Boolean
    ): String? {
        val deadline = SystemClock.elapsedRealtime() + Atc3Const.COMMAND_BUDGET_MS
        var sends = 0
        var lastFailure = "the pump did not answer"
        while (sends < Atc3Const.COMMAND_SEND_ATTEMPTS && SystemClock.elapsedRealtime() < deadline) {
            // Nothing sent or read on a link that is gone says anything: fail at once.
            if (!atc3BLE.isConnected) return "the link is down"
            sends++
            val answer = send()
            if (answer == Atc3Answer.REFUSED) return "the pump refused $what"
            if (answer == Atc3Answer.NONE) {
                // Unanswered may still be done: look before sending again.
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

    // The link

    override fun onLinkUp() {
        heartbeatPeriodSet = false
        synchronized(collectedProfiles) { collectedProfiles.clear() }
        // A different pump inherits nothing of what was read from the last one.
        val serial = preferences.get(Atc3StringKey.Atc3SerialNumber)
        if (pumpState.serialNumber.isNotEmpty() && pumpState.serialNumber != serial) {
            aapsLogger.debug(LTag.PUMP, "ATC3: a different pump answered, forgetting what was known of the last one")
            trace.event(Atc3TraceCat.SESS, "pump_changed", "was" to pumpState.serialNumber, "now" to serial)
            pumpState.reset()
        }
        pumpState.serialNumber = serial
    }

    override fun onFrame(frame: Atc3ResponseFrame) {
        if (frame.frameId == Atc3Protocol.MODE_CONTROL) {
            when (frame.objectType) {
                Atc3Protocol.ObjectType.REJECTED                ->
                    aapsLogger.error(LTag.PUMPCOMM, "ATC3: pump refused the command")

                Atc3Protocol.ObjectType.BOLUS_PROGRESS          -> bolusDelivery.onProgress(frame)
                Atc3Protocol.ObjectType.BOLUS_COMPLETED         -> bolusDelivery.onCompleted(frame)

                Atc3Protocol.ObjectType.EXTENDED_BOLUS_PROGRESS -> {
                    // Started elsewhere: the driver never asks for an extended bolus.
                    val delivered = if (frame.has(2, 2)) frame.u16le(2) * Atc3Protocol.DOSE_SCALE else 0.0
                    aapsLogger.debug(LTag.PUMPCOMM, "ATC3: extended bolus progress $delivered U, not ours")
                }

                else                                         ->
                    aapsLogger.debug(LTag.PUMPCOMM, "ATC3: control answer, object ${frame.objectType}")
            }
            return
        }
        if (frame.frameId != Atc3Protocol.MODE_HISTORY) {
            aapsLogger.debug(LTag.PUMPCOMM, "ATC3: unhandled frame $frame")
            return
        }
        when (frame.objectType) {
            Atc3Protocol.ReadOpcode.STATUS_V1      -> handleStatusV1(frame)
            Atc3Protocol.ReadOpcode.BASAL_PROFILES -> handleBasalProfile(frame)

            Atc3Protocol.ReadOpcode.VERSION        ->
                Atc3Version.decode(frame)?.let {
                    pumpState.version = it
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: firmware ${it.firmwareText}, protocol ${it.protocolText}"
                    )
                    rxBus.send(EventAtc3PumpDataChanged())
                } ?: aapsLogger.error(LTag.PUMPCOMM, "ATC3: handshake answer too short, size ${frame.raw.size}")

            Atc3Protocol.ObjectType.LATEST_BOLUS,
            Atc3Protocol.ObjectType.BOLUS_RECORD   ->
                Atc3BolusRecord.decode(frame)?.let {
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: bolus record ${it.index}, requested ${it.requestedUnits} delivered ${it.deliveredUnits}" +
                            (if (it.carriesExtendedPart) ", extended ${it.extendedRequestedUnits} delivered ${it.extendedDeliveredUnits}" else "")
                    )
                }

            Atc3Protocol.ObjectType.ALARM_RECORD    ->
                Atc3AlarmRecord.decode(frame)?.let {
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: alarm record ${it.index}, code ${it.code} ${it.alarm ?: "unknown"} at ${it.timestamp}"
                    )
                }

            Atc3Protocol.ObjectType.TBR_ACTIVE     ->
                Atc3TbrStatus.decode(frame)?.let {
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: last temporary basal command ${it.amountAsked} since ${it.startTimestamp}, " +
                            "${it.deliveredUnits} U so far"
                    )
                }

            Atc3Protocol.ReadOpcode.STATUS_V2      ->
                Atc3StatusV2.decode(frame)?.let {
                    pumpState.applyStatusV2(it)
                    aapsLogger.debug(LTag.PUMPCOMM, "ATC3: battery ${it.batteryVolts} V, pump reckons ${it.activeInsulinUnits} U on board")
                    rxBus.send(EventAtc3PumpDataChanged())
                }

            Atc3Protocol.ObjectType.TBR_RECORD     ->
                Atc3TbrRecord.decode(frame)?.let {
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: temporary basal record ${it.index}, ${it.amountAsked} for " +
                            "${it.durationMinutes} min from ${it.startTimestamp}, ${it.deliveredUnits} U given"
                    )
                }

            Atc3Protocol.ObjectType.TBR_FINISHED   ->
                Atc3FinishedTbr.decode(frame)?.let {
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: last finished temporary basal ${it.amountAsked}, ${it.resultText}, " +
                            "${it.startTimestamp} to ${it.endTimestamp}, ${it.deliveredUnits} U given"
                    )
                }

            Atc3Protocol.ObjectType.REFILL_RECORD  ->
                Atc3RefillRecord.decode(frame)?.let {
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "ATC3: refill record ${it.index} at ${it.timestamp}, ${it.typeText}, ${it.amountUnits} U"
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
        val settings = Atc3Settings.decode(frame, preferences.get(Atc3IntNonKey.AlarmDuration))
        if (settings == null) aapsLogger.error(LTag.PUMPCOMM, "ATC3: Status V1 too short for the settings block")
        pumpState.applyStatus(status, dateUtil.now(), settings)
        aapsLogger.debug(
            LTag.PUMPCOMM,
            (if (status.suspended) "ATC3: THE PUMP IS SUSPENDED, " else "ATC3: ") +
                "status profile=${status.activeProfileIndex} reservoir=${status.reservoirUnits} " +
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
        // Kept by readBasalProfiles, which knows when the answer is over.
    }

    companion object {
    }
}
