package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A journal answer whose count of records is 0 is an empty journal: a state, not a silence. */
class ReadAgainTest {

    private val notWorking = ArrayList<String>()
    private var reads = 0

    private fun journal(answer: ApexJournalAnswer<Int>?) =
        ReadAgain.journal(AAPSLoggerTest(), "bolus journal", { reads++; answer }, { notWorking.add(it) }) { emptyList() }

    @Test
    fun `a count of 0 is an empty journal - taken at once, the pump works`() {
        val read = journal(ApexJournalAnswer(emptyList(), recordCount = 0))
        assertNotNull(read)
        assertTrue(read!!.answer.empty)
        assertEquals(1, reads)
        assertTrue(notWorking.isEmpty())
    }

    private fun record(value: Int) = ApexRead(ApexResponseFrame(byteArrayOf(0xAA.toByte(), 0x12, 0x64, 0xA3.toByte(), 0x26, value.toByte(), 0, 0)), value)

    @Test
    fun `an answer with a record missing is read again, and a whole one after it is taken`() {
        val holed = ApexJournalAnswer((1..9).map { record(it) }, recordCount = 100, lost = 1)
        val whole = ApexJournalAnswer((0..9).map { record(it) }, recordCount = 100)
        val answers = ArrayDeque(listOf(holed, whole))
        val read = ReadAgain.journal(AAPSLoggerTest(), "daily statistics", { reads++; answers.removeFirst() }, { notWorking.add(it) }) { emptyList() }
        assertEquals(2, reads)
        assertEquals(10, read!!.answer.entries.size)
        assertTrue(notWorking.isEmpty())
    }

    @Test
    fun `a record missing every time - not read this time, the pump works`() {
        assertNull(journal(ApexJournalAnswer((1..9).map { record(it) }, recordCount = 100, lost = 1)))
        assertEquals(3, reads)
        assertTrue(notWorking.isEmpty())
    }

    @Test
    fun `a count above 0 with no record read brings nothing - three such reads, the pump is not working`() {
        assertNull(journal(ApexJournalAnswer(emptyList(), recordCount = 128)))
        assertEquals(3, reads)
        assertEquals(1, notWorking.size)
    }

    /** The daily statistics read three times, the answers one after another; the record 0 looks broken. */
    private fun statistics(vararg answers: ApexJournalAnswer<Int>) =
        ReadAgain.journal(AAPSLoggerTest(), "daily statistics", { answers[minOf(reads++, answers.size - 1)] }, { notWorking.add(it) }) { a -> a.entries.filter { it.record == 0 } }

    @Test
    fun `a broken-looking record the same at all three reads is what the pump holds - for every journal, the statistics too`() {
        val read = statistics(ApexJournalAnswer(listOf(record(0), record(1)), recordCount = 2))
        assertEquals(3, reads)
        assertTrue(read!!.repeatedBroken)
    }

    @Test
    fun `the same twice, then another way - not read this time`() {
        val broken = ApexJournalAnswer(listOf(record(0), record(1)), recordCount = 2)
        assertNull(statistics(broken, broken, ApexJournalAnswer(listOf(record(0), record(2)), recordCount = 2)))
        assertEquals(3, reads)
    }
}
