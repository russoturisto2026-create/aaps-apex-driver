package app.aaps.pump.apex.command

import kotlin.math.roundToLong

/**
 * When a bolus of the ordered dose is over, by the delivery mechanics (page «Такты подачи Apex»). Our
 * bolus runs as a state of the whole driver until its completion frame or its accepted cancel; when
 * neither comes, until this time from the pump's acceptance and [AFTER_END_MS] more, see
 * [ApexBolusDelivery]. Nothing is asked of the pump in it.
 *
 * - Delivered by t seconds after the acceptance: min(B, p × ⌊t − 0.8 + 1⌋) at the normal bolus speed:
 *   the first portion at 0.8 s, then one a second, so the last of N portions at 0.8 s + (N − 1) s.
 *   At the slow speed the first portion at 5.2 s, then one every 5 s.
 * - The portion p by the dose B: below 1 U 0.025 U; to 7.5 U 0.05 U; from 7.55 U to 15 U always 150
 *   portions; above 15 U 0.1 U.
 */
object BolusTiming {

    /** From the pump's acceptance to the last portion of a bolus of [units], milliseconds. */
    fun durationMs(units: Double, slowSpeed: Boolean): Long {
        val rest = portions(units) - 1
        return if (slowSpeed) SLOW_FIRST_MS + rest * SLOW_PORTION_MS else FIRST_MS + rest * PORTION_MS
    }

    /** How many portions a bolus of [units] goes in. */
    fun portions(units: Double): Long = when {
        units < 1.0 - HALF_STEP   -> (units / 0.025).roundToLong()
        units <= 7.5 + HALF_STEP  -> (units / 0.05).roundToLong()
        units <= 15.0 + HALF_STEP -> LARGE_PORTIONS
        else                      -> (units / 0.1).roundToLong()
    }

    /** With no completion frame, the state «a bolus runs» lasts this much past the last portion: the record is written by then. */
    const val AFTER_END_MS = 5_000L

    private const val FIRST_MS = 800L
    private const val PORTION_MS = 1_000L
    private const val SLOW_FIRST_MS = 5_200L
    private const val SLOW_PORTION_MS = 5_000L
    private const val LARGE_PORTIONS = 150L
    private const val HALF_STEP = 0.0125
}
