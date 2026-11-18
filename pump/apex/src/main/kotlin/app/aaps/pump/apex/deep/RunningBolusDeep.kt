package app.aaps.pump.apex.deep

import android.os.SystemClock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.command.ApexBolusDelivery
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Is a bolus running now? Asked first at every reconciliation, see `Reconciliation`.
 *
 * The problem. The pump writes the record of a bolus only after the bolus ends, and no field of
 * Status V1 or Status V2 tells that a bolus runs. While one runs, the pump's count already holds a
 * part of it that no record explains. Nothing can be checked against such a count, and a status
 * kept with it would count that part twice: once without the record, once more by the record.
 *
 * When it applies. At every reconciliation, right after its first read, Status V1, before anything
 * else is read, settled or kept: the start, every heartbeat. After our bolus only its record is settled, see `BolusCommand`: nothing is asked then.
 *
 * The way out, by the delivery mechanics of the pump (page «Такты подачи Apex»):
 * - Frames of a bolus on the air: our bolus runs, see [ApexBolusDelivery.bolusInProgress]. The
 *   reconciliation is put off at once. The pump sends these frames only for our own bolus: a bolus of
 *   another client, or from the pump's keys, sends none to us.
 * - No frames: the count of Status V1 tells. A running bolus adds a portion every second at the normal
 *   bolus speed, every five seconds at the slow one, and the count takes a portion in one step. So the
 *   count of the heartbeat is read again after [PAUSE_MS], or [SLOW_PAUSE_MS] at the slow speed (the
 *   switch is in the same frame). It stood: no bolus runs. It moved: the move may be a basal portion, so
 *   it is read once more after the same pause. It moved again: a bolus runs; basal never gives two
 *   portions that close at any rate. It stood: that was basal, no bolus runs.
 * - No bolus runs: the last Status V1 read is the one the reconciliation goes by, see [quietStatus].
 * - A temporary basal of 0 U/h delivers no basal at all: the first move is a bolus, two reads are enough.
 * - A read that is not answered counts as a bolus that runs: a mistake here only puts the
 *   reconciliation off by one heartbeat.
 */
@SingleIn(AppScope::class)
@Inject
class RunningBolusDeep(
    private val aapsLogger: AAPSLogger,
    private val manager: ApexManager,
    private val bolusDelivery: ApexBolusDelivery,
    private val trace: ApexTrace
) {

    /** The wait between two reads; a test replaces it. */
    internal var pause: (Long) -> Unit = { SystemClock.sleep(it) }

    /**
     * Null when a bolus runs now, by the air and the count of [card], the Status V1 just read, see the
     * rules above; else the last Status V1 the check read, the one the reconciliation goes by: its count
     * stood, and it is the nearest to the reads after it. [why] is told in the log.
     */
    fun quietStatus(card: ApexPumpState.StatusCard, why: String): ApexPumpState.StatusCard? {
        if (bolusDelivery.bolusInProgress) {
            aapsLogger.info(LTag.PUMP, "Apex: $why: our bolus runs, its frames are on the air; no reconciliation at this heartbeat")
            trace.event(ApexTraceCat.STATE, "bolus_runs_check", "why" to why, "on_air" to true, "runs" to true)
            return null
        }
        // Settings not read yet: the slow speed, the longer pause, is the safe one.
        val pauseMs = if (card.settings?.lowBolusSpeed == false) PAUSE_MS else SLOW_PAUSE_MS
        val noBasal = card.status.tbrActive && card.status.tbrRate < ApexProtocol.DOSE_SCALE / 2
        val values = mutableListOf(card.deliveredTodayUnits)
        val reads = if (noBasal) 1 else 2
        for (i in 1..reads) {
            pause(pauseMs)
            val last = manager.readStatus() ?: return null.also { told(why, values, pauseMs, noBasal, runs = true, unanswered = true) }
            values += last.deliveredTodayUnits
            if (!moved(values)) return last.also { told(why, values, pauseMs, noBasal, runs = false) }
        }
        return null.also { told(why, values, pauseMs, noBasal, runs = true) }
    }

    private fun moved(values: List<Double>): Boolean = values[values.size - 1] - values[values.size - 2] >= ApexProtocol.DOSE_SCALE / 2

    private fun told(why: String, values: List<Double>, pauseMs: Long, noBasal: Boolean, runs: Boolean, unanswered: Boolean = false) {
        val verdict = when {
            unanswered -> "a read was not answered, taken as a bolus that runs"
            runs       -> "a bolus runs, no reconciliation at this heartbeat"
            else       -> "no bolus runs"
        }
        aapsLogger.info(LTag.PUMP, "Apex: $why: the pump's count ${pauseMs / 1000.0} s apart: $values U${if (noBasal) ", no basal under a temporary basal of 0" else ""}; $verdict")
        trace.event(ApexTraceCat.STATE, "bolus_runs_check", "why" to why, "values" to values.joinToString(" "), "pause" to pauseMs, "no_basal" to noBasal, "runs" to runs, "unanswered" to unanswered)
    }

    companion object {

        /** The pause between two reads at the normal bolus speed: past the second between its portions. */
        const val PAUSE_MS = 2_000L

        /** The pause at the slow bolus speed: past the five seconds between its portions. */
        const val SLOW_PAUSE_MS = 6_000L
    }
}
