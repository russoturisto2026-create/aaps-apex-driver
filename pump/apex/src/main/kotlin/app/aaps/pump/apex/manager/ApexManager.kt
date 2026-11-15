package app.aaps.pump.apex.manager

import android.os.SystemClock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.ApexConst
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.command.ApexBolusDelivery
import app.aaps.pump.apex.command.ApexClockWrite
import app.aaps.pump.apex.command.ApexFailure
import app.aaps.pump.apex.events.EventApexPumpDataChanged
import app.aaps.pump.apex.exchange.ApexAnswer
import app.aaps.pump.apex.exchange.ApexExchange
import app.aaps.pump.apex.keys.ApexIntKey
import app.aaps.pump.apex.keys.ApexIntNonKey
import app.aaps.pump.apex.keys.ApexStringKey
import app.aaps.pump.apex.link.ApexBLE
import app.aaps.pump.apex.link.ApexBtPassword
import app.aaps.pump.apex.link.ApexConnection
import app.aaps.pump.apex.protocol.ApexBasalProfile
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexFinishedTbr
import app.aaps.pump.apex.protocol.ApexFinishedTbrAnswer
import app.aaps.pump.apex.protocol.ApexFrame
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexPayload
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.protocol.ApexSettings
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.protocol.ApexStatusV2
import app.aaps.pump.apex.protocol.ApexLastTbrCommand
import app.aaps.pump.apex.protocol.ApexTbrStatus
import app.aaps.pump.apex.protocol.ApexVersion
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * What the driver asks of the pump: reads, control commands and the bolus, each carried by
 * [ApexExchange] one request and its answer at a time, and what the pump's frames say about
 * itself put into [ApexPumpState]. Times in the frames stay the pump's wall clock keys; the
 * account turns them into moments.
 *
 * Callers on the command queue thread get a definite outcome. Nothing is reported as successful
 * on the strength of an acknowledgement alone: a control operation is confirmed by reading the
 * pump's own state back afterwards.
 */
