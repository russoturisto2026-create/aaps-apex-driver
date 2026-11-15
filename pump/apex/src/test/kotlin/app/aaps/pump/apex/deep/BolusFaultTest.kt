package app.aaps.pump.apex.deep

import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.NoticeKind
import app.aaps.pump.apex.account.bolus.BolusAccount
import app.aaps.pump.apex.account.bolus.BolusRecordFrames.record
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexResponseFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A new journal cell the pump left with an old record. The records that can be read are written as
 * they say; all the faulty cells of the answer are one row, with what the move of the bolus field
 * leaves, at our command when one stands before them, else at the middle of their window. The loop
 * runs on. The faulty cells are the frames a pump answered, byte for byte.
 */
class BolusFaultTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private val journal = JournalSnapshot(store, serial, SnapshotKind.BOLUS)
    private val account = BolusAccount(store, serial)
    private val deep = BolusDeep(store, serial)

    /** The pump's clock stands at UTC in these tests: a key is the moment in seconds. */
    private val offsetMs = 0L

    /** 2026-11-03 14:00 UTC, a whole minute. */
    private val two = 1_793_714_400L
    private val nowMs = two * 1000L + 90_000L

    /** The past of the pump: five records a minute apart before two o'clock. */
    private fun pastRecords() = (1..5).map { record(two - it * 60L, 20, 20) }

    /** The new cell of 03.11 14:05: 03.11 05:39:59, asked 0.2, delivered 0.2. */
    private val cellOf1405 = cell("aa1680a321001a0b0305273b080008000000000092c6")

    /** The new cell of 03.11 08:45: 02.11 16:32:59, asked 0.675, delivered 0.675. */
    private val cellOf0845 = cell("aa1680a321001a0b0210203b1b001b0000000000d869")

    private fun cell(hex: String): JournalSnapshot.Answered {
        val frame = ApexResponseFrame(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        return JournalSnapshot.Answered(frame, ApexBolusRecord.decode(frame)!!.clockSeconds)
    }

    /** [top] over the past, read at [nowMs]; the past was read a minute before. */
    private fun answerWith(vararg top: JournalSnapshot.Answered): JournalSnapshot.Added {
        journal.take(pastRecords(), offsetMs, nowMs - 60_000L)
        return journal.take(top.toList() + pastRecords(), offsetMs, nowMs)!!
    }

    private fun settle(added: JournalSnapshot.Added) = deep.settleFaults(added, canClose = { true }, notBeforeMs = 0L, nowMs = nowMs)!!

    /** The span closed by a bolus field that moved [rest] beyond the rows settled before: as `BolusJournal` does it. */
    private fun close(found: BolusDeep.Settled, rest: Double): List<AapsWrite> {
        val id = found.faultyRowId ?: return found.writes
        return deep.noticeFor(BolusByDaysDeep(store, serial).correct(found.writes, rest, id).writes, id)
    }

    private fun bolusRows(writes: List<AapsWrite>) = writes.filterIsInstance<AapsWrite.Bolus>()

    private fun ourBolus(acceptedAtMs: Long, units: Double, seenUnits: Double, type: String = "NORMAL") =
        account.commandAccepted(acceptedAtMs, units, type).also { account.finished(it, seenUnits, completed = false) }

    @Test
    fun `the night of 14_05 - our 0_8 U stopped, the cell holds a record of 05_39 - the dose by the field, 0_225, at our command`() {
        journal.take(pastRecords(), offsetMs, nowMs - 60_000L)
        val acceptedAt = two * 1000L + 29_131L
        val ours = ourBolus(acceptedAt, 0.8, seenUnits = 0.225)
        val found = settle(journal.take(listOf(cellOf1405) + pastRecords(), offsetMs, nowMs)!!)

        val closed = store.actions(serial).single { it.id == ours.id }
        assertTrue(closed.done)
        assertEquals(6L, closed.recordSeq)

        val writes = close(found, 0.225)
        val row = bolusRows(writes).single()
        assertEquals(0.225, row.amountUnits, 1e-9)
        assertEquals(acceptedAt, row.timestampMs)
        assertEquals("NORMAL", row.type)
        assertEquals(NoticeKind.JOURNAL_FAULT, writes.filterIsInstance<AapsWrite.Notice>().single().kind)
        assertEquals(0.225, account.settledSinceHeartbeat().sum(), 1e-9)
    }

    @Test
    fun `the night of 08_45 - our 1_0 U stopped, the cell holds 0_675 U of the day before - 0_65 U by the field, at our command`() {
        journal.take(pastRecords(), offsetMs, nowMs - 60_000L)
        val acceptedAt = two * 1000L + 27_106L
        ourBolus(acceptedAt, 1.0, seenUnits = 0.625)
        val found = settle(journal.take(listOf(cellOf0845) + pastRecords(), offsetMs, nowMs)!!)

        val row = bolusRows(close(found, 0.65)).single()
        assertEquals(0.65, row.amountUnits, 1e-9)
        assertEquals(acceptedAt, row.timestampMs)
    }

    /** A record of [minuteKey] with no month and no day, as the pump has left in a cell. */
    private fun undated(minuteKey: Long, raw: Int): JournalSnapshot.Answered {
        val frame = record(minuteKey, raw, raw).frame.raw.copyOf()
        frame[7] = 0
        frame[8] = 0
        val cell = ApexResponseFrame(frame)
        return JournalSnapshot.Answered(cell, ApexBolusRecord.decode(cell)!!.clockSeconds)
    }

    @Test
    fun `a faulty cell over a record with no date - our command's moment and type, the dose by the field`() {
        val past = listOf(undated(two - 30L, 20)) + pastRecords()
        journal.take(past, offsetMs, nowMs - 60_000L)
        val acceptedAt = two * 1000L + 41_000L
        ourBolus(acceptedAt, 0.5, seenUnits = 0.5, type = "SMB")
        // The new cell holds 02.11 16:45, 0.675 U: later than the "moment" of the record with no date.
        val found = settle(journal.take(listOf(cellOf0845) + past, offsetMs, nowMs)!!)

        val writes = close(found, 0.5)
        val row = bolusRows(writes).single()
        assertEquals(0.5, row.amountUnits, 1e-9)
        assertEquals(acceptedAt, row.timestampMs)
        assertEquals("SMB", row.type)
        assertEquals(NoticeKind.JOURNAL_FAULT, writes.filterIsInstance<AapsWrite.Notice>().single().kind)
    }

    @Test
    fun `a sound record of another dose after our command - written as it says, at its minute, our command finds no record`() {
        journal.take(pastRecords(), offsetMs, nowMs - 60_000L)
        val ours = ourBolus(two * 1000L + 5_000L, 0.5, seenUnits = 0.5)
        val found = settle(journal.take(listOf(record(two, 12, 12)) + pastRecords(), offsetMs, nowMs)!!)

        assertNull(found.faultyRowId)
        val row = bolusRows(found.writes).single()
        assertEquals(0.3, row.amountUnits, 1e-9)
        assertEquals(two * 1000L, row.timestampMs)
        assertEquals("NORMAL", row.type)
        assertEquals(ours.id, account.openActions().single().id)
    }

    @Test
    fun `a sound record and a faulty cell above it, no command - the record stays, the cell takes the rest between the last read and this one`() {
        val found = settle(answerWith(cellOf1405, record(two, 20, 20)))

        val writes = close(found, 0.725)
        val rows = bolusRows(writes)
        assertEquals(0.5, rows.single { it.pumpId != found.faultyRowId }.amountUnits, 1e-9)
        val cell = rows.single { it.pumpId == found.faultyRowId }
        assertEquals(0.225, cell.amountUnits, 1e-9)
        // The last read, 14:00:30, is later than the record of 14:00: the window runs from it to this read, 14:01:30.
        assertEquals(two * 1000L + 60_000L, cell.timestampMs)
        val notice = writes.filterIsInstance<AapsWrite.Notice>().single()
        assertEquals(NoticeKind.JOURNAL_FAULT, notice.kind)
        assertEquals(0.225, notice.units, 1e-9)
    }

    @Test
    fun `a faulty cell below our record, no other command - the record as it says, the cell between the last read and our record`() {
        journal.take(pastRecords(), offsetMs, nowMs - 60_000L)
        val acceptedAt = two * 1000L + 61_000L
        ourBolus(acceptedAt, 0.3, seenUnits = 0.3, type = "SMB")
        val found = settle(journal.take(listOf(record(two + 60L, 12, 12), cellOf1405) + pastRecords(), offsetMs, nowMs)!!)

        val rows = bolusRows(close(found, 0.8))
        assertEquals(acceptedAt, rows.single { it.pumpId != found.faultyRowId }.timestampMs)
        val cell = rows.single { it.pumpId == found.faultyRowId }
        assertEquals(0.5, cell.amountUnits, 1e-9)
        assertEquals("NORMAL", cell.type)
        // From the last read, 14:00:30, to our record, 14:01:01.
        assertEquals(two * 1000L + 45_500L, cell.timestampMs)
    }

    @Test
    fun `our stopped bolus in a faulty cell and our next bolus with its record - the record as it says, the cell takes the rest at the stopped command`() {
        journal.take(pastRecords(), offsetMs, nowMs - 60_000L)
        val stoppedAt = two * 1000L + 29_131L
        ourBolus(stoppedAt, 0.8, seenUnits = 0.15)
        ourBolus(two * 1000L + 61_000L, 0.3, seenUnits = 0.3, type = "SMB")
        val found = settle(journal.take(listOf(record(two + 60L, 12, 12), cellOf1405) + pastRecords(), offsetMs, nowMs)!!)

        val rows = bolusRows(close(found, 0.6))
        val cell = rows.single { it.pumpId == found.faultyRowId }
        assertEquals(0.3, cell.amountUnits, 1e-9)
        assertEquals(stoppedAt, cell.timestampMs)
        val record = rows.single { it.pumpId != found.faultyRowId }
        assertEquals(0.3, record.amountUnits, 1e-9)
        assertEquals("SMB", record.type)
    }

    @Test
    fun `the heartbeat of 13_11 20_20 - our bolus and a bolus past the driver in a journal started over - one row of the whole move, at our command`() {
        journal.take(pastRecords(), offsetMs, nowMs - 60_000L)
        val acceptedAt = two * 1000L + 41_000L
        ourBolus(acceptedAt, 0.5, seenUnits = 0.5)
        val found = settle(journal.startOver(listOf(record(two + 30L, 12, 12), record(two + 40L, 20, 20)), offsetMs, nowMs))

        val row = bolusRows(close(found, 0.8)).single()
        assertEquals(0.8, row.amountUnits, 1e-9)
        assertEquals(acceptedAt, row.timestampMs)
    }

    @Test
    fun `a record stamped in the future in our cell - a faulty cell, and the next record is not older for it`() {
        journal.take(pastRecords(), offsetMs, nowMs - 60_000L)
        ourBolus(two * 1000L + 29_131L, 0.5, seenUnits = 0.5)
        val future = record(two + 3600L, 20, 20)
        val found = settle(journal.take(listOf(future) + pastRecords(), offsetMs, nowMs)!!)
        assertEquals(0.5, bolusRows(close(found, 0.5)).single().amountUnits, 1e-9)

        // A bolus on the pump two minutes after two: a plain record, not a faulty cell behind the future stamp.
        val next = settle(journal.take(listOf(record(two + 120L, 12, 12), future) + pastRecords(), offsetMs, nowMs)!!)
        assertNull(next.faultyRowId)
        assertEquals(0.3, bolusRows(next.writes).single().amountUnits, 1e-9)
    }

    @Test
    fun `two faulty cells in one span are one row with the whole of it, in the middle of the span`() {
        val found = settle(answerWith(cellOf1405, cellOf0845))

        val row = bolusRows(close(found, 0.9)).single()
        assertEquals(0.9, row.amountUnits, 1e-9)
        assertEquals(nowMs - 30_000L, row.timestampMs)
    }

    @Test
    fun `the row of the cells never stands after the heartbeat the read is compared at`() {
        val heartbeat = nowMs - 3_000L
        val found = deep.settleFaults(answerWith(cellOf1405, cellOf0845), canClose = { true }, notBeforeMs = 0L, nowMs = nowMs, comparedAtMs = heartbeat)!!

        assertEquals((nowMs - 60_000L + heartbeat) / 2, bolusRows(found.writes).single().timestampMs)
    }

    @Test
    fun `a field that did not move writes nothing for the cell and tells the dose is unknown`() {
        val found = settle(answerWith(cellOf1405))

        val writes = close(found, 0.0)
        assertTrue(writes.none { it is AapsWrite.Bolus })
        assertEquals(NoticeKind.JOURNAL_FAULT_UNKNOWN, writes.filterIsInstance<AapsWrite.Notice>().single().kind)
    }

    @Test
    fun `no word from the statistics on the kept day writes nothing for the cell and tells the dose is unknown`() {
        val found = settle(answerWith(cellOf1405))

        val writes = deep.noticeFor(found.writes, found.faultyRowId!!)
        assertTrue(writes.none { it is AapsWrite.Bolus })
        assertEquals(NoticeKind.JOURNAL_FAULT_UNKNOWN, writes.filterIsInstance<AapsWrite.Notice>().single().kind)
    }

    @Test
    fun `a faulty cell without the statistics is not settled, nothing is changed`() {
        journal.take(pastRecords(), offsetMs, nowMs - 60_000L)
        val ours = ourBolus(two * 1000L + 29_131L, 0.8, seenUnits = 0.225)
        val added = journal.take(listOf(cellOf1405) + pastRecords(), offsetMs, nowMs)!!

        assertNull(deep.settleFaults(added, canClose = { false }, notBeforeMs = 0L, nowMs = nowMs))
        assertEquals(ours.id, account.openActions().single().id)
        // The cell is not remembered: the next answer, with the statistics, settles it.
        assertEquals(0.225, bolusRows(close(settle(added), 0.225)).single().amountUnits, 1e-9)
    }

    @Test
    fun `the same old record in a new cell again, over the faulty cell before - a new faulty cell, at our next command`() {
        close(settle(answerWith(cellOf1405)), 0.225)
        val acceptedAt = two * 1000L + 120_000L
        val ours = ourBolus(acceptedAt, 0.5, seenUnits = 0.5)

        val found = settle(journal.take(listOf(cellOf1405, cellOf1405) + pastRecords(), offsetMs, nowMs + 120_000L)!!)

        val row = bolusRows(close(found, 0.5)).single()
        assertEquals(found.faultyRowId, row.pumpId)
        assertEquals(acceptedAt, row.timestampMs)
        assertEquals(0.5, row.amountUnits, 1e-9)
        assertTrue(store.actions(serial).single { it.id == ours.id }.done)
    }

    @Test
    fun `the same old record in a new cell again, no command of ours - a faulty cell in the middle of its span, not a row of its old moment`() {
        close(settle(answerWith(cellOf1405)), 0.225)

        val later = nowMs + 120_000L
        val found = deep.settleFaults(journal.take(listOf(cellOf1405, cellOf1405) + pastRecords(), offsetMs, later)!!, canClose = { true }, notBeforeMs = 0L, nowMs = later)!!
        val writes = close(found, 0.2)

        val row = bolusRows(writes).single()
        assertEquals(found.faultyRowId, row.pumpId)
        assertEquals(nowMs + 60_000L, row.timestampMs)
        assertEquals(NoticeKind.JOURNAL_FAULT, writes.filterIsInstance<AapsWrite.Notice>().single().kind)
    }

    @Test
    fun `an answer that was not settled is taken back, its records come as new again`() {
        journal.take(pastRecords(), offsetMs, nowMs - 60_000L)
        val mark = journal.mark()
        journal.take(listOf(cellOf1405) + pastRecords(), offsetMs, nowMs)
        journal.rollBack(mark)

        val again = journal.take(listOf(cellOf1405) + pastRecords(), offsetMs, nowMs)!!
        assertEquals(1, again.records.size)
        assertEquals(6L, again.records.single().seq)
    }
}
