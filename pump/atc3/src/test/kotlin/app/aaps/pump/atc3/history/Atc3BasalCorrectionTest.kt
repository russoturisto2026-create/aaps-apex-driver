package app.aaps.pump.atc3.history

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * Where the difference between the pump's count and the journal is written: by time, at the rate
 * that was set, into the half hour of the pump's clock that has ended.
 */
class Atc3BasalCorrectionTest {

    private val minute = 60_000L
    private val hour = 3_600_000.0

    /** 10:00 on the local calendar; the read is at 10:32, so the half hour written is 10:00-10:30. */
    private val h: Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 1, 10, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private val now = h + 32 * minute

    private fun row(id: Long, from: Long, to: Long, rate: Double, editable: Boolean = true) =
        Atc3BasalCorrection.Row(id, from, to, rate, editable)

    private fun plan(units: Double, rows: List<Atc3BasalCorrection.Row>, open: Long? = null, profile: Double = 1.0, max: Double = 7.0) =
        Atc3BasalCorrection.plan(units, h + 30 * minute, now, rows, open, max) { profile }

    @Test
    fun `the mechanics allow a portion of the scheduled rate a half hour and half a portion a temporary basal`() {
        // 10:00-10:32 touches two half hours of 2.05 (portion 0.1); a 3.0 row (0.025) and a 6.0 row
        // (0.1) run in it, and a zero row, which rounds nothing.
        val rows = listOf(
            row(1, h + 5 * minute, h + 10 * minute, 3.0),
            row(2, h + 10 * minute, h + 15 * minute, 6.0),
            row(3, h + 15 * minute, h + 20 * minute, 0.0),
            row(4, h - 20 * minute, h - 10 * minute, 3.0)
        )
        val bound = Atc3BasalCorrection.mechanicsBound(h, now, rows) { 2.05 }
        assertThat(bound).isWithin(1e-9).of(2 * 0.1 + 0.0125 + 0.05)
        assertThat(Atc3BasalCorrection.mechanicsBound(h, h + 30 * minute, emptyList()) { 0.5 }).isWithin(1e-9).of(0.025)
        assertThat(Atc3BasalCorrection.mechanicsBound(h, h + 30 * minute, emptyList()) { 1.05 }).isWithin(1e-9).of(0.05)
    }

    @Test
    fun `nothing to write writes nothing`() {
        assertThat(plan(0.0001, listOf(row(1, h + 5 * minute, h + 10 * minute, 0.0))).edits).isEmpty()
    }

    @Test
    fun `a pump ahead of the journal shortens the latest closed row below the scheduled rate`() {
        // A zero temporary basal 10:05-10:25 at a profile of 1.0: 0.05 U ahead is three minutes of
        // it given back to the profile.
        val p = plan(0.05, listOf(row(1, h + 5 * minute, h + 25 * minute, 0.0)))
        val cut = p.edits.single() as Atc3BasalCorrection.Edit.Shorten
        assertThat(cut.pumpId).isEqualTo(1)
        assertThat(cut.rateUnitsPerHour).isEqualTo(0.0)
        assertThat(cut.durationMs).isEqualTo(17 * minute)
        assertThat(p.units).isWithin(1e-5).of(0.05)
    }

    @Test
    fun `a pump behind the journal shortens a row above the scheduled rate, not one below it`() {
        val rows = listOf(row(1, h + 5 * minute, h + 10 * minute, 3.0), row(2, h + 10 * minute, h + 15 * minute, 0.0))
        val p = plan(-0.05, rows)
        val cut = p.edits.single() as Atc3BasalCorrection.Edit.Shorten
        // The later row is below the profile and is passed over; 0.05 U at 3.0 - 1.0 is 90 s.
        assertThat(cut.pumpId).isEqualTo(1)
        assertThat(cut.durationMs).isEqualTo(5 * minute - 90_000L)
        assertThat(p.units).isWithin(1e-5).of(-0.05)
    }

