package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.basal.BasalPeriodKeeper
import app.aaps.pump.apex.basal.TbrFactsReader
import app.aaps.pump.apex.bolus.JournalBench.Companion.at
import app.aaps.pump.apex.bolus.JournalBench.Companion.oldCell
import app.aaps.pump.apex.bolus.JournalBench.Companion.ten
import app.aaps.pump.apex.bolus.BolusRecordFrames
import app.aaps.pump.apex.bolus.JournalBench
import app.aaps.pump.apex.bolus.BolusCells
import app.aaps.pump.apex.store.FakeStore
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.protocol.ApexResponseFrame
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
    private val store = FakeStore()
    private val reader = PictureReader(AAPSLoggerTest(), manager, store, mock(ApexPumpState::class.java), mock(TbrFactsReader::class.java), mock(BasalPeriodKeeper::class.java), mock(ApexTrace::class.java))
    private var reads = 0

    /** The driver's own test of a flickering bolus record. */
    private val looksBroken: (ApexJournalAnswer<ApexBolusRecord>) -> List<ApexRead<ApexBolusRecord>> = { BrokenRecord.brokenBolus(it, null) }

    /** The bolus journal read by the rule of reading again, as the heartbeat reads it. */
    private fun journal(read: () -> ApexJournalAnswer<ApexBolusRecord>?) = reader.readJournal("bolus journal", read, looksBroken)

    /** The newest cell read half written, the pump's shapes: the stamp and the order with nothing delivered yet, or zeros but the year. */
    private fun halfWritten(askedRaw: Int = 20) = BolusRecordFrames.record(JournalBench.TWO, askedRaw, 0)
    private fun yearOnly(): BolusCells.Answered {
        val bytes = at(0, 20).frame.raw.copyOf().also { b -> (7..19).forEach { b[it] = 0 }; b[11] = 0x3B }
        val frame = ApexResponseFrame(bytes)
        return BolusCells.Answered(frame, ApexBolusRecord.decode(frame)!!.clockSeconds)
    }

    /** The answer of [records], newest first, each frame with its number in the journal. */
    private fun answerOf(records: List<BolusCells.Answered>) = ApexJournalAnswer(
        records.mapIndexed { i, it ->
            val frame = ApexResponseFrame(it.frame.raw.copyOf().also { b -> b[ApexResponseFrame.INDEX_OFFSET] = i.toByte() })
            ApexRead(frame, ApexBolusRecord.decode(frame)!!)
        }, records.size
    )

    /** The journal read up to three times, the cell on top showing [cells] one after another. */
    private fun read(vararg cells: BolusCells.Answered) =
        journal({ answerOf(listOf(cells[minOf(reads++, cells.size - 1)]) + ten().take(9)) })

    @Test
    fun `broken once, then the record - the record is handed on after two reads`() {
        val read = read(halfWritten(), at(0, 20))!!
        assertEquals(2, reads)
        assertFalse(read.repeatedBroken)
        assertFalse(read.answer.entries.first().record.flickers)
    }

    @Test
    fun `broken, broken another way, then an old record that stays - the old record is handed on`() {
        val read = read(halfWritten(), yearOnly(), oldCell())!!
        assertEquals(3, reads)
        assertEquals(oldCell().frame.raw.toList(), read.answer.entries.first().frame.raw.toList())
    }

    @Test
    fun `broken the same way at all three reads - it does not flicker, handed on as what the pump holds`() {
        val read = read(yearOnly())!!
        assertEquals(3, reads)
        assertTrue(read.repeatedBroken)
    }

    @Test
    fun `broken the same way twice, then another way - nothing is handed on, the heartbeat is skipped`() {
        assertNull(read(yearOnly(), yearOnly(), halfWritten()))
        assertEquals(3, reads)
    }

    @Test
    fun `a faulty cell taken at one heartbeat - the next heartbeat reads the journal once`() {
        val held = answerOf(ten().take(3) + yearOnly() + ten().drop(3).take(6))
        BolusCells(store, "12345678").restart(BolusCells.boluses(held), 0L, at(0, 20).clockSeconds * 1000L)
        val read = reader.readBolusJournal("bolus journal", { reads++; held }, "12345678")!!
        assertEquals(1, reads)
        assertFalse(read.repeatedBroken)
    }

    @Test
    fun `a flickering record the cells already hold is not read again, a new one above them is`() {
        val held = answerOf(ten().take(3) + yearOnly() + ten().drop(3).take(6))
        val kept = held.entries.map { it.frame.raw }
        assertTrue(BrokenRecord.brokenBolus(held, kept).isEmpty())
        val above = answerOf(listOf(halfWritten()) + ten().take(3) + yearOnly() + ten().drop(3).take(5))
        assertEquals(listOf(0), BrokenRecord.brokenBolus(above, kept).map { it.frame.recordIndex })
    }

    @Test
    fun `an answer that declares records and has none read is silence - asked again, nothing is handed on after three reads, the pump is taken as not working`() {
        assertNull(journal({ reads++; ApexJournalAnswer(emptyList<ApexRead<ApexBolusRecord>>(), 128) }))
        assertEquals(3, reads)
        verify(manager).pumpNotWorking(anyString())
    }

    @Test
    fun `an answer with a record - the pump works`() {
        assertNotNull(journal({ reads++; answerOf(ten()) }))
        verify(manager, never()).pumpNotWorking(anyString())
    }

    @Test
    fun `no answer, then a sound one - the sound one is handed on after two reads`() {
        val sound = answerOf(ten())
        assertEquals(sound, journal({ reads++; if (reads == 1) null else sound })?.answer)
        assertEquals(2, reads)
    }

    @Test
    fun `broken, no answer, broken the same way - two answered reads are not enough, the heartbeat is skipped`() {
        val broken = answerOf(ten().take(3) + yearOnly() + ten().drop(3).take(6))
        assertNull(journal { reads++; if (reads == 2) null else broken })
        assertEquals(3, reads)
    }

    @Test
    fun `broken, no answer, no answer - one answered read is not enough, the heartbeat is skipped`() {
        val broken = answerOf(ten().take(3) + yearOnly() + ten().drop(3).take(6))
        assertNull(journal({ reads++; if (reads == 1) broken else null }))
        assertEquals(3, reads)
    }

    @Test
    fun `broken three times, each another way - nothing is handed on, the heartbeat is skipped`() {
        assertNull(read(halfWritten(20), yearOnly(), halfWritten(12)))
        assertEquals(3, reads)
    }

    @Test
    fun `each read with another record flickering - every record taken from the read where it came sound, after two reads`() {
        val first = answerOf(listOf(halfWritten()) + ten().take(9))
        val second = answerOf(listOf(at(0, 20)) + ten().take(2) + yearOnly() + ten().drop(3).take(6))
        val answers = ArrayDeque(listOf(first, second))
        val read = journal({ reads++; answers.removeFirst() })!!
        assertEquals(2, reads)
        assertFalse(read.repeatedBroken)
        assertTrue(read.answer.entries.none { it.record.flickers })
        assertEquals(at(0, 20).frame.raw.drop(6).toList(), read.answer.entries[0].frame.raw.drop(6).toList())
        assertEquals(first.entries[3].frame.raw.toList(), read.answer.entries[3].frame.raw.toList())
    }

    @Test
    fun `a new record between the reads moves the numbers - nothing is taken from the earlier read, the third read is handed on`() {
        val first = answerOf(ten().take(2) + yearOnly() + ten().drop(2).take(7))
        val second = answerOf(listOf(halfWritten()) + ten().take(9))
        val third = answerOf(listOf(at(0, 20)) + ten().take(9))
        val answers = ArrayDeque(listOf(first, second, third))
        val read = journal({ reads++; answers.removeFirst() })!!
        assertEquals(3, reads)
        assertEquals(third, read.answer)
    }
}
