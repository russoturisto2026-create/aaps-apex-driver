package app.aaps.pump.atc3.history

import app.aaps.core.data.model.BS
import app.aaps.pump.atc3.comm.Atc3BolusHistory
import app.aaps.pump.atc3.comm.Atc3BolusRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reconciler decides what AAPS is told about every record the pump holds, so each case of
 * what the pump does with its records is pinned here.
 */
class Atc3BolusReconcilerTest {

    private val phoneNow = 1_700_000_000_000L

    private fun record(secondsAgo: Long, requested: Int, delivered: Int, extReq: Int = 0, extDlv: Int = 0) =
        Atc3BolusRecord(
            index = 0,
            timestamp = phoneNow - secondsAgo * 1000L,
            pumpClockUtcSeconds = (phoneNow - secondsAgo * 1000L) / 1000L,
            rawRequested = requested,
            rawDelivered = delivered,
            rawExtendedRequested = extReq,
            rawExtendedDelivered = extDlv
        )

    /**
     * A bolus of ours accepted by the pump at [startedAtMs].
     *
     * The key is put on the same scale as [record] here, plain epoch seconds, so the cases do not
     * depend on the timezone of the machine running them; the driver itself uses the pump's wall
     * clock for both, see [PendingBolus.startUtcSeconds].
     */
    private fun pending(startedAtMs: Long, units: Double, type: BS.Type = BS.Type.SMB, reported: Double = 0.0) =
        PendingBolus(startedAtMs, startedAtMs, units, type, reported, startUtcSeconds = startedAtMs / 1000L)

    /** A bolus of ours the pump closed with its completion frame, at [units], and filed under its id. */
    private fun completed(ledger: Atc3HistoryLedger, startedAtMs: Long, units: Double): Atc3HistoryLedger {
        val startUtcSeconds = startedAtMs / 1000L
        return ledger.withSettled(
            SettledBolus(
                startedAtMs = startedAtMs,
                requestedUnits = units,
                units = units,
                settledAtMs = startedAtMs + 30_000L,
                pumpId = ledger.assignOwnPumpId(startUtcSeconds),
                startUtcSeconds = startUtcSeconds
            )
        )
    }

    /**
     * The record the pump writes for a bolus started at [startedAtMs]: its start minute, second 59,
     * moved by [shiftMinutes] for a pump clock that is off.
     */
    private fun recordOf(startedAtMs: Long, requested: Int, delivered: Int = requested, shiftMinutes: Int = 0): Atc3BolusRecord {
        val stamp = Math.floorDiv(startedAtMs / 1000L, 60L) * 60L + 59L + shiftMinutes * 60L
        return Atc3BolusRecord(
            index = 0,
            timestamp = stamp * 1000L,
            pumpClockUtcSeconds = stamp,
            rawRequested = requested,
            rawDelivered = delivered
        )
    }

    /** A ledger that has already taken stock of the pump, so imports are allowed. */
    private fun ready(watermarkSecondsAgo: Long = 3600L) =
        Atc3HistoryLedger().withWatermark(
            (phoneNow - watermarkSecondsAgo * 1000L) / 1000L,
            phoneNow - watermarkSecondsAgo * 1000L
        )

    private fun reconcile(
        records: List<Atc3BolusRecord>,
        ledger: Atc3HistoryLedger
    ) = Atc3BolusReconciler.reconcile(records, records.size, ledger, phoneNow)

    @Test
    fun `a stranger's bolus is handed to AAPS at the time its record carries`() {
        val outcome = reconcile(listOf(record(300, 40, 40)), ready())
        assertEquals(phoneNow - 300_000L, outcome.actions.filterIsInstance<Atc3BolusAction.Import>().single().timestamp)
    }

    @Test
    fun `after the clock is put back, records on the new clock are still imported`() {
        // A pump ten minutes fast. Its record sets the import mark ten minutes ahead.
        val fast = Atc3BolusRecord(0, phoneNow + 300_000L, (phoneNow + 300_000L) / 1000L, 40, 40)
        val before = reconcile(listOf(fast), ready())
        assertEquals(1, before.actions.filterIsInstance<Atc3BolusAction.Import>().size)

        // The clock is written now, and the mark moves to the moment of the write, as
        // Atc3HistorySync.onPumpClockWritten does. The next record is stamped on the new clock.
        val written = before.ledger.withWatermark(phoneNow / 1000L - 120L, phoneNow - 120_000L)
        val imported = reconcile(listOf(fast, record(60, 80, 80)), written).actions.filterIsInstance<Atc3BolusAction.Import>()
        assertEquals(2.0, imported.single().units, 1e-9)
    }

