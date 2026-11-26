package app.aaps.pump.apex.command

import app.aaps.pump.apex.protocol.ApexProtocol
import kotlin.math.roundToLong

/**
 * When a bolus of the ordered dose is over, by the delivery mechanics. Our
 * bolus runs as a state of the whole driver until its completion frame or its accepted cancel; when
 * neither comes, until this time from the pump's acceptance and [AFTER_END_MS] more, see
 * [ApexBolusDelivery]. Nothing is asked of the pump in it.
 *
 * The rule of the page. The portions of a bolus go one a second at the normal bolus speed in the pump's
 * settings, one every 5 seconds at the slow speed: the first at 0.8 s after the acceptance (5.2 s at the
 * slow speed). A bolus has 150 portions at most. While the dose fits in 150 portions of 0.05 U, the pump
 * gives it so; when it does not fit, the first portions are 0.1 U:
 * - a dose B below 1.0 U: B ÷ 0.025 portions of 0.025 U;
 * - from 1.0 to 7.5 U: B ÷ 0.05 portions of 0.05 U;
 * - from 7.55 to 15 U: 150 portions, the first a = 20 × (B − 7.5) of 0.1 U, the other 150 − a of 0.05 U
 *   (8 U: 10 × 0.1 + 140 × 0.05; 10 U: 50 × 0.1 + 100 × 0.05; 15 U: 150 × 0.1);
 * - above 15 U the dose does not fit in 150 portions of 0.1 U: the portions are 0.1 U from the start, no
 *   0.15 U, B ÷ 0.1 of them. Only the first 20 s of such a bolus were seen; its length was not measured.
 */
object BolusTiming {

    /** From the pump's acceptance to the last portion of a bolus of [units], milliseconds. */
    fun durationMs(units: Double, slowSpeed: Boolean): Long {
        val rest = portions(units) - 1
        return if (slowSpeed) SLOW_FIRST_MS + rest * SLOW_PORTION_MS else FIRST_MS + rest * PORTION_MS
    }

    /** How many portions a bolus of [units] goes in: [MAX_PORTIONS] at most while the dose fits in them at 0.1 U. */
    fun portions(units: Double): Long = when {
        units < 1.0 - ApexProtocol.HALF_STEP   -> (units / 0.025).roundToLong()
        units <= 7.5 + ApexProtocol.HALF_STEP  -> (units / 0.05).roundToLong()
        units <= 15.0 + ApexProtocol.HALF_STEP -> MAX_PORTIONS
        else                      -> (units / 0.1).roundToLong()
    }

    /** With no completion frame, the state «a bolus runs» lasts this much past the last portion: the record is written by then. */
    const val AFTER_END_MS = 5_000L

    private const val FIRST_MS = 800L
    private const val PORTION_MS = 1_000L
    private const val SLOW_FIRST_MS = 5_200L
    private const val SLOW_PORTION_MS = 5_000L
    private const val MAX_PORTIONS = 150L
}
