package app.aaps.pump.atc3.check

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The question every tick starts with: does the AAPS journal say what the pump counted?
 *
 * The rules pinned down here: the tolerance is the caller's, a disagreement counts in both
 * directions, and a settled difference is not a disagreement until more comes on top of it.
 */
class Atc3StateCheckTest {

    private fun verdict(pumpUnits: Double, aapsUnits: Double, settled: Double = 0.0, tolerance: Double = 0.025) =
        Atc3StateCheck.compare(pumpUnits, aapsUnits, settled, tolerance)

    @Test
    fun `a count the journal accounts for is a match`() {
        // Six minutes at 1 U/h is 0.1 U in the journal, and the pump counted 0.1 U.
        assertThat(verdict(0.1, 0.1)).isEqualTo(Atc3StateCheck.Verdict.Matches)
    }

    @Test
    fun `a disagreement of one step is not a disagreement`() {
        assertThat(verdict(0.125, 0.1)).isEqualTo(Atc3StateCheck.Verdict.Matches)
        assertThat(verdict(0.075, 0.1)).isEqualTo(Atc3StateCheck.Verdict.Matches)
    }

    @Test
    fun `insulin the journal does not hold is reported, with how much`() {
        val verdict = verdict(0.35, 0.1)
        assertThat(verdict).isInstanceOf(Atc3StateCheck.Verdict.Differs::class.java)
        assertThat((verdict as Atc3StateCheck.Verdict.Differs).units).isWithin(1e-9).of(0.25)
        assertThat(verdict.trace()).isEqualTo("excess")
    }

    @Test
    fun `insulin the journal holds and the pump did not count is reported too`() {
        // The direction a pause AAPS did not see, or a pump not delivering, shows in.
        val verdict = verdict(0.0, 0.3)
        assertThat(verdict).isInstanceOf(Atc3StateCheck.Verdict.Differs::class.java)
        assertThat((verdict as Atc3StateCheck.Verdict.Differs).units).isWithin(1e-9).of(-0.3)
        assertThat(verdict.trace()).isEqualTo("shortfall")
    }

    @Test
    fun `what the caller allows between the two sides is what decides`() {
        // A minute's worth at 7.5 U/h allowed, and 0.1 U between the two.
        assertThat(verdict(0.2, 0.1, tolerance = 0.125)).isEqualTo(Atc3StateCheck.Verdict.Matches)
        assertThat(verdict(0.2, 0.1, tolerance = 0.05)).isInstanceOf(Atc3StateCheck.Verdict.Differs::class.java)
    }

    @Test
    fun `a difference settled is counted but not a disagreement, until more comes on top of it`() {
        val wide = 0.125
        // 0.3 U the pump counted and the journal does not hold: looked into and settled.
        val first = verdict(0.4, 0.1, tolerance = wide)
        assertThat((first as Atc3StateCheck.Verdict.Differs).units).isWithin(1e-9).of(0.3)
        // The same 0.3 on the next read agrees; the whole difference is still what is reported.
        assertThat(verdict(0.5, 0.2, settled = 0.3, tolerance = wide)).isEqualTo(Atc3StateCheck.Verdict.Matches)
        val more = verdict(0.8, 0.3, settled = 0.3, tolerance = wide)
        assertThat((more as Atc3StateCheck.Verdict.Differs).units).isWithin(1e-9).of(0.5)
    }
}