    @Test
    fun `without moving the mark, a clock put back would hide the records that follow`() {
        // What the move is for: the same records against the mark the fast clock left behind.
        val fast = Atc3BolusRecord(0, phoneNow + 300_000L, (phoneNow + 300_000L) / 1000L, 40, 40)
        val before = reconcile(listOf(fast), ready())
        val imported = reconcile(listOf(fast, record(60, 80, 80)), before.ledger).actions.filterIsInstance<Atc3BolusAction.Import>()
        assertTrue(imported.isEmpty())
    }

    // Records AAPS will refuse whatever the driver does, see Atc3BolusReconciler.reconcile

    @Test
    fun `a record from before AAPS adopted the pump is consumed rather than offered`() {
        // AAPS refuses anything stamped before it registered the pump. Offering it would be a write
        // that always fails and a record marked as counted that never reached anywhere.
        val adopted = phoneNow - 600_000L
        val outcome = Atc3BolusReconciler.reconcile(
            records = listOf(record(1200, 40, 40)),
            recordCount = 1,
            ledger = ready(),
            phoneNow = phoneNow,
            earliestAcceptedMs = adopted
        )
        val consumed = outcome.actions.filterIsInstance<Atc3BolusAction.Consume>().single()
        assertTrue(consumed.reason.contains("adopted"))
        assertTrue(outcome.actions.none { it is Atc3BolusAction.Import })
    }

    @Test
    fun `a record from after AAPS adopted the pump is still imported`() {
        val adopted = phoneNow - 600_000L
        val outcome = Atc3BolusReconciler.reconcile(
            records = listOf(record(300, 40, 40)),
            recordCount = 1,
            ledger = ready(),
            phoneNow = phoneNow,
            earliestAcceptedMs = adopted
        )
        assertEquals(1, outcome.actions.filterIsInstance<Atc3BolusAction.Import>().size)
    }

    private fun history(records: List<Atc3BolusRecord>, storedByPump: Int) =
        Atc3BolusHistory(records, storedByPump)

    // The pump holds more than the periodic search returns, see Atc3BolusReconciler.recordsMissing

    @Test
    fun `nothing is missing when the answer carried every record the pump counts`() {
        val records = listOf(record(60, 40, 40), record(600, 200, 200))
        assertFalse(Atc3BolusReconciler.recordsMissing(history(records, storedByPump = 2), ready()))
    }

    @Test
    fun `records are missing when the oldest one sent is newer than the watermark`() {
        // The pump says it holds seventeen and sent ten, and the oldest of those ten is half an
        // hour old while the watermark is an hour back: the half hour in between is unseen.
        val records = (0..9).map { record(1800L + it * 60, 40, 40) }
        assertTrue(
            Atc3BolusReconciler.recordsMissing(history(records, storedByPump = 17), ready(watermarkSecondsAgo = 3600L))
        )
    }

    @Test
    fun `nothing is missing when the answer reaches back past the watermark`() {
        // The pump holds more than it sent, but what it sent already covers everything after the
        // watermark, so the rest is history that has been accounted for.
        val records = (0..9).map { record(1800L + it * 60, 40, 40) }
        assertFalse(
            Atc3BolusReconciler.recordsMissing(history(records, storedByPump = 17), ready(watermarkSecondsAgo = 1800L))
        )
    }

    @Test
    fun `nothing is missing before the first pass`() {
        // The first pass imports nothing whatever it reads, so reading more of it would be wasted.
        val records = (0..9).map { record(1800L + it * 60, 40, 40) }
        assertFalse(Atc3BolusReconciler.recordsMissing(history(records, storedByPump = 17), Atc3HistoryLedger()))
    }

    @Test
    fun `nothing is missing when the pump returned no records at all`() {
        assertFalse(Atc3BolusReconciler.recordsMissing(history(emptyList(), storedByPump = 17), ready()))
    }

