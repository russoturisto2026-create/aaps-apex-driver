package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.ApexConst
import app.aaps.pump.apex.pulse.HeartbeatGrid.Step
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The two steps that move the heartbeat onto the grid of the chosen period, and when the grid is left alone. */
class HeartbeatGridTest {

    @Test
    fun `every period the user can choose divides the half hour, so the grid holds 00 and 30`() {
        for (period in ApexConst.HEARTBEAT_PERIODS) {
            assertTrue(HeartbeatGrid.onGrid(0, period))
            assertTrue(HeartbeatGrid.onGrid(30, period))
        }
    }

    @Test
    fun `a heartbeat on the grid needs nothing`() {
        for (minute in listOf(0, 5, 25, 30, 55)) assertEquals(Step.NONE, HeartbeatGrid.stepFor(minute, moving = false, misses = 0, period = 5))
        for (minute in listOf(0, 3, 27, 57)) assertEquals(Step.NONE, HeartbeatGrid.stepFor(minute, moving = false, misses = 0, period = 3))
        for (minute in 0..59) assertEquals(Step.NONE, HeartbeatGrid.stepFor(minute, moving = false, misses = 0, period = 1))
    }

    @Test
    fun `the half hour closes at minute 0 and 30 only`() {
        for (minute in listOf(0, 30)) assertEquals(true, HeartbeatGrid.onHalfHour(minute))
        for (minute in listOf(5, 15, 25, 35, 55)) assertEquals(false, HeartbeatGrid.onHalfHour(minute))
    }

    @Test
    fun `off the grid, the period that lands the next heartbeat on it`() {
        assertEquals(Step.SHORTEN, HeartbeatGrid.stepFor(28, moving = false, misses = 0, period = 5))
        assertEquals(2, HeartbeatGrid.shortPeriod(28, 5)) // 28 + 2 = 30
        assertEquals(1, HeartbeatGrid.shortPeriod(29, 5)) // 29 + 1 = 30
        assertEquals(1, HeartbeatGrid.shortPeriod(4, 5))  // 4 + 1 = 5
        assertEquals(4, HeartbeatGrid.shortPeriod(31, 5)) // 31 + 4 = 35
        assertEquals(1, HeartbeatGrid.shortPeriod(29, 2)) // 29 + 1 = 30
        assertEquals(2, HeartbeatGrid.shortPeriod(31, 3)) // 31 + 2 = 33
    }

    @Test
    fun `the heartbeat that landed on the grid brings the period back, one that did not is a miss`() {
        assertEquals(Step.RESTORE, HeartbeatGrid.stepFor(30, moving = true, misses = 0, period = 5))
        assertEquals(Step.MISSED, HeartbeatGrid.stepFor(31, moving = true, misses = 0, period = 5))
        assertEquals(Step.RESTORE, HeartbeatGrid.stepFor(33, moving = true, misses = 0, period = 3))
    }

    @Test
    fun `after enough misses the grid is left as it is`() {
        assertEquals(Step.SHORTEN, HeartbeatGrid.stepFor(31, moving = false, misses = HeartbeatGrid.GIVE_UP_AFTER - 1, period = 5))
        assertEquals(Step.NONE, HeartbeatGrid.stepFor(31, moving = false, misses = HeartbeatGrid.GIVE_UP_AFTER, period = 5))
    }
}
