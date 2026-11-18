package app.aaps.pump.apex.deep

import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A journal answer whose count of records is 0 is an empty journal: a state, not a silence. */
class ReadAgainDeepTest {

    private val notWorking = ArrayList<String>()
    private var reads = 0

    private fun journal(answer: ApexJournalAnswer<Int>?) =
        ReadAgainDeep.journal(AAPSLoggerTest(), "bolus journal", { reads++; answer }, { notWorking.add(it) }) { null }

    @Test
    fun `a count of 0 is an empty journal - taken at once, the pump works`() {
        val read = journal(ApexJournalAnswer(emptyList(), recordCount = 0))
        assertNotNull(read)
        assertTrue(read!!.answer.empty)
        assertEquals(1, reads)
        assertTrue(notWorking.isEmpty())
    }

    @Test
    fun `a count above 0 with no record read brings nothing - three such reads, the pump is not working`() {
        assertNull(journal(ApexJournalAnswer(emptyList(), recordCount = 128)))
        assertEquals(3, reads)
        assertEquals(1, notWorking.size)
    }
}