    @Test
    fun `a full history answer never asks for itself again`() {
        // Seventeen stored, seventeen sent: the count matches, so no second read is triggered.
        val records = (0..16).map { record(60L + it * 60, 40, 40) }
        assertFalse(Atc3BolusReconciler.recordsMissing(history(records, storedByPump = 17), ready()))
    }

    @Test
    fun `the first pass imports nothing and remembers everything`() {
        val records = listOf(record(60, 40, 40), record(600, 200, 84))
        val outcome = reconcile(records, Atc3HistoryLedger())
        assertTrue(outcome.actions.all { it is Atc3BolusAction.Consume })
        assertTrue(outcome.ledger.firstPassDone)
        assertEquals(2, outcome.ledger.seen.size)
    }

    @Test
    fun `a bolus given on the pump is imported once and never again`() {
        val records = listOf(record(60, 40, 40))
        val first = reconcile(records, ready())
        assertEquals(1, first.actions.filterIsInstance<Atc3BolusAction.Import>().size)
        assertEquals(1.0, (first.actions[0] as Atc3BolusAction.Import).units, 1e-9)

        val second = reconcile(records, first.ledger)
        assertTrue(second.actions.isEmpty())
    }

    @Test
    fun `a record moved back a second inside its minute is not imported twice`() {
        val first = reconcile(listOf(record(60, 40, 40)), ready())
        val shifted = listOf(record(61, 40, 40))
        assertTrue(reconcile(shifted, first.ledger).actions.isEmpty())
    }

    @Test
    fun `a delivered amount the pump corrects upwards is rewritten under the same id`() {
        val first = reconcile(listOf(record(60, 200, 84)), ready())
        val importedId = (first.actions[0] as Atc3BolusAction.Import).pumpId

        val second = reconcile(listOf(record(60, 200, 86)), first.ledger)
        val rewrite = second.actions.filterIsInstance<Atc3BolusAction.Rewrite>().single()
        assertEquals(importedId, rewrite.pumpId)
        assertTrue(second.actions.none { it is Atc3BolusAction.Import })
    }

    // Which record is a bolus of ours: the minute it started in and the dose it asked for

    @Test
    fun `the bolus AAPS started is resolved rather than imported`() {
        val startedAtMs = phoneNow - 30_000L
        val outcome = reconcile(listOf(recordOf(startedAtMs, 40)), ready().withPending(pending(startedAtMs, 1.0)))
        val resolved = outcome.actions.filterIsInstance<Atc3BolusAction.ResolvePending>().single()
        assertEquals(1.0, resolved.units, 1e-9)
        assertTrue(outcome.ledger.pending.isEmpty())
        assertEquals(0, outcome.clockShiftMinutes)
    }

    @Test
    fun `a bolus of ours keeps the moment the pump accepted it, not the record's stamp`() {
        // The record carries only the minute, at second 59; the bolus began when the pump accepted
        // the command, and that is where AAPS keeps it.
        val startedAtMs = phoneNow - 50_000L
        val outcome = reconcile(listOf(recordOf(startedAtMs, 40)), ready().withPending(pending(startedAtMs, 1.0)))
        assertEquals(startedAtMs, outcome.actions.filterIsInstance<Atc3BolusAction.ResolvePending>().single().timestamp)
    }

    @Test
    fun `a bolus cut short is resolved at what the record says went in`() {
        // No completion frame came, so the pump's record is what says how much was delivered, and
        // it is less than was asked for: a cancel, an alarm or the link.
        val startedAtMs = phoneNow - 30_000L
        val outcome = reconcile(
            listOf(recordOf(startedAtMs, requested = 40, delivered = 10)),
            ready().withPending(pending(startedAtMs, 1.0, type = BS.Type.NORMAL, reported = 0.2))
        )
        val resolved = outcome.actions.filterIsInstance<Atc3BolusAction.ResolvePending>().single()
        assertEquals(0.25, resolved.units, 1e-9)
        assertTrue(outcome.actions.none { it is Atc3BolusAction.Import })
    }

