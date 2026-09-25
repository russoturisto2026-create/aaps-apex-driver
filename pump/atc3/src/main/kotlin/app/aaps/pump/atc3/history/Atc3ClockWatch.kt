package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.Atc3Const
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Whether the pump's clock has to be written, decided off the pump's own bolus records.
 *
 * Nothing else answers it: the time in Status V1 is the moment of the pump's last status snapshot,
 * not its clock, and no time is taken from it.
 *
 * **A bolus record answers it.** The pump stamps a bolus record with the minute the bolus started,
 * at second 59, and the driver knows that minute on the phone's clock, because it is the minute the
 * pump accepted the command. The driver writes the pump's clock from the phone's, seconds included,
 * so right after a write the two agree to the millisecond and every record of ours falls in the
 * minute it started in. When the history is read, the records of our own boluses are matched with
 * one shift of the minute for the whole read ([Atc3BolusReconciler]); that shift is the verdict
 * this class keeps.
 *
 * The rules:
 *
 * - **A read matched with no shift clears the count.** The clock is where the driver put it.
 * - **A read matched only a minute early or late counts one miss.**
 * - **Two misses in a row and the clock is written** ([Atc3Const.CLOCK_JOURNAL_MISSES_TO_SET]).
 * - **After a clock write the count starts again from nothing**, see [forget].
 * - **A verdict goes stale after a day.** [Atc3Const.RECONCILE_MAX_AGE_MS] is a day, and nothing
 *   older than that is acted on anywhere else in the driver. Past its life the answer is "no
 *   verdict", and a miss that old does not count towards the next one.
 *
 * Pure by construction: no Android, no clock of its own, every time it needs arrives as an
 * argument. That is what lets the cases be pinned as unit tests.
 */
@Singleton
class Atc3ClockWatch @Inject constructor() {

    /** One read's shift of the pump's minute, and the phone time it was taken at. */
    private data class Verdict(val shiftMinutes: Int, val atPhoneMs: Long)

    private var newest: Verdict? = null

    /** Reads in a row that matched our own boluses only a minute off. */
    private var misses: Int = 0

    /**
     * Our own temporary basal commands in a row whose stamp from the pump sat too far from the
     * acknowledgement, and when the last of them was seen. Counted apart from the boluses: a
     * temporary basal that sat well says only that the two clocks are within a minute and a
     * half, which is not the bolus history's word that they agree to the minute.
     */
    private var tbrMisses: Int = 0
    private var tbrMissAtPhoneMs: Long = 0L

    /**
     * The history was just matched against our own boluses with this shift of the pump's minute.
     *
     * @param shiftMinutes 0 when the records sat in the minutes our boluses started in, -1 or +1
     *   when they sat a minute early or late
     * @param atPhoneMs now, on the phone clock, so the verdict can grow old
     */
    fun matched(shiftMinutes: Int, atPhoneMs: Long) {
        val carried = if (fresh(atPhoneMs) != null) misses else 0
        misses = if (shiftMinutes == 0) 0 else carried + 1
        newest = Verdict(shiftMinutes, atPhoneMs)
    }

    /**
     * The pump stamped our own temporary basal command [lagMs] before the acknowledgement.
     *
     * The stamp is the pump's last whole minute before the command, so the lag is the pump's
     * clock behind the phone's plus up to a minute; a lag of a minute and a half proves the pump
     * at least half a minute behind, and a stamp after the acknowledgement proves it ahead. Two
     * such commands in a row and the clock is written, quietly, as for the boluses. The loop
     * sets a temporary basal every cycle, so this notices a clock going off hours before a
     * bolus would -- and before the stamp sits too far from the acknowledgement for the command
     * to be recognised as ours at all (Atc3PumpPlugin.OWN_TBR_START_MS).
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

        /**
         * How far the pump's stamp of our own temporary basal may sit before the acknowledgement
         * before it counts as a miss, milliseconds: a minute for the stamp being the last whole
         * minute, and half a minute of the pump's clock behind the phone's.
         */
        const val TBR_LAG_MISS_MS = 90_000L

        /**
         * How long a verdict is worth anything, milliseconds.
         *
         * A day, because [Atc3Const.RECONCILE_MAX_AGE_MS] is a day: the driver acts on nothing
         * older than that anywhere else.
         */
        const val OBSERVATION_LIFE_MS = Atc3Const.RECONCILE_MAX_AGE_MS
    }
}
