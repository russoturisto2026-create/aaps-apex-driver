package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.Atc3Const
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Keeping two of the driver's own boluses far enough apart for the pump's records to differ.
 *
 * The rule being pinned here: a bolus waits until a minute has passed since the **start** of the
 * previous one, the moment the pump accepted it, because the start minute is what the pump stamps
 * into the record and what the record is matched on. The wait is never negative and never longer
 * than the interval, whatever the phone's clock does.
 */
class Atc3BolusSpacingTest {

    private val second = 1_000L
    private val minute = Atc3Const.BOLUS_SPACING_MS
    private val now = 1_700_000_000_000L

    @Test
    fun `the first bolus of a session waits for nothing`() {
        // Nothing has been given, so there is no record for the next one to be confused with.
        assertThat(Atc3BolusSpacing().waitMs(now)).isEqualTo(0L)
    }

    @Test
    fun `a bolus that started minutes ago holds nothing back`() {
        val spacing = Atc3BolusSpacing()
        spacing.started(now - 5 * minute)

        assertThat(spacing.waitMs(now)).isEqualTo(0L)
    }

    @Test
    fun `half a minute since the last one started leaves half a minute to wait`() {
        val spacing = Atc3BolusSpacing()
        spacing.started(now - 30 * second)

        assertThat(spacing.waitMs(now)).isEqualTo(30 * second)
    }

    @Test
    fun `a bolus that has only just started holds the next one the whole interval`() {
        // The case the whole mechanism exists for: two records in one minute, equal doses, and
        // nothing in either of them to say which is which.
        val spacing = Atc3BolusSpacing()
        spacing.started(now)

        assertThat(spacing.waitMs(now)).isEqualTo(minute)
    }

    @Test
    fun `exactly the interval is enough, the boundary does not wait`() {
        val spacing = Atc3BolusSpacing()
        spacing.started(now - minute)

        assertThat(spacing.waitMs(now)).isEqualTo(0L)
    }

    @Test
    fun `a delivery that ran past the minute holds nothing back`() {
        // Counted from the start, not the end: a bolus that took seventy seconds to deliver leaves
        // nothing to wait for once it is over.
        val spacing = Atc3BolusSpacing()
        spacing.started(now - 70 * second)

        assertThat(spacing.waitMs(now)).isEqualTo(0L)
    }

    @Test
    fun `a phone clock that moved backwards cannot hold a bolus for longer than the interval`() {
        // The previous bolus now starts in the future, an hour of it. Subtracting would hold the
        // next bolus for that hour; the ceiling is what stops a clock jump becoming a refusal.
        val spacing = Atc3BolusSpacing()
        spacing.started(now + 60 * minute)

        assertThat(spacing.waitMs(now)).isEqualTo(minute)
    }

    @Test
    fun `no arrangement of the two moments produces a negative wait`() {
        val starts = listOf(0L, now - 10 * minute, now - second, now, now + second, now + 10 * minute)

        for (start in starts) {
            val wait = Atc3BolusSpacing.waitFor(start, now)
            assertThat(wait).isAtLeast(0L)
            assertThat(wait).isAtMost(minute)
        }
    }

    @Test
    fun `the newest start is the one that counts`() {
        // Two boluses in a session: what the next one waits on is the last start, not the first.
        val spacing = Atc3BolusSpacing()
        spacing.started(now - 10 * minute)
        spacing.started(now - 20 * second)

        assertThat(spacing.waitMs(now)).isEqualTo(40 * second)
    }
}