    @Test
    fun `a record that delivered more than it asked for is not ours`() {
        val startedAtMs = phoneNow - 30_000L
        val outcome = reconcile(
            listOf(recordOf(startedAtMs, requested = 40, delivered = 42)),
            ready().withPending(pending(startedAtMs, 1.0))
        )
        assertTrue(outcome.actions.none { it is Atc3BolusAction.ResolvePending })
        assertEquals(1, outcome.actions.filterIsInstance<Atc3BolusAction.Import>().size)
    }

    @Test
    fun `another dose in the same minute is not ours and is imported`() {
        // A bolus given on the pump in the minute ours started, of a different dose.
        val startedAtMs = phoneNow - 30_000L
        val outcome = reconcile(
            listOf(recordOf(startedAtMs, 200)),
            ready().withPending(pending(startedAtMs, 1.0))
        )
        assertTrue(outcome.actions.none { it is Atc3BolusAction.ResolvePending })
        assertEquals(5.0, outcome.actions.filterIsInstance<Atc3BolusAction.Import>().single().units, 1e-9)
        assertEquals(1, outcome.ledger.pending.size)
        assertNull(outcome.clockShiftMinutes)
    }

    @Test
    fun `another dose in the next minute is not ours and is imported`() {
        val startedAtMs = phoneNow - 90_000L
        val outcome = reconcile(
            listOf(recordOf(startedAtMs, 200, shiftMinutes = 1)),
            ready().withPending(pending(startedAtMs, 1.0))
        )
        assertTrue(outcome.actions.none { it is Atc3BolusAction.ResolvePending })
        assertEquals(1, outcome.actions.filterIsInstance<Atc3BolusAction.Import>().size)
    }

    @Test
    fun `a record two minutes away is not ours`() {
        val startedAtMs = phoneNow - 300_000L
        val outcome = reconcile(
            listOf(recordOf(startedAtMs, 40, shiftMinutes = 2)),
            ready().withPending(pending(startedAtMs, 1.0))
        )
        assertTrue(outcome.actions.none { it is Atc3BolusAction.ResolvePending })
        assertEquals(1, outcome.actions.filterIsInstance<Atc3BolusAction.Import>().size)
    }

    @Test
    fun `a bolus of ours and one given on the pump in the same minute, different doses, each go their way`() {
        val startedAtMs = phoneNow - 30_000L
        val ours = recordOf(startedAtMs, 40)
        val manual = recordOf(startedAtMs, 80).let { it.copy(timestamp = it.timestamp - 1000L, pumpClockUtcSeconds = it.pumpClockUtcSeconds - 1) }
        val outcome = reconcile(listOf(ours, manual), ready().withPending(pending(startedAtMs, 1.0)))

        assertEquals(1.0, outcome.actions.filterIsInstance<Atc3BolusAction.ResolvePending>().single().units, 1e-9)
        assertEquals(2.0, outcome.actions.filterIsInstance<Atc3BolusAction.Import>().single().units, 1e-9)
    }

    @Test
    fun `a long bolus is matched on the minute it started, however long it ran`() {
        // Delivery ended minutes after the start; the record still carries the start minute.
        val startedAtMs = phoneNow - 400_000L
        val long = pending(startedAtMs, 5.0, type = BS.Type.NORMAL)
        val outcome = reconcile(listOf(recordOf(startedAtMs, 200)), ready().withPending(long))
        assertEquals(1, outcome.actions.filterIsInstance<Atc3BolusAction.ResolvePending>().size)
        assertTrue(outcome.actions.none { it is Atc3BolusAction.Import })
    }

    // Records paired with our boluses first in, first out

    @Test
    fun `two microboluses in neighbouring minutes on a right clock each take their own record`() {
        val first = phoneNow - 150_000L
        val second = first + 60_000L
        val ledger = ready().withPending(pending(first, 0.1)).withPending(pending(second, 0.1).copy(temporaryId = second + 1))
        val outcome = reconcile(listOf(recordOf(first, 4), recordOf(second, 4)), ledger)

        assertEquals(2, outcome.actions.filterIsInstance<Atc3BolusAction.ResolvePending>().size)
        assertTrue(outcome.actions.none { it is Atc3BolusAction.Import })
        assertEquals(0, outcome.clockShiftMinutes)
    }

