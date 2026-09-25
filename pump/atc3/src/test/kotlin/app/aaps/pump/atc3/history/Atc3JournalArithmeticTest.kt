package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.history.Atc3JournalArithmetic.Row
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * The AAPS side of the state check, by AAPS's own rules: rows cut each other, the scheduled rate
 * fills what they leave, a bolus counts where it stands.
 */
class Atc3JournalArithmeticTest {

    private val minute = 60_000L

    /** A whole hour on the local calendar, so half hour boundaries fall where the test says. */
    private val hour: Long = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 12, 10, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun insulin(
        from: Long, to: Long,
        boluses: List<Pair<Long, Double>> = emptyList(),
        rows: List<Row> = emptyList(),
        scheduled: (Long) -> Double = { 1.0 }
    ) = Atc3JournalArithmetic.insulin(from, to, boluses, rows, scheduledAt = scheduled)

    @Test
    fun `an empty journal is the scheduled basal for the whole interval`() {
        val result = insulin(hour, hour + 30 * minute)
        assertThat(result.scheduledUnits).isWithin(1e-9).of(0.5)
        assertThat(result.temporaryBasalUnits).isWithin(1e-9).of(0.0)
        assertThat(result.totalUnits).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `a bolus counts where it stands and not outside the interval`() {
        val result = insulin(
            hour, hour + 10 * minute,
            boluses = listOf(hour + 5 * minute to 1.0, hour + 10 * minute to 2.0, hour - 1 to 4.0)
        )
        assertThat(result.bolusUnits).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `a temporary basal replaces the scheduled rate for as long as it runs`() {
        // 3 U/h for six minutes of a ten minute interval at 1 U/h scheduled: 0.3 + 0.0667.
        val result = insulin(
            hour, hour + 10 * minute,
            rows = listOf(Row(hour + 2 * minute, hour + 8 * minute, 3.0))
        )
        assertThat(result.temporaryBasalUnits).isWithin(1e-9).of(0.3)
        assertThat(result.scheduledUnits).isWithin(1e-9).of(4.0 / 60.0)
    }

    @Test
    fun `a row is cut where the next one begins, whatever its own duration says`() {
        val result = insulin(
            hour, hour + 10 * minute,
            rows = listOf(
                Row(hour, hour + 30 * minute, 3.0),
                Row(hour + 4 * minute, hour + 34 * minute, 1.0)
            )
        )
        // 3 U/h for four minutes, then 1 U/h for six: 0.2 + 0.1.
        assertThat(result.temporaryBasalUnits).isWithin(1e-9).of(0.3)
        assertThat(result.scheduledUnits).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `a zero temporary basal delivers nothing and blocks the scheduled rate`() {
        val result = insulin(
            hour, hour + 10 * minute,
            rows = listOf(Row(hour, hour + 60 * minute, 0.0))
        )
        assertThat(result.totalUnits).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `only the part of a row inside the interval counts`() {
        val result = insulin(
            hour + 5 * minute, hour + 10 * minute,
            rows = listOf(Row(hour, hour + 60 * minute, 2.0))
        )
        assertThat(result.temporaryBasalUnits).isWithin(1e-9).of(2.0 * 5 / 60)
    }

    @Test
    fun `the scheduled rate changes on the half hour and each half is credited its own`() {
        // 1 U/h before 10:30, 2 U/h from 10:30: twenty minutes of each.
        val result = insulin(
            hour + 10 * minute, hour + 50 * minute,
            scheduled = { if (it < hour + 30 * minute) 1.0 else 2.0 }
        )
        assertThat(result.scheduledUnits).isWithin(1e-9).of(1.0 / 3 + 2.0 / 3)
    }

    @Test
    fun `a percentage row delivers that much of the scheduled rate`() {
        val result = insulin(
            hour, hour + 30 * minute,
            rows = listOf(Row(hour, hour + 30 * minute, null, percent = 150)),
            scheduled = { 1.0 }
        )
        assertThat(result.temporaryBasalUnits).isWithin(1e-9).of(0.75)
    }

    @Test
    fun `an interval of no length is nothing`() {
        assertThat(insulin(hour, hour).totalUnits).isWithin(1e-9).of(0.0)
    }
}
