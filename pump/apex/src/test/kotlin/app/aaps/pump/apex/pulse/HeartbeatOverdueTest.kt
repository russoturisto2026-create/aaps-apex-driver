package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.pulse.HeartbeatOverdue.Companion.isOverdue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** When the watch asks the pump: two periods and a little more with no heartbeat, then once a period. */
class HeartbeatOverdueTest {

    private val minute = 60_000L
    private val start = 1_000_000_000L

    @Test
    fun `a heartbeat within two periods and the slack is not overdue`() {
        val last = start + 10 * minute
        assertFalse(isOverdue(last + 10 * minute + HeartbeatOverdue.SLACK_MS - 1, last, start, 0L, 5 * minute))
        assertTrue(isOverdue(last + 10 * minute + HeartbeatOverdue.SLACK_MS, last, start, 0L, 5 * minute))
    }

    @Test
    fun `the period the user chose sets the wait - two minutes are overdue after four and the slack`() {
        val last = start + 10 * minute
        assertFalse(isOverdue(last + 4 * minute, last, start, 0L, 2 * minute))
        assertTrue(isOverdue(last + 4 * minute + HeartbeatOverdue.SLACK_MS, last, start, 0L, 2 * minute))
    }

    @Test
    fun `with no heartbeat yet the wait counts from when the watch began`() {
        assertFalse(isOverdue(start + 5 * minute, 0L, start, 0L, 5 * minute))
        assertTrue(isOverdue(start + 11 * minute, 0L, start, 0L, 5 * minute))
    }

    @Test
    fun `once asked, the pump is asked again no sooner than one period later`() {
        val asked = start + 11 * minute
        assertFalse(isOverdue(asked + 4 * minute, 0L, start, asked, 5 * minute))
        assertTrue(isOverdue(asked + 5 * minute, 0L, start, asked, 5 * minute))
    }

    @Test
    fun `a heartbeat after the ask ends the watch until the next overdue`() {
        val asked = start + 11 * minute
        val beat = asked + minute
        assertFalse(isOverdue(beat + 5 * minute, beat, start, asked, 5 * minute))
    }
}
