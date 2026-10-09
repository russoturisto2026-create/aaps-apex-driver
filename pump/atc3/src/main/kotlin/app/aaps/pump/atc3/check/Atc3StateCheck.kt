package app.aaps.pump.atc3.check

import app.aaps.pump.atc3.protocol.Atc3Protocol
import kotlin.math.abs

/**
 * Does the AAPS journal say the same as the pump's own count of what it delivered? The pump's side
 * is its count since the window began; the AAPS side is what its rows add up to over the same time,
 * see [Atc3JournalArithmetic]. A difference the caller has looked into and found nothing for is
 * settled: it stays counted, and only what comes on top of it is new. Decides nothing.
 */
object Atc3StateCheck {

    /** What the comparison says when it is asked. */
    sealed interface Verdict {

        /** One word for a trace line. */
        fun trace(): String = when (this) {
            is Matches -> "balanced"
            is Unknown -> "unknown"
            is Differs -> if (units > 0) "excess" else "shortfall"
        }

        /** The journal accounts for the count, to within the tolerance, beyond what is settled. */
        data object Matches : Verdict

        /** Nothing to compare against. */
        data object Unknown : Verdict

        /**
         * The pump counted [units] more than the journal since the window began, the settled part
         * included: positive is insulin AAPS does not hold, negative is less delivered than AAPS holds.
         */
        data class Differs(val units: Double) : Verdict
    }

    /**
     * @param pumpUnits what the pump counted since the window began
     * @param aapsUnits what AAPS's rows add up to over the same time
     * @param settledUnits the difference already looked into, see the object
     * @param toleranceUnits how far apart the two may sit, beyond what is settled, and still agree
     */
    fun compare(pumpUnits: Double, aapsUnits: Double, settledUnits: Double, toleranceUnits: Double): Verdict {
        val difference = pumpUnits - aapsUnits
        val agrees = abs(difference - settledUnits) <= toleranceUnits + Atc3Protocol.COUNT_EPSILON
        return if (agrees) Verdict.Matches else Verdict.Differs(difference)
    }
}
