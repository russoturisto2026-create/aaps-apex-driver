package app.aaps.pump.apex.deep

import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/** The pump taken as not working: after three requests that reached nothing, until its heartbeat or a new link. */
class PumpDownDeepTest {

    private val dropped = ArrayList<String>()
    private val told = ArrayList<Boolean>()
    private val deep = PumpDownDeep(AAPSLoggerTest(), mock<ApexTrace>(), dropLink = { dropped.add(it) }) { told.add(it) }

    @Test
    fun `three requests that reached nothing - the link dropped, the pump down`() {
        assertFalse(deep.noteReachability(reached = false, why = "silent"))
        assertFalse(deep.noteReachability(reached = false, why = "not_sent"))
        assertFalse(deep.down)
        assertTrue(deep.noteReachability(reached = false, why = "silent"))

        assertTrue(deep.down)
        assertEquals(listOf("no usable answer to 3 requests, the last one silent"), dropped)
    }

    @Test
    fun `an answer between - the count starts over`() {
        deep.noteReachability(reached = false, why = "silent")
        deep.noteReachability(reached = false, why = "silent")
        deep.noteReachability(reached = true, why = "answered")
        assertFalse(deep.noteReachability(reached = false, why = "silent"))
        assertFalse(deep.noteReachability(reached = false, why = "silent"))
        assertFalse(deep.down)
    }

    @Test
    fun `its heartbeat brings the pump back`() {
        deep.notWorking("3 reads brought nothing")
        assertTrue(deep.down)
        deep.onHeartbeat()
        assertFalse(deep.down)
    }

    @Test
    fun `a new link brings the pump back and the count starts over`() {
        deep.noteReachability(reached = false, why = "silent")
        deep.notWorking("3 reads brought nothing")
        deep.onLinkUp()
        assertFalse(deep.down)
        assertFalse(deep.noteReachability(reached = false, why = "silent"))
        assertFalse(deep.noteReachability(reached = false, why = "silent"))
        assertTrue(deep.noteReachability(reached = false, why = "silent"))
    }

    @Test
    fun `taken as not working - the link is dropped first, every time`() {
        deep.notWorking("first")
        deep.notWorking("second")
        assertEquals(listOf("first", "second"), dropped)
        assertTrue(deep.down)
    }

    @Test
    fun `the user is told once - the notice goes with the first usable answer, not with a new link`() {
        repeat(3) { deep.noteReachability(reached = false, why = "unsound_answer") }
        deep.onLinkUp()
        repeat(3) { deep.noteReachability(reached = false, why = "broken_answer") }
        assertEquals(listOf(true), told)

        deep.onLinkUp()
        deep.noteReachability(reached = true, why = "ok")
        deep.noteReachability(reached = true, why = "ok")
        assertEquals(listOf(true, false), told)
    }
}
