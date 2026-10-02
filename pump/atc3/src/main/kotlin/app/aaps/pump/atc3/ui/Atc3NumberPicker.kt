package app.aaps.pump.atc3.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.content.ContextCompat
import app.aaps.core.ui.elements.NumberPicker
import app.aaps.pump.atc3.R
import com.google.android.material.textfield.TextInputLayout

/**
 * The AAPS number picker, styled to match the rest of the pump screen.
 *
 * Here a stepper is two round outlined buttons with the value in a pill between them, the same
 * capsule shapes as the rest of the screen. The stock picker draws two square Material buttons
 * overlapping the ends of an outlined rectangle.
 *
 * Only the appearance is changed. Everything the picker does, the long press acceleration, the
 * typed entry, the range and the formatter, is the AAPS widget's own and is left alone, which is
 * why this dresses the inflated views rather than replacing the layout: the layout is bound
 * through `NumberPickerViewAdapter`, which only accepts the two layouts core ships.
 */
class Atc3NumberPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : NumberPicker(context, attrs) {

    init {
        // The stock layout is a fixed 130dp wide whatever the wrapper around it says, so it has to
        // be let go before any of the widths below mean anything.
        getChildAt(0)?.let { root ->
            root.layoutParams = root.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        roundStepper(binding.minusButton)
        roundStepper(binding.plusButton)
        pillValue(binding.textInputLayout)
    }

    /**
     * A round button rather than a square one.
     *
     * The size has to be set in both directions: the stock layout gives the button the full height
     * and a fixed width, which an oval background would draw as an ellipse. The negative margins go
     * with it, they exist only to tuck a square button into the corners of the value box.
     */
    private fun roundStepper(button: ImageButton) {
        (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.width = dp(STEPPER_DP)
            params.height = dp(STEPPER_DP)
            params.setMargins(0, 0, 0, 0)
            params.marginStart = 0
            params.marginEnd = 0
            button.layoutParams = params
        }
        button.background = ContextCompat.getDrawable(context, R.drawable.atc3_halo_stepper)
        button.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.atc3_halo_ink))
        button.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val padding = dp(STEPPER_PADDING_DP)
        button.setPadding(padding, padding, padding, padding)
    }

    /**
     * The value in a pill of its own, inset far enough to leave the two buttons standing free.
     *
     * The outlined box the stock picker uses is stripped rather than restyled: what is wanted is
     * the same pill the select rows on these screens use.
     */
    private fun pillValue(layout: TextInputLayout) {
        (layout.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.marginStart = dp(STEPPER_DP + STEPPER_GAP_DP)
            params.marginEnd = dp(STEPPER_DP + STEPPER_GAP_DP)
            layout.layoutParams = params
        }
        layout.boxStrokeWidth = 0
        layout.boxStrokeWidthFocused = 0
        val radius = dp(PILL_RADIUS_DP).toFloat()
        layout.setBoxCornerRadii(radius, radius, radius, radius)
        layout.setBoxBackgroundColor(ContextCompat.getColor(context, R.color.atc3_halo_surface_2))
        // The stock padding is sized for a box that runs the whole width; here the number has a
        // narrow pill to sit in and needs all of it.
        binding.editText.setPadding(0, dp(VALUE_PADDING_DP), 0, dp(VALUE_PADDING_DP))
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {

        /** Diameter of a stepper button, density independent pixels. */
        private const val STEPPER_DP = 34

        /** Space between a stepper button and the value pill. */
        private const val STEPPER_GAP_DP = 4

        private const val PILL_RADIUS_DP = 17
        private const val STEPPER_PADDING_DP = 9
        private const val VALUE_PADDING_DP = 7
    }
}
