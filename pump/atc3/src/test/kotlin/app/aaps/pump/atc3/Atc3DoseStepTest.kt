package app.aaps.pump.atc3

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.defs.determineCorrectBasalSize
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusSize
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The pump delivers whole portions only: 0.025 U below 1 U, 0.05 U from 1 U, 0.1 U from 2 U. A bolus
 * and a basal rate are ordered on those steps, so that nothing asked of the pump is rounded by it.
 */
class Atc3DoseStepTest {

    private fun stepOf(amount: Double) = when {
        amount < 1.0 - 1e-9 -> 0.025
        amount < 2.0 - 1e-9 -> 0.05
        else                -> 0.1
    }

    private fun onStep(value: Double): Boolean {
        val step = stepOf(value)
        return abs(value / step - (value / step).roundToLong()) < 1e-6
    }

    @Test
    fun `every bolus is put on the step of its range, at most half a step away`() {
        var amount = 0.01
        while (amount < 10.0) {
            val bolus = PumpType.ATC3.determineCorrectBolusSize(amount)
            assertThat(onStep(bolus)).isTrue()
            assertThat(abs(bolus - amount)).isAtMost(stepOf(amount) / 2 + 1e-6)
            amount += 0.005
        }
    }

    @Test
    fun `every basal rate is put on the same steps`() {
        var rate = 0.01
        while (rate < 10.0) {
            val basal = PumpType.ATC3.determineCorrectBasalSize(rate)
            assertThat(onStep(basal)).isTrue()
            rate += 0.005
        }
    }

    @Test
    fun `the amounts the pump can deliver whole are kept as they are`() {
        for (amount in listOf(0.025, 0.325, 0.975, 1.0, 1.05, 1.45, 1.95, 2.0, 2.1, 5.0)) {
            assertThat(PumpType.ATC3.determineCorrectBolusSize(amount)).isWithin(1e-9).of(amount)
            assertThat(PumpType.ATC3.determineCorrectBasalSize(amount)).isWithin(1e-9).of(amount)
        }
    }

    @Test
    fun `an amount off the step is moved onto it`() {
        assertThat(onStep(PumpType.ATC3.determineCorrectBolusSize(1.425))).isTrue()
        assertThat(PumpType.ATC3.determineCorrectBolusSize(2.05)).isAnyOf(2.0, 2.1)
        assertThat(PumpType.ATC3.determineCorrectBasalSize(1.025)).isAnyOf(1.0, 1.05)
    }
}
