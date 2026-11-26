package app.aaps.pump.apex.basal

import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexFinishedTbr
import app.aaps.pump.apex.protocol.ApexFinishedTbrAnswer
import app.aaps.pump.apex.basal.TbrFrames.finished
import app.aaps.pump.apex.basal.TbrFrames.key
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails

/** The last finished temporary basal, read when the gate says so. */
class TbrFactsReaderTest {

    private val manager: ApexManager = mock(ApexManager::class.java)
    private val reader = TbrFactsReader(AAPSLoggerTest(), manager)

    @Test
    fun `the pump has it - it is read and handed on`() {
        doReturn(ApexFinishedTbrAnswer(ApexFinishedTbr.decode(finished(9, 0, 9, 17, rateRaw = 60)))).`when`(manager).readFinishedTbr()
        val facts = reader.read()!!
        assertTrue(facts.endAsked)
        assertEquals(key(9, 17), facts.end!!.endClockSeconds)
        assertEquals(listOf("readFinishedTbr"), mockingDetails(manager).invocations.map { it.method.name })
    }

    @Test
    fun `none finished since a reset - read, no end`() {
        doReturn(ApexFinishedTbrAnswer(null)).`when`(manager).readFinishedTbr()
        val facts = reader.read()!!
        assertTrue(facts.endAsked)
        assertNull(facts.end)
    }

    @Test
    fun `a read that got no sound answer gives no facts`() {
        doReturn(null).`when`(manager).readFinishedTbr()
        assertNull(reader.read())
    }
}
