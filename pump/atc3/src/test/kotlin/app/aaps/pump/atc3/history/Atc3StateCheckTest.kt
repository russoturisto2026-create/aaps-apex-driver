package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.Atc3Const
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * The question every tick starts with: does the AAPS journal say what the pump counted?
 *
 * The rules pinned down here: the baseline lives in memory or not at all, both ends of the
 * interval are snapshot moments, the tolerance is one delivery step, a disagreement counts in both
 * directions, and the pump's midnight starts the count again.
 */
class Atc3StateCheckTest {

    @org.junit.jupiter.api.Test
    fun `the bolus window an accepted snapshot names is where the next interval's boluses start`() {
        val check = Atc3StateCheck()
        val now = 1_700_000_000_000L
        check.accept(now, 10.0, bolusUntilMs = now + 40_000L)
        assertThat(check.baselineFor(now + 300_000L, 10.5)?.bolusUntilMs).isEqualTo(now + 40_000L)
        check.accept(now + 300_000L, 10.5)
        assertThat(check.baselineFor(now + 600_000L, 10.5)?.bolusUntilMs).isEqualTo(now + 300_000L)
    }

    private val minute = 60_000L

    /** Ten in the morning on the local calendar, well inside a day. */
    private val start: Long = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 12, 10, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** A check that has been given a snapshot, so "unknown" is not the answer to everything. */
    private fun anchored(counter: Double = 20.0) = Atc3StateCheck().apply { accept(start, counter) }

    private fun Atc3StateCheck.verdict(readMs: Long, counter: Double, aapsUnits: Double): Atc3StateCheck.Verdict {
        val base = baselineFor(readMs, counter) ?: return Atc3StateCheck.Verdict.Unknown
        return compare(base, readMs, counter, aapsUnits, Atc3Const.DOSE_SCALE)
    }

    @Test
    fun `with nothing to compare against nothing is claimed`() {
        assertThat(Atc3StateCheck().baselineFor(start, 20.0)).isNull()
    }

    @Test
    fun `a count the journal accounts for is a match`() {
        // Six minutes at 1 U/h is 0.1 U in the journal, and the pump counted 0.1 U.
        assertThat(anchored().verdict(start + 6 * minute, 20.1, 0.1)).isInstanceOf(Atc3StateCheck.Verdict.Matches::class.java)
    }

    @Test
    fun `a disagreement of one step is not a disagreement`() {
        val over = anchored().verdict(start + 6 * minute, 20.125, 0.1)
        assertThat(over).isInstanceOf(Atc3StateCheck.Verdict.Matches::class.java)
        assertThat(anchored().verdict(start + 6 * minute, 20.075, 0.1)).isInstanceOf(Atc3StateCheck.Verdict.Matches::class.java)
    }

    @Test
    fun `insulin the journal does not hold is reported, with how much`() {
        val verdict = anchored().verdict(start + 6 * minute, 20.35, 0.1)
        assertThat(verdict).isInstanceOf(Atc3StateCheck.Verdict.Differs::class.java)
        assertThat((verdict as Atc3StateCheck.Verdict.Differs).units).isWithin(1e-9).of(0.25)
        assertThat(verdict.trace()).isEqualTo("excess")
    }

    @Test
    fun `insulin the journal holds and the pump did not count is reported too`() {
        // The direction a pause AAPS did not see, or a pump not delivering, shows in.
        val verdict = anchored().verdict(start + 6 * minute, 20.0, 0.3)
        assertThat(verdict).isInstanceOf(Atc3StateCheck.Verdict.Differs::class.java)
        assertThat((verdict as Atc3StateCheck.Verdict.Differs).units).isWithin(1e-9).of(-0.3)
        assertThat(verdict.trace()).isEqualTo("shortfall")
    }

    @Test
    fun `the baseline moves only when a snapshot is accepted`() {
        val check = anchored()
        check.verdict(start + 6 * minute, 20.35, 0.1)
        // Not accepted: the next comparison still starts from the first snapshot.
        assertThat(check.baseline()).isEqualTo(Atc3StateCheck.Baseline(start, 20.0))
        check.accept(start + 6 * minute, 20.35)
        assertThat(check.baseline()).isEqualTo(Atc3StateCheck.Baseline(start + 6 * minute, 20.35))
    }