    @Test
    fun `the open row and rows outside the half hour are never touched`() {
        val rows = listOf(
            row(1, h - 20 * minute, h - 10 * minute, 0.0),          // the half hour before
            row(2, h + 28 * minute, h + 33 * minute, 0.0),          // ends in the half hour still running
            row(3, h + 31 * minute, h + 61 * minute, 0.0)           // open
        )
        val p = plan(0.05, rows, open = 3)
        assertThat(p.edits.none { it is Atc3BasalCorrection.Edit.Shorten }).isTrue()
    }

    @Test
    fun `a stop is a fact of its own and is not shortened`() {
        val p = plan(0.05, listOf(row(1, h + 5 * minute, h + 25 * minute, 0.0, editable = false)))
        assertThat(p.edits.none { it is Atc3BasalCorrection.Edit.Shorten }).isTrue()
    }

    @Test
    fun `a row is cut no further than a minute of itself and no further back than the half hour`() {
        // 2 minutes of zero, 10:10-10:12: one minute can go, worth 1/60 U at a profile of 1.0.
        val p = plan(0.05, listOf(row(1, h + 10 * minute, h + 12 * minute, 0.0)))
        val cut = p.edits.first() as Atc3BasalCorrection.Edit.Shorten
        assertThat(cut.durationMs).isEqualTo(minute)
        // What is left goes into a correction row at the highest rate.
        val insert = p.edits.last() as Atc3BasalCorrection.Edit.Insert
        assertThat(insert.rateUnitsPerHour).isEqualTo(7.0)
        assertThat(p.units).isWithin(1e-5).of(0.05)
    }

    @Test
    fun `a half hour of the profile alone takes a correction row at the highest rate when the pump is ahead`() {
        // 0.15 U ahead at a profile of 2.05 and a highest rate of 7.0: 0.15 / 4.95 h, at the end of the half hour.
        val p = plan(0.15, emptyList(), profile = 2.05)
        val insert = p.edits.single() as Atc3BasalCorrection.Edit.Insert
        assertThat(insert.rateUnitsPerHour).isEqualTo(7.0)
        assertThat(insert.durationMs).isEqualTo((0.15 / 4.95 * hour).toLong())
        assertThat(insert.startMs + insert.durationMs).isEqualTo(h + 30 * minute)
        assertThat(p.units).isWithin(1e-5).of(0.15)
    }

    @Test
    fun `and a row of zero when the pump is behind`() {
        val p = plan(-0.05, emptyList(), profile = 1.05)
        val insert = p.edits.single() as Atc3BasalCorrection.Edit.Insert
        assertThat(insert.rateUnitsPerHour).isEqualTo(0.0)
        assertThat(insert.durationMs).isEqualTo((0.05 / 1.05 * hour).toLong())
        assertThat(p.units).isWithin(1e-5).of(-0.05)
    }

    @Test
    fun `a correction row goes into the scheduled time between rows, not under one`() {
        // Rows above the profile cover 10:00-10:20 and 10:25-10:30; the pump ahead wants a row
        // above the profile, which only the gap 10:20-10:25 can take.
        val rows = listOf(row(1, h, h + 20 * minute, 3.0), row(2, h + 25 * minute, h + 30 * minute, 3.0))
        val p = plan(0.05, rows)
        val insert = p.edits.single() as Atc3BasalCorrection.Edit.Insert
        assertThat(insert.startMs + insert.durationMs).isEqualTo(h + 25 * minute)
        assertThat(insert.startMs).isAtLeast(h + 20 * minute)
    }

    @Test
    fun `what does not fit is left for the next half hour`() {
        // No room: the whole half hour runs a row above the profile, and the pump is ahead.
        val p = plan(0.05, listOf(row(1, h, h + 30 * minute, 3.0)))
        assertThat(p.edits).isEmpty()
    }

    @Test
    fun `the scheduled rate the pump cannot go past takes no correction row`() {
        assertThat(plan(0.05, emptyList(), profile = 7.0, max = 7.0).edits).isEmpty()
        assertThat(plan(-0.05, emptyList(), profile = 0.0).edits).isEmpty()
    }
}
