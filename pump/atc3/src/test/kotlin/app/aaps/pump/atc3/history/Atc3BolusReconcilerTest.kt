package app.aaps.pump.atc3.history

import app.aaps.core.data.model.BS
import app.aaps.pump.atc3.protocol.Atc3BolusHistory
import app.aaps.pump.atc3.protocol.Atc3BolusRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reconciler decides which of the pump's records become rows, so each case of what the pump
 * does with its records, and of what the user does with the rows, is pinned here.
 *
 * Stamps are plain epoch seconds on both scales here, so the cases do not depend on the timezone of
 * the machine running them; the driver itself uses the pump's wall clock for the keys.
 */
class Atc3BolusReconcilerTest {

    private val phoneNow = 1_700_000_000_000L

    /** The start of a minute ten minutes ago. */
    private val minute = phoneNow - phoneNow % 60_000L - 10 * 60_000L

    /** A record of the minute starting at [minuteStart], at [position] in it: 59 for the newest, 58 for the one before. */
    private fun record(minuteStart: Long, requested: Int, delivered: Int = requested, position: Int = 59, extReq: Int = 0, extDlv: Int = 0): Atc3BolusRecord {
        val stamp = minuteStart + position * 1000L
        return Atc3BolusRecord(
            index = 0,
            timestamp = stamp,
            pumpClockUtcSeconds = stamp / 1000L,
            rawRequested = requested,
            rawDelivered = delivered,
            rawExtendedRequested = extReq,
            rawExtendedDelivered = extDlv
        )
    }

    private fun row(timestamp: Long, raw: Int) = Atc3BolusRow(timestamp, raw)

    /** A bolus of ours the pump accepted at [acceptedAtMs]. */
    private fun expected(acceptedAtMs: Long, units: Double, type: BS.Type = BS.Type.SMB) =
        ExpectedBolus(acceptedAtMs, units, type, startUtcSeconds = acceptedAtMs / 1000L)

    /** A ledger that set its import boundary an hour ago. */
    private fun ready(secondsAgo: Long = 3600L) =
        Atc3HistoryLedger().withWatermark((phoneNow - secondsAgo * 1000L) / 1000L, phoneNow - secondsAgo * 1000L)

    private fun reconcile(
        records: List<Atc3BolusRecord>,
        rows: List<Atc3BolusRow> = emptyList(),
        ledger: Atc3HistoryLedger = ready(),
        earliestAcceptedMs: Long = 0L
    ) = Atc3BolusReconciler.reconcile(records, rows, ledger, phoneNow, earliestAcceptedMs)

    // A minute of the pump's journal against the rows of that minute

    @Test
    fun `a record without a row of its dose in its minute is written at the start of the minute`() {
        val write = reconcile(listOf(record(minute, 40))).writes.single()
        assertEquals(minute, write.timestamp)
        assertEquals(1.0, write.units, 1e-9)
        assertEquals(minute, write.pumpId)
        assertNull(write.expected)
    }

    @Test
    fun `a record with a row of its dose in its minute is not written again`() {
        assertTrue(reconcile(listOf(record(minute, 40)), rows = listOf(row(minute, 40))).writes.isEmpty())
    }

    @Test
    fun `three boluses of one dose in one minute are three rows, and nothing more on the next read`() {
        val records = listOf(record(minute, 20, position = 59), record(minute, 20, position = 58), record(minute, 20, position = 57))

        val first = reconcile(records).writes
        assertEquals(listOf(minute, minute + 1, minute + 2), first.map { it.pumpId })
        assertTrue(first.all { it.units == 0.5 })

        val again = reconcile(records, rows = first.map { row(it.timestamp, 20) })
        assertTrue(again.writes.isEmpty())
    }

    @Test
    fun `three doses in one minute each get their own row`() {
        val writes = reconcile(
            listOf(record(minute, 40), record(minute, 80, position = 58), record(minute, 20, position = 57)),
            rows = listOf(row(minute, 80))
        ).writes
        assertEquals(setOf(1.0, 0.5), writes.map { it.units }.toSet())
    }

    @Test
    fun `a record moved from second 59 to 58 is still the row it has`() {
        assertTrue(reconcile(listOf(record(minute, 40, position = 58)), rows = listOf(row(minute, 40))).writes.isEmpty())
    }

