package app.aaps.pump.apex.command

import android.os.SystemClock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.pump.apex.exchange.ApexAnswer
import app.aaps.pump.apex.exchange.ApexExchange
import app.aaps.pump.apex.protocol.ApexPayload
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexResponseFrame
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * A bolus, from the command to the pump's last word on it: sent, followed by its progress
 * frames, stopped on the user's say-so, and given up on when the pump falls silent.
 */
@SingleIn(AppScope::class)
@Inject
class ApexBolusDelivery(
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val exchange: ApexExchange
) {

    /** What the pump has reported delivered of the running bolus, U. */
    @Volatile var bolusDelivered: Double = 0.0
        private set

    /** Set once the pump reports the running bolus as finished. */
    @Volatile private var bolusFinished: Boolean = false

    /** True between asking for a bolus and learning what became of it. */
    @Volatile var bolusInProgress: Boolean = false
        private set

    /**
     * The pump accepted the user's cancel: only that ends the watch over the bolus, and only then is a
     * shortfall what was wanted. A cancel the pump did not answer leaves the bolus watched; what it
     * delivered comes from its record, and a shortfall is told.
     */
    @Volatile private var bolusCancelAccepted: Boolean = false

    /** When the pump last reported progress, for noticing a bolus that went quiet. */
    @Volatile private var lastProgressAt: Long = 0L

    /** Progress frames of the running bolus so far: the pump's word that it took the command, when it gave no other. */
    @Volatile private var progressFrames: Int = 0

    /** The pump's heartbeat that came while the bolus runs, phone clock; 0 when none waits. */
    @Volatile private var heartbeatAtMs: Long = 0L

    /**
     * The pump's heartbeat came at [atMs]. While our bolus runs it is answered between the progress
     * frames, by [bolus]'s caller: true then, and the heartbeat is noted for it. False when no bolus
     * of ours is being followed, or the one followed has ended (its completion frame or its cancel
     * came), and the heartbeat is somebody else's to answer: the usual reconciliation, which then
     * takes its turn behind the bolus command and sees the record settled. The heartbeat was once
     * claimed in the moment between the completion frame and the end of the command, and nobody
     * served it; the picture of that beat was lost.
     */
    fun heartbeatCame(atMs: Long): Boolean {
        if (!bolusInProgress || bolusFinished || bolusCancelAccepted) return false
        heartbeatAtMs = atMs
        return true
    }

    /**
     * Deliver a bolus and follow it to the end. [onAccepted] is called the moment the pump accepts it,
     * before anything can have been delivered: from then on AAPS counts the bolus whatever fails
     * after. A pump that answers the command with silence may have taken it all the same: its
     * progress frames, which follow the acceptance within a second or two, say so, and then the
     * bolus is followed as an accepted one. What is reported is what the pump last said; the pump's
     * record is read by the caller. A heartbeat that comes meanwhile is handed to [onHeartbeat]
     * between two progress frames: the pump answers requests while it delivers, and the frames of
     * the bolus keep coming by themselves, so nothing of the bolus is lost while the picture is read.
     */
    suspend fun bolus(units: Double, onAccepted: suspend (Long) -> Unit, onProgress: suspend (Double) -> Unit, onHeartbeat: suspend (Long) -> Unit): ApexBolusOutcome {
        bolusDelivered = 0.0
        bolusFinished = false
        bolusCancelAccepted = false
        progressFrames = 0
        heartbeatAtMs = 0L

        val payload = ApexPayload.bolus(units)
        bolusInProgress = true
        try {
            val answer = exchange.sendControlAndWait(ApexProtocol.ControlOpcode.BOLUS, payload)
            if (answer == ApexAnswer.REFUSED) return ApexBolusOutcome.Refused
            // The time to a first progress frame is counted from the pump's acceptance.
            lastProgressAt = SystemClock.elapsedRealtime()
            if (answer == ApexAnswer.NONE) {
                val until = lastProgressAt + FIRST_PROGRESS_WAIT_MS
                while (progressFrames == 0 && !bolusFinished && SystemClock.elapsedRealtime() < until) SystemClock.sleep(BOLUS_POLL_MS)
                if (progressFrames == 0 && !bolusFinished) return ApexBolusOutcome.NotSent
                aapsLogger.warn(LTag.PUMP, "Apex: the pump did not answer the bolus command but delivers: its progress frames came")
            }
            onAccepted(dateUtil.now())

            var lastReported = -1.0
            while (!bolusFinished && !bolusCancelAccepted) {
                if (bolusDelivered != lastReported) {
                    lastReported = bolusDelivered
                    onProgress(bolusDelivered)
                }
                val heartbeat = heartbeatAtMs
                if (heartbeat != 0L) {
                    heartbeatAtMs = 0L
                    onHeartbeat(heartbeat)
                    continue
                }
                // A bolus that stops for any reason goes quiet: silence ends the watch, and the pump is asked.
                if (SystemClock.elapsedRealtime() - lastProgressAt > BOLUS_STALL_MS) {
                    aapsLogger.debug(LTag.PUMP, "Apex: no progress for ${BOLUS_STALL_MS}ms, checking the pump")
                    break
                }
                SystemClock.sleep(BOLUS_POLL_MS)
            }
            // The completion frame carries a little more than the last progress frame: reported once more.
            if (bolusDelivered != lastReported) onProgress(bolusDelivered)
            // A heartbeat noted just before the end, which the loop above did not get to: served here, before the record.
            val late = heartbeatAtMs
            if (late != 0L) {
                heartbeatAtMs = 0L
                onHeartbeat(late)
            }
        } finally {
            bolusInProgress = false
        }
        return ApexBolusOutcome.Delivered(
            reportedUnits = bolusDelivered,
            cancelled = bolusCancelAccepted,
            completed = bolusFinished
        )
    }

    /**
     * Stop the running bolus, keeping what it delivered. Sent only while a bolus runs: with none, the
     * pump can write a record of its own. The watch ends only once the pump has accepted the cancel.
     */
    fun stopBolus(): Boolean {
        if (!bolusInProgress) {
            aapsLogger.warn(LTag.PUMP, "Apex: no bolus is running, not sending a cancel")
            return true
        }
        val sent = exchange.sendControlAndWait(
            ApexProtocol.ControlOpcode.CANCEL_BOLUS,
            byteArrayOf(0x00, 0x00),
            group = ApexProtocol.GROUP_QUERY
        ) == ApexAnswer.ACCEPTED
        if (sent) {
            bolusCancelAccepted = true
        } else {
            // The bolus is still running as far as anyone knows, so do not pretend it was stopped.
            aapsLogger.error(LTag.PUMP, "Apex: the pump did not accept the cancel, the bolus goes on being watched")
        }
        return sent
    }

    /** A progress frame of the running bolus. */
    fun onProgress(frame: ApexResponseFrame) {
        if (!frame.has(2, 2)) return
        if (!bolusInProgress) {
            // Another client's bolus on the same pump.
            aapsLogger.debug(LTag.PUMPCOMM, "Apex: bolus progress while no bolus of ours is running, ignored")
            return
        }
        bolusDelivered = frame.u16le(2) * ApexProtocol.DOSE_SCALE
        lastProgressAt = SystemClock.elapsedRealtime()
        progressFrames++
        exchange.noteUnaskedFrame()
        aapsLogger.debug(LTag.PUMPCOMM, "Apex: bolus progress $bolusDelivered U")
    }

    /** The completion frame of the running bolus. */
    fun onCompleted(frame: ApexResponseFrame) {
        if (!bolusInProgress) {
            aapsLogger.debug(LTag.PUMPCOMM, "Apex: bolus completion while no bolus of ours is running, ignored")
            return
        }
        if (frame.has(2, 2)) bolusDelivered = frame.u16le(2) * ApexProtocol.DOSE_SCALE
        bolusFinished = true
        exchange.noteUnaskedFrame()
        aapsLogger.debug(LTag.PUMPCOMM, "Apex: bolus finished at $bolusDelivered U")
    }

    private companion object {

        /** How often progress is reported while the pump delivers. */
        private const val BOLUS_POLL_MS = 500L

        /** How long the pump may be silent before its bolus is taken to be over and its record is asked for. */
        private const val BOLUS_STALL_MS = 15_000L

        /** How long a command the pump did not answer waits for a first progress frame before it counts as not taken. */
        private const val FIRST_PROGRESS_WAIT_MS = 5_000L
    }
}
