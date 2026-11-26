package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.bolus.BolusRecordFrames.record
import app.aaps.pump.apex.store.FakeStore
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The plain path: our picture of each bolus, its row at once, and the pairing of a record with it. */
class BolusAccountTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private val journal = BolusCells(store, serial)
    private val account = BolusAccount(store, serial)

    /** The pump's clock stands at UTC in these tests: a key is the moment in seconds. */
    private val offsetMs = 0L

    private val tenOClock = 1_760_000_000L / 60 * 60 // a whole minute
    private val nowMs = tenOClock * 1000L + 90_000L

    /** The past of the pump: [count] records a minute apart, newest first as the pump answers, taken in on a first read. */
    private fun pastOf(count: Int): BolusCells.Added {
        val past = (1..count).map { record(tenOClock - it * 60L, 20, 20) }
        return journal.restart(past, offsetMs, nowMs)
    }

    /** A short answer: [top] over the five records of the past. */
    private fun answerWith(vararg top: BolusCells.Answered): BolusCells.Added {
        val answer = top.toList() + (1..5).map { record(tenOClock - it * 60L, 20, 20) }
        return journal.take(answer, offsetMs, nowMs)!!
    }

    @Test
    fun `the whole journal lays over the copy of a short answer and a short answer over the copy of the whole journal`() {
        pastOf(5)
        // The whole journal, under its own object byte: one record new over the five of the copy.
        val whole = listOf(record(tenOClock, 20, 20, objectType = ApexProtocol.HistoryOpcode.BOLUS_HISTORY)) +
            (1..5).map { record(tenOClock - it * 60L, 20, 20, objectType = ApexProtocol.HistoryOpcode.BOLUS_HISTORY) }
        val added = journal.take(whole, offsetMs, nowMs)!!
        assertEquals(1, added.records.size)
        assertEquals(6L, journal.topSeq())
        // Then a short answer, one record newer, over the copy taken from the whole journal.
        val short = listOf(record(tenOClock + 60L, 20, 20), record(tenOClock, 20, 20)) + (1..4).map { record(tenOClock - it * 60L, 20, 20) }
        val again = journal.take(short, offsetMs, nowMs)!!
        assertEquals(1, again.records.size)
        assertEquals(7L, journal.topSeq())
    }

    @Test
    fun `the pump accepts our bolus - the picture opens where the journal stood, the row goes at once at the ordered dose`() {
        pastOf(3)
        val acceptedAt = tenOClock * 1000L + 17_000L
        val (action, row) = account.commandAccepted(acceptedAt, 1.0, "NORMAL")
        assertEquals(3L, action.journalTopSeq)
        assertEquals(AapsWrite.EarlyBolus(acceptedAt, acceptedAt, 1.0, "NORMAL"), row)
        assertTrue(action.seenNothing)
        assertFalse(action.lastWordSeen)
    }

    @Test
    fun `the end of the bolus is kept in the picture - frames, the cancel asked and accepted, the completion`() {
        pastOf(3)
        val action = account.commandAccepted(tenOClock * 1000L, 1.0, "NORMAL").first
        account.progress(action, 0.3)
        val ended = account.finished(account.openActions().single(), 0.35, frames = 7, cancelPressed = true, cancelAccepted = true, completed = false)
        val kept = account.openActions().single()
        assertEquals(ended, kept)
        assertEquals(0.35, kept.seenUnits!!, 1e-9)
        assertEquals(7, kept.frames)
        assertTrue(kept.cancelPressed && kept.cancelAccepted && !kept.completed)
        assertTrue(kept.lastWordSeen)
    }

    @Test
    fun `cancelled with a frame seen - the row shows the last frame at once, nothing seen or all given - the row stays`() {
        val stopped = account.commandAccepted(tenOClock * 1000L, 1.0, "NORMAL").first
        val shown = account.seenRow(account.finished(stopped, 0.4, frames = 3, cancelPressed = true, cancelAccepted = true, completed = false))!!
        assertEquals(0.4, shown.amountUnits, 1e-9)
        assertEquals(stopped.tempId, shown.tempId)
        assertNull(shown.pumpId)

        val unanswered = account.commandAccepted(tenOClock * 1000L + 1_000L, 1.0, "NORMAL").first
        assertNull(account.seenRow(account.finished(unanswered, 0.4, frames = 3, cancelPressed = true, cancelAccepted = false, completed = false)), "a cancel the pump did not accept")
        val blind = account.commandAccepted(tenOClock * 1000L + 2_000L, 1.0, "NORMAL").first
        assertNull(account.seenRow(account.finished(blind, 0.0, frames = 0, cancelPressed = true, cancelAccepted = true, completed = false)), "no frame seen")
        val whole = account.commandAccepted(tenOClock * 1000L + 3_000L, 1.0, "NORMAL").first
        assertNull(account.seenRow(account.finished(whole, 1.0, frames = 20, cancelPressed = false, cancelAccepted = false, completed = true)))
        val noDose = account.commandAccepted(tenOClock * 1000L + 4_000L, 1.0, "NORMAL").first
        assertNull(account.seenRow(account.finished(noDose, 0.0, frames = 0, cancelPressed = false, cancelAccepted = false, completed = true)), "a completion with no dose in it")
    }

    @Test
    fun `a record pairs with our picture by what was ordered and what was written - the oldest picture first`() {
        pastOf(5)
        val first = account.finished(account.commandAccepted(tenOClock * 1000L + 10_000L, 0.5, "SMB").first, 0.5, 10, false, false, true)
        val second = account.finished(account.commandAccepted(tenOClock * 1000L + 40_000L, 0.8, "NORMAL").first, 0.15, 3, true, true, false)
        // The 0.8 U bolus was stopped at 0.15 U; its record comes over the 0.5 U one.
        val added = answerWith(record(tenOClock + 60L, 32, 6), record(tenOClock, 20, 20))
        val open = account.openActions().toMutableList()
        val pairs = account.pair(added.records, open)
        assertEquals(first.id, pairs[0]!!.id)
        assertEquals(second.id, pairs[1]!!.id)
        assertTrue(open.isEmpty())
        val row = account.row(ApexBolusRecord.decode(added.records[1].frame)!!, pairs[1]!!, journal.keys()[added.records[1].seq], notBeforeMs = 0L)
        assertEquals(journal.keys()[added.records[1].seq], row.pumpId)
        assertEquals(second.acceptedAtMs, row.timestampMs)
        assertEquals(0.15, row.amountUnits, 1e-9)
        assertEquals("NORMAL", row.type)
        assertEquals(second.tempId, row.tempId)
    }

    @Test
    fun `a record one step short of the last frame of a stopped bolus pairs, three steps does not`() {
        pastOf(5)
        account.finished(account.commandAccepted(tenOClock * 1000L, 1.0, "NORMAL").first, 0.4, 8, true, true, false)
        assertSame(null, account.pair(answerWith(record(tenOClock, 40, 13)).records, account.openActions().toMutableList()).single(), "0.325 against 0.4")
        val near = account.pair(listOf(answerWith(record(tenOClock + 60, 40, 15), record(tenOClock, 40, 13)).records.last()), account.openActions().toMutableList())
        assertEquals(account.openActions().single().id, near.single()!!.id, "0.375 against 0.4")
    }

    @Test
    fun `a record of another dose, or one with an extended part, pairs with nothing`() {
        pastOf(5)
        account.commandAccepted(tenOClock * 1000L + 5_000L, 0.4, "SMB")
        assertNull(account.pair(answerWith(record(tenOClock, 12, 12)).records, account.openActions().toMutableList()).single())
        assertNull(account.pair(answerWith(record(tenOClock + 60, 16, 16, extendedRaw = 4), record(tenOClock, 12, 12)).records.take(1), account.openActions().toMutableList()).single())
    }

    @Test
    fun `a record that delivered nothing is nobody's - a faulty cell, it does not pair`() {
        pastOf(5)
        account.commandAccepted(tenOClock * 1000L, 0.5, "SMB")
        assertNull(account.pair(answerWith(record(tenOClock, 20, 0)).records, account.openActions().toMutableList()).single())
    }

    @Test
    fun `a record older than the place of our picture pairs with nothing`() {
        val added = pastOf(5)
        account.commandAccepted(tenOClock * 1000L, 0.5, "SMB")
        assertNull(account.pair(added.records.take(1), account.openActions().toMutableList()).single())
    }

    @Test
    fun `an equal record stamped minutes before our bolus is not ours, though it stands above our place`() {
        pastOf(5)
        // Another's 0.2 U at 10:00 was not read before our 0.2 U at 10:05:41: both records stand above our place.
        val ours = account.commandAccepted(tenOClock * 1000L + 341_000L, 0.2, "SMB").first
        val pairs = account.pair(answerWith(record(tenOClock + 300L, 8, 8), record(tenOClock, 8, 8)).records, account.openActions().toMutableList())
        assertNull(pairs[0], "the record of 10:00 is another's")
        assertEquals(ours.id, pairs[1]!!.id)
    }

    @Test
    fun `our bolus has its cell from the moment the pump accepted it, with no record yet`() {
        pastOf(3)
        val action = account.commandAccepted(tenOClock * 1000L, 0.5, "SMB").first
        val cell = store.cells(serial).single { it.action != null }
        assertEquals(action.id, cell.id)
        assertNull(cell.seq)
        assertTrue(cell.inAaps && cell.open)
    }
}
