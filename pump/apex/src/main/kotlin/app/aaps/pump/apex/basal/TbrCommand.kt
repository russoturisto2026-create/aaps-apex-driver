package app.aaps.pump.apex.basal

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.pump.apex.clock.ApexPumpClock
import app.aaps.pump.apex.R
import app.aaps.pump.apex.command.AwaitResult
import app.aaps.pump.apex.clock.ApexClock
import app.aaps.pump.apex.exchange.ApexAnswer
import app.aaps.pump.apex.exchange.ApexExchange
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexPayload
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * A temporary basal from the order to its row in AAPS, and its cancel. The pump's word on the
 * command is only the beginning: what counts is its state read after, Status V1 showing the
 * temporary basal running with the rate and minutes asked, or not running after a cancel. The
 * row begins at the moment of the order and ends at the moment of the cancel's order: the pump has
 * no better moment, its stamps are only minutes. A command the pump answered with silence is judged
 * the same way, with one more check: the start worked out from the status must be in the command's minute, else what runs is an older temporary basal with the same
 * numbers. Nothing is sent twice: a second send would be a second order.
 *
 * A new temporary basal over a running one is sent as it is: the pump replaces the running one,
 * and AAPS cuts the old row at the new row's start.
 *
 * The status read after a command done is the last reconciliation, see `TbrReconciliation`. Before a command
 * goes, the status is read, and the last finished temporary basal when the gate asks for it: what the
 * pump did since the last read would be covered by the command, so it is settled first, see
 * [settleBeforeCommand].
 * When the pump does not answer that read, a new temporary basal is not sent: the loop decides
 * again. A cancel goes all the same: it only lowers the delivery, and the status confirms it. Before
 * it, the status read is settled alone, as on a pump without 0x0B.
 */
