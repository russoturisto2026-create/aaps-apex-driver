package app.aaps.pump.apex.clock

import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.ApexConst
import app.aaps.pump.apex.bolus.BolusAccount
import app.aaps.pump.apex.bolus.BolusCells
import app.aaps.pump.apex.store.ApexStore
import app.aaps.pump.apex.command.ApexBolusDelivery
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.state.ApexPumpState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.abs

/**
 * The pump's clock against the phone's. The pump has a clock and no zone; the driver sets it to the
 * phone's local time and keeps the offset it set it in, see [ApexClock]. This is the writing side:
 * when the clock is due, what the phone's offset is now, and the write itself in the minute after the pump's heartbeat.
 */
@SingleIn(AppScope::class)
@Inject
class ApexPumpClock(
    private val aapsLogger: AAPSLogger,
    private val store: ApexStore,
    private val pumpState: ApexPumpState,
    private val manager: ApexManager,
    private val bolusDelivery: ApexBolusDelivery
) {

    /** AAPS said the phone's time moved: the pump's clock follows in the minute after the next heartbeat. */
    @Volatile private var phoneTimeChanged = false

    /** The phone's offset from UTC now, milliseconds: what the pump's clock is written in. */
    fun phoneOffsetMs(): Long = ApexClock.phoneOffsetMs(System.currentTimeMillis())

    /** The offset the clock of the pump [serial] stands in, see [ApexClock.offsetMs]: the one written last, else the phone's. */
    fun offsetMs(serial: String): Long = ApexClock.offsetMs(store, serial, phoneOffsetMs())

    /** AAPS says the phone's time moved: the clock is written in the minute after the next heartbeat. */
    fun onPhoneTimeChanged(timeChangeType: TimeChangeType) {
        aapsLogger.debug(LTag.PUMP, "Apex: phone time changed, $timeChangeType")
        phoneTimeChanged = true
    }

    /**
     * Why the pump's clock is to be written at this heartbeat, null when it is not due, see [writeIfDue].
     * Asked before the picture is read: a heartbeat that writes the clock reads everything first.
     */
    fun dueReason(serial: String, storedOffsetMs: Long): String? = when {
        phoneOffsetMs() != storedOffsetMs    -> "the zone or summer time moved"
        phoneTimeChanged                     -> "phone time changed"
        store.clock(serial) == null          -> "first write"
        clockOff(manager.pumpClockErrorMs()) -> "the heartbeat shows the clock off by ${manager.pumpClockErrorMs()} ms"
        else                                 -> null
    }

    /**
     * The pump's clock is the phone's local time. It is written once when the driver first meets the
     * pump, then when the pump's heartbeat shows it off by more than [ApexConst.CLOCK_ERROR_LIMIT_MS],
     * and when the phone's time moved; never while our bolus runs or an alarm is raised. It is written
     * only in the minute after the pump's heartbeat, at the end of the heartbeat's reconciliation:
     * the next heartbeat is minutes away, and nothing meets it. A write closes a period: what came
     * before it is the past, settled in the old time. So that heartbeat reads every object, the journals
     * too, and the clock is written only when its reconciliation succeeded, see `HeartbeatTick`.
     *
     * When the zone moved, or summer time began or ended, the old time is closed first: every record of the past that could
     * come in the new time and be placed wrong. The temporary basal that runs, ours or not, is ended
     * with its row; the pump writes its record when it ends, stamped with its start, and a record that
     * straddles the change could not be placed. The journals need nothing more: this heartbeat read and
     * settled them, and their records are placed by their order, not by their stamps. Then the clock is written, and right after it the same rate is ordered again, as ours, for what
     * was left of it rounded up to the pump's quarter hour, so that the pump does not run without it until the loop orders the next one.
     * A step that fails leaves the clock as it is, and the next heartbeat tries again; what was settled
     * before it is kept, the rows of it are in AAPS already.
     *
     * @param storedOffsetMs the offset the pump's clock stands in
     * @param endTbr ends the running temporary basal with its row; what is wrong, or null
     * @param restartTbr orders a temporary basal of the rate (U/h) for the minutes, as ours; what is wrong, or null
     * @return true when the clock was written
     */
    fun writeIfDue(
        serial: String, storedOffsetMs: Long, endTbr: () -> String?, restartTbr: (Double, Int) -> String?
    ): Boolean {
        val phoneOffsetMs = phoneOffsetMs()
        val zoneMoved = phoneOffsetMs != storedOffsetMs
        val reason = dueReason(serial, storedOffsetMs) ?: return false
        if (bolusDelivery.bolusInProgress || pumpState.activeAlarmCodes.isNotEmpty()) return false
        // A bolus of ours not reconciled yet stands in the period the write would close: the next heartbeat reconciles it first.
        if (BolusAccount(store, serial).openActions().isNotEmpty()) return false
        // What ran, to be ordered again after the write: an absolute rate and the minutes left of it.
        var rerun: Pair<Double, Int>? = null
        if (zoneMoved) {
            val card = pumpState.statusCard
            if (card?.tbrActive == true) {
                if (card.status.tbrMode == ApexProtocol.TbrPayload.MODE_ABSOLUTE.toInt()) rerun = card.tbrRate to (card.tbrDurationMinutes - card.tbrElapsedMinutes)
                val failure = endTbr()
                if (failure != null) {
                    aapsLogger.warn(LTag.PUMP, "Apex: the temporary basal was not ended before the clock write, $failure; the write waits for the next heartbeat")
                    return false
                }
                aapsLogger.info(LTag.PUMP, "Apex: the temporary basal ended before the clock write")
            }
        }
        val bolusCount = BolusCells(store, serial).topSeq()
        val write = manager.writeClock(phoneOffsetMs)
        write.failure?.let { failure ->
            aapsLogger.warn(LTag.PUMP, "Apex: clock not written, $failure")
            return false
        }
        ApexClock.clockWritten(store, serial, phoneOffsetMs, write.atMs, reason, bolusCount)
        phoneTimeChanged = false
        aapsLogger.debug(LTag.PUMP, "Apex: clock written, offset ${phoneOffsetMs / 60_000} min, $reason")
        manager.readStatus()
        rerun?.let { (rate, minutesLeft) ->
            if (minutesLeft <= 0) return@let
            // The pump counts in quarter hours: what was left is rounded up. The loop sets the next one anyway.
            val unit = ApexProtocol.TbrPayload.DURATION_UNIT_MINUTES
            val minutes = (minutesLeft + unit - 1) / unit * unit
            val failure = restartTbr(rate, minutes)
            if (failure == null) aapsLogger.info(LTag.PUMP, "Apex: the temporary basal of $rate U/h ordered again after the clock write, for $minutes min")
            else aapsLogger.warn(LTag.PUMP, "Apex: the temporary basal was not ordered again after the clock write, $failure; the loop orders the next one")
        }
        return true
    }

    /** The pump's clock, by its heartbeat, is further off than [ApexConst.CLOCK_ERROR_LIMIT_MS]. */
    private fun clockOff(errorMs: Long?): Boolean = errorMs != null && abs(errorMs) > ApexConst.CLOCK_ERROR_LIMIT_MS
}
