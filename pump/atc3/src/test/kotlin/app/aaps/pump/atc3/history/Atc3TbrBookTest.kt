package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.comm.Atc3TbrRecord
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The book of temporary basals: the insulin of a minute's records lands, once and in full, in the
 * rows of that minute that have time, at the average rate that makes it of their time.
 */
class Atc3TbrBookTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 3_600_000.0

    private fun record(startMs: Long, rate: Double?, delivered: Double, index: Int = 0, minutes: Int = 30, percent: Int? = null) =
        Atc3TbrRecord(
            index = index, startTimestamp = startMs, startUtcSeconds = startMs / 1000L,
            rate = rate, percent = percent, durationMinutes = minutes, deliveredUnits = delivered
        )

    private fun row(id: Long, startMs: Long, rate: Double, rowMs: Long = startMs, endMs: Long? = null, shaped: Double? = null, carried: Double? = null) =
        Atc3TbrBook.Row(id, startMs / 1000L, rowMs, (rate / 0.025).toInt(), endMs, shaped, carried)

    private fun Atc3TbrBook.Shape.units() = rateUnitsPerHour * durationMs / hour

    private fun account(records: List<Atc3TbrRecord>, rows: List<Atc3TbrBook.Row>, bookStart: Long = (now - 3 * 3_600_000L) / 1000L) =
        Atc3TbrBook.account(records, rows, bookStart, now, rows.mapTo(HashSet()) { it.pumpId })

    @Test
    fun `a closed row takes the average rate its record makes of its time`() {
        val start = now - 30 * minute
        val outcome = account(
            listOf(record(start, 3.0, 0.2)),
            listOf(row(1L, start, 3.0, endMs = start + 280_000L))
        )
        val shape = outcome.shapes.single()
        assertThat(shape.pumpId).isEqualTo(1L)
        assertThat(shape.durationMs).isEqualTo(280_000L)
        assertThat(shape.units()).isWithin(1e-9).of(0.2)
        assertThat(outcome.imports).isEmpty()
        assertThat(outcome.minutesWithRows).isEqualTo(1)
    }

    @Test
    fun `a row already holding what its record says is left alone`() {
        val start = now - 30 * minute
        val outcome = account(
            listOf(record(start, 3.0, 0.2)),
            listOf(row(1L, start, 3.0, endMs = start + 280_000L, shaped = 0.2))
        )
        assertThat(outcome.shapes).isEmpty()
    }

    @Test
    fun `a row is never brought below what the journal already gave it`() {
        // The first half's record has gone off the journal's end; the second half's must not cut
        // the row down.
        val start = now - 40 * minute
        val outcome = account(
            listOf(record(start, 3.0, 0.05)),
            listOf(row(1L, start, 3.0, endMs = start + 20 * minute, shaped = 0.2))
        )
        assertThat(outcome.shapes).isEmpty()
    }

    @Test
    fun `a row still open is not shaped, it takes its record when it closes`() {
        val start = now - 10 * minute
        val outcome = account(listOf(record(start, 3.0, 0.2)), listOf(row(1L, start, 3.0)))
        assertThat(outcome.shapes).isEmpty()
        assertThat(outcome.carries).isEmpty()
    }

    @Test
    fun `a rate that delivered no pulse keeps its time at a rate of nothing`() {
        val start = now - 30 * minute
        val outcome = account(listOf(record(start, 0.1, 0.0)), listOf(row(1L, start, 0.1, endMs = start + 4 * minute)))
        val shape = outcome.shapes.single()
        assertThat(shape.durationMs).isEqualTo(4 * minute)
        assertThat(shape.rateUnitsPerHour).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `a zero temporary basal still open ends where the next minute begins`() {
        val start = now - 40 * minute
        val outcome = account(
            listOf(record(start, 0.0, 0.0, index = 1, minutes = 120), record(start + 17 * minute, 2.0, 0.1, index = 0)),
            listOf(row(1L, start, 0.0), row(2L, start + 17 * minute, 2.0))
        )
        // The zero row is open and nothing was delivered: nothing to shape it by, and it is left
        // for the tick to close; the 2.0 row is open too.
        assertThat(outcome.shapes).isEmpty()
    }

    @Test
    fun `a zero temporary basal that delivered nothing is not written again`() {
        val start = now - 40 * minute
        val outcome = account(
            listOf(record(start, 0.0, 0.0, minutes = 120)),
            listOf(row(1L, start, 0.0, endMs = start + 30 * minute))
        )
        assertThat(outcome.shapes).isEmpty()
        assertThat(outcome.minutesWithRows).isEqualTo(1)
    }

    @Test
    fun `a minute with two rows and two records gives each row its own record`() {
        // A stop cut a 3.0 U/h in two: the first half's row and the continuation from the resume.
        val start = now - 40 * minute
        val resumed = start + 3 * minute
        val outcome = account(
            listOf(record(start, 3.0, 0.025, index = 1), record(start, 3.0, 0.075, index = 0)),
            listOf(row(1L, start, 3.0, endMs = start + 40_000L), row(2L, start, 3.0, rowMs = resumed, endMs = resumed + 10 * minute))
        )
        val byId = outcome.shapes.associateBy { it.pumpId }
        assertThat(byId.keys).containsExactly(1L, 2L)
        assertThat(byId[1L]!!.units()).isWithin(1e-9).of(0.025)
        assertThat(byId[2L]!!.units()).isWithin(1e-9).of(0.075)
    }

    @Test
    fun `a minute with one row and two records gives the row their sum`() {
        val start = now - 40 * minute
        val outcome = account(
            listOf(record(start, 3.0, 0.025, index = 1), record(start, 3.0, 0.075, index = 0)),
            listOf(row(1L, start, 3.0, endMs = start + 20 * minute))
        )
        val shape = outcome.shapes.single()
        assertThat(shape.units()).isWithin(1e-9).of(0.1)
        assertThat(shape.durationMs).isEqualTo(20 * minute)
    }

    @Test
    fun `the insulin of a row cut to nothing goes to the row of its minute that has time`() {
        // 3.0 U/h delivered 0.050 U in 65 s and was replaced by the loop's 0.75 U/h in the same
        // minute; AAPS cut the first row to a millisecond.
        val start = now - 40 * minute
        val outcome = account(
            listOf(record(start, 3.0, 0.05, index = 1), record(start, 0.75, 0.05, index = 0)),
            listOf(row(1L, start, 3.0, endMs = start + 1L), row(2L, start, 0.75, endMs = start + 4 * minute))
        )
        val shape = outcome.shapes.single()
        assertThat(shape.pumpId).isEqualTo(2L)
        assertThat(shape.units()).isWithin(1e-9).of(0.10)
        assertThat(shape.durationMs).isEqualTo(4 * minute)
    }

    @Test
    fun `insulin handed to a row still open is kept for its close`() {
        val start = now - 10 * minute
        val outcome = account(
            listOf(record(start, 3.0, 0.05, index = 0)),
            listOf(row(1L, start, 3.0, endMs = start + 1L), row(2L, start, 0.75))
        )
        assertThat(outcome.shapes).isEmpty()
        val carry = outcome.carries.single()
        assertThat(carry.toPumpId).isEqualTo(2L)
        assertThat(carry.units).isWithin(1e-9).of(0.05)
    }

    @Test
    fun `a carry already kept is not handed again`() {
        val start = now - 10 * minute
        val outcome = account(
            listOf(record(start, 3.0, 0.05, index = 0)),
            listOf(row(1L, start, 3.0, endMs = start + 1L), row(2L, start, 0.75, carried = 0.05))
        )
        assertThat(outcome.carries).isEmpty()
    }

    @Test
    fun `the only record left of a temporary basal cut in two belongs to the row of its rate that has time`() {
        val start = now - 40 * minute
        val resumed = start + 9 * minute
        val outcome = account(
            listOf(record(start, 3.0, 0.05, index = 9)),
            listOf(
                row(1L, start, 3.0, endMs = start + 3 * minute, shaped = 0.15),
                row(2L, start, 3.0, rowMs = resumed, endMs = resumed + 65_000L)
            )
        )
        // Paired in order, the record would be the first row's; the first row already holds more
        // and keeps it, and the second row is then left alone as well: nothing for it.
        assertThat(outcome.shapes).isEmpty()
    }

    @Test
    fun `a stranger's minute is imported at the average rate over the time the journal allows`() {
        // Set at 12:01 and replaced at 12:04 by the next record: 0.1 U over three minutes.
        val start = now - 40 * minute
        val outcome = account(
            listOf(record(start, 3.0, 0.1, index = 1), record(start + 3 * minute, 0.0, 0.0, index = 0, minutes = 120)),
            listOf(row(7L, start + 3 * minute, 0.0))
        )
        val import = outcome.imports.single()
        assertThat(import.timestamp).isEqualTo(start)
        assertThat(import.durationMs).isEqualTo(3 * minute)
        assertThat(import.rateUnitsPerHour * import.durationMs / hour).isWithin(1e-9).of(0.1)
        assertThat(import.pumpId).isEqualTo(Atc3PumpId.of(start, Atc3PumpId.KIND_TBR_START))
    }

    @Test
    fun `a stranger's minute alone runs no longer than its ordered time or until now`() {
        val start = now - 10 * minute
        val outcome = account(listOf(record(start, 2.0, 0.3, minutes = 30)), emptyList())
        assertThat(outcome.imports.single().durationMs).isEqualTo(10 * minute)
    }

    @Test
    fun `the first read imports nothing and only says where the book begins`() {
        val start = now - 40 * minute
        val outcome = account(listOf(record(start, 3.0, 0.1)), emptyList(), bookStart = 0L)
        assertThat(outcome.imports).isEmpty()
        assertThat(outcome.newestUtcSeconds).isEqualTo(start / 1000L)
    }

    @Test
    fun `a minute behind the book's start with no row is left alone`() {
        val start = now - 40 * minute
        val outcome = account(listOf(record(start, 3.0, 0.1)), emptyList(), bookStart = start / 1000L + 600)
        assertThat(outcome.imports).isEmpty()
    }

    @Test
    fun `records too old or from the future are not believed`() {
        val outcome = account(
            listOf(record(now - 30 * 3_600_000L, 3.0, 0.1), record(now + 5 * minute, 3.0, 0.1)),
            emptyList()
        )
        assertThat(outcome.stale).isEqualTo(2)
        assertThat(outcome.imports).isEmpty()
    }

    @Test
    fun `a percentage record is a stranger's and is imported by its insulin`() {
        val start = now - 40 * minute
        val outcome = account(
            listOf(record(start, null, 0.075, minutes = 15, percent = 150), record(start + 7 * minute, 0.0, 0.0, index = 0, minutes = 120)),
            listOf(row(7L, start + 7 * minute, 0.0))
        )
        val import = outcome.imports.single()
        assertThat(import.durationMs).isEqualTo(7 * minute)
        assertThat(import.rateUnitsPerHour * import.durationMs / hour).isWithin(1e-9).of(0.075)
    }

    @Test
    fun `a row shorter than a second is never given a rate`() {
        val start = now - 30 * minute
        val outcome = account(listOf(record(start, 3.0, 0.05)), listOf(row(1L, start, 3.0, endMs = start + 1L)))
        assertThat(outcome.shapes).isEmpty()
        assertThat(outcome.carries).isEmpty()
    }
}