    @Test
    fun `two microboluses in neighbouring minutes on a clock a minute behind still each take their own`() {
        // The trap an exact-minute-first match falls into: the first bolus takes the second's record,
        // which sits in its minute, the second finds nothing, and the first's record is imported as
        // a stranger's. First in, first out pairs both.
        val first = phoneNow - 150_000L
        val second = first + 60_000L
        val ledger = ready().withPending(pending(first, 0.1)).withPending(pending(second, 0.1).copy(temporaryId = second + 1))
        val records = listOf(recordOf(first, 4, shiftMinutes = -1), recordOf(second, 4, shiftMinutes = -1))
        val outcome = reconcile(records, ledger)

        // Each row moves onto the minute the pump keeps it in, with the second it started at.
        val resolved = outcome.actions.filterIsInstance<Atc3BolusAction.ResolvePending>()
        assertEquals(2, resolved.size)
        assertEquals(first - 60_000L, resolved.single { it.pending.startedAtMs == first }.timestamp)
        assertEquals(second - 60_000L, resolved.single { it.pending.startedAtMs == second }.timestamp)
        assertTrue(outcome.actions.none { it is Atc3BolusAction.Import })
        assertTrue(outcome.ledger.pending.isEmpty())
        assertEquals(-1, outcome.clockShiftMinutes)
    }

    @Test
    fun `a bolus whose record is not written yet does not push the shift anywhere`() {
        // One of ours has its record in its own minute, the other has none yet (an alarm holds it
        // back). No shift pairs one; a shift a minute either way pairs none. No shift it is.
        val first = phoneNow - 150_000L
        val second = first + 60_000L
        val ledger = ready().withPending(pending(first, 0.1)).withPending(pending(second, 0.2).copy(temporaryId = second + 1))
        val outcome = reconcile(listOf(recordOf(first, 4)), ledger)

        assertEquals(1, outcome.actions.filterIsInstance<Atc3BolusAction.ResolvePending>().size)
        assertEquals(0, outcome.clockShiftMinutes)
        assertEquals(1, outcome.ledger.pending.size)
    }

    @Test
    fun `with the pump's clock seconds behind, an early start and a later one each take their own record`() {
        // The pump 7 to 9 seconds behind the phone. A bolus started
        // at second 3 is written into the minute before; one started at second 20 is not. One shift
        // for the whole read would pair only one of them and import the other as a stranger's.
        val early = phoneNow - 17_000L - 5 * 60_000L
        val later = phoneNow - 2 * 60_000L
        val ledger = completed(completed(ready(), early, 0.3), later, 0.9)
        val records = listOf(recordOf(early, 12, shiftMinutes = -1), recordOf(later, 36))
        val outcome = reconcile(records, ledger)

        assertTrue(outcome.actions.none { it is Atc3BolusAction.Import })
        assertEquals(early - 60_000L, outcome.actions.filterIsInstance<Atc3BolusAction.Rewrite>().single().timestamp)
        assertEquals(1, outcome.actions.filterIsInstance<Atc3BolusAction.Consume>().size)
        assertTrue(outcome.ledger.settled.isEmpty())
        // One of them sat a minute off its start: that is a miss for the clock.
        assertEquals(-1, outcome.clockShiftMinutes)
    }

    @Test
    fun `an equal stranger's dose in the minute before is taken first, and the count stays right`() {
        // The records carry no identity: two equal doses in neighbouring minutes cannot be told
        // apart, only counted. First in, first out gives ours the older one; the other is imported.
        val startedAtMs = phoneNow - 10 * 60_000L
        val ledger = ready().withPending(pending(startedAtMs, 0.1))
        val stranger = recordOf(startedAtMs, 4, shiftMinutes = -1)
        val ours = recordOf(startedAtMs, 4)
        val outcome = reconcile(listOf(ours, stranger), ledger)

        assertEquals(1, outcome.actions.filterIsInstance<Atc3BolusAction.ResolvePending>().size)
        assertEquals(0.1, outcome.actions.filterIsInstance<Atc3BolusAction.Import>().single().units, 1e-9)
        assertTrue(outcome.ledger.pending.isEmpty())
    }

    // A bolus closed on its completion frame, recognised when the history is next read

