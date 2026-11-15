package app.aaps.pump.apex

import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.command.ApexBolusDelivery
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.state.ApexPumpState
import java.util.TimeZone
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
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

    /** AAPS says the phone's time moved: the clock is written in the minute after the next heartbeat. */
    fun onPhoneTimeChanged(timeChangeType: TimeChangeType) {
        aapsLogger.debug(LTag.PUMP, "Apex: phone time changed, $timeChangeType")
        phoneTimeChanged = true
    }

    /**
     * The pump's clock is the phone's local time. It is written once when the driver first meets the
     * pump, then when the pump's heartbeat shows it off by more than [ApexConst.CLOCK_ERROR_LIMIT_MS],
     * and when the phone's time moved; never while our bolus runs or an alarm is raised. It is written
     * only in the minute after the pump's heartbeat, at the end of the heartbeat's reconciliation:
     * the next heartbeat is minutes away, and nothing meets it.
     *
     * When the zone moved, or summer time began or ended, the old time is closed first: every record of the past that could
     * come in the new time and be placed wrong. The temporary basal that runs, ours or not, is ended
     * with its row; the pump writes its record when it ends, stamped with its start, and a record that
     * straddles the change could not be placed. Then both journals are read again and settled. Only
     * then the clock is written, and right after it the same rate is ordered again, as ours, for what
     * was left of it rounded up to the pump's quarter hour, so that the pump does not run without it until the loop orders the next one.
     * A step that fails leaves the clock as it is, and the next heartbeat tries again; what was settled
     * before it is kept, the rows of it are in AAPS already.
     *
     * @param storedOffsetMs the offset the pump's clock stands in
     * @param nowMs now
     * @param endTbr ends the running temporary basal with its row; what is wrong, or null
     * @param settleJournals reads and settles both journals in the old time; false when the pump stopped answering
     * @param restartTbr orders a temporary basal of the rate (U/h) for the minutes, as ours; what is wrong, or null
     * @return true when the clock was written
     */
    fun writeIfDue(
        serial: String, storedOffsetMs: Long, nowMs: Long, endTbr: () -> String?, settleJournals: () -> Boolean, restartTbr: (Double, Int) -> String?
    ): Boolean {
        val phoneOffsetMs = phoneOffsetMs()
        val stored = store.clock(serial)
        val zoneMoved = phoneOffsetMs != storedOffsetMs
        val reason = when {
            zoneMoved                                                     -> "the zone or summer time moved"
            phoneTimeChanged                                              -> "phone time changed"
            stored == null                       -> "first write"
            clockOff(manager.pumpClockErrorMs()) -> "the heartbeat shows the clock off by ${manager.pumpClockErrorMs()} ms"
            else                                 -> return false
        }
        if (bolusDelivery.bolusInProgress || pumpState.activeAlarmCodes.isNotEmpty()) return false
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
            if (!settleJournals()) {
                aapsLogger.warn(LTag.PUMP, "Apex: the journals were not settled before the clock write; the write waits for the next heartbeat")
                return false
            }
        }
        val bolusCount = JournalSnapshot(store, serial, SnapshotKind.BOLUS).topSeq()
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
