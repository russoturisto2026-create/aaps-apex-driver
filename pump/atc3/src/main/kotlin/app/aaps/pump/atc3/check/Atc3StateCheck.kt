package app.aaps.pump.atc3.check

import app.aaps.pump.atc3.clock.Atc3DayClock
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.protocol.Atc3Protocol
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Does the AAPS journal say the same as the pump's own count of what it delivered?
 *
 * The pump's side is its count of today, read with every status; the AAPS side is what its rows add
 * up to over the same interval, [Atc3JournalArithmetic]. The interval runs from the anchor, the read
 * the comparison last started from, to the read now; the anchor stays while the two agree, since only
 * the sum over the whole distance says whether the steps of delivery are all that is between them.
 *
 * - The tolerance is the caller's.
 * - A difference inside it that stays on one side for [STUCK_READS] reads is a disagreement too.
 * - Nothing is compared across a beginning: a start, a refill, a new pump, midnight, a count begun
 *   anew. The caller reads the journals and starts again.
 *
 * It decides nothing: what to read and what to correct is the caller's.
 */
@Singleton
class Atc3StateCheck @Inject constructor() {

    /** What the comparison says when it is asked. */
    sealed interface Verdict {

        /** One word for a trace line. */
        fun trace(): String = when (this) {
            is Matches -> "balanced"
            is Unknown -> "unknown"
            is Differs -> if (units > 0) "excess" else "shortfall"
        }

        /** The journal accounts for the count, to within the tolerance. */
        data object Matches : Verdict

        /** Nothing to compare against. */
        data object Unknown : Verdict

        /**
         * The pump counted [units] more than the journal: positive is insulin AAPS does not hold, negative
         * is less delivered than AAPS holds.
         */
        data class Differs(val units: Double) : Verdict
    }

    /**
     * The read the next comparison starts from.
     *
     * @param learnedAfterMs when this read was accepted: boluses learned after it are the next
     *   interval's, see [app.aaps.pump.atc3.history.Atc3HistorySync.bolusesLearnedAfter]
     */
    data class Baseline(val readMs: Long, val counterUnits: Double, val learnedAfterMs: Long = readMs)

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

    /** The baseline this read compares against, or null: none yet, after [forget], across midnight, or when time or count went back. */
    @Synchronized
    fun baselineFor(readMs: Long, counterUnits: Double): Baseline? {
        val base = baseline ?: return null
        if (!Atc3DayClock.sameDay(base.readMs, readMs) || readMs < base.readMs || counterUnits + PULSE_EPSILON < base.counterUnits) {
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
        // Asked again on the same read after the journals: the same sample.
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
    fun accept(readMs: Long, counterUnits: Double, learnedAfterMs: Long = readMs) {
        baseline = Baseline(readMs, counterUnits, learnedAfterMs)
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

    /** True when the journal owed a whole step and the count did not move, twice running: a pump that stopped without saying so. */
    @Synchronized
    fun notDelivering(): Boolean = motionlessSamples >= NOT_DELIVERING_SAMPLES

    private fun noteMotion(readMs: Long, base: Baseline, counterUnits: Double, aapsUnits: Double) {
        if (readMs == motionSampledAtMs) return
        motionSampledAtMs = readMs
        val moved = counterUnits - (lastCounterUnits ?: base.counterUnits)
        val owed = aapsUnits - lastAapsUnits
        lastCounterUnits = counterUnits
        lastAapsUnits = aapsUnits
        if (moved >= Atc3Protocol.DOSE_SCALE - PULSE_EPSILON) {
            motionlessSamples = 0
            return
        }
        if (owed < Atc3Protocol.DOSE_SCALE) return
        motionlessSamples++
    }

    companion object {

        /** Reads owing insulin and counting none before the pump is called stopped. */
        const val NOT_DELIVERING_SAMPLES = 2

        /** Reads running on one side before a difference inside the tolerance is looked into. */
        const val STUCK_READS = 3

        /** How far out a difference has to sit to count towards [STUCK_READS], units. */
        const val STUCK_UNITS = 2 * Atc3Protocol.DOSE_SCALE

        /** Slack so that exactly one pulse lands inside the tolerance, not on its edge. */
        private const val PULSE_EPSILON = 1e-9
    }
}