    @Test
    fun `a bolus closed on its completion frame is recognised later and AAPS is left alone`() {
        val startedAtMs = phoneNow - 10 * 60_000L
        val ledger = completed(ready(), startedAtMs, 1.0)
        val ourId = ledger.settled.single().pumpId

        val outcome = reconcile(listOf(recordOf(startedAtMs, 40)), ledger)

        assertTrue(outcome.actions.none { it is Atc3BolusAction.Import || it is Atc3BolusAction.Rewrite })
        assertEquals(ourId, outcome.actions.filterIsInstance<Atc3BolusAction.Consume>().single().pumpId)
        assertTrue(outcome.ledger.settled.isEmpty())
        assertEquals(ourId, outcome.ledger.seen.single().pumpId)
        assertEquals(0, outcome.clockShiftMinutes)

        // And the next read of the same record is nothing at all.
        assertTrue(reconcile(listOf(recordOf(startedAtMs, 40)), outcome.ledger).actions.isEmpty())
    }

    @Test
    fun `a completed bolus found a minute off is moved onto the pump's minute`() {
        val startedAtMs = phoneNow - 10 * 60_000L
        val ledger = completed(ready(), startedAtMs, 1.0)
        val outcome = reconcile(listOf(recordOf(startedAtMs, 40, shiftMinutes = -1)), ledger)

        val rewrite = outcome.actions.filterIsInstance<Atc3BolusAction.Rewrite>().single()
        assertEquals(ledger.settled.single().pumpId, rewrite.pumpId)
        assertEquals(startedAtMs - 60_000L, rewrite.timestamp)
        assertEquals(1.0, rewrite.units, 1e-9)
        assertTrue(outcome.actions.none { it is Atc3BolusAction.Import })
        assertEquals(-1, outcome.clockShiftMinutes)
    }

    @Test
    fun `the id a completed bolus is filed under is the one its record would have had`() {
        val startedAtMs = phoneNow - 10 * 60_000L
        val record = recordOf(startedAtMs, 40)
        assertEquals(ready().assignPumpId(record), completed(ready(), startedAtMs, 1.0).settled.single().pumpId)
    }

    @Test
    fun `a record that disagrees with the completion frame corrects the row under our id`() {
        val startedAtMs = phoneNow - 10 * 60_000L
        val ledger = completed(ready(), startedAtMs, 1.0)
        val outcome = reconcile(listOf(recordOf(startedAtMs, 40, delivered = 38)), ledger)

        val rewrite = outcome.actions.filterIsInstance<Atc3BolusAction.Rewrite>().single()
        assertEquals(ledger.settled.single().pumpId, rewrite.pumpId)
        assertEquals(0.95, rewrite.units, 1e-9)
        assertEquals(startedAtMs, rewrite.timestamp)
        assertTrue(outcome.actions.none { it is Atc3BolusAction.Import })
    }

    @Test
    fun `a stranger's record in the second our completed bolus's record will take gets an id of its own`() {
        // Until our record is read, its id is held by our row; nothing else may take it.
        val startedAtMs = phoneNow - 10 * 60_000L
        val ledger = completed(ready(), startedAtMs, 1.0)
        val stranger = recordOf(startedAtMs, 80)
        val outcome = reconcile(listOf(stranger), ledger)

        val imported = outcome.actions.filterIsInstance<Atc3BolusAction.Import>().single()
        assertTrue(imported.pumpId != ledger.settled.single().pumpId)
        assertEquals(1, outcome.ledger.settled.size)
    }

    @Test
    fun `two equal records in one minute are two boluses`() {
        val one = record(60, 40, 40)
        val outcome = reconcile(listOf(one, one.copy(index = 1, pumpClockUtcSeconds = one.pumpClockUtcSeconds - 1)), ready())
        assertEquals(2, outcome.actions.filterIsInstance<Atc3BolusAction.Import>().size)
    }

