package app.aaps.pump.apex.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** When a bolus of the ordered dose is over: the first portion at 0.8 s, then one a second (5.2 s and five at the slow speed). */
class BolusTimingTest {

    @Test
    fun `the portion goes by the dose`() {
        assertEquals(4L, BolusTiming.portions(0.1))
        assertEquals(36L, BolusTiming.portions(0.9))
        assertEquals(20L, BolusTiming.portions(1.0))
        assertEquals(50L, BolusTiming.portions(2.5))
        assertEquals(150L, BolusTiming.portions(7.5))
        assertEquals(150L, BolusTiming.portions(10.0))
        assertEquals(150L, BolusTiming.portions(15.0))
        assertEquals(200L, BolusTiming.portions(20.0))
    }

    @Test
    fun `the last portion at 0_8 s and a second for each one after the first`() {
        assertEquals(3_800L, BolusTiming.durationMs(0.1, slowSpeed = false))
        assertEquals(19_800L, BolusTiming.durationMs(0.5, slowSpeed = false))
        assertEquals(19_800L, BolusTiming.durationMs(1.0, slowSpeed = false))
        assertEquals(35_800L, BolusTiming.durationMs(0.9, slowSpeed = false))
    }

    @Test
    fun `the slow speed - the first portion at 5_2 s, then one every five seconds`() {
        assertEquals(100_200L, BolusTiming.durationMs(1.0, slowSpeed = true))
    }
}
