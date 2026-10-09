package app.aaps.pump.atc3.basal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * When a passed window is closed by the pump's count, and at how much.
 *
 * The rules pinned down here: the first read in a new half hour ends the window before it; boluses
 * come first, and what is left of the count is the basal; across the pump's midnight the day that
 * ended is counted to its total in the pump's journal.
 */
class Atc3BasalPeriodTest {

    private val minute = 60_000L

    private fun at(hour: Int, min: Int, sec: Int = 0, day: Int = 3): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, day, hour, min, sec)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun mark(ms: Long, counter: Double) = Atc3BasalPeriod.Mark(ms, counter, ms)

    private fun step(
        start: Atc3BasalPeriod.Mark, readMs: Long, counter: Double,
        bolus: Double = 0.0, settled: Boolean = true, dayTotal: Double? = null, force: Boolean = false
    ) = Atc3BasalPeriod.step(start, readMs, counter, bolus, settled, dayTotal, force)

    private fun unitsOf(step: Atc3BasalPeriod.Step): Double = (step as Atc3BasalPeriod.Step.Close).units

    @Test
    fun `a read inside the half hour the window began in closes nothing`() {
        assertThat(step(mark(at(10, 0, 3), 20.0), at(10, 25), 20.5)).isEqualTo(Atc3BasalPeriod.Step.Wait)
    }

    @Test
    fun `the first read of the next half hour closes the window at the count less its boluses`() {
        val closed = step(mark(at(10, 0, 3), 20.0), at(10, 30, 3), 21.6, bolus = 1.0) as Atc3BasalPeriod.Step.Close
        assertThat(closed.units).isWithin(1e-9).of(0.6)
        assertThat(closed.countedUnits).isWithin(1e-9).of(1.6)
    }

    @Test
    fun `a window is what lies between two reads, however late the second comes`() {
        assertThat(unitsOf(step(mark(at(10, 0, 3), 20.0), at(10, 34), 20.7))).isWithin(1e-9).of(0.7)
        // Hours without a link are one window all the same: the count is the pump's over all of it.
        assertThat(unitsOf(step(mark(at(10, 0, 3), 20.0), at(13, 10), 23.2))).isWithin(1e-9).of(3.2)
    }

    @Test
    fun `a window begun late in the half hour still ends on the next half hour`() {
        val start = mark(at(10, 24), 20.0)
        assertThat(step(start, at(10, 29), 20.05)).isEqualTo(Atc3BasalPeriod.Step.Wait)
        assertThat(unitsOf(step(start, at(10, 30, 3), 20.1))).isWithin(1e-9).of(0.1)
    }

    @Test
    fun `a bolus of ours still waiting for its record puts the close off to the next read`() {
        val start = mark(at(10, 0, 3), 20.0)
        assertThat(step(start, at(10, 30, 3), 21.0, settled = false)).isEqualTo(Atc3BasalPeriod.Step.Postpone("boluses"))
        assertThat(unitsOf(step(start, at(10, 35), 21.0, bolus = 0.5))).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `across the pump's midnight the day that ended is counted to its total`() {
        val start = mark(at(23, 30, 3), 40.0)
        val afterMidnight = at(0, 0, 3, day = 4)
        // 40.0 at the start, 40.9 by midnight, 0.025 since.
        assertThat(unitsOf(step(start, afterMidnight, 0.025, dayTotal = 40.9))).isWithin(1e-9).of(0.925)
        // Without the day's total there is nothing to count to; the next read asks again.
        assertThat(step(start, afterMidnight, 0.025)).isEqualTo(Atc3BasalPeriod.Step.Postpone("day_total"))
    }

    @Test
    fun `more than one midnight between two reads leaves no count to close from, and the window begins anew`() {
        assertThat(step(mark(at(23, 30, 3), 40.0), at(0, 0, 3, day = 5), 0.0, dayTotal = 41.0)).isEqualTo(Atc3BasalPeriod.Step.Restart("days_apart"))
    }

    @Test
    fun `a window closed again for a bolus that ran across its end closes at any read`() {
        assertThat(unitsOf(step(mark(at(10, 0, 3), 20.0), at(10, 35), 23.1, bolus = 2.5, force = true))).isWithin(1e-9).of(0.6)
    }

    @Test
    fun `the difference of a window is its basal against what the rows order`() {
        val window = Atc3BasalPeriod.Window(at(10, 0, 3), at(10, 30, 3), 1.6, 1.0, 0.6, orderedUnits = 0.5, again = false)
        assertThat(window.differenceUnits!!).isWithin(1e-9).of(0.1)
        assertThat(window.copy(orderedUnits = null).differenceUnits).isNull()
    }

    @Test
    fun `the read is asked for a little past the half hour`() {
        assertThat(Atc3BasalPeriod.msToNextRead(at(10, 12))).isEqualTo(18 * minute + Atc3BasalPeriod.AFTER_BOUNDARY_MS)
        assertThat(Atc3BasalPeriod.msToNextRead(at(10, 30))).isEqualTo(30 * minute + Atc3BasalPeriod.AFTER_BOUNDARY_MS)
        assertThat(Atc3BasalPeriod.msToNextRead(at(23, 59, 40))).isEqualTo(20_000L + Atc3BasalPeriod.AFTER_BOUNDARY_MS)
    }
}