    @Test
    fun `our bolus and another of the same dose in one minute`() {
        // 23:30: 0.1 U through the driver, closed on its completion frame, then 0.1 U straight at
        // the pump. The pump stamped them 23:30:59 and 23:30:58.
        val startedAtMs = phoneNow - 10 * 60_000L
        val ledger = completed(ready(), startedAtMs, 0.1)
        val newer = recordOf(startedAtMs, 4)
        val older = newer.copy(index = 1, timestamp = newer.timestamp - 1000L, pumpClockUtcSeconds = newer.pumpClockUtcSeconds - 1)
        val outcome = reconcile(listOf(newer, older), ledger)

        assertEquals(1, outcome.actions.filterIsInstance<Atc3BolusAction.Consume>().size)
        assertEquals(0.1, outcome.actions.filterIsInstance<Atc3BolusAction.Import>().single().units, 1e-9)
        assertTrue(outcome.ledger.settled.isEmpty())

        // Read again: both are known now, nothing more happens.
        assertTrue(reconcile(listOf(newer, older), outcome.ledger).actions.isEmpty())
    }

    @Test
    fun `the bookkeeping record is imported while its neighbour is not imported again`() {
        // A cancelled 5.0 U bolus, and the 0.05 U the pump writes up afterwards in the very second
        // the first record has just vacated.
        val cancelled = record(600, 200, 84)
        val first = reconcile(listOf(cancelled), ready())
        assertEquals(1, first.actions.filterIsInstance<Atc3BolusAction.Import>().size)

        val correction = record(599, 0, 2)
        val second = reconcile(listOf(cancelled.copy(timestamp = cancelled.timestamp - 1000L, pumpClockUtcSeconds = cancelled.pumpClockUtcSeconds - 1), correction), first.ledger)
        val imports = second.actions.filterIsInstance<Atc3BolusAction.Import>()
        assertEquals(1, imports.size)
        assertEquals(0.05, imports[0].units, 1e-9)
    }

    @Test
    fun `a record with no amounts is remembered but not recorded`() {
        val outcome = reconcile(listOf(record(60, 0, 0)), ready())
        assertEquals(1, outcome.actions.filterIsInstance<Atc3BolusAction.Consume>().size)
        assertEquals(1, outcome.ledger.seen.size)
    }

    @Test
    fun `an extended bolus is imported as one normal bolus of what it delivered`() {
        val extended = reconcile(listOf(record(60, 0, 0, extReq = 136, extDlv = 2)), ready())
        val importedExtended = extended.actions.filterIsInstance<Atc3BolusAction.Import>().single()
        assertEquals(0.05, importedExtended.units, 1e-9)
        assertTrue(importedExtended.carriesExtendedPart)

        val dual = reconcile(listOf(record(120, 80, 18, extReq = 40, extDlv = 0)), ready())
        val importedDual = dual.actions.filterIsInstance<Atc3BolusAction.Import>().single()
        assertEquals(0.45, importedDual.units, 1e-9)
        assertTrue(importedDual.carriesExtendedPart)
    }

    @Test
    fun `records older than the watermark or than a day are not imported`() {
        val old = reconcile(listOf(record(7200, 40, 40)), ready(watermarkSecondsAgo = 3600))
        assertTrue(old.actions.all { it is Atc3BolusAction.Consume })

        val ancient = reconcile(listOf(record(26 * 3600, 40, 40)), ready(watermarkSecondsAgo = 30 * 3600))
        assertTrue(ancient.actions.all { it is Atc3BolusAction.Consume })
    }

    @Test
    fun `a bolus held back by an alarm waits for its record however many reads it takes`() {
        // An empty reservoir cuts a 1.00 U bolus off at 0.25 U. The pump answers read after read
        // without the record and produces it once the alarm is cleared.
        val startedAtMs = phoneNow - 40 * 60_000L
        var ledger = ready().withPending(pending(startedAtMs, 1.0, reported = 0.25))
        repeat(104) {
            val outcome = reconcile(emptyList(), ledger)
            assertEquals(1, outcome.actions.filterIsInstance<Atc3BolusAction.CountAttempt>().size)
            assertTrue(outcome.actions.none { it is Atc3BolusAction.DropPending })
            ledger = outcome.ledger
        }
        assertEquals(104, ledger.pending.single().confirmAttempts)

        // The late record carries the minute the bolus started, and what really went in.
        val late = reconcile(listOf(recordOf(startedAtMs, 40, delivered = 10)), ledger)
        assertEquals(0.25, late.actions.filterIsInstance<Atc3BolusAction.ResolvePending>().single().units, 1e-9)
        assertTrue(late.actions.none { it is Atc3BolusAction.Import || it is Atc3BolusAction.DropPending })
        assertTrue(late.ledger.pending.isEmpty())
    }