    @Test
    fun `the pump's midnight starts the count again and nothing is claimed across it`() {
        val check = anchored()
        check.accept(start + 5 * minute, 20.1)
        val tomorrow = Calendar.getInstance().apply {
            timeInMillis = start
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 3)
        }.timeInMillis
        assertThat(check.baselineFor(tomorrow, 0.05)).isNull()
    }

    @Test
    fun `a snapshot older than the baseline is a clock put back, and nothing is claimed`() {
        assertThat(anchored().baselineFor(start - minute, 20.0)).isNull()
    }

    @Test
    fun `a count that went backwards within the day is nothing to compare against`() {
        assertThat(anchored().baselineFor(start + minute, 19.0)).isNull()
    }

    @Test
    fun `forgetting leaves nothing to compare against`() {
        val check = anchored()
        check.forget()
        assertThat(check.baselineFor(start + minute, 20.0)).isNull()
    }

    @Test
    fun `a difference inside the tolerance that stays on one side for three reads is a disagreement`() {
        val check = anchored()
        val wide = 0.125
        assertThat(check.compare(check.baseline()!!, start + 5 * minute, 20.18, 0.1, wide)).isEqualTo(Atc3StateCheck.Verdict.Matches)
        assertThat(check.compare(check.baseline()!!, start + 10 * minute, 20.28, 0.2, wide)).isEqualTo(Atc3StateCheck.Verdict.Matches)
        val third = check.compare(check.baseline()!!, start + 15 * minute, 20.38, 0.3, wide)
        assertThat(third).isInstanceOf(Atc3StateCheck.Verdict.Differs::class.java)
        // Asked again on the same read it says the same, and an acceptance starts it over.
        assertThat(check.compare(check.baseline()!!, start + 15 * minute, 20.38, 0.3, wide)).isInstanceOf(Atc3StateCheck.Verdict.Differs::class.java)
        check.accept(start + 15 * minute, 20.38)
        assertThat(check.compare(check.baseline()!!, start + 20 * minute, 20.48, 0.1, wide)).isEqualTo(Atc3StateCheck.Verdict.Matches)
    }

    @Test
    fun `a difference that comes back to the other side is the pump's steps and nothing else`() {
        val check = anchored()
        val wide = 0.125
        check.compare(check.baseline()!!, start + 5 * minute, 20.18, 0.1, wide)
        check.compare(check.baseline()!!, start + 10 * minute, 20.28, 0.2, wide)
        check.compare(check.baseline()!!, start + 15 * minute, 20.22, 0.3, wide)
        assertThat(check.compare(check.baseline()!!, start + 20 * minute, 20.48, 0.4, wide)).isEqualTo(Atc3StateCheck.Verdict.Matches)
    }

    @Test
    fun `what the caller allows between the two sides is what decides`() {
        val base = Atc3StateCheck.Baseline(start, 20.0)
        // A minute's worth at 7.5 U/h allowed, and 0.1 U between the two.
        assertThat(Atc3StateCheck().compare(base, start + minute, 20.2, 0.1, 0.125)).isEqualTo(Atc3StateCheck.Verdict.Matches)
        assertThat(Atc3StateCheck().compare(base, start + minute, 20.2, 0.1, 0.05)).isInstanceOf(Atc3StateCheck.Verdict.Differs::class.java)
    }

    @Test
    fun `unexplained comparisons are counted in a row and an acceptance clears them`() {
        val check = anchored()
        assertThat(check.unexplained()).isEqualTo(1)
        assertThat(check.unexplained()).isEqualTo(2)
        check.accept(start + 5 * minute, 20.1)
        assertThat(check.unexplainedRuns()).isEqualTo(0)
    }

    // Delivery that has stopped without the pump saying so

    @Test
    fun `one snapshot that counted nothing proves nothing`() {
        val check = anchored()
        check.verdict(start + 6 * minute, 20.0, 0.1)
        assertThat(check.notDelivering()).isFalse()
    }

    @Test
    fun `two snapshots owing a step and counting nothing are the pump not delivering`() {
        val check = anchored()
        check.verdict(start + 6 * minute, 20.0, 0.1)
        check.verdict(start + 12 * minute, 20.0, 0.2)
        assertThat(check.notDelivering()).isTrue()
    }

    @Test
    fun `the same snapshot asked twice is one sample`() {
        val check = anchored()
        check.verdict(start + 6 * minute, 20.0, 0.1)
        check.verdict(start + 6 * minute, 20.0, 0.1)
        assertThat(check.notDelivering()).isFalse()
    }

    @Test
    fun `the count moving forgets the samples at once`() {
        val check = anchored()
        check.verdict(start + 6 * minute, 20.0, 0.1)
        check.verdict(start + 12 * minute, 20.1, 0.2)
        check.verdict(start + 18 * minute, 20.1, 0.3)
        assertThat(check.notDelivering()).isFalse()
    }

    @Test
    fun `a snapshot that owed less than a step does not count against the pump`() {
        // 0.1 U/h for five minutes is 0.008 U: the pump has nothing to deliver yet.
        val check = anchored()
        check.verdict(start + 5 * minute, 20.0, 0.008)
        check.verdict(start + 10 * minute, 20.0, 0.016)
        check.verdict(start + 15 * minute, 20.0, 0.024)
        assertThat(check.notDelivering()).isFalse()
    }
}
