package app.aaps.pump.atc3.state

import app.aaps.core.data.pump.defs.DoseStepSize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The amounts of insulin this pump works in, [DoseStepSize.Atc3]: finer below 1 U, coarser from
 * 1 U and 2 U. Every amount named for the pump is put on it, limits as well as doses, and shown with
 * as many decimals as its step has.
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

    /** The decimals an amount is shown with: those of its step, or all of them for an amount off the scale. */
    fun pattern(amount: Double): String = when {
        !isOn(amount) || amount < 1.0 - EPSILON -> "0.000"
        amount < 2.0 - EPSILON                  -> "0.00"
        else                                    -> "0.0"
    }

    /** Rid of what a multiplication leaves past the finest step. */
    private fun clean(amount: Double): Double = Math.round(amount * 1000.0) / 1000.0

    private const val EPSILON = 1e-6
}
