package app.aaps.pump.apex.exchange

import android.os.SystemClock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.ApexConst
import app.aaps.pump.apex.deep.HeartbeatOverAnswerDeep
import app.aaps.pump.apex.keys.ApexStringKey
import app.aaps.pump.apex.link.ApexBLE
import app.aaps.pump.apex.link.ApexConnection
import app.aaps.pump.apex.protocol.ApexFrame
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.protocol.ApexResponseParser
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import app.aaps.pump.apex.R
import app.aaps.pump.apex.deep.PumpDownDeep
import app.aaps.pump.apex.deep.ReadAgainDeep
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * One request and its answer at a time over the pump's link.
 *
 * Holds the exchange lock, waits for the answer or the burst of answers, tells a late or
 * foreign answer from the awaited one, drops a link that answers nothing, and hands every
 * frame that is not a heartbeat to [frameSink].
 */
@SingleIn(AppScope::class)
@Inject
class ApexExchange(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val apexBLE: ApexBLE,
    private val trace: ApexTrace,
    private val connection: ApexConnection,
    private val notificationManager: NotificationManager,
    private val rh: ResourceHelper
) : ApexConnection.Listener {

    /** Whoever applies what the pump says: the frames of every answer, and the link going up or down. */
    interface FrameSink {

        /** True while the pump is delivering something the sink follows, a bolus. */
        val deliveryRunning: Boolean

        fun onFrame(frame: ApexResponseFrame)
        fun onLinkUp() {}
    }

    @Volatile var frameSink: FrameSink? = null

    init {
        connection.listener = this
    }

    /** The frames of the burst being read, guarded by [waitLock]. */
    private val burstCollected = ArrayList<ApexResponseFrame>()

    /**
     * True while an exchange holds the link or a bolus is being delivered: the pump takes no other
     * command then. Both clear in a `finally`, so the command queue cannot spin on a stuck flag.
     */
    val isBusy: Boolean get() = exchangeLock.isLocked || frameSink?.deliveryRunning == true

    private val parser = ApexResponseParser()

    private val waitLock = Any()

    private var pendingMatch: ((ApexResponseFrame) -> Boolean)? = null

    private var pendingLatch: CountDownLatch? = null

    /** The frame that answered the exchange in progress, handed from the Bluetooth thread to the waiter. */
    private var pendingAnswer: ApexResponseFrame? = null

    /** Whether the heartbeat came over the answer of the exchange that waits: the acknowledgement it hid is taken as given, see [HeartbeatOverAnswerDeep]. */
    @Volatile private var heartbeatOverAnswer = false

    /**
     * Set when a frame failed its CRC while a single answer was waited for: the answer came and
     * came broken, as two answers spliced into one frame do. The wait ends at once and the request
     * goes again, see [runExchange]; a silence is another thing and is not retried.
     */
    @Volatile private var answerBroken = false

    /** Recognises the frames of the burst being waited for. */
    private var pendingBurst: ((ApexResponseFrame) -> Boolean)? = null

    private var burstFrames: Int = 0

    private var burstLastFrameAt: Long = 0L

    /** The connection a foreign answer was last reported for: said once, then counted. */
    private var foreignReportedFor: Long = -1L

    /** Tells this exchange's answer from a late one, see [ApexAnswerGate]. */
    private val answerGate = ApexAnswerGate()

    /** Set when the link dropped during a wait, so the wait fails rather than passes. */
    @Volatile private var linkLost: Boolean = false

    /** One exchange at a time, from the request until its answer: a second write while one is unanswered is refused. */
    private val exchangeLock = ReentrantLock()

    /** The pump taken as not working: no request goes to it until it comes back. */
    private val pumpDown = PumpDownDeep(aapsLogger, trace, dropLink = { connection.drop(it) }) { down ->
        if (down) notificationManager.post(NotificationId.PUMP_UNREACHABLE, rh.gs(R.string.apex_pump_no_answer), level = NotificationLevel.URGENT)
        else notificationManager.dismiss(NotificationId.PUMP_UNREACHABLE)
    }

    /**
     * When the pump's last answer frame came, by [SystemClock.elapsedRealtime]; the next request
     * keeps [ApexConst.REQUEST_GAP_MS] from it. An exchange that got no answer counts from its end.
     */
    @Volatile private var lastAnswerFrameMs = 0L

    /** When the pump's last heartbeat came, by [SystemClock.elapsedRealtime]; 0 until seen, or after the clock moved. See [keepClearOfHeartbeat]. */
    @Volatile private var lastHeartbeatMs = 0L

    /** The period of the heartbeat last set on the pump, milliseconds: the next heartbeat is waited for on it, see [keepClearOfHeartbeat]. */
    @Volatile var heartbeatPeriodMs = ApexConst.HEARTBEAT_PERIOD_DEFAULT * 60_000L

    /** How far behind the phone the pump's clock is, by its last heartbeat, see [clockErrorOf]; null until one came after the last clock write. */
    @Volatile var clockErrorMs: Long? = null
        private set

    /** One request and its answer; a failure leaves nothing behind for the next exchange. */
    fun exchange(
        what: String,
        match: (ApexResponseFrame) -> Boolean,
        burstOf: ((ApexResponseFrame) -> Boolean)? = null,
        burstFrameCap: Int = 0,
        sender: () -> Boolean
    ): Boolean = runExchange(what, match, burstOf, burstFrameCap, sender = sender).answered

    /** One request answered by a burst: the frames [burstOf] recognised, in arrival order, or null when it failed. */
    fun exchangeForBurst(
        what: String,
        burstOf: (ApexResponseFrame) -> Boolean,
        burstFrameCap: Int = 0,
        silenceDropsLink: Boolean = true,
        sender: () -> Boolean
    ): List<ApexResponseFrame>? {
        val done = runExchange(what, { burstOf(it) && it.isLastRecord }, burstOf, burstFrameCap, silenceDropsLink = silenceDropsLink, sender = sender)
        return if (done.answered) done.frames else null
    }

    /** One request and the single frame that answered it, or null when none did. Bursts go through [exchange]. */
    fun exchangeForAnswer(
        what: String,
        match: (ApexResponseFrame) -> Boolean,
        sender: () -> Boolean
    ): ApexResponseFrame? = runExchange(what, match, null, 0, sender = sender).answer

    /**
     * Whether the exchange was answered, and by which frame when a single one answered it.
     * [unsound]: the last answer of a read that [runExchange]'s `valid` refused, when no sound one came.
     */
    private class Exchanged(
        val answered: Boolean,
        val answer: ApexResponseFrame?,
        val frames: List<ApexResponseFrame> = emptyList(),
        val unsound: ApexResponseFrame? = null
    )

    /**
     * One request until it is answered. The Bluetooth module of the pump now and then swallows an
     * answer, so a read is asked again when the pump is silent, when the answer fails its CRC, and
     * when [valid] refuses the frame that came, see [askAgain] and `ReadAgainDeep`. A command is never
     * sent twice: a second send would be a second order.
     *
     * @param read true for a read of one object; bursts are read again by their callers, see `ReadAgainDeep.journal`
     * @param valid whether the single frame that answered a read can be used
     * @param silenceDropsLink false for a probe of an object the pump may not have: its silence is not counted toward giving up the link, see [PumpDownDeep.noteReachability]
     */
    private fun runExchange(
        what: String,
        match: (ApexResponseFrame) -> Boolean,
        burstOf: ((ApexResponseFrame) -> Boolean)?,
        burstFrameCap: Int,
        read: Boolean = false,
        valid: (ApexResponseFrame) -> Boolean = { true },
        silenceDropsLink: Boolean = true,
        sender: () -> Boolean
    ): Exchanged {
        val queuedAt = trace.now()
        exchangeLock.lock()
        val startedAt = trace.now()
        try {
            if (pumpDown.down) {
                aapsLogger.warn(LTag.PUMPCOMM, "Apex: $what not sent: the pump is taken as not working until its heartbeat or a new link")
                finished(what, false, "pump_down", queuedAt, startedAt)
                return Exchanged(false, null)
            }
            // A link held from before the password was taken out of AAPS is no link to work on.
            if (!connection.checkPassword()) {
                connection.drop("no password")
                finished(what, false, "no_password", queuedAt, startedAt)
                return Exchanged(false, null)
            }
            settleAfterConnecting()
            keepRequestsApart()
            keepClearOfHeartbeat()
            var asked = 0
            var unsound: ApexResponseFrame? = null
            while (true) {
                asked++
                val latch = arm(match, burstOf)
                if (!sender()) {
                    aapsLogger.error(LTag.PUMPCOMM, "Apex: could not send $what")
                    abort()
                    pumpDown.noteReachability(reached = false, why = "not_sent")
                    finished(what, false, "not_sent", queuedAt, startedAt)
                    return Exchanged(false, null)
                }
                val answered = if (burstOf == null) await(latch, what) else awaitBurst(latch, what, burstFrameCap)
                val answer = synchronized(waitLock) { pendingAnswer }
                val outcome = when {
                    linkLost                                                           -> Ask.LINK_LOST
                    // A command acknowledged under the heartbeat has no frame: it is answered all the same.
                    // A read whose answer the heartbeat lay over has none: it is a swallowed answer, asked again.
                    answered && answer == null && burstOf == null                      -> if (read) Ask.UNDER_HEARTBEAT else Ask.ANSWERED
                    answered && (burstOf != null || valid(answer!!))                   -> Ask.ANSWERED
                    answered                                                           -> Ask.UNSOUND
                    answerBroken                                                       -> Ask.BROKEN
                    else                                                               -> Ask.SILENT
                }
                if (outcome == Ask.ANSWERED) {
                    pumpDown.noteReachability(reached = true, why = outcome.word)
                    finished(what, true, outcome.word, queuedAt, startedAt)
                    return synchronized(waitLock) { Exchanged(true, answer, ArrayList(burstCollected)) }
                }
                if (outcome == Ask.UNSOUND) unsound = answer
                val dropped = usableAnswer(outcome, silenceDropsLink)?.let { pumpDown.noteReachability(reached = it, why = outcome.word) } == true
                if (dropped || !askAgain(outcome, asked, read)) {
                    abort()
                    finished(what, false, outcome.word, queuedAt, startedAt)
                    return Exchanged(false, null, unsound = unsound)
                }
                val seen = if (outcome == Ask.UNSOUND) " (${answer?.raw?.let { ApexFrame.hex(it) }})" else ""
                aapsLogger.warn(LTag.PUMPCOMM, "Apex: ${outcome.text} $what$seen; asked again, ${asked + 1} of ${ReadAgainDeep.READS}")
                trace.event(ApexTraceCat.EXCH, "asked_again", "what" to what, "why" to outcome.word)
                abort()
                keepRequestsApart()
                keepClearOfHeartbeat()
            }
        } finally {
            exchangeLock.unlock()
        }
    }

    /** How one ask of an exchange ended: [word] for the trace, [text] for the log line of the next ask. */
    internal enum class Ask(val word: String, val text: String) {
        ANSWERED("ok", "an answer to"),
        SILENT("no_answer", "no answer to"),
        BROKEN("broken_answer", "the answer failed its CRC, to"),
        UNSOUND("unsound_answer", "the answer could not be read, to"),
        UNDER_HEARTBEAT("answer_under_heartbeat", "the heartbeat lay over the answer to"),
        LINK_LOST("link_lost", "the link was lost, waiting for")
    }

    /** Trace one finished exchange: `waitMs` it waited behind another, `ms` the pump took. */
    private fun finished(what: String, ok: Boolean, outcome: String, queuedAt: Long, startedAt: Long) {
        if (!ok) lastAnswerFrameMs = SystemClock.elapsedRealtime()
        trace.countExchange(ok)
        trace.event(
            ApexTraceCat.EXCH, "done",
            "what" to what,
            "ok" to ok,
            "outcome" to outcome,
            "waitMs" to startedAt - queuedAt,
            "ms" to trace.since(startedAt)
        )
    }

    /** Three reads in a row brought nothing, [why]: the pump is taken as not working, see [PumpDownDeep]. */
    fun pumpNotWorking(why: String) = pumpDown.notWorking(why)

    /** Drop anything left over from a failed exchange. */
    private fun abort() {
        disarm()
        parser.reset()
    }

    fun sendRead(opcode: Byte): Boolean =
        send(ApexProtocol.GROUP_CONTROL, ApexProtocol.MODE_HISTORY, opcode, ApexFrame.NO_PARAMETER)

    /**
     * A read of one object, asked again while the pump is silent, the answer fails its CRC, or
     * [valid] refuses it, see [runExchange].
     * [silenceDropsLink] false for a probe, see [runExchange].
     *
     * @return the frame [valid] took; when none did by the last ask, the last frame it refused, for
     *   the caller to judge; null when no frame came
     */
    fun sendAndWait(opcode: Byte, valid: (ApexResponseFrame) -> Boolean = { true }, silenceDropsLink: Boolean = true, match: (ApexResponseFrame) -> Boolean): ApexResponseFrame? {
        val done = runExchange("read 0x%02X".format(opcode), match, null, 0, read = true, valid = valid, silenceDropsLink = silenceDropsLink) { sendRead(opcode) }
        return done.answer ?: done.unsound
    }

    /** Send a control command and wait for the pump to accept or refuse it. */
    fun sendControlAndWait(
        opcode: Byte,
        payload: ByteArray,
        group: Byte = ApexProtocol.GROUP_CONTROL
    ): ApexAnswer = sendControlAndWait(opcode, group) { payload }

    /** A control command whose [payload] is made the moment the request goes, after any wait for the pump. */
    fun sendControlAndWait(
        opcode: Byte,
        group: Byte = ApexProtocol.GROUP_CONTROL,
        payload: () -> ByteArray
    ): ApexAnswer {
        val answer = exchangeForAnswer(
            "control 0x%02X".format(opcode),
            {
                it.frameId == ApexProtocol.MODE_CONTROL &&
                    (it.objectType == ApexProtocol.ObjectType.ACK || it.objectType == ApexProtocol.ObjectType.REJECTED)
            }
        ) {
            send(group, ApexProtocol.MODE_CONTROL, opcode, ApexFrame.NO_PARAMETER, payload())
        }
        if (answer == null) {
            if (!heartbeatOverAnswer) return ApexAnswer.NONE
            aapsLogger.info(LTag.PUMP, "Apex: command 0x%02X acknowledged under the heartbeat".format(opcode))
            trace.event(ApexTraceCat.EXCH, "ack_under_heartbeat", "opcode" to "0x%02X".format(opcode))
            return ApexAnswer.ACCEPTED
        }
        if (answer.objectType == ApexProtocol.ObjectType.REJECTED) {
            aapsLogger.error(LTag.PUMP, "Apex: pump refused command 0x%02X".format(opcode))
            trace.event(ApexTraceCat.EXCH, "refused", "opcode" to "0x%02X".format(opcode))
            return ApexAnswer.REFUSED
        }
        return ApexAnswer.ACCEPTED
    }

    fun send(group: Byte, mode: Byte, opcode: Byte, parameter: Byte, payload: ByteArray = ByteArray(0)): Boolean {
        val identity = runCatching { identity() }.getOrElse {
            aapsLogger.error(LTag.PUMP, "Apex: cannot build request, ${it.message}")
            return false
        }
        return apexBLE.write(ApexFrame.buildRequest(group, mode, opcode, identity, parameter, payload))
    }

    private fun arm(match: (ApexResponseFrame) -> Boolean, burst: ((ApexResponseFrame) -> Boolean)?): CountDownLatch {
        val latch = CountDownLatch(1)
        synchronized(waitLock) {
            pendingMatch = match
            pendingLatch = latch
            pendingAnswer = null
            heartbeatOverAnswer = false
            answerBroken = false
            pendingBurst = burst
            burstFrames = 0
            burstCollected.clear()
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
            answerGate.reset()
        }
    }

    private fun await(latch: CountDownLatch, what: String): Boolean {
        val released = latch.await(ApexConst.COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        disarm()
        // A dropped link releases the wait as well, and that is a failure, not an answer.
        if (released && linkLost) {
            aapsLogger.error(LTag.PUMPCOMM, "Apex: link lost while waiting for $what")
            return false
        }
        // So does an answer that failed its CRC: the caller asks again.
        if (released && answerBroken) return false
        if (!released) aapsLogger.error(LTag.PUMPCOMM, "Apex: no answer to $what")
        return released
    }

    /**
     * Whether a burst is complete by its frame count: only an answer of known size, [cap] above 0.
     * `>=`, so that a frame past the cap cannot leave the wait open.
     */
    internal fun burstCompleteByCount(frames: Int, cap: Int): Boolean = cap > 0 && frames >= cap

    /**
     * Wait for an answer that comes as a burst, one frame per record, then for the line to go quiet
     * before anyone speaks: the record count cannot say how many frames come, and the stack refuses a
     * write while it is still delivering notifications.
     */
    private fun awaitBurst(latch: CountDownLatch, what: String, burstFrameCap: Int): Boolean {
        // On the monotonic clock: a phone clock corrected meanwhile must not end or stretch the wait.
        val deadline = SystemClock.elapsedRealtime() + ApexConst.COMMAND_TIMEOUT_MS
        var matched = false
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!matched && latch.await(BURST_POLL_MS, TimeUnit.MILLISECONDS)) {
                matched = true
            } else if (matched) {
                SystemClock.sleep(BURST_POLL_MS)
            }
            // A dropped link releases the wait as well, and that is a failure, not an answer.
            if (matched && linkLost) {
                disarm()
                aapsLogger.error(LTag.PUMPCOMM, "Apex: link lost while waiting for $what")
                return false
            }
            // An answer that stops at its cap carries no last-record flag: reaching the cap is the flag.
            if (!matched) {
                val frames = synchronized(waitLock) { burstFrames }
                if (burstCompleteByCount(frames, burstFrameCap)) {
                    matched = true
                    trace.event(ApexTraceCat.EXCH, "burst_capped", "what" to what, "frames" to frames)
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
        // A burst that matched and never fell quiet is answered all the same.
        if (matched) return true
        aapsLogger.error(LTag.PUMPCOMM, "Apex: no answer to $what")
        return false
    }

    /** A frame the pump sent unasked, bolus progress or completion, counts for the spacing of the next request like an answer. */
    fun noteUnaskedFrame() {
        lastAnswerFrameMs = SystemClock.elapsedRealtime()
    }

    /** Requests too close together make the pump's radio fall silent: wait out the gap since the pump's last answer frame. */
    private fun keepRequestsApart() {
        val wait = lastAnswerFrameMs + ApexConst.REQUEST_GAP_MS - SystemClock.elapsedRealtime()
        if (wait > 0L) SystemClock.sleep(wait)
    }

    /**
     * The pump's heartbeat is the one frame we do not ask for, and it comes on its own beat, whatever
     * runs. A request it meets may lose its answer, or the heartbeat itself is lost. So no request
     * starts in the window around the next heartbeat: it waits until the heartbeat came, or the window
     * is over. The heartbeat keeps the pump's clock: a write of the clock moves it, see [forgetHeartbeat].
     */
    private fun keepClearOfHeartbeat() {
        val last = lastHeartbeatMs
        val now = SystemClock.elapsedRealtime()
        val untilMs = heartbeatClearUntil(last, now, heartbeatPeriodMs) ?: return
        trace.event(ApexTraceCat.EXCH, "heartbeat_wait", "ms" to untilMs - now)
        while (lastHeartbeatMs == last && SystemClock.elapsedRealtime() < untilMs) SystemClock.sleep(HEARTBEAT_POLL_MS)
    }

    /** The pump's clock was written: its heartbeat moves with it, and the next one is waited for anew. */
    fun forgetHeartbeat() {
        lastHeartbeatMs = 0L
        clockErrorMs = null
    }

    /** Hold the first request of a link back until the pump listens, see [ApexConst.FIRST_REQUEST_SETTLE_MS]; owed only by whoever talks first. */
    private fun settleAfterConnecting() {
        val owed = settleDelayMs(apexBLE.linkUpAtMs, System.currentTimeMillis())
        if (owed <= 0L) return
        aapsLogger.debug(LTag.PUMPCOMM, "Apex: the link is ${owed}ms too young to be asked anything, waiting")
        trace.event(ApexTraceCat.EXCH, "settle", "ms" to owed)
        SystemClock.sleep(owed)
    }

    private fun identity(): ByteArray =
        ApexFrame.identityOf(preferences.get(ApexStringKey.ApexSerialNumber))

    override fun onLinkDown() {
        parser.reset()
        // Release whatever waits, marked as a lost link rather than an answer.
        synchronized(waitLock) {
            linkLost = true
            pendingLatch?.countDown()
        }
        disarm()
    }

    override fun onDataReceived(chunk: ByteArray) {
        val corruptBefore = parser.crcErrors
        val insideBefore = parser.heartbeatsInside
        for (frame in parser.feed(chunk)) {
            val late = lateAnswer(frame)
            if (late != null) {
                aapsLogger.warn(
                    LTag.PUMPCOMM,
                    "Apex: frame 0x%02X object 0x%02X is not this exchange's answer (%s), discarded"
                        .format(frame.frameId, frame.objectType ?: 0, late)
                )
                trace.event(
                    ApexTraceCat.BLE, "late_answer",
                    "frame" to "0x%02X".format(frame.frameId),
                    "object" to "0x%02X".format(frame.objectType ?: 0),
                    "why" to late
                )
                // Discarded but still counted: a stale answer nothing asked for is another client's.
                synchronized(waitLock) { if (pendingMatch == null) noteIfForeign(frame) }
                continue
            }
            if (frame.frameId == ApexProtocol.MODE_HEARTBEAT && frame.raw.size == ApexProtocol.HEARTBEAT_FRAME_SIZE) {
                // The heartbeat answers nothing; it is told by its size and noted as the pump being there.
                lastHeartbeatMs = SystemClock.elapsedRealtime()
                pumpDown.onHeartbeat()
                clockErrorMs = clockErrorOf(System.currentTimeMillis())
                apexBLE.noteHeartbeat()
                trace.event(ApexTraceCat.BLE, "heartbeat", "period" to frame.objectType?.toInt())
            } else {
                frameSink?.onFrame(frame)
            }
            synchronized(waitLock) {
                if (pendingBurst?.invoke(frame) == true) {
                    burstFrames++
                    burstCollected.add(frame)
                    burstLastFrameAt = SystemClock.elapsedRealtime()
                    lastAnswerFrameMs = burstLastFrameAt
                }
                if (pendingMatch?.invoke(frame) == true) {
                    lastAnswerFrameMs = SystemClock.elapsedRealtime()
                    answerGate.answerAccepted()
                    pendingAnswer = frame
                    pendingLatch?.countDown()
                } else if (pendingMatch == null) noteIfForeign(frame)
            }
        }
        if (parser.heartbeatsInside > insideBefore) {
            aapsLogger.info(LTag.PUMPCOMM, "Apex: the heartbeat came inside another frame and was taken out; the frame it lay over is lost")
            trace.event(ApexTraceCat.BLE, "heartbeat_inside")
            // The frame it lay over is the acknowledgement of the command that waits: the pump carries the command out.
            synchronized(waitLock) {
                if (pendingMatch != null && pendingBurst == null && pendingAnswer == null) {
                    heartbeatOverAnswer = true
                    lastAnswerFrameMs = SystemClock.elapsedRealtime()
                    answerGate.answerAccepted()
                    pendingLatch?.countDown()
                }
            }
        }
        // A frame that failed its CRC is an answer somebody waits for: the single answer waited for ends its wait, to be asked for again.
        if (parser.crcErrors > corruptBefore) {
            aapsLogger.error(
                LTag.PUMPCOMM,
                "Apex: ${parser.crcErrors - corruptBefore} frame(s) failed their CRC and were dropped, " +
                    "${parser.crcErrors} since this connection began"
            )
            synchronized(waitLock) {
                if (pendingMatch != null && pendingBurst == null && pendingAnswer == null) {
                    answerBroken = true
                    lastAnswerFrameMs = SystemClock.elapsedRealtime()
                    pendingLatch?.countDown()
                }
            }
        }
    }

    /**
     * Count an answer nothing was waiting for: another client on the same pump. Answers carry no
     * identity, so this counts rather than filters. Bolus progress frames come unasked and do not count.
     */
    private fun noteIfForeign(frame: ApexResponseFrame) {
        val isReply = when (frame.frameId) {
            ApexProtocol.MODE_HISTORY -> true
            ApexProtocol.MODE_CONTROL ->
                frame.objectType == ApexProtocol.ObjectType.ACK || frame.objectType == ApexProtocol.ObjectType.REJECTED

            else                   -> false
        }
        if (!isReply) return
        trace.countForeign()
        // Detailed once per connection, then only counted.
        if (foreignReportedFor != trace.sessionId) {
            foreignReportedFor = trace.sessionId
            aapsLogger.warn(
                LTag.PUMPCOMM,
                "Apex: an answer arrived that nothing asked for, frame 0x%02X object 0x%02X - another client is on this link"
                    .format(frame.frameId, frame.objectType ?: 0)
            )
            trace.event(
                ApexTraceCat.BLE, "foreign_answer",
                "frame" to "0x%02X".format(frame.frameId),
                "object" to "0x%02X".format(frame.objectType ?: 0)
            )
        }
    }

    /** Why this frame is not the answer to the exchange in front of us, or null when it may be; a frame refused here is not applied. */
    private fun lateAnswer(frame: ApexResponseFrame): String? {
        // Asks the matcher again what onDataReceived will ask it: every matcher is a pure predicate of the frame.
        val second = synchronized(waitLock) {
            answerGate.isSecondAnswer(pendingMatch?.invoke(frame) == true)
        }
        if (second) return "second answer to one request"
        return null
    }

    override fun onLinkUp() {
        pumpDown.onLinkUp()
        parser.reset()
        // Nothing owed on the last link is owed on this one.
        answerGate.reset()
        frameSink?.onLinkUp()
    }

    companion object {

        /**
         * How an ask that did not end answered counts toward taking the pump as not working, see [PumpDownDeep]:
         * true when it still shows the pump is there (the heartbeat lay over the answer), false when nothing
         * usable came (no answer, or an answer that cannot be read), null when it does not count: the link was
         * lost, or [silenceDropsLink] is false, a probe of an object the pump may not have.
         */
        internal fun usableAnswer(outcome: Ask, silenceDropsLink: Boolean): Boolean? = when {
            outcome == Ask.LINK_LOST -> null
            outcome == Ask.UNDER_HEARTBEAT -> true
            !silenceDropsLink -> null
            else -> false
        }

        /**
         * Whether a request is asked again after [asked] asks ended with [outcome], by the one rule of
         * reading again, see [ReadAgainDeep]: only a [read] that brought nothing sound. A command is never
         * sent twice, not even when its answer came broken: the pump may have carried it out.
         */
        internal fun askAgain(outcome: Ask, asked: Int, read: Boolean): Boolean =
            read && ReadAgainDeep.asksAgain(outcome == Ask.SILENT || outcome == Ask.BROKEN || outcome == Ask.UNSOUND || outcome == Ask.UNDER_HEARTBEAT, asked)

        /**
         * How far behind the phone the pump's clock is, milliseconds, by a heartbeat heard at [heardAtMs]:
         * the heartbeat comes on second 0 of the pump's clock, [ApexConst.HEARTBEAT_LATENCY_MS] before it
         * is heard. Negative when the pump's clock is ahead. Within half a minute either way.
         */
        fun clockErrorOf(heardAtMs: Long): Long {
            val intoMinute = Math.floorMod(heardAtMs - ApexConst.HEARTBEAT_LATENCY_MS, 60_000L)
            return if (intoMinute < 30_000L) intoMinute else intoMinute - 60_000L
        }

        /** How often a request held for the heartbeat looks whether it came. */
        private const val HEARTBEAT_POLL_MS = 50L

        /**
         * Until when a request now must wait for the heartbeat, or null when it is not near. The pump
         * beats on its own clock, one period after another to within a second a day: the next one is
         * due a whole number of periods after [lastMs], and one that did not come keeps the beat.
         *
         * @param lastMs when the last heartbeat came, 0 when none came since the link or the clock write
         * @param periodMs the period of the heartbeat last set on the pump
         */
        fun heartbeatClearUntil(lastMs: Long, nowMs: Long, periodMs: Long): Long? {
            if (lastMs == 0L) return null
            var dueMs = lastMs + periodMs
            while (dueMs + ApexConst.HEARTBEAT_CLEAR_AFTER_MS < nowMs) dueMs += periodMs
            return if (nowMs < dueMs - ApexConst.HEARTBEAT_CLEAR_BEFORE_MS) null else dueMs + ApexConst.HEARTBEAT_CLEAR_AFTER_MS
        }

        /**
         * How much of the settle window is still owed, milliseconds, never more than the window itself
         * (a corrected phone clock can put the link's start in the future). Apart from the sleep so a
         * test can check it.
         *
         * @param linkUpAtMs when the link came up, 0 when there is none
         */
        fun settleDelayMs(linkUpAtMs: Long, now: Long): Long {
            if (linkUpAtMs == 0L) return 0L
            return (ApexConst.FIRST_REQUEST_SETTLE_MS - (now - linkUpAtMs))
                .coerceIn(0L, ApexConst.FIRST_REQUEST_SETTLE_MS)
        }

        /** How often to look at a burst that is still arriving, milliseconds. */
        private const val BURST_POLL_MS = 100L

        /** How long a burst may be quiet before it is finished, well clear of the gaps inside one. */
        private const val BURST_IDLE_MS = 500L

        /** How long the line stays quiet after a complete burst, so the stack has finished with its notifications before the next write. */
        private const val BURST_SETTLE_MS = 200L
    }
}
