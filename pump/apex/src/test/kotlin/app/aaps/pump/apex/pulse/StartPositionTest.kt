package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.account.bolus.BolusAccount
import app.aaps.pump.apex.account.bolus.BolusCountMark
import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.protocol.ApexVersion
import app.aaps.pump.apex.state.ApexAbilities
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

/** The start position is taken once, before any work, when none is kept, and never while a bolus of ours waits for its record. */
class StartPositionTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private val firmware = "1.1.1"
    private val manager: ApexManager = mock(ApexManager::class.java)
    private val pumpState: ApexPumpState = mock(ApexPumpState::class.java)
    private val start = StartPosition(AAPSLoggerTest(), manager, store, pumpState, mock(ApexTrace::class.java))
    private val readAtMs = 1_793_714_400_000L

    private fun pumpHas(abilities: ApexAbilities) {
        doReturn(ApexVersion(listOf(1, 1, 1), 1, 0, emptyList())).`when`(pumpState).version
        ApexAbilities.store(AAPSLoggerTest(), store, serial, firmware, abilities)
    }

    private fun statusIs(countUnits: Double, reservoirUnits: Double) {
        val status = mock(ApexStatusV1::class.java)
        doReturn(countUnits).`when`(status).deliveredTodayUnits
        doReturn(readAtMs / 1000L).`when`(status).clockSeconds
        doReturn(reservoirUnits).`when`(status).reservoirUnits
        doReturn(ApexPumpState.StatusCard(status, ByteArray(0), readAtMs, null)).`when`(manager).readStatus()
    }

    private fun days(bolusRaw: Int): ApexJournalAnswer<ApexDailyRecord> {
        val raw = ByteArray(ApexDailyRecord.RECORD_SIZE)
        raw[0] = ApexResponseFrame.MARKER
        raw[1] = 0x12
        raw[2] = 23
        raw[3] = 0xA3.toByte()
        raw[4] = ApexProtocol.HistoryOpcode.LATEST_DAILY_STATS
        raw[6] = (bolusRaw and 0xFF).toByte(); raw[7] = (bolusRaw shr 8).toByte()
        raw[12] = 26; raw[13] = 11; raw[14] = 3
        val frame = ApexResponseFrame(raw)
        return ApexJournalAnswer(listOf(ApexRead(frame, ApexDailyRecord.decode(frame)!!)), 1)
    }

    @Test
    fun `no statistics, no count kept - the status is read and its count and reservoir are the position`() {
        pumpHas(ApexAbilities(dayRecord = false))
        statusIs(countUnits = 62.15, reservoirUnits = 212.875)

        assertTrue(start.takeIfAbsent(serial))

        val kept = BolusCountMark(store, serial).get()!!
        assertEquals(readAtMs, kept.atMs)
        assertEquals(62.15, kept.count.units, 1e-9)
        assertEquals(212.875, kept.reservoirUnits!!, 1e-9)
        verify(manager, never()).readDailyStats()
    }

    @Test
    fun `no statistics, a count is kept - nothing is read, the count stays`() {
        pumpHas(ApexAbilities(dayRecord = false))
        statusIs(countUnits = 10.0, reservoirUnits = 200.0)
        assertTrue(start.takeIfAbsent(serial))
        statusIs(countUnits = 12.0, reservoirUnits = 198.0)

        assertTrue(start.takeIfAbsent(serial))

        assertEquals(10.0, BolusCountMark(store, serial).get()!!.count.units, 1e-9)
        verify(manager, times(1)).readStatus()
    }

    @Test
    fun `statistics, no day kept - the status and the statistics are read, today's record is the position`() {
        pumpHas(ApexAbilities())
        statusIs(countUnits = 5.5, reservoirUnits = 250.0)
        doReturn(days(220)).`when`(manager).readDailyStats()
        assertFalse(BolusGate(store, serial).hasDayKept())

        assertTrue(start.takeIfAbsent(serial))

        assertTrue(BolusGate(store, serial).hasDayKept())
        assertEquals(0.0, BolusGate(store, serial).bolusMovedSinceKept(days(220))!!, 1e-9)
        assertNull(BolusCountMark(store, serial).get())
        verify(manager, times(1)).readStatus()
    }

    @Test
    fun `a bolus of ours waits for its record - no position is taken, the pump is not asked`() {
        pumpHas(ApexAbilities(dayRecord = false))
        statusIs(countUnits = 62.65, reservoirUnits = 212.4)
        BolusAccount(store, serial).commandAccepted(readAtMs - 60_000L, 0.5, "NORMAL")

        assertTrue(start.takeIfAbsent(serial))

        assertNull(BolusCountMark(store, serial).get())
        verify(manager, never()).readStatus()
    }

    @Test
    fun `the pump does not answer - no position, and the caller is told`() {
        pumpHas(ApexAbilities(dayRecord = false))

        assertFalse(start.takeIfAbsent(serial))
        assertNull(BolusCountMark(store, serial).get())

        pumpHas(ApexAbilities())
        statusIs(countUnits = 5.5, reservoirUnits = 250.0)
        assertFalse(start.takeIfAbsent(serial))
        assertFalse(BolusGate(store, serial).hasDayKept())
    }
}
