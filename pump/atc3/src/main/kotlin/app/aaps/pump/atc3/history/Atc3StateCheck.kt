package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.Atc3Const
import java.util.Calendar
import kotlin.math.abs

/**
 * Does the AAPS journal say the same as the pump's own count of what it delivered?
 *
 * Every tick reads Status V1, and Status V1 carries the pump's count of insulin delivered today,
 * basal and bolus together. That count is the pump's side. The AAPS side is what the rows AAPS
 * holds — boluses, temporary basals, the scheduled rate in between — add up to over the same
 * interval, worked out by [Atc3JournalArithmetic]. While the two agree to within the tolerance the
 * journal is the pump's; when they do not, something the pump did is not in AAPS as the pump did
 * it, and the pump's journals are read to put it there.
 *
 * **The interval runs from the anchor to the read.** The count is live: it moves with every pulse
 * while the snapshot time beside it stays on its minute, so it belongs to the moment it was read.
 * The anchor is the read the comparison was last started from, and it stays where it is while the
 * two sides agree: the journal counts a rate over its time and the pump delivers it in steps, so
 * each row sits a part of a step from the count, and only the sum over the whole distance says
 * whether that is all there is between them. The reservoir takes no part: it and the counter do
 * not move in step, one or two steps apart either way.
 *
 * **The tolerance is the caller's**, what the pump can deliver in one go at the highest basal rate
 * it is set to allow. Anything beyond it is a mistake or somebody else's hand.
 *
 * **A difference that stays is one too.** The steps of delivery push the two sides apart now one
 * way and now the other, so a difference inside the tolerance comes back by itself. One that sits
 * on the same side for [STUCK_READS] reads running, more than [STUCK_UNITS] out, is not that: it is
 * something that began and ended between two reads, and it is reported as a disagreement.
 *
 * **The anchor is not stored.** It lives in memory: a restart, a new pump, the pump's midnight (the
 * count starts again from nothing) and a clock write each leave the check with nothing to compare
 * against, which the caller answers by reading everything and accepting.
 *
 * **This class decides nothing.** It says whether the two sides agree and by how much they do not.
 * What to read and what to correct belongs to the caller.
 */
class Atc3StateCheck {

    /** What the comparison says when it is asked. */
    sealed interface Verdict {

        /** One word for a trace line. */
        fun trace(): String = when (this) {
            is Matches -> "balanced"
            is Unknown -> "unknown"
            is Differs -> if (units > 0) "excess" else "shortfall"
        }

        /** The journal accounts for what the pump counted, to within a step. */
        data object Matches : Verdict

        /** There is nothing to compare against, so nothing can be claimed. */
        data object Unknown : Verdict

        /**
         * The pump counted [units] more than the journal accounts for, beyond one step.
         *
         * Positive: the pump delivered insulin AAPS does not hold — a stranger's bolus or a
         * temporary basal above what AAPS has. Negative: AAPS holds more than went in — a pause it
         * did not see, a temporary basal below what it has, or the pump not delivering at all.
         */
        data class Differs(val units: Double) : Verdict
    }

    /**
     * The read the next comparison starts from.
     *
     * @param bolusUntilMs where the boluses of the interval ending at this read were counted
     *   up to, which is where the next interval's boluses start from: the snapshot's time, or the
     *   end of its minute when the snapshot was an event's own -- a stranger's bolus is stamped
     *   at second 59 of the minute it started in, and the snapshot a bolus rebuilds already
     *   counts it, while the journal would not yet hold its row.
     */
    data class Baseline(val readMs: Long, val counterUnits: Double, val bolusUntilMs: Long = readMs)

    private var baseline: Baseline? = null

    /** Reads running that left the two sides more than [STUCK_UNITS] apart on the same side, and which side. */
    private var stuckReads: Int = 0
    private var stuckSide: Int = 0
    private var stuckSampledAtMs: Long = 0L

    /** The count and the journal's sum at the comparison before this one, for [notDelivering]. */
    private var lastCounterUnits: Double? = null
    private var lastAapsUnits: Double = 0.0

    /** How many comparisons in a row ended unexplained after the journals were read. */
    private var unexplainedRuns: Int = 0

    /** Consecutive reads where the journal owed a step of insulin and the count did not move. */
    private var motionlessSamples: Int = 0
    private var motionSampledAtMs: Long = 0L

    /**
     * The baseline this read can be compared against, or null when nothing can be claimed.
     *
     * Null on the first read, after [forget], after the pump's midnight — the count is of
     * "today" and starts again — and when the time or the count went backwards, which
     * is a clock put back or a count reset the driver did not see.
     */
    @Synchronized
    fun baselineFor(readMs: Long, counterUnits: Double): Baseline? {
        val base = baseline ?: return null
        if (!sameDay(base.readMs, readMs) || readMs < base.readMs || counterUnits + PULSE_EPSILON < base.counterUnits) {
            baseline = null
            return null
        }
        return base
    }

