package app.aaps.pump.apex.pulse

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.basal.BasalPeriodKeeper
import app.aaps.pump.apex.basal.ExactBasalMode
import app.aaps.pump.apex.basal.TbrFactsReader
import app.aaps.pump.apex.basal.TbrFrames
import app.aaps.pump.apex.bolus.JournalBench
import app.aaps.pump.apex.keys.ApexStringKey
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.store.FakeStore
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The daily statistics are read once at a heartbeat, the bolus journal read or not: no bolus ends between them. */
class StatisticsReadOnceTest {

    private val serial = "A1"
    private val heartbeatAtMs = JournalBench.NOW_MS
    private val manager: ApexManager = mock()
    private val preferences: Preferences = mock()
    private val store = FakeStore()
    private val keeper = BasalPeriodKeeper(AAPSLoggerTest(), preferences, store, mock(), mock(), mock(), mock(), mock())
    private val reader = PictureReader(AAPSLoggerTest(), manager, store, mock<ApexPumpState>(), mock<TbrFactsReader>(), keeper, mock())
    private val status = TbrFrames.status(running = false)

    @Test
    fun `the bolus journal is read - the statistics are not read again after it`() {
        whenever(preferences.get(ApexStringKey.ExactBasal)).thenReturn(ExactBasalMode.OFF.value)
        whenever(manager.readDailyStats()).thenReturn(ApexJournalAnswer(listOf(JournalBench.day(40, 3, 0)), 1))
        whenever(manager.readBolusHistory()).thenReturn(ApexJournalAnswer(emptyList(), 0))
        // No day kept yet: the bolus journal is read.
        val picture = reader.read(heartbeatAtMs, serial, ApexPumpState.StatusCard(TbrFrames.statusOf(status), status.raw, heartbeatAtMs, null))
        assertNotNull(picture?.boluses)
        verify(manager, times(1)).readDailyStats()
    }
}
