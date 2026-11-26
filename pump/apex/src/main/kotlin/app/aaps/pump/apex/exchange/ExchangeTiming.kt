package app.aaps.pump.apex.exchange

import app.aaps.pump.apex.ApexConst

/** The arithmetic of when a request may go: the pump's heartbeat, its clock, and the first moments of a link. */
object ExchangeTiming {

    /**
     * How far behind the phone the pump's clock is, milliseconds, by a heartbeat heard at [heardAtMs]:
     * the heartbeat comes on second 0 of the pump's clock, [ApexConst.HEARTBEAT_LATENCY_MS] before it
     * is heard. Negative when the pump's clock is ahead. Within half a minute either way.
     */
    fun clockErrorOf(heardAtMs: Long): Long {
        val intoMinute = Math.floorMod(heardAtMs - ApexConst.HEARTBEAT_LATENCY_MS, 60_000L)
        return if (intoMinute < 30_000L) intoMinute else intoMinute - 60_000L
    }

    /**
     * Until when a request now must wait for the heartbeat, or null when it is not near. The pump
     * beats on its own clock, one period after another to within a second a day: the next one is
     * due a whole number of periods after [lastMs], and one that did not come keeps the beat.
     *
     * @param lastMs when the last heartbeat came, 0 when none came since the link or the clock write
     * @param periodMs the period of the heartbeat last set on the pump
     */
    fun heartbeatClearUntil(lastMs: Long, nowMs: Long, periodMs: Long): Long? {
        if (lastMs == 0L) return null
        var dueMs = lastMs + periodMs
        while (dueMs + ApexConst.HEARTBEAT_CLEAR_AFTER_MS < nowMs) dueMs += periodMs
        return if (nowMs < dueMs - ApexConst.HEARTBEAT_CLEAR_BEFORE_MS) null else dueMs + ApexConst.HEARTBEAT_CLEAR_AFTER_MS
    }

    /**
     * How much of the settle window is still owed, milliseconds, never more than the window itself
     * (a corrected phone clock can put the link's start in the future). Apart from the sleep so a
     * test can check it.
     *
     * @param linkUpAtMs when the link came up, 0 when there is none
     */
    fun settleDelayMs(linkUpAtMs: Long, now: Long): Long {
        if (linkUpAtMs == 0L) return 0L
        return (ApexConst.FIRST_REQUEST_SETTLE_MS - (now - linkUpAtMs))
            .coerceIn(0L, ApexConst.FIRST_REQUEST_SETTLE_MS)
    }
}
