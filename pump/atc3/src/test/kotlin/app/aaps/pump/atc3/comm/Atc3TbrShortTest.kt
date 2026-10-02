package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.comm.CrcUtilTest.Companion.hexToBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Decodes the short form of the last temporary basal command, object `0x09`.
 *
 *
 * What it has that `0x0A` does not: a rate that is absolute in both modes, and the minutes elapsed.
 */
class Atc3TbrShortTest {

    private fun decode(hex: String): Atc3TbrShort? {
        val frames = Atc3ResponseParser().feed(hexToBytes(hex))
        assertEquals(1, frames.size)
        return Atc3TbrShort.decode(frames[0])
    }

    @Test
    fun `a running command decodes to what the pump displayed`() {
        val short = decode(RUNNING)
        assertNotNull(short)
        assertEquals(1.200, short!!.rateUnitsPerHour, 1e-9)
        assertTrue(short.absolute)
        assertEquals(30, short.durationMinutes)
        assertEquals(14, short.elapsedMinutes)
    }

    @Test
    fun `how long is left is the one thing nothing else states outright`() {
        assertEquals(16, decode(RUNNING)!!.remainingMinutes)
    }

    @Test
    fun `once nothing runs the rate keeps its last value and the rest goes to zero`() {
        // A cancelled 1.000 U/h temporary basal. The rate field still holds 40, which is why this
        // object can never be asked whether anything is running — Status V1 offset 53 is.
        val short = decode(NOTHING_RUNNING)
        assertNotNull(short)
        assertEquals(1.000, short!!.rateUnitsPerHour, 1e-9)
        assertEquals(0, short.durationMinutes)
        assertEquals(0, short.elapsedMinutes)
        assertFalse(short.looksRunning)
    }

    @Test
    fun `time left never goes negative`() {
        // The pump can report an elapsed figure that has caught up with the duration in the moment
        // before it clears both.
        assertEquals(0, decode(NOTHING_RUNNING)!!.remainingMinutes)
    }

    @Test
    fun `refuses a frame of another object`() {
        // Object 0x0A, the long form of the same command.
        assertNull(decode(ACTIVE_TBR))
    }

    companion object {

        /** 1.200 U/h for 30 minutes, 14 of them gone. */
        private const val RUNNING = "AA 10 00 A3 09 00 30 00 01 00 02 00 0E 00 E5 4F"

        /**
         * The payload of a cancelled 1.000 U/h temporary basal, with the frame built around it
         * and its check bytes computed.
         */
        private const val NOTHING_RUNNING = "AA 10 00 A3 09 00 28 00 01 00 00 00 00 00 E0 3D"

        /** Object 0x0A, the last temporary basal command: 4.000 U/h from 2026-08-20 15:31. */
        private const val ACTIVE_TBR = """
            AA 1C 00 A3 0A 00 1A 08 14 0F 1F 00 00 1A 08 14
            0F 1F 00 01 04 00 A0 00 00 00 74 6D
            """
    }
}
