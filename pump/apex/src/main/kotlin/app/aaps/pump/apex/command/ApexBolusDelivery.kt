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
 * frames, stopped on the user's say-so, and given up on when the link to the pump is lost.
 */
@SingleIn(AppScope::class)
@Inject
class ApexBolusDelivery(
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val exchange: ApexExchange
) {

    /** What the pump has reported delivered of the running bolus, U. */
    @Volatile private var bolusDelivered: Double = 0.0

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

    /** The user asked to stop the running bolus, whether the pump accepted it or not. */
    @Volatile private var bolusCancelPressed: Boolean = false

    /** Progress frames of the running bolus so far: the pump's word that it took the command, when it gave no other. */
    @Volatile private var progressFrames: Int = 0

    /** The pump's heartbeat that came while the bolus runs, phone clock; 0 when none waits. */
    @Volatile private var heartbeatAtMs: Long = 0L

    /**
     * The pump's heartbeat came at [atMs]. While our bolus runs there is no reconciliation: true then,
     * and the heartbeat is noted for [bolus]'s caller, which answers it once the bolus is settled. False
     * when no bolus of ours runs, or it is over (its completion frame or its accepted cancel came): the
     * heartbeat is then answered as usual, in its turn behind the bolus command.
     */
    fun heartbeatCame(atMs: Long): Boolean {
        if (!bolusInProgress || bolusFinished || bolusCancelAccepted) return false
        heartbeatAtMs = atMs
        return true
    }

    /**
     * The heartbeat noted while the command was sent and the bolus never started (refused, or not taken):
     * [bolus] returned before handing it on. Taken once; null when none waits.
     */
    fun takeNotedHeartbeat(): Long? = heartbeatAtMs.takeIf { it != 0L }?.also { heartbeatAtMs = 0L }

    /**
     * Deliver a bolus and follow it to the end. [onAccepted] is called the moment the pump accepts it,
     * before anything can have been delivered: from then on AAPS counts the bolus whatever fails
     * after. A pump that answers the command with silence may have taken it all the same: its
     * progress frames, which follow the acceptance within a second or two, say so, and then the
     * bolus is followed as an accepted one. What is reported is what the pump last said; the pump's
     * record is read by the caller. A heartbeat that comes meanwhile is handed to [onHeartbeat], which
     * only notes it: nothing is read of the pump while the bolus runs.
     *
     * «A bolus runs» is a state of the whole driver: nothing is asked of the pump in it, neither a
     * reconciliation nor a journal. Four things end it: the completion frame, the accepted cancel, the
     * lost link, or the time the bolus takes by its ordered dose, [durationMs] from the acceptance (see
     * [BolusTiming]), and [BolusTiming.AFTER_END_MS] more, when its frames were lost on the way. After a
     * lost link the pump goes on: what it delivered comes from its record at the next reconciliation.
     */
    suspend fun bolus(
        units: Double, durationMs: Long, onAccepted: suspend (Long) -> Unit, onProgress: suspend (Double) -> Unit, onHeartbeat: suspend (Long) -> Unit
    ): ApexBolusOutcome {
        bolusDelivered = 0.0
        bolusFinished = false
        bolusCancelAccepted = false
        bolusCancelPressed = false
        progressFrames = 0
        heartbeatAtMs = 0L

        val payload = ApexPayload.bolus(units)
        bolusInProgress = true
        try {
            val answer = exchange.sendControlAndWait(ApexProtocol.ControlOpcode.BOLUS, payload)
            if (answer == ApexAnswer.REFUSED) return ApexBolusOutcome.Refused
            // The time to a first progress frame is counted from the pump's acceptance.
            val acceptedAt = SystemClock.elapsedRealtime()
            val overBy = acceptedAt + durationMs + BolusTiming.AFTER_END_MS
            if (answer == ApexAnswer.NONE) {
                val until = acceptedAt + FIRST_PROGRESS_WAIT_MS
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
                if (!exchange.linked) {
                    aapsLogger.warn(LTag.PUMP, "Apex: the link was lost during the bolus of $units U, it is left to the record")
                    break
                }
                // No completion frame and no cancel: the bolus is over by its ordered dose, and its record written.
                if (SystemClock.elapsedRealtime() >= overBy) {
                    aapsLogger.warn(LTag.PUMP, "Apex: the bolus of $units U is over by its time, its completion frame did not come")
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
            completed = bolusFinished,
            frames = progressFrames,
            cancelPressed = bolusCancelPressed
        )
    }

    /**
     * Stop the running bolus, keeping what it delivered. Sent only while a bolus runs: with none, the
     * pump can write a record of its own. The watch ends only once the pump has accepted the cancel.
     */
    fun stopBolus() {
        if (!bolusInProgress || bolusFinished || bolusCancelAccepted) {
            aapsLogger.warn(LTag.PUMP, "Apex: no bolus is running, or it is over: not sending a cancel")
            return
        }
        bolusCancelPressed = true
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

        /** How often the running bolus is looked at. */
        private const val BOLUS_POLL_MS = 500L

        /** How long a command the pump did not answer waits for a first progress frame before it counts as not taken. */
        private const val FIRST_PROGRESS_WAIT_MS = 5_000L
    }
}
