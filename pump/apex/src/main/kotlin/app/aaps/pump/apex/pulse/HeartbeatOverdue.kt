package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.pump.apex.clock.ApexClock
import app.aaps.pump.apex.link.ApexConnection
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** The heartbeat is overdue: the pump is asked, in its turn on the AAPS queue, see [HeartbeatOverdue]. */
class ApexHeartbeatOverdue : CustomCommand {

    override val statusDescription: String = "Apex HEARTBEAT OVERDUE"
}

/**
 * The watch over the pump's heartbeat.
 *
 * The problem. The driver reads the pump only at its heartbeat. Two things can stop the heartbeat
 * without anyone telling the driver: the link goes quiet while the Bluetooth stack still says it is
 * up, or another client turns the pump's heartbeat off or sets another period. Then the driver reads
 * nothing at all: no rows, no checks, and the loop runs on old data.
 *
 * When it applies. No heartbeat came for [OVERDUE_PERIODS] periods and [SLACK_MS] more, counted from
 * the last heartbeat or from the start of the watch. On the plain path the heartbeat comes every
 * period and this watch does nothing.
 *
 * The way out. The status is read through the AAPS queue; the queue brings the link up first when
 * it is down. A read that reaches nothing counts towards giving the link up, and the next ask brings a
 * new link. A read that is answered proves the pump: its heartbeat is set again, on the period the
 * user chose. The pump is asked again no sooner than one period later.
 */
@SingleIn(AppScope::class)
@Inject
class HeartbeatOverdue(
    private val aapsLogger: AAPSLogger,
    private val pumpState: ApexPumpState,
    private val manager: ApexManager,
    private val connection: ApexConnection,
    private val commandQueue: CommandQueue,
    private val trace: ApexTrace
) {

    /** When the watch began, phone clock: a heartbeat is due from then on even when none came yet. */
    @Volatile private var startedAtMs = 0L

    /** When the pump was last asked for an overdue heartbeat, phone clock; 0 when never. */
    @Volatile private var askedAtMs = 0L

    fun start(nowMs: Long) {
        startedAtMs = nowMs
        askedAtMs = 0L
    }

    /** Looked at every [CHECK_MS]: true when the read is to be queued now, the heartbeat being overdue and no read waiting yet. */
    fun check(nowMs: Long): Boolean {
        if (!connection.isConfigured || !connection.isPasswordEntered) return false
        val periodMs = manager.heartbeatPeriodMinutes() * ApexClock.MINUTE_MS
        if (!isOverdue(nowMs, pumpState.lastHeartbeatAtMs, startedAtMs, askedAtMs, periodMs)) return false
        if (commandQueue.isCustomCommandInQueue(ApexHeartbeatOverdue::class)) return false
        askedAtMs = nowMs
        val sinceMs = nowMs - maxOf(pumpState.lastHeartbeatAtMs, startedAtMs)
        aapsLogger.warn(LTag.PUMP, "Apex: no heartbeat for ${sinceMs / 1000} s, the period is ${periodMs / 60_000} min: the pump is asked")
        trace.event(ApexTraceCat.SESS, "heartbeat_overdue", "ms" to sinceMs, "connected" to connection.isConnected)
        return true
    }

    /**
     * The overdue read in its turn on the queue: the status, and when it is answered the heartbeat set
     * again. True when the pump answered.
     */
    fun ask(): Boolean {
        if (!connection.isConnected) return false
        if (manager.readStatus() == null) {
            aapsLogger.warn(LTag.PUMP, "Apex: the heartbeat is overdue and the pump did not answer its status")
            return false
        }
        aapsLogger.warn(LTag.PUMP, "Apex: the heartbeat is overdue and the pump answered: its heartbeat is set again")
        manager.setHeartbeatPeriod(manager.heartbeatPeriodMinutes())
        return true
    }

    companion object {

        /** How often the watch looks. */
        const val CHECK_MS = 30_000L

        /** Periods without a heartbeat before it is overdue. */
        const val OVERDUE_PERIODS = 2

        /** Added to the periods: the reconciliation after a heartbeat and the pump's own drift. */
        const val SLACK_MS = 30_000L

        /**
         * True when no heartbeat came for [OVERDUE_PERIODS] periods of [periodMs] and [SLACK_MS], counted
         * from the last heartbeat, [lastBeatMs], or from when the watch began, [startedMs], whichever is
         * later; and the pump was not asked within the last period, [askedMs].
         */
        fun isOverdue(nowMs: Long, lastBeatMs: Long, startedMs: Long, askedMs: Long, periodMs: Long): Boolean {
            val since = maxOf(lastBeatMs, startedMs)
            if (nowMs - since < OVERDUE_PERIODS * periodMs + SLACK_MS) return false
            return askedMs == 0L || nowMs - askedMs >= periodMs
        }
    }
}