@SingleIn(AppScope::class)
@Inject
class TbrCommand(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val dateUtil: DateUtil,
    private val manager: ApexManager,
    private val exchange: ApexExchange,
    private val pumpClock: ApexPumpClock,
    private val rows: CommandRows,
    private val tbrReconciliation: TbrReconciliation,
    private val tbrReader: TbrFactsReader,
    private val trace: ApexTrace
) {

    /** What the command reports; [failure] null when the pump did what was asked. */
    class Outcome(val failure: String?, val rate: Double = 0.0, val durationMinutes: Int = 0, val enacted: Boolean = failure == null)

    /** Start an absolute temporary basal of [rate] U/h for [minutes], whole quarter hours. */
    fun set(serial: String, rate: Double, minutes: Int, type: PumpSync.TemporaryBasalType): Outcome {
        val quarters = Math.round(minutes.toDouble() / ApexProtocol.TbrPayload.DURATION_UNIT_MINUTES).toInt()
        if (quarters <= 0) return Outcome("a temporary basal of $minutes min is shorter than the pump's step")
        val wanted = quarters * ApexProtocol.TbrPayload.DURATION_UNIT_MINUTES
        val before = manager.readStatus() ?: return Outcome(rh.gs(R.string.apex_not_connected))
        if (!settleBeforeCommand(serial, before)) return notSettled()
        val sentAt = dateUtil.now()
        val answer = exchange.sendControlAndWait(ApexProtocol.ControlOpcode.START_TBR, ApexPayload.absoluteTbr(rate, quarters))
        trace.event(ApexTraceCat.STATE, "tbr_sent", "rate" to rate, "minutes" to wanted, "answer" to answer.name)
        if (answer == ApexAnswer.REFUSED) return Outcome(rh.gs(R.string.apex_refused_by_pump))

        val card = statusShows { it.tbrActive && ApexProtocol.sameDose(it.tbrRate, rate) && it.tbrDurationMinutes == wanted }
            ?: return notDone("the status does not show the temporary basal of $rate U/h for $wanted min", R.string.apex_tbr_not_confirmed)
        val offsetMs = pumpClock.offsetMs(serial)
        // With the clocks kept together the start the status works out is never before the minute of the command; it may be one later.
        val startAt = ApexClock.momentOf(TbrFacts.startFromStatus(card.status), offsetMs)
        if (answer == ApexAnswer.NONE && startAt < ApexClock.minuteStart(sentAt)) {
            return notDone("no answer to the command, and the temporary basal the pump runs began before it", R.string.apex_tbr_not_confirmed)
        }
        // The row first, then the status after the command is the last reconciliation: a restart in between
        // leaves the status before it, and the next reconciliation writes the one that runs by the status.
        rows.tbrStarted(serial, sentAt, card.tbrRate, card.tbrDurationMinutes, type)
        tbrReconciliation.ordered(serial, card)
        return Outcome(null, card.tbrRate, card.tbrDurationMinutes)
    }

    /** Cancel the running temporary basal; with none running there is nothing to do and nothing is sent. */
    fun cancel(serial: String): Outcome {
        val before = manager.readStatus() ?: return Outcome(rh.gs(R.string.apex_not_connected))
        val settled = settleBeforeCommand(serial, before)
        // None runs: there is nothing to send, and the heartbeat settles what is left.
        if (!before.tbrActive) return Outcome(null, enacted = false)
        // A cancel only lowers the delivery, and the status after it says whether it was done: it goes
        // even when the temporary basal part could not be read. Only a new temporary basal waits for it.
        // The status read now is settled on its own first, as on a pump without 0x0B: after the cancel it
        // is no longer the last reconciliation, and another's temporary basal it shows would get no row.
        if (!settled) {
            aapsLogger.warn(LTag.PUMP, "Apex: the temporary basal part could not be read before the cancel; settled by the status alone, the cancel goes all the same")
            tbrReconciliation.settle(serial, before, TbrFacts(null, endAsked = false), pumpClock.offsetMs(serial), before.readAtMs)
        }
        val sentAt = dateUtil.now()
        val answer = exchange.sendControlAndWait(ApexProtocol.ControlOpcode.CANCEL_TBR, ByteArray(0))
        trace.event(ApexTraceCat.STATE, "tbr_cancel_sent", "answer" to answer.name)
        if (answer == ApexAnswer.REFUSED) return Outcome(rh.gs(R.string.apex_refused_by_pump))

        val after = statusShows { !it.tbrActive } ?: return notDone("the status still shows the temporary basal running", R.string.apex_tbr_still_running)
        // The row is cut first, then the status after the cancel is the last reconciliation.
        rows.tbrStopped(serial, sentAt)
        tbrReconciliation.ordered(serial, after)
        return Outcome(null)
    }

    /**
     * Before our command, with the status [card] just read: a reconciliation like any other, see
     * `TbrGate`. The minute is not over: the read knows the pump up to its own moment.
     *
     * @return false when the pump gave no sound answer to these reads: the command is not sent then,
     *   as it would cover what the pump did without the rows knowing it
     */
    private fun settleBeforeCommand(serial: String, card: ApexPumpState.StatusCard): Boolean {
        val offsetMs = pumpClock.offsetMs(serial)
        val gate = TbrGate.decide(tbrReconciliation.last(serial)?.status, card.status)
        val facts = if (gate.read) tbrReader.read() ?: return unsettled() else null
        tbrReconciliation.settle(serial, card, facts, offsetMs, card.readAtMs)
        return true
    }

    private fun unsettled(): Boolean {
        aapsLogger.warn(LTag.PUMP, "Apex: before our command, no sound answer to the read of the temporary basal facts")
        return false
    }

    private fun notSettled(): Outcome {
        aapsLogger.error(LTag.PUMP, "Apex: temporary basal command not sent: the temporary basal part could not be read before it")
        trace.event(ApexTraceCat.STATE, "tbr_not_settled")
        return Outcome(rh.gs(R.string.apex_tbr_not_settled))
    }

    /** The status read until it says what [check] asks, up to [STATUS_READS] reads, see [AwaitResult]; null when it never did, or the pump fell silent. */
    private fun statusShows(check: (ApexPumpState.StatusCard) -> Boolean): ApexPumpState.StatusCard? =
        AwaitResult.until(STATUS_READS, pauseMs = 0L, silenceEnds = true, read = manager::readStatus, took = check).takeIf { it.took }?.last

    private fun notDone(why: String, comment: Int): Outcome {
        aapsLogger.error(LTag.PUMP, "Apex: temporary basal command not done: $why")
        trace.event(ApexTraceCat.STATE, "tbr_not_done", "why" to why)
        return Outcome(rh.gs(comment))
    }

    private companion object {

        /** Reads of the status before the command is judged not done: the status follows the acknowledgement by a moment. */
        const val STATUS_READS = 2
    }
}