@SingleIn(AppScope::class)
@Inject
class ApexManager(
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val apexBLE: ApexBLE,
    private val pumpState: ApexPumpState,
    private val trace: ApexTrace,
    private val connection: ApexConnection,
    private val exchange: ApexExchange,
    private val bolusDelivery: ApexBolusDelivery
) : ApexExchange.FrameSink {

    init {
        exchange.frameSink = this
    }

    /** Profiles collected from the current read, keyed by pump profile index. */
    private val collectedProfiles = HashMap<Int, DoubleArray>()

    /** True while the pump will not take another command, see [ApexExchange.isBusy]. Connecting is not busy: that breaks AAPS's keepalive. */
    val isBusy: Boolean get() = exchange.isBusy

    override val deliveryRunning: Boolean get() = bolusDelivery.bolusInProgress

    // Reads

    /** Read Status V1, what most of what the driver knows comes from. A frame that does not decode is asked for again. */
    fun readStatus(): ApexPumpState.StatusCard? =
        exchange.sendAndWait(ApexProtocol.ReadOpcode.STATUS_V1, valid = { ApexStatusV1.decode(it) != null }) {
            it.frameId == ApexProtocol.MODE_HISTORY && it.objectType == ApexProtocol.ReadOpcode.STATUS_V1
        }?.takeIf { ApexStatusV1.decode(it) != null }?.let { pumpState.statusCard }

    /** Read the firmware version, which says whether the pump can be given a Bluetooth password. */
    fun readVersion(): ApexVersion? = exchange.sendAndWait(ApexProtocol.ReadOpcode.VERSION, valid = { ApexVersion.decode(it) != null }) {
        it.frameId == ApexProtocol.MODE_HISTORY && it.objectType == ApexProtocol.ReadOpcode.VERSION
    }?.takeIf { ApexVersion.decode(it) != null }?.let { pumpState.version }

    /** Read Status V2, for the battery. */
    fun readStatusV2(): ApexStatusV2? = exchange.sendAndWait(ApexProtocol.ReadOpcode.STATUS_V2, valid = { ApexStatusV2.decode(it) != null }) {
        it.frameId == ApexProtocol.MODE_HISTORY && it.objectType == ApexProtocol.ReadOpcode.STATUS_V2
    }?.takeIf { ApexStatusV2.decode(it) != null }?.let { pumpState.statusV2 }

    /**
     * Read the last temporary basal command the pump accepted. The answer stays the same after that
     * temporary basal ends or is cancelled, until the next command. A pump that has had no command
     * since a reset answers with zeros: an answer, not a silence, but only while no temporary basal
     * runs. With one running, [tbrRuns], the status says there was a command, and zeros, like a
     * frame that does not decode, are a broken answer: it is asked for again, see `ApexExchange.ASKS`.
     *
     * @return the answer, or null when no sound one came
     */
    fun readLastTbrCommand(tbrRuns: Boolean): ApexLastTbrCommand? {
        val sound = { frame: ApexResponseFrame -> ApexTbrStatus.decode(frame) != null || (!tbrRuns && ApexTbrStatus.isNone(frame)) }
        val frame = exchange.sendAndWait(ApexProtocol.ReadOpcode.TBR_ACTIVE, valid = sound) {
            it.frameId == ApexProtocol.MODE_HISTORY && it.objectType == ApexProtocol.ReadOpcode.TBR_ACTIVE
        } ?: return null
        if (!sound(frame)) {
            aapsLogger.warn(LTag.PUMPCOMM, "Apex: the last temporary basal command came unsound every time${if (tbrRuns) ", though one runs" else ""}: ${hex(frame.raw)}")
            return null
        }
        val command = ApexTbrStatus.decode(frame)
        if (command == null) aapsLogger.debug(LTag.PUMPCOMM, "Apex: no temporary basal command since the pump was reset")
        return ApexLastTbrCommand(command, frame.raw)
    }

    /**
     * Read the last finished temporary basal: how and when it ended. A pump on which none has
     * finished since a reset answers with zeros or a short frame: an answer, not a silence.
     *
     * The object is read when a temporary basal the driver holds open no longer runs: the pump
     * holds its end, unless it was reset since. An empty answer then is a fault far more
     * often than a reset, and the pump builds the object anew for every read: it is asked again in
     * the same count as a silence, see `ApexExchange.ASKS`, and an answer that stays empty is what
     * the pump holds.
     *
     * @return the answer, or null when none arrived
     */
    fun readFinishedTbr(): ApexFinishedTbrAnswer? {
        val frame = exchange.sendAndWait(ApexProtocol.ReadOpcode.TBR_FINISHED, valid = { ApexFinishedTbr.decode(it) != null }) {
            it.frameId == ApexProtocol.MODE_HISTORY && it.objectType == ApexProtocol.ReadOpcode.TBR_FINISHED
        } ?: return null
        val finished = ApexFinishedTbr.decode(frame)
        if (finished != null) aapsLogger.debug(LTag.PUMPCOMM, "Apex: last finished temporary basal ${finished.amountAsked} since key ${finished.startClockSeconds}, ${finished.resultText} at key ${finished.endClockSeconds}")
        else aapsLogger.debug(LTag.PUMPCOMM, "Apex: no temporary basal has finished since the pump was reset")
        return ApexFinishedTbrAnswer(finished)
    }

    /**
     * Read the stored basal profiles; true when at least one came. The pump can send fewer than it
     * declares, so the read ends when the pump goes quiet and what arrived is kept.
     */
    fun readBasalProfiles(selectedIndex: Int?): Boolean {
        synchronized(collectedProfiles) { collectedProfiles.clear() }
        val isProfile = { frame: ApexResponseFrame ->
            frame.frameId == ApexProtocol.MODE_HISTORY && frame.objectType == ApexProtocol.ReadOpcode.BASAL_PROFILES
        }
        // A burst is asked again here, not in the exchange: a silent read counts as one of the asks.
        val answered = (1..ApexExchange.ASKS).any { ask ->
            exchange.exchange(
                "read 0x%02X, ask $ask of ${ApexExchange.ASKS}".format(ApexProtocol.ReadOpcode.BASAL_PROFILES),
                match = {
                    isProfile(it) &&
                        synchronized(collectedProfiles) { collectedProfiles.size >= it.recordCount && it.recordCount > 0 }
                },
                burstOf = isProfile
            ) {
                exchange.sendRead(ApexProtocol.ReadOpcode.BASAL_PROFILES)
            }
        }
        if (!answered) return false
        return publishProfiles(selectedIndex)
    }

    /** Hand the profiles that came to [pumpState]; a profile not sent stays an empty slot, so an index keeps its slot. */
    private fun publishProfiles(selectedIndex: Int?): Boolean {
        val profiles = synchronized(collectedProfiles) { HashMap(collectedProfiles) }
        if (profiles.isEmpty()) {
            aapsLogger.error(LTag.PUMPCOMM, "Apex: the pump sent no basal profiles")
            return false
        }
        val count = maxOf(profiles.keys.max() + 1, ApexProtocol.PROFILE_COUNT)
        pumpState.profiles = ApexPumpState.Profiles(
            Array(count) { index -> profiles[index] ?: DoubleArray(ApexProtocol.BASAL_SLOTS) },
            selectedIndex
        )
        pumpState.lastConnection = dateUtil.now()
        aapsLogger.debug(LTag.PUMPCOMM, "Apex: ${profiles.size} basal profile(s) read of $count slots")
        return true
    }

    // Basal profile

    /**
     * Put [rates] into the driver's profile slot and prove it took: the slot made active, the same
     * rates read back.
     *
     * @return null on success, else what did not match
     */
    fun writeBasalProfile(rates: DoubleArray): String? {
        require(rates.size == ApexProtocol.BASAL_SLOTS) { "expected ${ApexProtocol.BASAL_SLOTS} rates" }

        val card = readStatus() ?: return "pump did not report its status"

        val target = ApexConst.DRIVER_PROFILE_INDEX
        if (card.status.activeProfileIndex != target) {
            aapsLogger.debug(
                LTag.PUMP,
                "Apex: pump is on profile ${card.status.activeProfileIndex}, selecting $target before writing"
            )
            if (!switchToProfile(target)) {
                return "pump stayed on profile ${pumpState.activeProfileIndex} instead of $target"
            }
        }

        if (exchange.sendControlAndWait(ApexProtocol.ControlOpcode.WRITE_BASAL_PROFILE, ApexPayload.basalProfile(rates)) != ApexAnswer.ACCEPTED) {
            return "pump did not acknowledge the basal profile"
        }

        // Read back, a few times: the stored profile follows the acknowledgement with a delay.
        var lastProblem = "pump did not return the basal profiles"
        repeat(ApexConst.EFFECT_POLL_ATTEMPTS) {
            SystemClock.sleep(ApexConst.EFFECT_POLL_INTERVAL_MS)
            if (!readBasalProfiles(target)) return@repeat
            val stored = pumpState.profiles?.rates?.getOrNull(target)
            if (stored == null) {
                lastProblem = "pump did not return profile $target"
                return@repeat
            }
            val differing = ApexPumpState.differingSlots(rates, stored)
            if (differing.isEmpty()) return null
            lastProblem = "pump stored different rates: " + differing.joinToString(", ", limit = 5) {
                "${ApexPumpState.slotLabel(it)} wanted ${rates[it]} got ${stored[it]}"
            }
            aapsLogger.debug(LTag.PUMP, "Apex: read back does not match yet, $lastProblem")
        }
        return lastProblem
    }

    /** Read the recent boluses, as the pump answers. An answer the pump spoilt is the deep look's to refuse. */
    fun readBolusHistory(): ApexJournalAnswer<ApexBolusRecord>? =
        readRecords(
            "bolus history", ApexProtocol.HistoryOpcode.LATEST_BOLUS,
            ApexProtocol.GROUP_CONTROL, ApexProtocol.HistoryOpcode.LATEST_BOLUS, ApexProtocol.PARAMETER_LATEST,
            burstFrameCap = ApexProtocol.ALIAS_ANSWER_FRAMES,
            decode = ApexBolusRecord::decode, describe = ::describeBolus
        )

    /**
     * Read every bolus record the pump holds, newest first. The records are the same as those of
     * [readBolusHistory], but the answer does not stop at ten: it is long, so it is asked only when
     * the short answer does not show the last record kept. Null when no answer came, or fewer
     * records came than the pump holds.
     */
    fun readWholeBolusHistory(): ApexJournalAnswer<ApexBolusRecord>? {
        val answer = readRecords(
            "whole bolus history", ApexProtocol.HistoryOpcode.BOLUS_HISTORY,
            ApexProtocol.GROUP_QUERY, ApexProtocol.HistoryOpcode.BOLUS_HISTORY, ApexFrame.NO_PARAMETER,
            decode = ApexBolusRecord::decode, describe = ::describeBolus
        ) ?: return null
        if (answer.cameWhole) return answer
        // A frame lost on the way leaves a hole no one can see: the answer is taken as not read.
        aapsLogger.warn(LTag.PUMPCOMM, "Apex: the whole bolus history brought ${answer.entries.size} records, the pump holds ${answer.recordCount}: taken as not read")
        return null
    }

    /** Read the newest days of the daily statistics, today first: insulin by kind, see [ApexDailyRecord]. */
    fun readDailyStats(): ApexJournalAnswer<ApexDailyRecord>? =
        readRecords(
            "daily statistics", ApexProtocol.HistoryOpcode.LATEST_DAILY_STATS,
            ApexProtocol.GROUP_CONTROL, ApexProtocol.HistoryOpcode.LATEST_DAILY_STATS, ApexProtocol.PARAMETER_LATEST,
            burstFrameCap = ApexProtocol.ALIAS_ANSWER_FRAMES,
            decode = ApexDailyRecord::decode
        ) { "day ${it.index} from key ${it.dayClockSeconds}: bolus ${it.bolusUnits} basal ${it.basalUnits} temporary ${it.tbrUnits} U" }

    private fun describeBolus(record: ApexBolusRecord): String =
        "bolus record ${record.index}, requested ${record.requestedUnits} delivered ${record.deliveredUnits}" +
            (if (record.carriesExtendedPart) ", extended ${record.extendedRequestedUnits} delivered ${record.extendedDeliveredUnits}" else "")

    /**
     * One read answered by a burst of [objectType] records, each decoded with [decode] once and
     * logged by [describe]; only frames of this read are taken, not another client's.
     *
     * @param burstFrameCap the most frames the answer can bring, for an answer that ends by its count
     * @return the records with their frames, in arrival order, or null when the read failed
     */
    private fun <T : Any> readRecords(
        what: String,
        objectType: Byte,
        group: Byte,
        opcode: Byte,
        parameter: Byte,
        burstFrameCap: Int = 0,
        decode: (ApexResponseFrame) -> T?,
        describe: (T) -> String
    ): ApexJournalAnswer<T>? {
        val isRecord = { frame: ApexResponseFrame -> frame.frameId == ApexProtocol.MODE_HISTORY && frame.objectType == objectType }
        val frames = exchange.exchangeForBurst(what, isRecord, burstFrameCap) {
            exchange.send(group, ApexProtocol.MODE_HISTORY, opcode, parameter)
        } ?: return null
        val decoded = frames.mapNotNull { frame -> decode(frame)?.let { ApexRead(frame, it) } }
        decoded.forEach { aapsLogger.debug(LTag.PUMPCOMM, "Apex: ${describe(it.record)}") }
        return ApexJournalAnswer(decoded, decoded.lastOrNull()?.frame?.recordCount ?: -1)
    }

    /**
     * Whether the pump sent any frame of the single object [opcode] in answer: nothing is decoded,
     * any content counts. Its silence is not counted toward giving up the link, for the pump may
     * not have the object.
     */
    fun answersRead(opcode: Byte): Boolean =
        exchange.sendAndWait(opcode, silenceDropsLink = false) { it.frameId == ApexProtocol.MODE_HISTORY && it.objectType == opcode } != null

    /**
     * Whether the pump sent at least one frame in answer to a read of the latest records of
     * [opcode]: nothing is decoded, any content counts. Its silence is not counted toward giving
     * up the link, see [answersRead].
     */
    fun answersLatestRecords(opcode: Byte): Boolean {
        val isRecord = { frame: ApexResponseFrame -> frame.frameId == ApexProtocol.MODE_HISTORY && frame.objectType == opcode }
        val frames = exchange.exchangeForBurst("probe of records 0x%02X".format(opcode), isRecord, ApexProtocol.ALIAS_ANSWER_FRAMES, silenceDropsLink = false) {
            exchange.send(ApexProtocol.GROUP_CONTROL, ApexProtocol.MODE_HISTORY, opcode, ApexProtocol.PARAMETER_LATEST)
        }
        return !frames.isNullOrEmpty()
    }

    /** Make [index] the active profile and confirm it from the status, which follows the acknowledgement with a delay. */
    fun switchToProfile(index: Int): Boolean {
        if (exchange.sendControlAndWait(ApexProtocol.ControlOpcode.SWITCH_PROFILE, byteArrayOf(index.toByte())) != ApexAnswer.ACCEPTED) {
            aapsLogger.error(LTag.PUMP, "Apex: no acknowledgement for selecting profile $index")
            return false
        }
        repeat(ApexConst.EFFECT_POLL_ATTEMPTS) { attempt ->
            SystemClock.sleep(ApexConst.EFFECT_POLL_INTERVAL_MS)
            val card = readStatus()
            if (card?.status?.activeProfileIndex == index) {
                aapsLogger.debug(LTag.PUMP, "Apex: profile $index active after ${attempt + 1} checks")
                return true
            }
            aapsLogger.debug(
                LTag.PUMP,
                "Apex: waiting for profile $index, pump reports ${card?.status?.activeProfileIndex}, check ${attempt + 1}"
            )
        }
        return false
    }

    // Clock

    /**
     * Put the phone's time in [offsetMs] into the pump, as a key in seconds, see [ApexStatusV1.encodeClock];
     * done when the pump acknowledges it. The time is taken when the request goes, after any wait for
     * the pump, and sent at the start of a whole second: the pump's clock then starts that second with
     * the phone's, not seconds behind it.
     *
     * @return the moment written, and what did not happen, when something did not
     */
    fun writeClock(offsetMs: Long): ApexClockWrite {
        var secondMs = 0L
        val answer = exchange.sendControlAndWait(ApexProtocol.ControlOpcode.SET_CLOCK) {
            val nowMs = System.currentTimeMillis()
            secondMs = (Math.floorDiv(nowMs, 1000L) + 1) * 1000L
            SystemClock.sleep(secondMs - nowMs)
            ApexStatusV1.encodeClock(ApexClock.clockSecondsOf(secondMs, offsetMs))
        }
        return when (answer) {
            ApexAnswer.REFUSED  -> ApexClockWrite(secondMs, "the pump refused the clock")
            ApexAnswer.NONE     -> ApexClockWrite(secondMs, "the pump did not acknowledge the clock")
            ApexAnswer.ACCEPTED -> {
                exchange.forgetHeartbeat()
                ApexClockWrite(secondMs, null)
            }
        }
    }

    /** How far behind the phone the pump's clock is, by its last heartbeat after the last clock write; null when none came. */
    fun pumpClockErrorMs(): Long? = exchange.clockErrorMs

    // Settings

    /**
     * Replace the pump's Bluetooth password; the acknowledgement is all the confirmation there is. The
     * caller stores the new value before the link, which the pump drops after a change, comes back.
     *
     * @return null on success, else what went wrong
     */
    fun setBtPassword(value: Int): ApexFailure? {
        if (!ApexBtPassword.isSettable(value)) return ApexFailure("out of range")
        return when (exchange.sendControlAndWait(ApexProtocol.ControlOpcode.SET_BT_PASSWORD, ApexBtPassword.changePayload(value))) {
            ApexAnswer.REFUSED  -> ApexFailure("the pump refused the new password")
            ApexAnswer.NONE     -> ApexFailure("the pump did not acknowledge the new password")
            ApexAnswer.ACCEPTED -> null
        }
    }

    /**
     * Write the whole settings block [wanted], built from what the pump last reported, and prove it
     * from the status, see [ApexSettings.mirroredFieldsMatch].
     *
     * @return null on success, else what did not match
     */
    fun writeSettings(wanted: ApexSettings): ApexFailure? {
        when (exchange.sendControlAndWait(ApexProtocol.ControlOpcode.WRITE_SETTINGS, wanted.toPayload())) {
            ApexAnswer.REFUSED  -> return ApexFailure("the pump refused the settings")
            ApexAnswer.NONE     -> return ApexFailure("the pump did not acknowledge the settings")
            ApexAnswer.ACCEPTED -> Unit
        }
        // Remembered only once accepted: a refused write sets nothing.
        preferences.put(ApexIntNonKey.AlarmDuration, wanted.alarmDuration)

        var lastProblem = "the pump did not report its settings back"
        repeat(ApexConst.EFFECT_POLL_ATTEMPTS) {
            SystemClock.sleep(ApexConst.EFFECT_POLL_INTERVAL_MS)
            val stored = readStatus()?.settings ?: return@repeat
            if (wanted.mirroredFieldsMatch(stored)) return null
            lastProblem = "the pump stored different settings"
            aapsLogger.debug(LTag.PUMP, "Apex: settings read back does not match yet, wanted $wanted got $stored")
        }
        return ApexFailure(lastProblem)
    }

    /** The period the user chose last went to the pump on this link, minutes; null when it did not yet. */
    @Volatile private var heartbeatPeriodSent: Int? = null

    /** The period of the heartbeat the user chose, minutes, one of [ApexConst.HEARTBEAT_PERIODS]. */
    fun heartbeatPeriodMinutes(): Int =
        preferences.get(ApexIntKey.HeartbeatPeriod).takeIf { it in ApexConst.HEARTBEAT_PERIODS } ?: ApexConst.HEARTBEAT_PERIOD_DEFAULT

    /** True when the period the user chose did not go to the pump on this link yet: at the link, or after the user changed it. */
    fun heartbeatPeriodDue(): Boolean = heartbeatPeriodSent != heartbeatPeriodMinutes()

    /**
     * Put the pump's heartbeat on the period the user chose, and turned on: a pump left with the
     * heartbeat off, or on another period, by another client comes back to it. Sent
     * once per link and again when the user changed the period. The same period changes nothing on
     * the pump, so this is safe to send on every link.
     */
    fun ensureHeartbeatPeriod() {
        if (!heartbeatPeriodDue() || !apexBLE.isConnected) return
        val minutes = heartbeatPeriodMinutes()
        if (setHeartbeatPeriod(minutes) != ApexAnswer.NONE) heartbeatPeriodSent = minutes
    }

    /**
     * Set the heartbeat period, minutes, with the heartbeat on. The pump rebuilds the grid from its
     * last heartbeat; see `HeartbeatGrid`. It does not always answer this command and carries it
     * out all the same, so the answer is reported, not judged.
     */
    fun setHeartbeatPeriod(minutes: Int): ApexAnswer {
        val answer = exchange.sendControlAndWait(
            ApexProtocol.ControlOpcode.SET_HEARTBEAT,
            byteArrayOf(minutes.toByte(), ApexProtocol.HEARTBEAT_ON)
        )
        trace.event(ApexTraceCat.SESS, "heartbeat_period", "minutes" to minutes, "answer" to answer.name)
        // The pump carries it out even unanswered: the next heartbeat is waited for on the new period.
        exchange.heartbeatPeriodMs = minutes * ApexClock.MINUTE_MS
        if (answer != ApexAnswer.ACCEPTED) aapsLogger.warn(LTag.PUMP, "Apex: the heartbeat period $minutes min was " + if (answer == ApexAnswer.REFUSED) "refused" else "not answered; the pump may carry it out all the same")
        return answer
    }

    // The link

    override fun onLinkUp() {
        heartbeatPeriodSent = null
        synchronized(collectedProfiles) { collectedProfiles.clear() }
        // A different pump inherits nothing of what was read from the last one.
        val serial = preferences.get(ApexStringKey.ApexSerialNumber)
        if (pumpState.serialNumber.isNotEmpty() && pumpState.serialNumber != serial) {
            aapsLogger.debug(LTag.PUMP, "Apex: a different pump answered, forgetting what was known of the last one")
            trace.event(ApexTraceCat.SESS, "pump_changed", "was" to pumpState.serialNumber, "now" to serial)
            pumpState.reset()
        }
        pumpState.serialNumber = serial
    }

    override fun onFrame(frame: ApexResponseFrame) {
        if (frame.frameId == ApexProtocol.MODE_CONTROL) {
            when (frame.objectType) {
                ApexProtocol.ObjectType.REJECTED                ->
                    aapsLogger.error(LTag.PUMPCOMM, "Apex: pump refused the command")

                ApexProtocol.ObjectType.BOLUS_PROGRESS          -> bolusDelivery.onProgress(frame)
                ApexProtocol.ObjectType.BOLUS_COMPLETED         -> bolusDelivery.onCompleted(frame)

                ApexProtocol.ObjectType.EXTENDED_BOLUS_PROGRESS -> {
                    // Started elsewhere: the driver never asks for an extended bolus.
                    val delivered = if (frame.has(2, 2)) frame.u16le(2) * ApexProtocol.DOSE_SCALE else 0.0
                    aapsLogger.debug(LTag.PUMPCOMM, "Apex: extended bolus progress $delivered U, not ours")
                }

                else                                         ->
                    aapsLogger.debug(LTag.PUMPCOMM, "Apex: control answer, object ${frame.objectType}")
            }
            return
        }
        if (frame.frameId != ApexProtocol.MODE_HISTORY) {
            aapsLogger.debug(LTag.PUMPCOMM, "Apex: unhandled frame $frame")
            return
        }
        when (frame.objectType) {
            ApexProtocol.ReadOpcode.STATUS_V1      -> handleStatusV1(frame)
            ApexProtocol.ReadOpcode.BASAL_PROFILES -> handleBasalProfile(frame)

            ApexProtocol.ReadOpcode.VERSION        ->
                ApexVersion.decode(frame)?.let {
                    pumpState.version = it
                    aapsLogger.debug(
                        LTag.PUMPCOMM,
                        "Apex: firmware ${it.firmwareText}, protocol ${it.protocolText}"
                    )
                    rxBus.send(EventApexPumpDataChanged())
                } ?: aapsLogger.error(LTag.PUMPCOMM, "Apex: handshake answer too short, size ${frame.raw.size}")

            ApexProtocol.ReadOpcode.STATUS_V2      ->
                ApexStatusV2.decode(frame)?.let {
                    pumpState.applyStatusV2(it)
                    aapsLogger.debug(LTag.PUMPCOMM, "Apex: battery ${it.batteryVolts} V, pump reckons ${it.activeInsulinUnits} U on board")
                    rxBus.send(EventApexPumpDataChanged())
                }

            // Journal records, the daily statistics and the temporary basal marks are decoded once, by the read that asked for them.
            ApexProtocol.HistoryOpcode.LATEST_BOLUS,
            ApexProtocol.HistoryOpcode.BOLUS_HISTORY,
            ApexProtocol.HistoryOpcode.LATEST_DAILY_STATS,
            ApexProtocol.ReadOpcode.TBR_ACTIVE,
            ApexProtocol.HistoryOpcode.TBR_HISTORY,
            ApexProtocol.ReadOpcode.TBR_FINISHED   -> Unit

            else                                   ->
                aapsLogger.debug(LTag.PUMPCOMM, "Apex: unhandled object in $frame")
        }
    }

    private fun handleStatusV1(frame: ApexResponseFrame) {
        val status = ApexStatusV1.decode(frame)
        if (status == null) {
            aapsLogger.error(LTag.PUMPCOMM, "Apex: Status V1 frame too short, size ${frame.raw.size}")
            return
        }
        val settings = ApexSettings.decode(frame, preferences.get(ApexIntNonKey.AlarmDuration))
        if (settings == null) aapsLogger.error(LTag.PUMPCOMM, "Apex: Status V1 too short for the settings block")
        pumpState.applyStatus(status, frame.raw, dateUtil.now(), settings)
        aapsLogger.debug(
            LTag.PUMPCOMM,
            (if (status.suspended) "Apex: THE PUMP IS SUSPENDED, " else "Apex: ") +
                "status profile=${status.activeProfileIndex} reservoir=${status.reservoirUnits} " +
                "basal=${status.scheduledBasalRate} tbr=${status.tbrActive} " +
                "tbrRate=${status.tbrRate} tbrMode=${status.tbrMode} tbrFor=${status.tbrDurationMinutes}"
        )
        rxBus.send(EventApexPumpDataChanged())
    }

    private fun handleBasalProfile(frame: ApexResponseFrame) {
        val profile = ApexBasalProfile.decode(frame)
        if (profile == null) {
            aapsLogger.error(LTag.PUMPCOMM, "Apex: malformed basal profile frame, size ${frame.raw.size}")
            return
        }
        synchronized(collectedProfiles) { collectedProfiles[profile.index] = profile.rates }
        aapsLogger.debug(
            LTag.PUMPCOMM,
            "Apex: profile ${profile.index} of ${frame.recordCount}, ${profile.dailyUnits} U/day"
        )
        // Kept by readBasalProfiles, which knows when the answer is over.
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }
}
