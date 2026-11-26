package app.aaps.pump.apex.clock

import app.aaps.pump.apex.store.ApexStore
import app.aaps.pump.apex.store.ClockOffset
import java.util.TimeZone

/**
 * The pump's wall clock against UTC. The pump has a clock and no zone; the driver sets the clock to
 * the phone's local time and keeps the offset it set it in. Every stamp the pump gives is a key,
 * seconds of the wall clock read on a UTC calendar; the key becomes a moment through the offset,
 * once, when the record is imported. A stamp made before a clock write belongs to the offset before
 * it: a bolus record by its number against the count the write found, any other stamp by the span
 * of the clock log it falls in.
 */
object ApexClock {

    /** The moment, UTC milliseconds, of a wall clock key under [offsetMs]. */
    fun momentOf(clockSeconds: Long, offsetMs: Long): Long = clockSeconds * 1000L - offsetMs

    /** The phone's offset from UTC at [utcMs], milliseconds: what the pump's clock is written in. */
    fun phoneOffsetMs(utcMs: Long): Long = TimeZone.getDefault().getOffset(utcMs).toLong()

    /** The start of the minute [ms] falls in. */
    fun minuteStart(ms: Long): Long = ms - Math.floorMod(ms, MINUTE_MS)

    /** The wall clock key the pump shows at [utcMs] under [offsetMs]. */
    fun clockSecondsOf(utcMs: Long, offsetMs: Long): Long = Math.floorDiv(utcMs + offsetMs, 1000L)

    /**
     * The offset the pump's clock stands in: the one stored at the last clock write, or, before the
     * driver has written the clock once, the phone's own [phoneOffsetMs].
     */
    fun offsetMs(store: ApexStore, serial: String, phoneOffsetMs: Long): Long = store.clock(serial)?.offsetMs ?: phoneOffsetMs

    /** Remember that the clock was just written in [offsetMs], with [bolusCount] records seen so far; the log keeps the old value. */
    fun clockWritten(store: ApexStore, serial: String, offsetMs: Long, atMs: Long, reason: String, bolusCount: Long) {
        store.saveClock(serial, ClockOffset(offsetMs, atMs, reason), bolusCount)
        store.pruneClockLog(serial, atMs - CLOCK_LOG_KEEP_MS)
    }

    /**
     * Drop the lines of the clock log that counted more bolus records than the driver has seen, [topSeq]:
     * the count only grows, so such a line is not of this count and would give its offset to new records.
     * How many went.
     */
    fun dropLogAhead(store: ApexStore, serial: String, topSeq: Long): Int = store.dropClockLogAbove(serial, topSeq)

    /**
     * The offset bolus record number [seq] was stamped in: the one before the first clock write that
     * had already counted it, else [currentOffsetMs].
     */
    fun offsetOfBolusRecord(store: ApexStore, serial: String, seq: Long, currentOffsetMs: Long): Long =
        store.clockLog(serial).firstOrNull { it.bolusCount >= seq }?.let { it.oldOffsetMs ?: it.newOffsetMs } ?: currentOffsetMs

    /**
     * The moment of a wall clock stamp whose place in the clock log is not known by number: the
     * offset of each span of the log is tried, newest first, and the first that puts the stamp
     * inside its own span wins. A stamp that fits no span takes [currentOffsetMs].
     *
     * A span starts at the minute of its write, not at the second: the pump stamps a temporary basal
     * to the minute, and one started a few seconds after the write is stamped before it. What the
     * pump stamped before the write in that minute, in the old offset, still fits the span before.
     *
     * The newest span ends at [nowMs], with the slack of [STAMP_AHEAD_MS]: no stamp lies after now.
     * After the zone moved back, a stamp of the old clock fits the new span too, an hour or more
     * ahead; it is the temporary basal the driver ended to write the clock, read at the next
     * heartbeat. It belongs to the span before. [notAfterMs]: a moment the stamp cannot lie after, as the
     * next record of a journal: a span that puts it later does not win.
     */
    fun momentOfStamp(store: ApexStore, serial: String, clockSeconds: Long, currentOffsetMs: Long, nowMs: Long, notAfterMs: Long = Long.MAX_VALUE): Long {
        val log = store.clockLog(serial)
        var end = nowMs + STAMP_AHEAD_MS
        for (entry in log.asReversed()) {
            val moment = momentOf(clockSeconds, entry.newOffsetMs)
            // Zones stand whole minutes apart: the minute of the write is the same on every clock.
            val startMs = minuteStart(entry.writtenAtMs)
            if (moment >= startMs && moment < end && moment <= notAfterMs) return moment
            end = entry.writtenAtMs
        }
        return momentOf(clockSeconds, currentOffsetMs)
    }

    const val MINUTE_MS = 60_000L
    const val MINUTE_SECONDS = 60L
    const val DAY_SECONDS = 86_400L
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS

    /** How far past now a stamp may stand and still be taken as now: the pump's clock may run ahead of the phone until the driver writes it. */
    const val STAMP_AHEAD_MS = 2 * MINUTE_MS

    /** How long the clock log is kept. */
    const val CLOCK_LOG_KEEP_MS = 90 * DAY_MS
}