    /**
     * Compare what the pump counted since [base] with what the journal accounts for.
     *
     * @param aapsUnits what AAPS's rows add up to over `[base.readMs, readMs)`
     * @param toleranceUnits how far apart the two may sit and still agree
     */
    @Synchronized
    fun compare(base: Baseline, readMs: Long, counterUnits: Double, aapsUnits: Double, toleranceUnits: Double): Verdict {
        noteMotion(readMs, base, counterUnits, aapsUnits)
        val difference = counterUnits - base.counterUnits - aapsUnits
        noteSide(readMs, difference)
        val agrees = abs(difference) <= toleranceUnits + PULSE_EPSILON && stuckReads < STUCK_READS
        return if (agrees) Verdict.Matches else Verdict.Differs(difference)
    }

    private fun noteSide(readMs: Long, difference: Double) {
        val side = if (abs(difference) <= STUCK_UNITS + PULSE_EPSILON) 0 else if (difference > 0) 1 else -1
        // Asked again on the same read, after the journals: the same sample, unless they have
        // brought the two sides together.
        if (readMs == stuckSampledAtMs && side == stuckSide) return
        stuckSampledAtMs = readMs
        stuckReads = if (side == 0) 0 else if (side == stuckSide) stuckReads + 1 else 1
        stuckSide = side
    }

    @Synchronized
    fun stuckReads(): Int = stuckReads

    /**
     * This read is the anchor from now on: there was none, or the journals have been read and
     * AAPS brought to what they say.
     */
    @Synchronized
    fun accept(readMs: Long, counterUnits: Double, bolusUntilMs: Long = readMs) {
        baseline = Baseline(readMs, counterUnits, bolusUntilMs)
        stuckReads = 0
        stuckSide = 0
        lastCounterUnits = null
        lastAapsUnits = 0.0
        unexplainedRuns = 0
    }

    /** The journals were read and the two sides still disagree. @return how many times in a row */
    @Synchronized
    fun unexplained(): Int = ++unexplainedRuns

    /** Nothing known any more: the pump changed, or its clock was written. */
    @Synchronized
    fun forget() {
        baseline = null
        stuckReads = 0
        stuckSide = 0
        lastCounterUnits = null
        lastAapsUnits = 0.0
        motionlessSamples = 0
        unexplainedRuns = 0
    }

    @Synchronized
    fun baseline(): Baseline? = baseline

    @Synchronized
    fun unexplainedRuns(): Int = unexplainedRuns

    /**
     * True when the journal has owed insulin and the pump has counted none for two reads
     * running.
     *
     * The one symptom of a mechanism that has stopped without saying so. A single read proves
     * nothing — the pump delivers in steps and a read can fall before the next one — but twice
     * in a row, each owing a whole step, is not that. A read that owed less than a step does not
     * count either way: at a low rate the pump genuinely has nothing to deliver yet.
     */
    @Synchronized
    fun notDelivering(): Boolean = motionlessSamples >= NOT_DELIVERING_SAMPLES

    private fun noteMotion(readMs: Long, base: Baseline, counterUnits: Double, aapsUnits: Double) {
        if (readMs == motionSampledAtMs) return
        motionSampledAtMs = readMs
        val moved = counterUnits - (lastCounterUnits ?: base.counterUnits)
        val owed = aapsUnits - lastAapsUnits
        lastCounterUnits = counterUnits
        lastAapsUnits = aapsUnits
        if (moved >= Atc3Const.DOSE_SCALE - PULSE_EPSILON) {
            motionlessSamples = 0
            return
        }
        if (owed < Atc3Const.DOSE_SCALE) return
        motionlessSamples++
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val x = Calendar.getInstance().apply { timeInMillis = a }
        val y = Calendar.getInstance().apply { timeInMillis = b }
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
    }

    companion object {

        /** Reads owing insulin and counting none before the pump is called stopped. */
        const val NOT_DELIVERING_SAMPLES = 2

        /** Reads running on one side before a difference inside the tolerance is looked into. */
        const val STUCK_READS = 3

        /** How far out a difference has to sit to count towards [STUCK_READS], units. */
        const val STUCK_UNITS = 2 * Atc3Const.DOSE_SCALE

        /** Slack so that exactly one pulse lands inside the tolerance, not on its edge. */
        private const val PULSE_EPSILON = 1e-9
    }
}
