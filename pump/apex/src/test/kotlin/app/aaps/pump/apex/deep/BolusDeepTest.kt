package app.aaps.pump.apex.deep

import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.bolus.BolusAccount
import app.aaps.pump.apex.account.bolus.BolusRecordFrames.record
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.account.store.SnapshotKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The bolus records off the plain path: a bolus given on the pump, a faulty record, a record with an extended part, a command outrun. */
class BolusDeepTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private val journal = JournalSnapshot(store, serial, SnapshotKind.BOLUS)
    private val account = BolusAccount(store, serial)
    private val deep = BolusDeep(store, serial)

    /** The pump's clock stands at UTC in these tests: a key is the moment in seconds. */
    private val offsetMs = 0L

    private val tenOClock = 1_760_000_000L / 60 * 60 // a whole minute
    private val nowMs = tenOClock * 1000L + 90_000L

    /** The past of the pump: five records a minute apart, taken in on a first read. The oldest is five minutes before ten. */
    private fun past(): JournalSnapshot.Added {
        val past = (1..5).map { record(tenOClock - it * 60L, 20, 20) }
        return journal.take(past, offsetMs, nowMs)!!
    }

    /** A short answer: [top] over the five records of the past. */
    private fun answerWith(top: JournalSnapshot.Answered): JournalSnapshot.Added {
        val answer = listOf(top) + (1..5).map { record(tenOClock - it * 60L, 20, 20) }
        return journal.take(answer, offsetMs, nowMs)!!
    }

    private fun settle(added: JournalSnapshot.Added) = deep.settleFaults(added, canClose = { true }, notBeforeMs = 0L, nowMs = nowMs)!!

    @Test
    fun `a record nobody here asked for is a bolus given on the pump, written as delivered, the loop runs on`() {
        past()
        val found = settle(answerWith(record(tenOClock, 44, 44)))

        val write = found.writes.single() as AapsWrite.Bolus
        assertEquals(1.1, write.amountUnits, 1e-9)
        assertEquals("NORMAL", write.type)
        assertEquals(tenOClock * 1000L, write.timestampMs)
    }

    @Test
    fun `a record with nothing asked and a little delivered is a bolus given on the pump too`() {
        past()
        // A cancel sent while no bolus runs: the pump leaves "asked 0, delivered 0.05" and counts it.
        val found = settle(answerWith(record(tenOClock, 0, 2)))

        val write = found.writes.single() as AapsWrite.Bolus
        assertEquals(0.05, write.amountUnits, 1e-9)
        assertEquals("NORMAL", write.type)
        assertEquals(tenOClock * 1000L, write.timestampMs)
    }

    @Test
    fun `a record of what we ordered takes our command, one of another amount before it is a bolus given on the pump`() {
        past()
        val acceptedAt = tenOClock * 1000L + 5_000L
        account.commandAccepted(acceptedAt, 0.4, "SMB")
        // Two new records, newest first as the pump answers: our 0.4 U over 0.3 U given on the pump just before it.
        val answer = listOf(record(tenOClock, 16, 16), record(tenOClock, 12, 12)) + (1..5).map { record(tenOClock - it * 60L, 20, 20) }
        val added = journal.take(answer, offsetMs, nowMs)!!

        val rows = settle(added).writes.filterIsInstance<AapsWrite.Bolus>()

        assertEquals(2, rows.size)
        assertEquals(0.3, rows[0].amountUnits, 1e-9)
        assertEquals("NORMAL", rows[0].type)
        assertEquals(tenOClock * 1000L, rows[0].timestampMs)
        assertEquals(0.4, rows[1].amountUnits, 1e-9)
        assertEquals("SMB", rows[1].type)
        assertEquals(acceptedAt, rows[1].timestampMs)
        assertEquals(7L, store.actions(serial).single().recordSeq)
    }

    @Test
    fun `one sound record of another dose after our command is a bolus of its own - as it says, our command finds no record`() {
        past()
        val acceptedAt = tenOClock * 1000L + 5_000L
        val ours = account.commandAccepted(acceptedAt, 0.8, "SMB")
        account.finished(ours, 0.8, completed = true)
        // The only new record asks 0.3 U: it can be read, so it is written as it says.
        val found = settle(answerWith(record(tenOClock, 12, 12)))

        val row = found.writes.single() as AapsWrite.Bolus
        assertEquals(0.3, row.amountUnits, 1e-9)
        assertEquals("NORMAL", row.type)
        assertEquals(tenOClock * 1000L, row.timestampMs)
        assertNull(found.faultyRowId)
        assertEquals(ours.id, account.closeAll().single().id)
    }

    @Test
    fun `two records no command took and one command of ours - the records are written as they are`() {
        past()
        account.commandAccepted(tenOClock * 1000L + 5_000L, 0.8, "SMB")
        val answer = listOf(record(tenOClock, 16, 16), record(tenOClock, 12, 12)) + (1..5).map { record(tenOClock - it * 60L, 20, 20) }
        val found = settle(journal.take(answer, offsetMs, nowMs)!!)

        val rows = found.writes.filterIsInstance<AapsWrite.Bolus>()
        assertEquals(0.3, rows[0].amountUnits, 1e-9)
        assertEquals(0.4, rows[1].amountUnits, 1e-9)
        assertNull(found.faultyRowId)
        assertEquals(1, account.closeAll().size)
    }

    @Test
    fun `a command with no record next to a later one of ours - ours gets its row, the answer closes the other`() {
        past()
        // The pump took 0.65 U and gave nothing; our 0.2 U came later and has its record.
        val silent = account.commandAccepted(tenOClock * 1000L - 6 * 60_000L, 0.65, "SMB")
        account.finished(silent, 0.0, completed = false)
        val acceptedAt = tenOClock * 1000L + 17_000L
        account.commandAccepted(acceptedAt, 0.2, "SMB")

        val found = settle(answerWith(record(tenOClock, 8, 8)))

        val write = found.writes.single() as AapsWrite.Bolus
        assertEquals(0.2, write.amountUnits, 1e-9)
        assertEquals(acceptedAt, write.timestampMs)
        assertEquals(silent.id, account.closeAll().single().id)
        assertTrue(store.actions(serial).isEmpty())
    }

    @Test
    fun `a record with an extended part gets one ordinary row with all the pump delivered, the loop runs on`() {
        past()
        // 1.0 U immediate and 0.1 U extended, both delivered.
        val found = settle(answerWith(record(tenOClock, 40, 40, extendedRaw = 4)))

        val bolus = found.writes.single() as AapsWrite.Bolus
        assertEquals(1.1, bolus.amountUnits, 1e-9)
        assertEquals("NORMAL", bolus.type)
        assertEquals(tenOClock * 1000L, bolus.timestampMs)
    }
}
