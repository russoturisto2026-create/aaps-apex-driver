package app.aaps.pump.apex.pulse

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.basal.BasalPeriod
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
import app.aaps.pump.apex.store.Snapshot
import app.aaps.pump.apex.store.SnapshotKind
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The read at a heartbeat with the exact basal record on: the last finished temporary basal is not read when
 * the heartbeat closes the period, and is read when it cannot close it. The status kept shows a temporary
 * basal that is gone now, so off the exact record it would be read.
 */
class ExactBasalTbrReadTest {

    private val serial = "A1"
    private val heartbeatAtMs = JournalBench.NOW_MS
    private val store = FakeStore()
    private val manager: ApexManager = mock()
    private val preferences: Preferences = mock()
    private val tbrReader: TbrFactsReader = mock()
    private val keeper = BasalPeriodKeeper(AAPSLoggerTest(), preferences, store, mock(), mock(), mock(), mock(), mock())
    private val reader = PictureReader(AAPSLoggerTest(), manager, store, mock<ApexPumpState>(), tbrReader, keeper, mock())

    private val today = JournalBench.day(40, 3, 0)
    private val nowStatus = TbrFrames.status(running = false)

    init {
        whenever(manager.readDailyStats()).thenReturn(ApexJournalAnswer(listOf(today), 1))
        store.saveSnapshot(serial, SnapshotKind.STATUS, Snapshot(listOf(TbrFrames.status(running = true).raw), heartbeatAtMs - 5 * 60_000L))
    }

    private fun mode(mode: ExactBasalMode) = whenever(preferences.get(ApexStringKey.ExactBasal)).thenReturn(mode.value)

    private fun periodBegunWith(dayOfMonth: Int) =
        store.putState(serial, BasalPeriodKeeper.STATE_PERIOD, BasalPeriod.Mark(heartbeatAtMs - 5 * 60_000L, JournalBench.day(40, dayOfMonth, 0).record).encode())

    private fun readPicture() {
        reader.read(heartbeatAtMs, serial, ApexPumpState.StatusCard(TbrFrames.statusOf(nowStatus), nowStatus.raw, heartbeatAtMs, null))
    }

    @Test
    fun `the heartbeat closes the period - the last finished temporary basal is not read`() {
        mode(ExactBasalMode.HEARTBEAT)
        periodBegunWith(dayOfMonth = 3)
        readPicture()
        verify(tbrReader, never()).read()
    }

    @Test
    fun `the pump started the day over - the period cannot close, the last finished temporary basal is read`() {
        mode(ExactBasalMode.HEARTBEAT)
        periodBegunWith(dayOfMonth = 2)
        readPicture()
        verify(tbrReader).read()
    }

    @Test
    fun `the exact record is off - the last finished temporary basal is read`() {
        mode(ExactBasalMode.OFF)
        periodBegunWith(dayOfMonth = 3)
        readPicture()
        verify(tbrReader).read()
    }
}
