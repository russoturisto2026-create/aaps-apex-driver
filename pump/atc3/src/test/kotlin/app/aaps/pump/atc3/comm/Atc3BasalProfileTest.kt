package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.comm.CrcUtilTest.Companion.hexToBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Decodes a basal profile frame and checks the rates and the daily total it carries.
 */
class Atc3BasalProfileTest {

    private fun profileB(): Atc3BasalProfile {
        val frames = Atc3ResponseParser().feed(hexToBytes(PROFILE_B))
        assertEquals(1, frames.size)
        return Atc3BasalProfile.decode(frames[0]).also { assertNotNull(it) }!!
    }

    @Test
    fun `a profile frame has the expected size`() {
        assertEquals(Atc3BasalProfile.FRAME_SIZE, hexToBytes(PROFILE_B).size)
        assertEquals(104, Atc3BasalProfile.FRAME_SIZE)
    }

    @Test
    fun `frame carries its record count and index`() {
        val frame = Atc3ResponseParser().feed(hexToBytes(PROFILE_B)).first()
        assertEquals(8, frame.recordCount) // the pump sends all eight stored profiles
        assertEquals(1, frame.recordIndex) // this is profile B
    }

    @Test
    fun `profile index is decoded`() {
        assertEquals(1, profileB().index)
    }

    @Test
    fun `all 48 half hour slots are decoded`() {
        assertEquals(Atc3Const.BASAL_SLOTS, profileB().rates.size)
    }

    @Test
    fun `daily total is the sum of the half hour rates`() {
        // Raw 1200 over half hour slots is 15.0 U per day.
        assertEquals(15.0, profileB().dailyUnits, 1e-9)
    }

    @Test
    fun `slot 38 matches the rate the pump showed at 19 00`() {
        // Raw 20 at 0.025 U/h per raw unit.
        assertEquals(0.500, profileB().rates[38], 1e-9)
        assertEquals(0.500, profileB().rateAt(19 * 3600), 1e-9)
    }

    @Test
    fun `slot 0 starts at midnight`() {
        // First two raw values are 0x000C, that is 12, so 0.300 U/h.
        assertEquals(0.300, profileB().rates[0], 1e-9)
        assertEquals(0.300, profileB().rateAt(0), 1e-9)
    }

    @Test
    fun `every rate is a whole number of pump steps`() {
        for (rate in profileB().rates) {
            val steps = rate / Atc3Const.DOSE_SCALE
            assertTrue(Math.abs(steps - Math.round(steps)) < 1e-9, "rate $rate is not a whole step")
        }
    }

    @Test
    fun `a frame of another object type is not decoded as a profile`() {
        val ack = Atc3ResponseParser().feed(hexToBytes("AA 0A 00 A1 55 AA 00 00 EC 39")).first()
        assertNull(Atc3BasalProfile.decode(ack))
    }

    companion object {

        /**
         * Profile B read back: basal rates only, no device identifiers. 15.0 U per day, and
         * 0.500 U/h at 19:00.
         */
        const val PROFILE_B = """
            AA 68 08 A3 08 01 0C 00 0C 00 14 00 14 00 18 00
            18 00 1C 00 1C 00 2C 00 2C 00 30 00 30 00 28 00
            28 00 20 00 20 00 18 00 18 00 18 00 18 00 18 00
            18 00 14 00 14 00 14 00 14 00 14 00 14 00 14 00
            14 00 1C 00 1C 00 20 00 20 00 20 00 20 00 18 00
            18 00 14 00 14 00 14 00 14 00 14 00 14 00 10 00
            10 00 08 00 08 00 4C 25
            """
    }
}
