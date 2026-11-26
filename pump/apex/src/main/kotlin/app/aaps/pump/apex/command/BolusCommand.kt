package app.aaps.pump.apex.command

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusSize
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.apex.R
import app.aaps.pump.apex.aaps.AapsCarrier
import app.aaps.pump.apex.bolus.BolusAccount
import app.aaps.pump.apex.store.Action
import app.aaps.pump.apex.store.ApexStore
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.pulse.HeartbeatTick
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlin.math.abs

/**
 * A bolus of ours, from the order to its end, all in one turn on the queue. Nothing is read of the pump
 * around it: the command itself shows whether the link works, and a pump that takes no command
 * (an alarm, waiting for priming) refuses it. The journal is read only at the heartbeat.
 *
 * 1. The bolus: a state of the whole driver, see [ApexBolusDelivery]: nothing is asked of the pump in it.
 *    The moment the pump accepts it, our picture of it is opened and its row goes to AAPS at the ordered
 *    dose, see [BolusAccount.commandAccepted]. While it runs, the picture keeps the progress frames.
 * 2. It ends with the completion frame, the accepted cancel, or the end by the ordered dose (see
 *    [BolusTiming]) and five seconds more. The picture keeps what the pump showed last, the cancel and
 *    the completion. When the pump's last word was seen and it gave less than ordered, the row shows
 *    that at once, see [BolusAccount.seenRow].
 * 3. The reconciliation at the next heartbeat with no bolus running holds the picture against the
 *    pump's records and its total, and closes it, see `BolusReconciliation`. A heartbeat noted during the
 *    bolus is answered as a heartbeat once the bolus ends.
 */
@SingleIn(AppScope::class)
@Inject
class BolusCommand(
    private val aapsLogger: AAPSLogger,
    private val bolusProgressData: BolusProgressData,
    private val rh: ResourceHelper,
    private val delivery: ApexBolusDelivery,
    private val store: ApexStore,
    private val carrier: AapsCarrier,
    private val pumpState: ApexPumpState,
    private val heartbeatTick: HeartbeatTick,
    private val trace: ApexTrace
) {

    /** What the command reports: [success] false with [enacted] true is a bolus that fell short. */
    class Result(val success: Boolean, val enacted: Boolean, val deliveredUnits: Double, val comment: String?)

    suspend fun deliver(info: DetailedBolusInfo, serial: String): Result {
        val asked = info.insulin
        val units = PumpType.APEX.determineCorrectBolusSize(asked)
        if (abs(units - asked) > 1e-9) {
            aapsLogger.warn(LTag.PUMP, "Apex: a bolus of $asked U is not on the pump's step, delivering $units U")
            info.insulin = units
        }
        if (units <= 0.0) return Result(false, false, 0.0, rh.gs(R.string.apex_bolus_zero))
        val type = info.bolusType
        val account = BolusAccount(store, serial)
        var action: Action? = null
        var heartbeatDuringBolus: Long? = null

        // Settings not read yet: the slow speed, the longer wait, is the safe one.
        val slow = pumpState.settings?.lowBolusSpeed != false
        val outcome = delivery.bolus(
            units = units,
            durationMs = BolusTiming.durationMs(units, slow),
            onAccepted = { at ->
                val (opened, row) = account.commandAccepted(at, units, type.name)
                action = opened
                carrier.carry(row, serial)
                trace.event(ApexTraceCat.STATE, "bolus_accepted", "order" to at, "units" to units, "type" to type.name)
            },
            onProgress = { delivered ->
                bolusProgressData.updateProgress(PumpInsulin(delivered))
                action = action?.let { account.progress(it, delivered) }
            },
            onHeartbeat = { at ->
                heartbeatDuringBolus = at
                aapsLogger.info(LTag.PUMP, "Apex: heartbeat during our bolus: no reconciliation while it runs, it is answered when the bolus ends")
            }
        )
        val order = action
        if (outcome !is ApexBolusOutcome.Delivered || order == null) {
            val comment = if (outcome is ApexBolusOutcome.Refused) rh.gs(R.string.apex_refused_by_pump) else rh.gs(R.string.apex_bolus_failed)
            aapsLogger.error(LTag.PUMP, "Apex: bolus of $units U not started, $comment")
            trace.event(ApexTraceCat.STATE, "bolus_not_started", "units" to units, "outcome" to outcome.toString())
            // A heartbeat that came while the command was sent is answered as a heartbeat.
            (heartbeatDuringBolus ?: delivery.takeNotedHeartbeat())?.let { heartbeatTick.run(it) }
            return Result(false, false, 0.0, comment)
        }
        val ended = account.finished(order, outcome.reportedUnits, outcome.frames, outcome.cancelPressed, outcome.cancelled, outcome.completed)
        account.seenRow(ended)?.let {
            aapsLogger.info(LTag.PUMP, "Apex: the pump's last word on our bolus of $units U says ${it.amountUnits} U: the row shows it until the reconciliation")
            carrier.carry(it, serial)
        }
        val answered = heartbeatDuringBolus?.let { heartbeatTick.run(it) } ?: true
        val delivered = outcome.reportedUnits
        trace.event(
            ApexTraceCat.STATE, "bolus_done", "order" to order.acceptedAtMs, "units" to delivered, "frames" to outcome.frames,
            "completed" to outcome.completed, "cancel_pressed" to outcome.cancelPressed, "cancelled" to outcome.cancelled, "answered" to answered
        )

        val short = units - delivered >= ApexProtocol.DOSE_SCALE
        if (short && !outcome.cancelled) {
            aapsLogger.error(LTag.PUMP, "Apex: the pump delivered $delivered U of the $units U ordered")
            return Result(false, true, delivered, rh.gs(R.string.apex_bolus_short, delivered, units))
        }
        return Result(true, true, delivered, null)
    }
}
