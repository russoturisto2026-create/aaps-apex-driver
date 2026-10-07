package app.aaps.pump.atc3.clock

import app.aaps.pump.atc3.Atc3Const
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Whether the pump's clock is to be written, from the pump's own records of our boluses and
 * temporary basals: a record of ours carries the minute it started on the pump's clock, which the
 * driver knows on the phone's.
 *
 * - a history read that matched our boluses with no shift clears the count;
 * - one matched only a minute off counts a miss;
 * - [Atc3Const.CLOCK_JOURNAL_MISSES_TO_SET] misses in a row and the clock is written;
 * - after a write the count starts over, see [forget];
 * - a verdict older than [OBSERVATION_LIFE_MS] counts for nothing.
 *
 * No clock of its own: every time arrives as an argument.
 */
@Singleton
class Atc3ClockWatch @Inject constructor() {

    /** One read's shift of the pump's minute, and the phone time it was taken at. */
    private data class Verdict(val shiftMinutes: Int, val atPhoneMs: Long)

    private var newest: Verdict? = null

    /** Reads in a row that matched our own boluses only a minute off. */
    private var misses: Int = 0

    /** Our temporary basals in a row the pump stamped too far from their acknowledgement, counted apart from the boluses. */
    private var tbrMisses: Int = 0
    private var tbrMissAtPhoneMs: Long = 0L

    /**
     * The history was matched against our own boluses with this shift of the pump's minute.
     *
     * @param shiftMinutes 0 when the records sat in our boluses' minutes, -1 or +1 when a minute off
     * @param atPhoneMs now on the phone's clock, so the verdict can grow old
     */
    fun matched(shiftMinutes: Int, atPhoneMs: Long) {
        val carried = if (fresh(atPhoneMs) != null) misses else 0
        misses = if (shiftMinutes == 0) 0 else carried + 1
        newest = Verdict(shiftMinutes, atPhoneMs)
    }

    /**
     * The pump stamped our temporary basal [lagMs] before its acknowledgement. Past
     * [TBR_LAG_MISS_MS], or after the acknowledgement, it is a miss; two in a row and the clock is
     * written. The loop sets a temporary basal every cycle, so this notices a drifting clock early.
     */
    fun ownTbrStamped(lagMs: Long, atPhoneMs: Long) {
        val miss = lagMs >= TBR_LAG_MISS_MS || lagMs < 0L
        val carried = if (atPhoneMs - tbrMissAtPhoneMs <= OBSERVATION_LIFE_MS) tbrMisses else 0
        tbrMisses = if (miss) carried + 1 else 0
        if (miss) tbrMissAtPhoneMs = atPhoneMs
    }

    /**
     * True when the history has put our own boluses a minute off twice in a row, or the pump has
     * stamped our own temporary basals too far from their acknowledgement twice in a row.
     */
    fun needsSetting(atPhoneMs: Long): Boolean =
        fresh(atPhoneMs) != null && misses >= Atc3Const.CLOCK_JOURNAL_MISSES_TO_SET ||
            tbrMisses >= Atc3Const.CLOCK_JOURNAL_MISSES_TO_SET && atPhoneMs - tbrMissAtPhoneMs <= OBSERVATION_LIFE_MS

    /** The pump was replaced, or its clock has just been written: nothing counted is about it. */
    fun forget() {
        newest = null
        misses = 0
        tbrMisses = 0
        tbrMissAtPhoneMs = 0L
    }

    private fun fresh(atPhoneMs: Long): Verdict? =
        newest?.takeIf { abs(atPhoneMs - it.atPhoneMs) <= OBSERVATION_LIFE_MS }

    companion object {

        /** How far before the acknowledgement our temporary basal's stamp may sit: the stamp's minute, and half a minute of drift. */
        const val TBR_LAG_MISS_MS = 90_000L

        /** How long a verdict is worth anything: as long as the driver acts on anything. */
        const val OBSERVATION_LIFE_MS = Atc3Const.RECONCILE_MAX_AGE_MS
    }
}
