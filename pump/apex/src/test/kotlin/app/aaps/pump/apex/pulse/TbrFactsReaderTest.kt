package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexFinishedTbr
import app.aaps.pump.apex.protocol.ApexFinishedTbrAnswer
import app.aaps.pump.apex.protocol.ApexVersion
import app.aaps.pump.apex.pulse.TbrFrames.finished
import app.aaps.pump.apex.pulse.TbrFrames.key
import app.aaps.pump.apex.state.ApexAbilities
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails

/** The last finished temporary basal, read when the pump has it; nothing sent when it does not. */
class TbrFactsReaderTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private val manager: ApexManager = mock(ApexManager::class.java)
    private val pumpState: ApexPumpState = mock(ApexPumpState::class.java)
    private val reader = TbrFactsReader(AAPSLoggerTest(), manager, store, pumpState)

    private fun pumpHas(abilities: ApexAbilities) {
        doReturn(ApexVersion(listOf(1, 1, 1), 1, 0, emptyList())).`when`(pumpState).version
        ApexAbilities.store(AAPSLoggerTest(), store, serial, "1.1.1", abilities)
    }

    @Test
    fun `the pump has it - it is read and handed on`() {
        doReturn(ApexFinishedTbrAnswer(ApexFinishedTbr.decode(finished(9, 0, 9, 17, rateRaw = 60)))).`when`(manager).readFinishedTbr()
        val facts = reader.read(serial)!!
        assertTrue(facts.endAsked)
        assertEquals(key(9, 17), facts.end!!.endClockSeconds)
        assertEquals(listOf("readFinishedTbr"), mockingDetails(manager).invocations.map { it.method.name })
    }

    @Test
    fun `none finished since a reset - read, no end`() {
        doReturn(ApexFinishedTbrAnswer(null)).`when`(manager).readFinishedTbr()
        val facts = reader.read(serial)!!
        assertTrue(facts.endAsked)
        assertNull(facts.end)
    }

    @Test
    fun `a read that got no sound answer gives no facts`() {
        doReturn(null).`when`(manager).readFinishedTbr()
        assertNull(reader.read(serial))
    }

    @Test
    fun `the pump does not have it - nothing is sent, nothing is known`() {
        pumpHas(ApexAbilities(finishedTbr = false))
        val facts = reader.read(serial)!!
        assertFalse(facts.endAsked)
        assertNull(facts.end)
        assertTrue(mockingDetails(manager).invocations.isEmpty())
    }
}
