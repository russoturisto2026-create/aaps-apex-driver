package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.account.time.ApexClock

/**
 * Where the comparison of the day on the screen starts, and what the pump had counted there.
 *
 * The problem. AAPS counts its rows from the phone's midnight, the pump counts its day from its own
 * midnight or from the moment it opened a new day. The two days are the same only while the pump's
 * clock stands in the phone's zone from midnight on. After AAPS started in the middle of a day, or
 * after the zone moved, the two sums cover different spans, and the gap shows insulin nobody lost.
 *
 * The rule. Both sides are counted from one start: the AAPS rows from its moment, the pump's count
 * less what the pump had counted there.
 * - The first heartbeat after AAPS started is the start.
 * - The zone moved, and the pump went on in the same day: the last heartbeat before the clock write
 *   is the start, with its count.
 * - The zone moved, and the pump opened a new day for the new date: the clock write is the start,
 *   from nothing.
 * - A new day with no move of the zone is the pump's midnight: the phone's midnight is the start,
 *   from nothing.
 * - A count below the one at the start is a day the pump began over after a power loss: the
 *   heartbeat that shows it is the start.
 * - The pump's count comes another way than at the start, with its parts or as the total alone:
 *   the two cannot be taken one from the other, so the heartbeat that shows it is the start.
 * Every start is a whole minute: the heartbeat of minute M brings the pump up to M-1:59.999, and the
 * rows of what the pump did after it start at M:00, so a start at the millisecond the heartbeat came
 * would leave them out.
 */
class DayWindow {

    /** The pump's counts of its day at one moment, U, with the day and the offset of its clock. */
    class Count(
        atMs: Long,
        /** The day of the daily statistics, as its clock key. */
        val dayKey: Long,
        val offsetMs: Long,
        val counterUnits: Double,
        val bolusUnits: Double,
        val basalUnits: Double,
        /** False when the pump gives the total alone: the parts are not known and are never shown. */
        val parts: Boolean = true
    ) {

        /** The start of the minute the count was taken in, see the rule above. */
        val atMs: Long = ApexClock.minuteStart(atMs)

        fun fromNothing(atMs: Long) = Count(atMs, dayKey, offsetMs, 0.0, 0.0, 0.0, parts)

        companion object {

            /**
             * The count of a pump that gives no daily statistics: only the total of the day, with the
             * day taken from the status clock. The parts are left at zero and are never shown.
             */
            fun ofTotal(atMs: Long, clockSeconds: Long, offsetMs: Long, counterUnits: Double) =
                Count(atMs, Math.floorDiv(clockSeconds, ApexClock.DAY_SECONDS) * ApexClock.DAY_SECONDS, offsetMs, counterUnits, 0.0, 0.0, parts = false)
        }

        /** Below [other] in some field: the same day cannot go back. */
        fun below(other: Count): Boolean =
            counterUnits < other.counterUnits - ApexProtocol.ROUNDING || bolusUnits < other.bolusUnits - ApexProtocol.ROUNDING || basalUnits < other.basalUnits - ApexProtocol.ROUNDING
    }

    @Volatile private var start: Count? = null
    @Volatile private var last: Count? = null

    /**
     * The start for the heartbeat that counted [now]. [clockWrittenAtMs] is the last clock write,
     * [midnightMs] the phone's midnight before [now].
     */
    fun move(now: Count, clockWrittenAtMs: Long?, midnightMs: Long): Count {
        val before = last
        val from = start
        val next = when {
            from == null || before == null   -> now
            now.parts != from.parts          -> now
            now.offsetMs != before.offsetMs  -> if (now.dayKey == before.dayKey) before else now.fromNothing(clockWrittenAtMs ?: before.atMs)
            now.dayKey != from.dayKey        -> now.fromNothing(maxOf(midnightMs, from.atMs))
            now.below(from)                  -> now
            else                             -> from
        }
        start = next
        last = now
        return next
    }

    /** Forget the start: another pump, or this one paired afresh. */
    fun reset() {
        start = null
        last = null
    }

    private companion object {
    }
}
