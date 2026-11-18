package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.account.bolus.JournalBench.Companion.at
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.oldCell
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.ten
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.undated
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.deep.BrokenRecordDeep
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/** A bolus journal read at a heartbeat while a cell flickers: what the reads hand on to the settling. */
class FlickerReadTest {

    private val manager = mock(ApexManager::class.java)
    private val reader = PictureReader(AAPSLoggerTest(), manager, FakeStore(), mock(ApexPumpState::class.java), mock(TbrFactsReader::class.java), mock(ApexTrace::class.java))
    private var reads = 0

    private fun answerOf(records: List<JournalSnapshot.Answered>) =
        ApexJournalAnswer(records.map { ApexRead(it.frame, ApexBolusRecord.decode(it.frame)!!) }, records.size)

    /** The journal read up to three times, the cell on top showing [cells] one after another. */
    private fun read(vararg cells: JournalSnapshot.Answered) =
        reader.readJournal("bolus journal", { answerOf(listOf(cells[minOf(reads++, cells.size - 1)]) + ten().take(9)) }, BrokenRecordDeep::brokenBolus)

    @Test
    fun `broken once, then the record - the record is handed on after two reads`() {
        val read = read(undated(20), at(0, 20))!!
        assertEquals(2, reads)
        assertFalse(read.repeatedBroken)
        assertFalse(read.answer.entries.first().record.looksBroken)
    }

    @Test
    fun `broken, broken another way, then an old record that stays - the old record is handed on`() {
        val read = read(undated(20), undated(16), oldCell())!!
        assertEquals(3, reads)
        assertEquals(oldCell().frame.raw.toList(), read.answer.entries.first().frame.raw.toList())
    }

    @Test
    fun `broken the same way three times - handed on as what the pump holds`() {
        val read = read(undated(20))!!
        assertEquals(3, reads)
        assertTrue(read.repeatedBroken)
    }

    @Test
    fun `an answer with no record at all is silence - asked again, nothing is handed on after three reads, the pump is taken as not working`() {
        assertNull(reader.readJournal("bolus journal", { reads++; answerOf(emptyList()) }, BrokenRecordDeep::brokenBolus))
        assertEquals(3, reads)
        verify(manager).pumpNotWorking(anyString())
    }

    @Test
    fun `an answer with a record - the pump works`() {
        assertNotNull(reader.readJournal("bolus journal", { reads++; answerOf(ten()) }, BrokenRecordDeep::brokenBolus))
        verify(manager, never()).pumpNotWorking(anyString())
    }

    @Test
    fun `no answer, then a sound one - the sound one is handed on after two reads`() {
        val sound = answerOf(ten())
        assertEquals(sound, reader.readJournal("bolus journal", { reads++; if (reads == 1) null else sound }, BrokenRecordDeep::brokenBolus)?.answer)
        assertEquals(2, reads)
    }

    @Test
    fun `broken, no answer, broken the same way - not every read was answered, the heartbeat is skipped`() {
        val broken = answerOf(ten().take(3) + undated(16) + ten().drop(3).take(6))
        assertNull(reader.readJournal("bolus journal", { reads++; if (reads == 2) null else broken }, BrokenRecordDeep::brokenBolus))
        assertEquals(3, reads)
    }

    @Test
    fun `broken three times, each another way - nothing is handed on, the heartbeat is skipped`() {
        assertNull(read(undated(20), undated(16), undated(12)))
        assertEquals(3, reads)
    }

    /** The copy kept: ten records with a cell of no date at the fourth place, newest first. */
    private val copy = ten().take(3) + undated(16) + ten().drop(3).take(6)
    private val kept = copy.map { it.frame.raw }

    @Test
    fun `a broken-looking cell that comes as the copy keeps it is not read again for - one read`() {
        val answer = answerOf(listOf(at(0, 20)) + copy.take(9))
        val read = reader.readJournal("bolus journal", { reads++; answer }) { BrokenRecordDeep.brokenBolus(it, kept) }!!
        assertEquals(1, reads)
        assertFalse(read.repeatedBroken)
        assertNotNull(BrokenRecordDeep.brokenBolus(answer), "with no copy the whole answer is checked")
    }

    @Test
    fun `a broken new record above the copy is read again for`() {
        assertNotNull(BrokenRecordDeep.brokenBolus(answerOf(listOf(undated(20)) + copy.take(9)), kept))
    }

    @Test
    fun `an old cell that flickers is read again for, and the sound read is handed on`() {
        val sound = ten()
        val flickered = listOf(at(0, 20)) + sound.take(4) + undated(8) + sound.drop(5).take(4)
        val answers = listOf(answerOf(flickered), answerOf(listOf(at(0, 20)) + sound.take(9)))
        val read = reader.readJournal("bolus journal", { answers[minOf(reads++, 1)] }) { BrokenRecordDeep.brokenBolus(it, sound.map { a -> a.frame.raw }) }!!
        assertEquals(2, reads)
        assertFalse(read.answer.entries.any { it.record.looksBroken })
    }


    @Test
    fun `an answer that does not lay over the copy is checked whole`() {
        assertNotNull(BrokenRecordDeep.brokenBolus(answerOf(listOf(undated(20)) + ten(minutesBeforeTwo = 300, raw = 40).take(9)), kept))
    }
}
