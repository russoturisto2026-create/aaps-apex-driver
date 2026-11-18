package app.aaps.pump.apex.state

import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexResponseParser
import app.aaps.pump.apex.protocol.ApexVersion
import app.aaps.pump.apex.protocol.CrcUtilTest.Companion.hexToBytes
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import app.aaps.pump.apex.link.ApexBLE

/**
 * An object is stored as missing only after two rounds of silence with Status V1 answering after
 * each. Any frame of the object says it is there.
 */
class AbilityProbeTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private val firmware = "1.1.1"
    private val manager: ApexManager = mock(ApexManager::class.java)
    private val state = ApexPumpState().apply {
        serialNumber = serial
        version = ApexVersion.decode(ApexResponseParser().feed(hexToBytes("AA 10 00 A3 31 00 00 00 00 01 01 01 04 0C 0C 7E"))[0])
    }
    private val probe = AbilityProbe(AAPSLoggerTest(), manager, store, state, mock(ApexBLE::class.java), mock(ApexTrace::class.java))
    private val lastCommand = ApexProtocol.ReadOpcode.TBR_ACTIVE
    private val statusV2 = ApexProtocol.ReadOpcode.STATUS_V2

    /** Every object answers, except what a test says otherwise. */
    private fun allAnswer() {
        doReturn(true).`when`(manager).answersRead(lastCommand)
        doReturn(true).`when`(manager).answersRead(ApexProtocol.ReadOpcode.TBR_FINISHED)
        doReturn(true).`when`(manager).answersRead(statusV2)
        doReturn(true).`when`(manager).answersLatestRecords(ApexProtocol.HistoryOpcode.LATEST_DAILY_STATS)
        doReturn(true).`when`(manager).answersLatestRecords(ApexProtocol.HistoryOpcode.TBR_HISTORY)
        doReturn(true).`when`(manager).answersLastBolusRecord()
    }

    private fun statusAnswers() = doReturn(mock(ApexPumpState.StatusCard::class.java)).`when`(manager).readStatus()

    @Test
    fun `objects that answer at once are present and Status V1 is not read`() {
        allAnswer()
        probe.probe()
        assertEquals(ApexAbilities(), ApexAbilities.stored(store, serial, firmware))
        verify(manager, never()).readStatus()
    }

    @Test
    fun `an object silent once and present the second time is present`() {
        allAnswer()
        doReturn(false, true).`when`(manager).answersRead(statusV2)
        statusAnswers()
        probe.probe()
        assertEquals(ApexAbilities(), ApexAbilities.stored(store, serial, firmware))
        verify(manager, times(1)).readStatus()
    }

    @Test
    fun `an object silent twice with Status V1 answering after each is missing and stored so`() {
        allAnswer()
        doReturn(false).`when`(manager).answersRead(statusV2)
        statusAnswers()
        probe.probe()
        assertEquals(ApexAbilities(statusV2 = false), ApexAbilities.stored(store, serial, firmware))
        verify(manager, times(2)).readStatus()
    }

    @Test
    fun `nothing is stored when Status V1 is silent after the first silence`() {
        allAnswer()
        doReturn(false).`when`(manager).answersLatestRecords(ApexProtocol.HistoryOpcode.LATEST_DAILY_STATS)
        doReturn(null).`when`(manager).readStatus()
        probe.probe()
        assertNull(ApexAbilities.stored(store, serial, firmware))
    }

    @Test
    fun `nothing is stored when Status V1 is silent after the second silence`() {
        allAnswer()
        doReturn(false).`when`(manager).answersRead(statusV2)
        doReturn(mock(ApexPumpState.StatusCard::class.java), null).`when`(manager).readStatus()
        probe.probe()
        assertNull(ApexAbilities.stored(store, serial, firmware))
        verify(manager, times(2)).readStatus()
    }

    @Test
    fun `the screen gets what the firmware has, the objects the account does not use among them`() {
        allAnswer()
        doReturn(false).`when`(manager).answersLatestRecords(ApexProtocol.HistoryOpcode.TBR_HISTORY)
        statusAnswers()
        probe.probe()
        val found = state.diagnosis!!
        assertEquals(true, found.lastBolusRecord)
        assertEquals(false, found.tbrHistory)
        assertEquals(true, found.dayRecord)
        assertEquals(true, found.passwordProtocol)
    }

    @Test
    fun `abilities stored are asked again at the next link setup - an object taken as missing is found again`() {
        ApexAbilities.store(AAPSLoggerTest(), store, serial, firmware, ApexAbilities(statusV2 = false))
        allAnswer()
        probe.probe()
        assertEquals(ApexAbilities(), ApexAbilities.stored(store, serial, firmware))
    }
}
