package app.aaps.pump.atc3.command

import android.os.SystemClock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.pump.atc3.command.Atc3BolusOutcome
import app.aaps.pump.atc3.exchange.Atc3Answer
import app.aaps.pump.atc3.exchange.Atc3Exchange
import app.aaps.pump.atc3.protocol.Atc3Payload
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3ResponseFrame
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A bolus, from the command to the pump's last word on it: sent, followed by its progress
 * frames, stopped on the user's say-so, and given up on when the pump falls silent.
 */
@Singleton
class Atc3BolusDelivery @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val exchange: Atc3Exchange
) {

    /** What the pump has reported delivered of the running bolus, U. */
    @Volatile var bolusDelivered: Double = 0.0
        private set

    /** Set once the pump reports the running bolus as finished. */
    @Volatile private var bolusFinished: Boolean = false

    /** Set once a progress frame of the running bolus has arrived. */
    @Volatile private var bolusSawProgress: Boolean = false

    /** True between asking for a bolus and learning what became of it. */
    @Volatile var bolusInProgress: Boolean = false
        private set

    /** The user asked for the running bolus to stop: a shortfall is then what was wanted, not a failure. */
    @Volatile private var bolusCancelAsked: Boolean = false

    /** The pump accepted the cancel: only that, not the asking, ends the watch over the bolus. */
    @Volatile private var bolusCancelAccepted: Boolean = false

    /** When the pump last reported progress, for noticing a bolus that went quiet. */
    @Volatile private var lastProgressAt: Long = 0L

    /**
     * Deliver a bolus and follow it to the end. [onAccepted] is called the moment the pump accepts it,
     * before anything can have been delivered: from then on AAPS counts the bolus whatever fails
     * after. What is reported is what the pump last said; the pump's record is read by the caller.
     */
    suspend fun bolus(units: Double, onAccepted: suspend (Long) -> Unit, onProgress: suspend (Double) -> Unit): Atc3BolusOutcome {
        bolusDelivered = 0.0
        bolusFinished = false
        bolusSawProgress = false
        bolusCancelAsked = false
        bolusCancelAccepted = false

        val payload = Atc3Payload.bolus(units)
        bolusInProgress = true
        var acceptedAt = 0L
        try {
            when (exchange.sendControlAndWait(Atc3Protocol.ControlOpcode.BOLUS, payload)) {
                Atc3Answer.REFUSED  -> return Atc3BolusOutcome.Refused
                Atc3Answer.NONE     -> return Atc3BolusOutcome.NotSent
                Atc3Answer.ACCEPTED -> Unit
            }
            acceptedAt = dateUtil.now()
            onAccepted(acceptedAt)
            // The time to a first progress frame is counted from the pump's acceptance.
            lastProgressAt = System.currentTimeMillis()

            var lastReported = -1.0
            while (!bolusFinished && !bolusCancelAccepted) {
                if (bolusDelivered != lastReported) {
                    lastReported = bolusDelivered
                    onProgress(bolusDelivered)
                }
                // A bolus that stops for any reason goes quiet: silence ends the watch, and the pump is asked.
                if (System.currentTimeMillis() - lastProgressAt > BOLUS_STALL_MS) {
                    aapsLogger.debug(LTag.PUMP, "ATC3: no progress for ${BOLUS_STALL_MS}ms, checking the pump")
                    break
                }
                SystemClock.sleep(BOLUS_POLL_MS)
            }
            // The completion frame carries a little more than the last progress frame: reported once more.
            if (bolusDelivered != lastReported) onProgress(bolusDelivered)
        } finally {
            bolusInProgress = false
        }
        return Atc3BolusOutcome.Delivered(
            reportedUnits = bolusDelivered,
            cancelled = bolusCancelAsked,
            completed = bolusFinished,
            sawProgress = bolusSawProgress,
            acceptedAtMs = acceptedAt
        )
    }

    /**
     * Stop the running bolus, keeping what it delivered. Sent only while a bolus runs: with none, the
     * pump can write a record of its own. The watch ends only once the pump has accepted the cancel.
     */
    fun stopBolus(): Boolean {
        if (!bolusInProgress) {
            aapsLogger.warn(LTag.PUMP, "ATC3: no bolus is running, not sending a cancel")
            return true
        }
        bolusCancelAsked = true
        val sent = exchange.sendControlAndWait(
            Atc3Protocol.ControlOpcode.CANCEL_BOLUS,
            byteArrayOf(0x00, 0x00),
            group = Atc3Protocol.GROUP_QUERY
        ) == Atc3Answer.ACCEPTED
        if (sent) {
            bolusCancelAccepted = true
        } else {
            // The bolus is still running as far as anyone knows, so do not pretend it was stopped.
            aapsLogger.error(LTag.PUMP, "ATC3: the pump did not accept the cancel, the bolus goes on being watched")
        }
        return sent
    }

    /** A progress frame of the running bolus. */
    fun onProgress(frame: Atc3ResponseFrame) {
        if (!frame.has(2, 2)) return
        if (!bolusInProgress) {
            // Another client's bolus on the same pump.
            aapsLogger.debug(LTag.PUMPCOMM, "ATC3: bolus progress while no bolus of ours is running, ignored")
            return
        }
        bolusDelivered = frame.u16le(2) * Atc3Protocol.DOSE_SCALE
        bolusSawProgress = true
        lastProgressAt = System.currentTimeMillis()
        aapsLogger.debug(LTag.PUMPCOMM, "ATC3: bolus progress $bolusDelivered U")
    }

    /** The completion frame of the running bolus. */
    fun onCompleted(frame: Atc3ResponseFrame) {
        if (!bolusInProgress) {
            aapsLogger.debug(LTag.PUMPCOMM, "ATC3: bolus completion while no bolus of ours is running, ignored")
            return
        }
        if (frame.has(2, 2)) bolusDelivered = frame.u16le(2) * Atc3Protocol.DOSE_SCALE
        bolusFinished = true
        aapsLogger.debug(LTag.PUMPCOMM, "ATC3: bolus finished at $bolusDelivered U")
    }

    private companion object {

        /** How often progress is reported while the pump delivers. */
        private const val BOLUS_POLL_MS = 500L

        /** How long the pump may be silent before its bolus is taken to be over and its record is asked for. */
        private const val BOLUS_STALL_MS = 15_000L
    }
}