    @Test
    fun `a row anywhere in the minute counts, including one an earlier build dated at second 59`() {
        assertTrue(reconcile(listOf(record(minute, 40)), rows = listOf(row(minute + 59_000L, 40))).writes.isEmpty())
    }

    @Test
    fun `a row the user deleted still counts, and a new row's id does not fall on it`() {
        // The deleted row is in the rows handed over, as the sync hands deleted ones over too.
        val writes = reconcile(listOf(record(minute, 40), record(minute, 80, position = 58)), rows = listOf(row(minute, 40))).writes
        assertEquals(2.0, writes.single().units, 1e-9)
        assertEquals(minute + 1, writes.single().pumpId)
    }

    @Test
    fun `rows beyond the pump's records are counted and left alone`() {
        val outcome = reconcile(listOf(record(minute, 40)), rows = listOf(row(minute, 40), row(minute, 80)))
        assertTrue(outcome.writes.isEmpty())
        assertEquals(1, outcome.rowsBeyond)
    }

    @Test
    fun `an empty record is nothing to write`() {
        assertTrue(reconcile(listOf(record(minute, 0, 0))).writes.isEmpty())
    }

    // Our boluses, told from anyone else's by the dose asked and the minute

    @Test
    fun `our record takes the type of the bolus expected, and the expectation closes`() {
        val ours = expected(minute + 5_000L, 1.0)
        val outcome = reconcile(listOf(record(minute, 40)), ledger = ready().withExpected(ours))
        assertEquals(ours, outcome.writes.single().expected)
        assertTrue(outcome.ledger.expected.isEmpty())
        assertEquals(0, outcome.clockShiftMinutes)
    }

    @Test
    fun `our record a minute late still closes the expectation, and says the clock is a minute off`() {
        val ours = expected(minute + 5_000L, 1.0)
        val outcome = reconcile(listOf(record(minute + 60_000L, 40)), ledger = ready().withExpected(ours))
        assertEquals(ours, outcome.writes.single().expected)
        assertEquals(1, outcome.clockShiftMinutes)
    }

    @Test
    fun `a record two minutes away, or of another dose, is not ours`() {
        val ours = expected(minute + 5_000L, 1.0)
        val outcome = reconcile(listOf(record(minute + 120_000L, 40), record(minute, 80)), ledger = ready().withExpected(ours))
        assertEquals(2, outcome.writes.size)
        assertTrue(outcome.writes.all { it.expected == null })
        assertNull(outcome.clockShiftMinutes)
        // And the record two minutes on says ours is not coming.
        assertTrue(outcome.ledger.expected.isEmpty())
    }

    @Test
    fun `our bolus and a stranger's of the same dose in one minute are two rows, one of them ours`() {
        val ours = expected(minute + 5_000L, 1.0)
        val writes = reconcile(listOf(record(minute, 40), record(minute, 40, position = 58)), ledger = ready().withExpected(ours)).writes
        assertEquals(2, writes.size)
        assertEquals(1, writes.count { it.expected == ours })
    }

    @Test
    fun `a bolus cut short is ours by the dose asked, and written at what went in`() {
        val ours = expected(minute + 5_000L, 2.0)
        val write = reconcile(listOf(record(minute, 80, delivered = 24)), ledger = ready().withExpected(ours)).writes.single()
        assertEquals(0.6, write.units, 1e-9)
        assertEquals(ours, write.expected)
    }

    @Test
    fun `an expectation whose record has not come stays open`() {
        val ours = expected(minute + 5_000L, 1.0)
        val outcome = reconcile(listOf(record(minute - 120_000L, 80)), ledger = ready().withExpected(ours))
        assertEquals(listOf(ours), outcome.ledger.expected)
        assertEquals(listOf(ours), reconcile(emptyList(), ledger = ready().withExpected(ours)).ledger.expected)
    }

    @Test
    fun `a record of a later minute than ours could sit in closes the expectation`() {
        // The pump writes in order, and under an alarm it writes nothing: a later record says ours came or never will.
        val ours = expected(minute + 5_000L, 1.0)
        val outcome = reconcile(listOf(record(minute + 120_000L, 80)), ledger = ready().withExpected(ours))
        assertTrue(outcome.ledger.expected.isEmpty())
        // A record in the next minute could still be ours: it stays.
        assertEquals(listOf(ours), reconcile(listOf(record(minute + 60_000L, 80)), ledger = ready().withExpected(ours)).ledger.expected)
    }

