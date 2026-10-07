package app.aaps.pump.atc3

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.defs.determineCorrectBasalSize
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.state.Atc3DoseGrid
import app.aaps.pump.atc3.state.Atc3PumpState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * A profile reaches the pump on the pump's own steps, whatever percentage it was switched to.
 *
 * A percentage makes rates the pump has no step for: 1.05 U/h at 120 % is 1.26. Each half hour's
 * rate is brought to the nearest amount on the pump's scale -- 0.025 below 1, 0.05 from 1, 0.1
 * from 2 -- by the step of the range the rate itself lies in.
 */
class Atc3ProfileStepsTest {

    /** A profile of one rate all day, switched to [percent]. */
    private fun slotsOf(rate: Double, percent: Int): DoubleArray {
        val profile = mock<Profile>()
        whenever(profile.getBasalTimeFromMidnight(any())).thenReturn(rate * percent / 100.0)
        return Atc3PumpState.buildBasalSlots(profile) { PumpType.ATC3.determineCorrectBasalSize(it) }
    }

    private fun sent(rate: Double, percent: Int): Double = slotsOf(rate, percent).also { slots ->
        assertThat(slots.size).isEqualTo(Atc3Protocol.BASAL_SLOTS)
        assertThat(slots.distinct().size).isEqualTo(1)
    }[0]

    @Test
    fun `a rate a percentage puts between two steps goes to the nearest`() {
        // 1.05 at 120 % is 1.26: between 1.25 and 1.30.
        assertThat(sent(1.05, 120)).isWithin(1e-9).of(1.25)
        // 0.55 at 130 % is 0.715: between 0.700 and 0.725.
        assertThat(sent(0.55, 130)).isWithin(1e-9).of(0.725)
        // 2.3 at 110 % is 2.53: between 2.5 and 2.6.
        assertThat(sent(2.3, 110)).isWithin(1e-9).of(2.5)
        // 0.5 at 90 % is 0.45, on the scale already.
        assertThat(sent(0.5, 90)).isWithin(1e-9).of(0.45)
    }

    @Test
    fun `a rate a percentage carries across a border takes the step of the range it lands in`() {
        // 0.95 at 110 % is 1.045, above 1: the step there is 0.05.
        assertThat(sent(0.95, 110)).isWithin(1e-9).of(1.05)
        // 1.9 at 110 % is 2.09, above 2: the step there is 0.1.
        assertThat(sent(1.9, 110)).isWithin(1e-9).of(2.1)
        // 1.1 at 90 % is 0.99, below 1: the step there is 0.025, and the nearest is the border itself.
        assertThat(sent(1.1, 90)).isWithin(1e-9).of(1.0)
        // 2.2 at 90 % is 1.98, below 2: the nearest on 0.05 is 2.0.
        assertThat(sent(2.2, 90)).isWithin(1e-9).of(2.0)
    }

    @Test
    fun `every rate sent is on the pump's scale`() {
        for (percent in 50..200 step 5) for (raw in 2..120) {
            val rate = raw * 0.025
            val out = sent(rate, percent)
            assertThat(Atc3DoseGrid.isOn(out)).isTrue()
            // And no further from what was asked than half the step of where it was asked.
            val asked = rate * percent / 100.0
            assertThat(kotlin.math.abs(out - asked)).isAtMost(Atc3DoseGrid.stepAt(asked) / 2 + 1e-9)
        }
    }
}
