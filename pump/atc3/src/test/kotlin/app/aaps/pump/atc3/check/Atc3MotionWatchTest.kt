package app.aaps.pump.atc3.check

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Atc3MotionWatchTest {

    private val start = 1_700_000_000_000L
    private val tick = 120_000L

    /** Ticks two minutes apart in one window, the journal owing 0.1 U more each tick. */
    private fun owedTicks(watch: Atc3MotionWatch, count: Int, counter: Double, from: Int = 0) {
        for (i in from until from + count) {
            watch.note(start, start + i * tick, counter, 0.1 * i)
        }
    }

    @Test
    fun `three motionless ticks with insulin owed stop the loop`() {
        val watch = Atc3MotionWatch()
        owedTicks(watch, 3, counter = 10.0)
        assertEquals(2, watch.motionlessTicks)
        assertTrue(watch.tellDue)
        assertFalse(watch.deliveryStopped)
        owedTicks(watch, 1, counter = 10.0, from = 3)
        assertTrue(watch.deliveryStopped)
    }

    @Test
    fun `the stop is dated at the read the count was last seen at`() {
        val watch = Atc3MotionWatch()
        // Count moving over the first ticks, then still.
        watch.note(start, start, 10.0, 0.0)
        watch.note(start, start + tick, 10.1, 0.1)
        watch.note(start, start + 2 * tick, 10.1, 0.2)
        watch.note(start, start + 3 * tick, 10.1, 0.3)
        watch.note(start, start + 4 * tick, 10.1, 0.4)
        assertTrue(watch.deliveryStopped)
        assertEquals(start + tick, watch.stillSinceMs)
    }

    @Test
    fun `a step of the count ends the hold`() {
        val watch = Atc3MotionWatch()
        owedTicks(watch, 4, counter = 10.0)
        assertTrue(watch.deliveryStopped)
        watch.note(start, start + 4 * tick, 10.025, 0.4)
        assertFalse(watch.deliveryStopped)
        assertEquals(0, watch.motionlessTicks)
        assertEquals(0L, watch.stillSinceMs)
    }

    @Test
    fun `nothing owed is not a motionless tick and keeps the hold as it is`() {
        val watch = Atc3MotionWatch()
        owedTicks(watch, 4, counter = 10.0)
        assertTrue(watch.deliveryStopped)
        // The journal holds nothing more: the stop written for the hold, or a zero temporary basal.
        watch.note(start, start + 4 * tick, 10.0, 0.3)
        watch.note(start, start + 5 * tick, 10.0, 0.3)
        assertTrue(watch.deliveryStopped)
        assertEquals(3, watch.motionlessTicks)
    }

    @Test
    fun `a window begun at the last read carries the count on`() {
        val watch = Atc3MotionWatch()
        owedTicks(watch, 3, counter = 10.0)
        assertEquals(2, watch.motionlessTicks)
        // The window closed at the half hour and a new one began at the last tick's read.
        val newStart = start + 2 * tick
        watch.note(newStart, newStart + tick, 10.0, 0.1)
        assertTrue(watch.deliveryStopped)
        assertEquals(start, watch.stillSinceMs)
    }

    @Test
    fun `a window begun elsewhere starts the count over`() {
        val watch = Atc3MotionWatch()
        owedTicks(watch, 3, counter = 10.0)
        watch.note(start + 1_000L, start + 3 * tick, 10.0, 0.1)
        assertEquals(0, watch.motionlessTicks)
        assertEquals(0L, watch.stillSinceMs)
    }
}
