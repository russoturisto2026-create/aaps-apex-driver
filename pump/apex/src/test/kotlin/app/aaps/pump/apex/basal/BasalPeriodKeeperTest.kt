package app.aaps.pump.apex.basal

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.keys.ApexStringKey
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.statistics.Delivered
import app.aaps.pump.apex.store.FakeStore
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Whether a heartbeat closes the period of the exact basal record: the temporary basal facts are not read then. */
class BasalPeriodKeeperTest {

    private val serial = "A1"
    private val today = 1_793_836_800L
    private val tenOhFive = today * 1000L + 10 * 3_600_000L + 5 * 60_000L + 2_000L
    private val tenTen = tenOhFive + 5 * 60_000L
    private val store = FakeStore()
    private val preferences: Preferences = mock()
    private val keeper = BasalPeriodKeeper(AAPSLoggerTest(), preferences, store, mock(), mock(), mock(), mock(), mock())

    private fun day(basalRaw: Int, key: Long = today) = ApexDailyRecord(0, 1.0, basalRaw * 0.025, 0.0, key)

    private fun mode(mode: ExactBasalMode) = whenever(preferences.get(ApexStringKey.ExactBasal)).thenReturn(mode.value)

    private fun begunAt(atMs: Long, day: ApexDailyRecord) =
        store.putState(serial, BasalPeriodKeeper.STATE_PERIOD, BasalPeriod.Mark(atMs - 2_000L, day).encode())

    @Test
    fun `the period begun at the last heartbeat closes at this one`() {
        mode(ExactBasalMode.HEARTBEAT)
        begunAt(tenOhFive, day(400))
        assertTrue(keeper.closesAt(serial, tenTen, Delivered(listOf(day(404)))))
    }

    @Test
    fun `nothing closes - the mode is off, no period is under way, the statistics are broken, or the day kept is gone`() {
        mode(ExactBasalMode.OFF)
        begunAt(tenOhFive, day(400))
        assertFalse(keeper.closesAt(serial, tenTen, Delivered(listOf(day(404)))))

        mode(ExactBasalMode.HEARTBEAT)
        assertFalse(keeper.closesAt(serial, tenTen, Delivered(listOf(day(404)), broken = true)))
        // The pump started the day over: the day kept is not found, the period begins anew and its rows stay.
        assertFalse(keeper.closesAt(serial, tenTen, Delivered(listOf(day(10, key = today + 1)))))

        store.putState(serial, BasalPeriodKeeper.STATE_PERIOD, null)
        assertFalse(keeper.closesAt(serial, tenTen, Delivered(listOf(day(404)))))
    }
}
