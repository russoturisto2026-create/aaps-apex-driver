package app.aaps.pump.atc3.exchange

import android.os.SystemClock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.exchange.Atc3Answer
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.link.Atc3BLE
import app.aaps.pump.atc3.link.Atc3Connection
import app.aaps.pump.atc3.protocol.Atc3Frame
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3ResponseFrame
import app.aaps.pump.atc3.protocol.Atc3ResponseParser
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One request and its answer at a time over the pump's link.
 *
 * Holds the exchange lock, waits for the answer or the burst of answers, tells a late or
 * foreign answer from the awaited one, drops a link that answers nothing, and hands every
 * frame that is not a heartbeat to [frameSink].
 */
@Singleton
class Atc3Exchange @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val atc3BLE: Atc3BLE,
    private val trace: Atc3Trace,
    private val connection: Atc3Connection
) : Atc3Connection.Listener {

    /** Whoever applies what the pump says: the frames of every answer, and the link going up or down. */
    interface FrameSink {

        /** True while the pump is delivering something the sink follows, a bolus. */
        val deliveryRunning: Boolean

        fun onFrame(frame: Atc3ResponseFrame)
        fun onLinkUp() {}
        fun onLinkDown() {}
    }

    var frameSink: FrameSink? = null

    init {
        connection.listener = this
    }

    /** The frames of the burst being read, guarded by [waitLock]. */
    private val burstCollected = ArrayList<Atc3ResponseFrame>()

    /**
     * True while an exchange holds the link or a bolus is being delivered: the pump takes no other
     * command then. Both clear in a `finally`, so the command queue cannot spin on a stuck flag.
     */
    override val isBusy: Boolean get() = exchangeLock.isLocked || frameSink?.deliveryRunning == true

    private val parser = Atc3ResponseParser()

    private val waitLock = Any()

    private var pendingMatch: ((Atc3ResponseFrame) -> Boolean)? = null

    private var pendingLatch: CountDownLatch? = null

    /** The frame that answered the exchange in progress, handed from the Bluetooth thread to the waiter. */
    private var pendingAnswer: Atc3ResponseFrame? = null

    /** Recognises the frames of the burst being waited for. */
    private var pendingBurst: ((Atc3ResponseFrame) -> Boolean)? = null

    private var burstFrames: Int = 0

    private var burstLastFrameAt: Long = 0L

    /** The connection a foreign answer was last reported for: said once, then counted. */
    private var foreignReportedFor: Long = -1L

    /** Tells this exchange's answer from a late one, see [Atc3AnswerGate]. */
    private val answerGate = Atc3AnswerGate()

    /** Set when the link dropped during a wait, so the wait fails rather than passes. */
    @Volatile private var linkLost: Boolean = false

    /** One exchange at a time, from the request until its answer: a second write while one is unanswered is refused. */
    private val exchangeLock = ReentrantLock()

    /** Exchanges in a row that reached nothing, see [noteReachability]. */
    @Volatile private var unreachable = 0

    /** One request and its answer; a failure leaves nothing behind for the next exchange. */
    fun exchange(
        what: String,
        match: (Atc3ResponseFrame) -> Boolean,
        burstOf: ((Atc3ResponseFrame) -> Boolean)? = null,
        burstFrameCap: Int = 0,
        sender: () -> Boolean
    ): Boolean = runExchange(what, match, burstOf, burstFrameCap, sender).answered

    /** One request answered by a burst: the frames [burstOf] recognised, in arrival order, or null when it failed. */
    fun exchangeForBurst(
        what: String,
        burstOf: (Atc3ResponseFrame) -> Boolean,
        burstFrameCap: Int = 0,
        sender: () -> Boolean
    ): List<Atc3ResponseFrame>? {
        val done = runExchange(what, { burstOf(it) && it.isLastRecord }, burstOf, burstFrameCap, sender)
        return if (done.answered) done.frames else null
    }

    /** One request and the single frame that answered it, or null when none did. Bursts go through [exchange]. */
    fun exchangeForAnswer(
        what: String,
        match: (Atc3ResponseFrame) -> Boolean,
        sender: () -> Boolean
    ): Atc3ResponseFrame? = runExchange(what, match, null, 0, sender).answer

    /** Whether the exchange was answered, and by which frame when a single one answered it. */
    private class Exchanged(val answered: Boolean, val answer: Atc3ResponseFrame?, val frames: List<Atc3ResponseFrame> = emptyList())

    private fun runExchange(
        what: String,
        match: (Atc3ResponseFrame) -> Boolean,
        burstOf: ((Atc3ResponseFrame) -> Boolean)?,
        burstFrameCap: Int,
        sender: () -> Boolean
    ): Exchanged {
        val queuedAt = trace.now()
        exchangeLock.lock()
        val startedAt = trace.now()
        try {
            // A link held from before the password was taken out of AAPS is no link to work on.
            if (!connection.checkPassword()) {
                connection.drop("no password")
                finished(what, false, "no_password", queuedAt, startedAt)
                return Exchanged(false, null)
            }
            settleAfterConnecting()
            val latch = arm(match, burstOf)
            if (!sender()) {
                aapsLogger.error(LTag.PUMPCOMM, "ATC3: could not send $what")
                abort()
                finished(what, false, "not_sent", queuedAt, startedAt)
                return Exchanged(false, null)
            }
            val answered = if (burstOf == null) await(latch, what) else awaitBurst(latch, what, burstFrameCap)
            if (!answered) {
                abort()
                finished(what, false, if (linkLost) "link_lost" else "no_answer", queuedAt, startedAt)
                return Exchanged(false, null)
            }
            finished(what, true, "ok", queuedAt, startedAt)
            return synchronized(waitLock) { Exchanged(true, pendingAnswer, ArrayList(burstCollected)) }
        } finally {
            exchangeLock.unlock()
        }
    }

    /** Trace one finished exchange: `waitMs` it waited behind another, `ms` the pump took. */
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
     * Give up on a link after [UNREACHABLE_BEFORE_DROP] exchanges in a row reached nothing: the stack
     * can lose a link without reporting it. Any answer, a refusal included, proves the link.
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
        connection.drop("no answer to $run exchanges")
    }

    /** Drop anything left over from a failed exchange. */
    private fun abort() {
        disarm()
        parser.reset()
    }

    fun sendRead(opcode: Byte, parameter: Byte = Atc3Frame.NO_PARAMETER): Boolean =
        send(Atc3Protocol.GROUP_CONTROL, Atc3Protocol.MODE_HISTORY, opcode, parameter)

    fun sendAndWait(opcode: Byte, match: (Atc3ResponseFrame) -> Boolean): Atc3ResponseFrame? =
        exchangeForAnswer("read 0x%02X".format(opcode), match) {
            sendRead(opcode)
        }

    /** Send a control command and wait for the pump to accept or refuse it. */
    fun sendControlAndWait(
        opcode: Byte,
        payload: ByteArray,
        group: Byte = Atc3Protocol.GROUP_CONTROL
    ): Atc3Answer {
        val answer = exchangeForAnswer(
            "control 0x%02X".format(opcode),
            {
                it.frameId == Atc3Protocol.MODE_CONTROL &&
                    (it.objectType == Atc3Protocol.ObjectType.ACK || it.objectType == Atc3Protocol.ObjectType.REJECTED)
            }
        ) {
            send(group, Atc3Protocol.MODE_CONTROL, opcode, Atc3Frame.NO_PARAMETER, payload)
        }
        if (answer == null) return Atc3Answer.NONE
        if (answer.objectType == Atc3Protocol.ObjectType.REJECTED) {
            aapsLogger.error(LTag.PUMP, "ATC3: pump refused command 0x%02X".format(opcode))
            trace.event(Atc3TraceCat.EXCH, "refused", "opcode" to "0x%02X".format(opcode))
            return Atc3Answer.REFUSED
        }
        return Atc3Answer.ACCEPTED
    }

    fun send(group: Byte, mode: Byte, opcode: Byte, parameter: Byte, payload: ByteArray = ByteArray(0)): Boolean {
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
            pendingAnswer = null
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
     * Whether a burst is complete by its frame count: only an answer of known size, [cap] above 0.
     * `>=`, so that a frame past the cap cannot leave the wait open.
     */
    internal fun burstCompleteByCount(frames: Int, cap: Int): Boolean = cap > 0 && frames >= cap

    /**
     * Wait for an answer that comes as a burst, one frame per record, then for the line to go quiet
     * before anyone speaks: the record count cannot say how many frames come, and the stack refuses a
     * write while it is still delivering notifications.
     */
    private fun awaitBurst(latch: CountDownLatch, what: String, burstFrameCap: Int = 0): Boolean {
        // On the monotonic clock: a phone clock corrected meanwhile must not end or stretch the wait.
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
            // An answer that stops at its cap carries no last-record flag: reaching the cap is the flag.
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
        // A burst that matched and never fell quiet is answered all the same.
        if (matched) return true
        aapsLogger.error(LTag.PUMPCOMM, "ATC3: no answer to $what")
        return false
    }

    /** Hold the first request of a link back until the pump listens, see [Atc3Const.FIRST_REQUEST_SETTLE_MS]; owed only by whoever talks first. */
    private fun settleAfterConnecting() {
        val owed = settleDelayMs(atc3BLE.linkUpAtMs, System.currentTimeMillis())
        if (owed <= 0L) return
        aapsLogger.debug(LTag.PUMPCOMM, "ATC3: the link is ${owed}ms too young to be asked anything, waiting")
        trace.event(Atc3TraceCat.EXCH, "settle", "ms" to owed)
        SystemClock.sleep(owed)
    }

    private fun identity(): ByteArray =
        Atc3Frame.identityOf(preferences.get(Atc3StringKey.Atc3SerialNumber))

    override fun onLinkDown() {
        parser.reset()
        // Release whatever waits, marked as a lost link rather than an answer.
        synchronized(waitLock) {
            linkLost = true
            pendingLatch?.countDown()
        }
        disarm()
        frameSink?.onLinkDown()
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
                // Discarded but still counted: a stale answer nothing asked for is another client's.
                synchronized(waitLock) { if (pendingMatch == null) noteIfForeign(frame) }
                continue
            }
            if (frame.frameId == Atc3Protocol.MODE_HEARTBEAT && frame.raw.size == Atc3Protocol.HEARTBEAT_FRAME_SIZE) {
                // The heartbeat answers nothing; it is told by its size and noted as the pump being there.
                atc3BLE.noteHeartbeat()
                trace.event(Atc3TraceCat.BLE, "heartbeat", "period" to frame.objectType?.toInt())
            } else {
                frameSink?.onFrame(frame)
            }
            synchronized(waitLock) {
                if (pendingBurst?.invoke(frame) == true) {
                    burstFrames++
                    burstCollected.add(frame)
                    burstLastFrameAt = SystemClock.elapsedRealtime()
                }
                if (pendingMatch?.invoke(frame) == true) {
                    answerGate.answerAccepted()
                    pendingAnswer = frame
                    pendingLatch?.countDown()
                } else if (pendingMatch == null) noteIfForeign(frame)
            }
        }
        // A frame that failed its CRC is an answer somebody waits for: said, or the wait times out unexplained.
        if (parser.crcErrors > corruptBefore) {
            aapsLogger.error(
                LTag.PUMPCOMM,
                "ATC3: ${parser.crcErrors - corruptBefore} frame(s) failed their CRC and were dropped, " +
                    "${parser.crcErrors} since this connection began"
            )
        }
    }

    /**
     * Count an answer nothing was waiting for: another client on the same pump. Answers carry no
     * identity, so this counts rather than filters. Bolus progress frames come unasked and do not count.
     */
    private fun noteIfForeign(frame: Atc3ResponseFrame) {
        val isReply = when (frame.frameId) {
            Atc3Protocol.MODE_HISTORY -> true
            Atc3Protocol.MODE_CONTROL ->
                frame.objectType == Atc3Protocol.ObjectType.ACK || frame.objectType == Atc3Protocol.ObjectType.REJECTED

            else                   -> false
        }
        if (!isReply) return
        trace.countForeign()
        // Detailed once per connection, then only counted.
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

    /** Why this frame is not the answer to the exchange in front of us, or null when it may be; a frame refused here is not applied. */
    private fun lateAnswer(frame: Atc3ResponseFrame): String? {
        // Asks the matcher again what onDataReceived will ask it: every matcher is a pure predicate of the frame.
        val second = synchronized(waitLock) {
            answerGate.isSecondAnswer(pendingMatch?.invoke(frame) == true)
        }
        if (second) return "second answer to one request"
        return null
    }

    override fun onLinkUp() {
        parser.reset()
        // Nothing owed on the last link is owed on this one.
        answerGate.connected()
        frameSink?.onLinkUp()
    }

    companion object {

        /** Exchanges in a row that reach nothing before the link is given up: one can be a busy stack. */
        private const val UNREACHABLE_BEFORE_DROP = 3

        /**
         * How much of the settle window is still owed, milliseconds, never more than the window itself
         * (a corrected phone clock can put the link's start in the future). Apart from the sleep so a
         * test can check it.
         *
         * @param linkUpAtMs when the link came up, 0 when there is none
         */
        fun settleDelayMs(linkUpAtMs: Long, now: Long): Long {
            if (linkUpAtMs == 0L) return 0L
            return (Atc3Const.FIRST_REQUEST_SETTLE_MS - (now - linkUpAtMs))
                .coerceIn(0L, Atc3Const.FIRST_REQUEST_SETTLE_MS)
        }

        /** How often to look at a burst that is still arriving, milliseconds. */
        private const val BURST_POLL_MS = 100L

        /** How long a burst may be quiet before it is finished, well clear of the gaps inside one. */
        private const val BURST_IDLE_MS = 500L

        /** How long the line stays quiet after a complete burst, so the stack has finished with its notifications before the next write. */
        private const val BURST_SETTLE_MS = 200L
    }
}
