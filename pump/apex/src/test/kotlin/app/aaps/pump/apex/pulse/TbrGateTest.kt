package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.pulse.TbrFrames.key
import app.aaps.pump.apex.pulse.TbrFrames.status
import app.aaps.pump.apex.pulse.TbrFrames.statusOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The status of the last reconciliation held against the status now: the last finished one is read only when something may have happened. */
class TbrGateTest {

    private fun running(rateRaw: Int, minutes: Int, elapsed: Int, clock: Long) = statusOf(status(true, rateRaw, minutes, elapsed, clock = clock))
    private fun none(clock: Long) = statusOf(status(false, clock = clock))

    @Test
    fun `level 1 - the same temporary basal runs, its minutes gone have grown - nothing is read`() {
        val decision = TbrGate.decide(running(80, 60, elapsed = 3, clock = key(9, 15, 40)), running(80, 60, elapsed = 8, clock = key(9, 20, 40)))
        assertFalse(decision.read)
    }

    @Test
    fun `level 1 - the start worked out one minute apart is the same start`() {
        assertFalse(TbrGate.decide(running(80, 60, elapsed = 3, clock = key(9, 15, 40)), running(80, 60, elapsed = 7, clock = key(9, 20, 5))).read)
    }

    @Test
    fun `level 2b - another rate, another length or another start - it is read`() {
        val before = running(80, 60, elapsed = 3, clock = key(9, 15, 40))
        assertTrue(TbrGate.decide(before, running(60, 60, elapsed = 8, clock = key(9, 20, 40))).read)
        assertTrue(TbrGate.decide(before, running(80, 30, elapsed = 8, clock = key(9, 20, 40))).read)
        assertTrue(TbrGate.decide(before, running(80, 60, elapsed = 2, clock = key(9, 20, 40))).read)
        assertTrue(TbrGate.decide(before, none(key(9, 20))).read)
        assertTrue(TbrGate.decide(none(key(9, 15)), running(80, 60, elapsed = 2, clock = key(9, 20, 40))).read)
    }

    @Test
    fun `level 2a - none before and none now - it is read`() {
        val decision = TbrGate.decide(none(key(9, 15)), none(key(9, 20)))
        assertTrue(decision.read)
    }

    @Test
    fun `the first reconciliation - nothing to hold against, nothing is read`() {
        assertFalse(TbrGate.decide(null, running(80, 60, elapsed = 3, clock = key(9, 15, 40))).read)
    }

    @Test
    fun `the start is the minute of the status clock less the minutes gone`() {
        val running = TbrFacts.runningOf(running(80, 60, elapsed = 3, clock = key(9, 15, 40)))!!
        assertEquals(key(9, 12), running.startClockSeconds)
        assertEquals(2.0, running.rate, 1e-9)
        assertEquals(60, running.durationMinutes)
        assertEquals(key(9, 15), TbrFacts.runningOf(running(80, 60, elapsed = 0, clock = key(9, 15, 47)))!!.startClockSeconds)
        assertNull(TbrFacts.runningOf(none(key(9, 15))))
    }
}