    @Test
    fun `the record of the next bolus of ours closes the one before, which never came`() {
        val first = expected(minute + 5_000L, 1.0)
        val second = expected(minute + 180_000L, 0.5)
        val outcome = reconcile(listOf(record(minute + 180_000L, 20)), ledger = ready().withExpected(first).withExpected(second))
        assertEquals(second, outcome.writes.single().expected)
        assertTrue(outcome.ledger.expected.isEmpty())
    }

    // An extended or dual bolus

    @Test
    fun `an extended or dual bolus is no row, and is reported with everything it delivered`() {
        val outcome = reconcile(listOf(record(minute, 40, 40, extReq = 80, extDlv = 80)))
        assertTrue(outcome.writes.isEmpty())
        assertEquals(minute, outcome.extended.single().timestamp)
        assertEquals(3.0, outcome.extended.single().units, 1e-9)
    }

    // The import boundary

    @Test
    fun `the first read sets the boundary at the moment AAPS adopted the pump, and writes only what is newer`() {
        // Adopted in the middle of the minute: the record of the minute before is stamped before that, this minute's after.
        val adopted = minute + 30_000L
        val outcome = reconcile(
            listOf(record(minute - 60_000L, 40), record(minute, 80)),
            ledger = Atc3HistoryLedger(),
            earliestAcceptedMs = adopted
        )
        assertEquals(2.0, outcome.writes.single().units, 1e-9)
        assertTrue(outcome.ledger.firstPassDone)
        assertEquals(adopted, outcome.ledger.importFromPhoneMs)
    }

    @Test
    fun `with no adoption moment the first read sets the boundary now and writes nothing`() {
        val outcome = reconcile(listOf(record(minute, 80)), ledger = Atc3HistoryLedger())
        assertTrue(outcome.writes.isEmpty())
        assertEquals(phoneNow, outcome.ledger.importFromPhoneMs)
    }

    @Test
    fun `records behind the boundary on both clocks, or older than a day, are not written`() {
        assertTrue(reconcile(listOf(record(phoneNow - 2 * 3_600_000L, 40))).writes.isEmpty())
        assertTrue(reconcile(listOf(record(phoneNow - 25 * 3_600_000L, 40)), ledger = ready(48 * 3600L)).writes.isEmpty())
    }

    @Test
    fun `a record behind the boundary on the pump's clock only is written, the clock having been put back`() {
        val ledger = Atc3HistoryLedger().withWatermark((phoneNow - 60_000L) / 1000L, phoneNow - 3_600_000L)
        assertEquals(1, reconcile(listOf(record(phoneNow - 120_000L, 40)), ledger = ledger).writes.size)
    }

    @Test
    fun `a record from before AAPS adopted the pump is not written`() {
        assertTrue(reconcile(listOf(record(minute, 40)), earliestAcceptedMs = minute + 60_000L).writes.isEmpty())
    }

    // Whether the usual answer of ten left records out

    @Test
    fun `an answer holding everything the pump has leaves nothing out`() {
        assertFalse(Atc3BolusReconciler.recordsMissing(Atc3BolusHistory(listOf(record(minute, 40)), 1), 0L))
        assertFalse(Atc3BolusReconciler.recordsMissing(Atc3BolusHistory(emptyList(), 0), 0L))
    }

    @Test
    fun `before any answer the whole history is read`() {
        assertTrue(Atc3BolusReconciler.recordsMissing(Atc3BolusHistory(listOf(record(minute, 40)), 128), 0L))
    }

    @Test
    fun `an answer that still holds the last answer's newest minute left nothing out`() {
        val history = Atc3BolusHistory(listOf(record(minute + 60_000L, 40), record(minute, 40)), 128)
        assertFalse(Atc3BolusReconciler.recordsMissing(history, record(minute, 40, position = 58).pumpClockUtcSeconds))
    }

    @Test
    fun `an answer without the last answer's newest minute left records out`() {
        val history = Atc3BolusHistory(listOf(record(minute + 60_000L, 40)), 128)
        assertTrue(Atc3BolusReconciler.recordsMissing(history, record(minute, 40).pumpClockUtcSeconds))
    }
}
