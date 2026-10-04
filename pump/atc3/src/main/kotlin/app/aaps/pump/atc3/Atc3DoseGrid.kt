package app.aaps.pump.atc3

import app.aaps.core.data.pump.defs.DoseStepSize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The amounts of insulin this pump works in.
 *
 * The pump's scale is not even: below 1 U an amount moves in steps of 0.025, from 1 U in steps of
 * 0.05 and from 2 U in steps of 0.1 -- [DoseStepSize.Atc3], the same scale a bolus, a basal rate and
 * a temporary basal rate are ordered on. Everything that names an amount of insulin for the pump is
 * on it: a limit set on the pump's screen as much as a dose, so that a field can only offer what
 * the pump works in and shows no more decimals than its step has.
 */
object Atc3DoseGrid {

    /** The step going up from [amount]. */
    fun stepAt(amount: Double): Double = DoseStepSize.Atc3.getStepSizeForAmount(amount.coerceAtLeast(0.0))

    /** The step going down from [amount]: on a border between two steps, the finer one below it. */
    fun stepBelow(amount: Double): Double = stepAt((amount - EPSILON).coerceAtLeast(0.0))

    /** The amount on the scale nearest to [amount]. */
    fun nearest(amount: Double): Double = stepAt(amount).let { step -> clean(Math.round(amount / step) * step) }

    /** The first amount on the scale at or above [amount]. */
    fun up(amount: Double): Double = stepAt(amount).let { step -> clean(ceil(amount / step - EPSILON) * step) }

    /** The last amount on the scale at or below [amount]. */
    fun down(amount: Double): Double = stepAt(amount).let { step -> clean(floor(amount / step + EPSILON) * step) }

    fun isOn(amount: Double): Boolean = abs(nearest(amount) - amount) < EPSILON

    /**
     * The decimals an amount is shown with: as many as its step has. An amount off the scale -- one
     * the pump was given before the scale was kept to -- is shown in full, so that the screen does
     * not round away what the pump really holds.
     */
    fun pattern(amount: Double): String = when {
        !isOn(amount) || amount < 1.0 - EPSILON -> "0.000"
        amount < 2.0 - EPSILON                  -> "0.00"
        else                                    -> "0.0"
    }

    /** Rid of the binary dust a multiplication leaves, on the pump's finest step's thousandths. */
    private fun clean(amount: Double): Double = Math.round(amount * 1000.0) / 1000.0

    private const val EPSILON = 1e-6
}
