package app.aaps.pump.apex.account.bolus

import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.bolus.BolusRecordFrames.record
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.ActionKind
import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.protocol.ApexProtocol
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The plain path: the record of our bolus becomes its row. */
class BolusAccountTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private val journal = JournalSnapshot(store, serial, SnapshotKind.BOLUS)
    private val account = BolusAccount(store, serial)

    /** The pump's clock stands at UTC in these tests: a key is the moment in seconds. */
    private val offsetMs = 0L

    private val tenOClock = 1_760_000_000L / 60 * 60 // a whole minute
    private val nowMs = tenOClock * 1000L + 90_000L

    /** The past of the pump: [count] records a minute apart, newest first as the pump answers, taken in on a first read. */
    private fun pastOf(count: Int): JournalSnapshot.Added {
        val past = (1..count).map { record(tenOClock - it * 60L, 20, 20) }
        return journal.take(past, offsetMs, nowMs)!!
    }

    /** A short answer: [top] over the five records of the past. */
    private fun answerWith(top: JournalSnapshot.Answered): JournalSnapshot.Added {
        val answer = listOf(top) + (1..5).map { record(tenOClock - it * 60L, 20, 20) }
        return journal.take(answer, offsetMs, nowMs)!!
    }

    @Test
    fun `the whole journal lays over the copy of a short answer and a short answer over the copy of the whole journal`() {
        pastOf(5)
        // The whole journal, under its own object byte: one record new over the five of the copy.
        val whole = listOf(record(tenOClock, 20, 20, objectType = ApexProtocol.HistoryOpcode.BOLUS_HISTORY)) +
            (1..5).map { record(tenOClock - it * 60L, 20, 20, objectType = ApexProtocol.HistoryOpcode.BOLUS_HISTORY) }
        val added = journal.take(whole, offsetMs, nowMs)!!
        assertFalse(added.firstRead)
        assertEquals(1, added.records.size)
        assertEquals(6L, journal.topSeq())
        // Then a short answer, one record newer, over the copy taken from the whole journal.
        val short = listOf(record(tenOClock + 60L, 20, 20), record(tenOClock, 20, 20)) + (1..4).map { record(tenOClock - it * 60L, 20, 20) }
        val again = journal.take(short, offsetMs, nowMs)!!
        assertEquals(1, again.records.size)
        assertEquals(7L, journal.topSeq())
    }

    @Test
    fun `the first read keeps the answer and writes nothing`() {
        val writes = account.settle(pastOf(5), notBeforeMs = 0L, nowMs = nowMs)
        assertTrue(writes.isEmpty())
        assertEquals(5, store.snapshot(serial, SnapshotKind.BOLUS)!!.frames.size)
        assertEquals(5L, journal.topSeq())
    }

    @Test
    fun `our bolus is the first new record after the command, with the second of the command`() {
        pastOf(5)
        val acceptedAt = tenOClock * 1000L + 17_000L
        account.commandAccepted(acceptedAt, 0.5, "SMB")

        // The record of a 0.5 U bolus, stamped in the minute of the command, over the top five of the past.
        val top = record(tenOClock, 20, 20)
        val added = answerWith(top)
        assertTrue(account.expects(added, nowMs))
        val write = account.settle(added, notBeforeMs = 0L, nowMs = nowMs).single() as AapsWrite.Bolus

        assertEquals(0.5, write.amountUnits, 1e-9)
        assertEquals("SMB", write.type)
        assertEquals(acceptedAt, write.timestampMs)
        val action = store.actions(serial).single()
        assertTrue(action.done)
        assertEquals(6L, action.recordSeq)
        assertEquals(write.pumpId, store.state(serial, BolusAccount.STATE_ID_BASE)!!.toLong() + 6L)
        // The bolus is kept for the next heartbeat's daily statistics.
        assertEquals(listOf(0.5), account.settledSinceHeartbeat())
        // The answer is the new last one.
        val kept = store.snapshot(serial, SnapshotKind.BOLUS)!!
        assertEquals(6, kept.frames.size)
        assertArrayEquals(top.frame.raw, kept.frames.first())
    }

    @Test
    fun `two boluses between heartbeats are both kept, with what each delivered`() {
        pastOf(5)
        account.commandAccepted(tenOClock * 1000L + 10_000L, 0.5, "SMB")
        account.commandAccepted(tenOClock * 1000L + 40_000L, 0.8, "NORMAL")
        // The 0.8 U bolus was stopped at 0.15 U; its record comes over the 0.5 U one.
        val answer = listOf(record(tenOClock + 60L, 32, 6), record(tenOClock, 20, 20)) + (1..5).map { record(tenOClock - it * 60L, 20, 20) }
        val added = journal.take(answer, offsetMs, nowMs)!!
        assertTrue(account.expects(added, nowMs))
        val writes = account.settle(added, notBeforeMs = 0L, nowMs = nowMs)
        assertEquals(2, writes.size)
        // Oldest first, as the records were settled.
        val kept = account.settledSinceHeartbeat()
        assertEquals(2, kept.size)
        assertEquals(0.5, kept[0], 1e-9)
        assertEquals(0.15, kept[1], 1e-9)
        account.clearSettledSinceHeartbeat()
        assertTrue(account.settledSinceHeartbeat().isEmpty())
    }

    @Test
    fun `a record nobody here asked for is not expected`() {
        pastOf(5)
        assertFalse(account.expects(answerWith(record(tenOClock, 44, 44)), nowMs))
    }

    @Test
    fun `a command with no record stays off the plain path's way, and closing the answer drops every command`() {
        pastOf(5)
        // The pump took 0.65 U and gave nothing; our 0.2 U came later and has its record.
        val silent = account.commandAccepted(tenOClock * 1000L - 6 * 60_000L, 0.65, "SMB")
        account.finished(silent, 0.0, completed = false)
        account.commandAccepted(tenOClock * 1000L + 17_000L, 0.2, "SMB")
        val added = answerWith(record(tenOClock, 8, 8))

        assertTrue(account.expects(added, nowMs))
        assertEquals(0.2, (account.settle(added, notBeforeMs = 0L, nowMs).single() as AapsWrite.Bolus).amountUnits, 1e-9)
        assertEquals(silent.id, account.closeAll().single().id)
        assertTrue(store.actions(serial).isEmpty())
    }

    @Test
    fun `a record that asks for another amount than our bolus is not expected`() {
        pastOf(5)
        account.commandAccepted(tenOClock * 1000L + 5_000L, 0.4, "SMB")
        assertFalse(account.expects(answerWith(record(tenOClock, 12, 12)), nowMs))
    }

    @Test
    fun `a record with an extended part is not expected even when our bolus waits`() {
        pastOf(5)
        account.commandAccepted(tenOClock * 1000L + 5_000L, 1.0, "NORMAL")
        assertFalse(account.expects(answerWith(record(tenOClock, 40, 40, extendedRaw = 4)), nowMs))
    }

    @Test
    fun `the command notes where the journal stood, and the end of the bolus what the pump reported`() {
        pastOf(3)
        val action = account.commandAccepted(tenOClock * 1000L, 1.0, "NORMAL")
        assertEquals(ActionKind.BOLUS, action.kind)
        assertEquals(3L, action.journalTopSeq)
        account.finished(action, 0.975, completed = true)
        val kept = store.actions(serial).single()
        assertTrue(kept.finished)
        assertEquals(0.975, kept.seenUnits!!, 1e-9)
    }
}
