package app.aaps.pump.atc3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The pump's scale of insulin amounts: 0.025 below 1 U, 0.05 from 1 U, 0.1 from 2 U.
 */
class Atc3DoseGridTest {

    @Test
    fun `an amount is brought to the step of its own range`() {
        assertThat(Atc3DoseGrid.nearest(0.337)).isWithin(1e-9).of(0.325)
        assertThat(Atc3DoseGrid.nearest(1.425)).isWithin(1e-9).of(1.45)
        assertThat(Atc3DoseGrid.nearest(1.03)).isWithin(1e-9).of(1.05)
        assertThat(Atc3DoseGrid.nearest(10.025)).isWithin(1e-9).of(10.0)
        assertThat(Atc3DoseGrid.nearest(0.99)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `a step up or down crosses a border onto the scale of the other side`() {
        // Up from 1.0 by the finer step lands between two amounts of the range above.
        assertThat(Atc3DoseGrid.up(1.025)).isWithin(1e-9).of(1.05)
        assertThat(Atc3DoseGrid.up(2.05)).isWithin(1e-9).of(2.1)
        assertThat(Atc3DoseGrid.down(0.975)).isWithin(1e-9).of(0.975)
        assertThat(Atc3DoseGrid.down(1.95)).isWithin(1e-9).of(1.95)
        assertThat(Atc3DoseGrid.up(1.05)).isWithin(1e-9).of(1.05)
    }

    @Test
    fun `on a border the step down is the finer one below it`() {
        assertThat(Atc3DoseGrid.stepBelow(1.0)).isEqualTo(0.025)
        assertThat(Atc3DoseGrid.stepAt(1.0)).isEqualTo(0.05)
        assertThat(Atc3DoseGrid.stepBelow(2.0)).isEqualTo(0.05)
        assertThat(Atc3DoseGrid.stepAt(2.0)).isEqualTo(0.1)
        assertThat(Atc3DoseGrid.stepBelow(0.0)).isEqualTo(0.025)
    }

    @Test
    fun `an amount is shown with the decimals of its step, and one off the scale in full`() {
        assertThat(Atc3DoseGrid.pattern(0.975)).isEqualTo("0.000")
        assertThat(Atc3DoseGrid.pattern(1.0)).isEqualTo("0.00")
        assertThat(Atc3DoseGrid.pattern(1.95)).isEqualTo("0.00")
        assertThat(Atc3DoseGrid.pattern(2.0)).isEqualTo("0.0")
        assertThat(Atc3DoseGrid.pattern(7.0)).isEqualTo("0.0")
        assertThat(Atc3DoseGrid.pattern(10.025)).isEqualTo("0.000")
    }
}
