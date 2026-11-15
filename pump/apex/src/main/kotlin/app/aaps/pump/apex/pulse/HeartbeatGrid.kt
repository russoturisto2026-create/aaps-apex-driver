package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import java.util.Calendar
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * Keeps the pump's heartbeat on the period the user chose, see [ApexManager.heartbeatPeriodMinutes],
 * and on the minutes of its grid: with 5 minutes :00, :05, ... :30, ...; with 1, 2 or 3 minutes
 * likewise. Every period divides the half hour, so the grid holds :00 and :30. A period the user
 * changed goes to the pump at the next heartbeat.
 *
 * What the pump does: a new period rebuilds the grid from the last
 * heartbeat, the next one coming at last heartbeat + period; a command with the same period
 * changes nothing. So, when a heartbeat lands on a minute whose class (minute mod period) is not
 * 0, the period is set to the number of minutes that brings the next heartbeat onto class 0; when
 * that heartbeat comes, the period is set back. Two commands, two minutes at most, both at a
 * heartbeat. The pump answers the first command only sometimes and carries it out all the same, so
 * no answer is not a failure here: the next heartbeat tells.
 */
@SingleIn(AppScope::class)
@Inject
class HeartbeatGrid(
    private val aapsLogger: AAPSLogger,
    private val manager: ApexManager,
    private val trace: ApexTrace
) {

    /** True after the short period went out, until the heartbeat it was for came. */
    @Volatile private var moving = false

    /** Heartbeats that did not land where they should have; the grid is left alone after [GIVE_UP_AFTER]. */
    @Volatile private var misses = 0

    /** A heartbeat came at [heartbeatAtMs]: the step the grid needs now, if any. */
    fun onHeartbeat(heartbeatAtMs: Long) {
        val period = manager.heartbeatPeriodMinutes()
        if (!moving && manager.heartbeatPeriodDue()) {
            // The user changed the period: it goes to the pump first, the grid is looked at from the next heartbeat on.
            aapsLogger.info(LTag.PUMP, "Apex: the heartbeat period is now $period min")
            manager.ensureHeartbeatPeriod()
            misses = 0
            return
        }
        val step = stepFor(minuteOf(heartbeatAtMs), moving, misses, period)
        when (step) {
            Step.NONE    -> Unit
            Step.SHORTEN -> {
                val minutes = shortPeriod(minuteOf(heartbeatAtMs), period)
                aapsLogger.info(LTag.PUMP, "Apex: the heartbeat is on minute ${minuteOf(heartbeatAtMs)}, period $minutes min for one beat to move it onto the grid of $period min")
                manager.setHeartbeatPeriod(minutes)
                moving = true
            }

            Step.RESTORE -> {
                aapsLogger.info(LTag.PUMP, "Apex: the heartbeat is on minute ${minuteOf(heartbeatAtMs)}, period $period min again")
                manager.setHeartbeatPeriod(period)
                moving = false
            }

            Step.MISSED  -> {
                misses++
                aapsLogger.warn(LTag.PUMP, "Apex: the heartbeat came on minute ${minuteOf(heartbeatAtMs)}, not on the grid of $period min; miss $misses of $GIVE_UP_AFTER")
                manager.setHeartbeatPeriod(period)
                moving = false
            }
        }
        if (step != Step.NONE) trace.event(ApexTraceCat.SESS, "heartbeat_grid", "step" to step.name, "minute" to minuteOf(heartbeatAtMs), "misses" to misses)
    }

    enum class Step { NONE, SHORTEN, RESTORE, MISSED }

    companion object {

        /** How many heartbeats off the grid, after the move, before the grid is left as it is. */
        const val GIVE_UP_AFTER = 2

        /** Minute of the hour a heartbeat at [ms] came in, by the phone's clock: it comes a fraction of a second into the pump's minute. */
        fun minuteOf(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.MINUTE)

        /** Whether [minute] is one the grid of [period] minutes holds: :00, then every [period] minutes. */
        fun onGrid(minute: Int, period: Int): Boolean = minute % period == 0

        /** Whether [minute] closes a half hour, :00 or :30: the pump closes its basal window then, and the slow objects are read. */
        fun onHalfHour(minute: Int): Boolean = minute % HALF_HOUR_MINUTES == 0

        private const val HALF_HOUR_MINUTES = 30

        /** The period, minutes, that brings the next heartbeat from [minute] onto the grid of [period] minutes. */
        fun shortPeriod(minute: Int, period: Int): Int = period - minute % period

        /**
         * What to do at a heartbeat on [minute]: nothing on the grid when not moving; the short
         * period off the grid when not moving and not given up; the normal period back when moving
         * and on the grid; a miss when moving and still off the grid.
         */
        fun stepFor(minute: Int, moving: Boolean, misses: Int, period: Int): Step = when {
            !moving && onGrid(minute, period)  -> Step.NONE
            !moving && misses >= GIVE_UP_AFTER -> Step.NONE
            !moving                            -> Step.SHORTEN
            onGrid(minute, period)             -> Step.RESTORE
            else                               -> Step.MISSED
        }
    }
}