    @Test
    fun `a later record with ours missing proves ours was never written`() {
        // The pump writes nothing else while a record of ours is owed, so a bolus ten minutes later
        // in the history with ours not there means ours will never come.
        val startedAtMs = phoneNow - 40 * 60_000L
        val outcome = reconcile(listOf(record(30 * 60, 80, 80)), ready().withPending(pending(startedAtMs, 1.0)))

        assertEquals(1, outcome.actions.filterIsInstance<Atc3BolusAction.DropPending>().size)
        assertEquals(2.0, outcome.actions.filterIsInstance<Atc3BolusAction.Import>().single().units, 1e-9)
        assertTrue(outcome.ledger.pending.isEmpty())
        // Nothing is claimed about how much went in: the entry carries zero.
        assertEquals(0.0, outcome.ledger.settled.single().units, 1e-9)
    }

    @Test
    fun `a later record already counted in an earlier read proves it just the same`() {
        val startedAtMs = phoneNow - 40 * 60_000L
        val before = reconcile(listOf(record(30 * 60, 80, 80)), ready())
        val outcome = reconcile(emptyList(), before.ledger.withPending(pending(startedAtMs, 1.0)))
        assertEquals(1, outcome.actions.filterIsInstance<Atc3BolusAction.DropPending>().size)
    }

    @Test
    fun `a record in the next minute does not prove ours missing`() {
        // One minute is the slack the pump's clock is allowed, so a record there could still be
        // where ours would have gone; it proves nothing.
        val startedAtMs = phoneNow - 40 * 60_000L
        val outcome = reconcile(listOf(recordOf(startedAtMs, 80, shiftMinutes = 1)), ready().withPending(pending(startedAtMs, 1.0)))

        assertTrue(outcome.actions.none { it is Atc3BolusAction.DropPending })
        assertEquals(1, outcome.actions.filterIsInstance<Atc3BolusAction.CountAttempt>().size)
        assertEquals(1, outcome.ledger.pending.size)
    }

    @Test
    fun `a bolus of the same size ten minutes later is imported, not taken for ours`() {
        val startedAtMs = phoneNow - 40 * 60_000L
        val outcome = reconcile(listOf(record(30 * 60, 40, 40)), ready().withPending(pending(startedAtMs, 1.0)))
        assertEquals(1.0, outcome.actions.filterIsInstance<Atc3BolusAction.Import>().single().units, 1e-9)
        assertEquals(1, outcome.actions.filterIsInstance<Atc3BolusAction.DropPending>().size)
    }

    @Test
    fun `a correction written into the second the watermark sits on is still imported`() {
        // The pump does exactly this: the new record takes the second the record before it has
        // just vacated, and that second is where the watermark was left standing.
        val cancelled = record(600, 200, 84)
        val first = reconcile(listOf(cancelled), ready())
        assertEquals(cancelled.pumpClockUtcSeconds, first.ledger.importFromUtcSeconds)

        val correction = record(600, 0, 2)
        val second = reconcile(listOf(correction), first.ledger)
        assertEquals(1, second.actions.filterIsInstance<Atc3BolusAction.Import>().size)
    }

    @Test
    fun `a correction is applied once and not on every poll afterwards`() {
        val first = reconcile(listOf(record(60, 200, 84)), ready())
        val corrected = listOf(record(60, 200, 86))
        val second = reconcile(corrected, first.ledger)
        assertEquals(1, second.actions.filterIsInstance<Atc3BolusAction.Rewrite>().size)

        val third = reconcile(corrected, second.ledger)
        assertTrue(third.actions.isEmpty())
    }

    @Test
    fun `wouldResolve agrees with what reconcile does and changes nothing`() {
        val startedAtMs = phoneNow - 30_000L
        val ours = pending(startedAtMs, 1.0)
        val ledger = ready().withPending(ours)
        assertTrue(Atc3BolusReconciler.wouldResolve(listOf(recordOf(startedAtMs, 40)), ours, ledger))
        assertEquals(1, ledger.pending.size)

        assertFalse(Atc3BolusReconciler.wouldResolve(listOf(recordOf(startedAtMs, 200)), ours, ledger))
    }
}
