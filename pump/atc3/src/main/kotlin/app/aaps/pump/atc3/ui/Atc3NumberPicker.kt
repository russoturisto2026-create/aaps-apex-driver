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
 * The AAPS number picker styled like the rest of the pump screen: round stepper buttons and the
 * value in a pill. Only the look changes; the views are dressed after inflation, since
 * `NumberPickerViewAdapter` accepts only core's two layouts.
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

    /** A round button: sized both ways, without the margins that tucked a square one into the box. */
    private fun roundStepper(button: ImageButton) {
        (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.width = dp(STEPPER_DP)
            params.height = dp(STEPPER_DP)
            params.setMargins(0, 0, 0, 0)
            params.marginStart = 0
            params.marginEnd = 0
            button.layoutParams = params
        }
        button.background = ContextCompat.getDrawable(context, R.drawable.atc3_stepper)
        button.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.atc3_ink))
        button.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val padding = dp(STEPPER_PADDING_DP)
        button.setPadding(padding, padding, padding, padding)
    }

    /** The value in a pill of its own, leaving the buttons free. */
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
        layout.setBoxBackgroundColor(ContextCompat.getColor(context, R.color.atc3_surface_2))
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
