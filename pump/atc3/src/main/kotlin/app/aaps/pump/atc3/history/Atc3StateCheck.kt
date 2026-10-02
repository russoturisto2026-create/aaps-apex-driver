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
 * interval, worked out by [Atc3JournalArithmetic]. The difference between the two is a running
 * account: the pump delivers in portions on a grid of its own, restarts its profile count on every
 * half hour and rounds every temporary basal to its portions, so the journal, which counts a rate
 * over its time, always sits some part of a portion off. Inside the tolerance that is left alone;
 * beyond it the caller first reads the pump's journals for anything AAPS is missing and then writes
 * what is left into the rows, see [Atc3BasalCorrection].
 *
 * **The interval runs from the anchor to the read.** The count is live: it moves with every pulse
 * while the snapshot time beside it stays on its minute, so it belongs to the moment it was read.
 * The anchor is the read the comparison was last started from, and it stays where it is: the
 * journal counts a rate over its time and the pump delivers it in steps, so each row sits a part of
 * a step from the count, and only the sum over the whole distance says what the journal owes. The reservoir takes no part: it and the counter do
 * not move in step, one or two steps apart either way.
 *
 * **The tolerance is the caller's**, what the pump delivers in a minute at the highest basal rate it
 * is set to allow.
 *
 * **A difference that stays is looked into.** One that sits on the same side for [STUCK_READS]
 * reads running, more than [STUCK_UNITS] out, can be a small bolus somebody gave between two reads,
 * so it is reported as a difference and the caller reads the journals; [lookedInto] then starts the
 * count of reads over.
 *
 * **The difference is carried, never dropped.** A new anchor -- the pump's midnight, when the count
 * starts again from nothing, or a clock write -- starts from the difference the last comparison
 * found, [lastDifference], so that what the journal still owes is not lost with the old anchor.
 * The anchor itself lives in memory: a restart of AAPS starts the account afresh.
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
         * The pump counted [units] more than the journal accounts for, beyond the tolerance or
         * for too many reads on one side.
         *
         * Positive: the pump delivered more than AAPS holds -- its portions running ahead of the
         * journal's rate, or a stranger's bolus. Negative: AAPS holds more than went in -- its
         * portions running behind, a pause it did not see, or the pump not delivering at all.
         */
        data class Differs(val units: Double) : Verdict
    }

    /**
     * The read the next comparison starts from.
     *
     * @param learnedAfterMs the moment this read was accepted: the boluses the driver learned of
     *   after it belong to the next interval, those before were in the count this read anchored.
     *   A bolus is learned when its delivered amount is known -- our own on its completion frame
     *   or its record, a stranger's on its import -- and the pump's count holds it by then, which
     *   no time on a bolus row can promise, see [app.aaps.pump.atc3.history.Atc3HistorySync.bolusesLearnedAfter].
     */
    data class Baseline(val readMs: Long, val counterUnits: Double, val learnedAfterMs: Long = readMs, val carriedUnits: Double = 0.0)

    private var baseline: Baseline? = null

    /** Reads running that left the two sides more than [STUCK_UNITS] apart on the same side, and which side. */
    private var stuckReads: Int = 0
    private var stuckSide: Int = 0
    private var stuckSampledAtMs: Long = 0L

    /** The count and the journal's sum at the comparison before this one, for [notDelivering]. */
    private var lastCounterUnits: Double? = null
    private var lastAapsUnits: Double = 0.0

    /** The difference the last comparison found, carried into the next anchor; null before any. */
    private var lastDifference: Double? = null

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
        val difference = base.carriedUnits + counterUnits - base.counterUnits - aapsUnits
        lastDifference = difference
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

    /** The journals were read for a difference that stayed: the reads running are counted afresh. */
    @Synchronized
    fun lookedInto() {
        stuckReads = 0
        stuckSide = 0
    }

    /** What the last comparison found the pump to have counted beyond the journal, or null. */
    @Synchronized
    fun lastDifference(): Double? = lastDifference

    /**
     * This read is the anchor from now on, because there was none to compare against.
     *
     * @param carriedUnits what the journal still owed at the last comparison, see [lastDifference]
     */
    @Synchronized
    fun accept(readMs: Long, counterUnits: Double, learnedAfterMs: Long = readMs, carriedUnits: Double = 0.0) {
        baseline = Baseline(readMs, counterUnits, learnedAfterMs, carriedUnits)
        stuckReads = 0
        stuckSide = 0
        lastCounterUnits = null
        lastAapsUnits = 0.0
    }

    /**
     * Nothing to compare against any more: the pump's clock was written. The difference found last
     * is kept, for the next anchor to carry.
     */
    @Synchronized
    fun forget() {
        baseline = null
        stuckReads = 0
        stuckSide = 0
        lastCounterUnits = null
        lastAapsUnits = 0.0
        motionlessSamples = 0
    }

    @Synchronized
    fun baseline(): Baseline? = baseline

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
