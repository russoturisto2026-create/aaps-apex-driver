package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.comm.CrcUtilTest.Companion.hexToBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * Decodes the temporary basal history, object `0x27`.
 *
 * The frames are fed through the real parser, so the check bytes are verified along with the
 * fields.
 *
 * What these pin down is [Atc3TbrRecord.deliveredUnits]. It is the reason to read this object at
 * all — the pump's own figure for what a temporary basal put in, which closes a gap in the driver's
 * accounting instead of reconstructing it out of rates and durations.
 */
class Atc3TbrRecordTest {

    private fun decode(hex: String): Atc3TbrRecord? {
        val frames = Atc3ResponseParser().feed(hexToBytes(hex))
        assertEquals(1, frames.size)
        return Atc3TbrRecord.decode(frames[0])
    }

    @Test
    fun `a record decodes to what the pump delivered`() {
        val record = decode(FIRST_OF_BURST)
        assertNotNull(record)
        assertEquals(2.000, record!!.rate!!, 1e-9)
        // An absolute record has no percentage to report, and saying so is the point of keeping the
        // two apart: raw 80 read as a percentage would be 80 %.
        assertNull(record.percent)
        assertEquals(30, record.durationMinutes)
        assertEquals(0.700, record.deliveredUnits, 1e-9)
    }

    @Test
    fun `the start is the pump's own, to the second`() {
        val record = decode(FIRST_OF_BURST)!!
        val calendar = Calendar.getInstance().apply { timeInMillis = record.startTimestamp }
        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, calendar.get(Calendar.MONTH))
        assertEquals(23, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(3, calendar.get(Calendar.MINUTE))
    }

    @Test
    fun `a record that delivered nothing is a record like any other`() {
        val record = decode(NOTHING_GIVEN)
        assertNotNull(record)
        assertEquals(0.0, record!!.rate!!, 1e-9)
        assertEquals(120, record.durationMinutes)
        assertEquals(0.0, record.deliveredUnits, 1e-9)
        // Position in the burst is what tells one record from another: they share the object and
        // the record count, and two temporary basals can agree in every other field.
        assertEquals(5, record.index)
    }

    // The pump's other temporary basal mode. Its keypad offers it and this driver never sends it.

    @Test
    fun `a percentage record is not passed off as a rate`() {
        val record = decode(PERCENTAGE)
        assertNotNull(record)
        // Raw 150 sits in the same two bytes an absolute record puts its rate in. Read on the dose
        // scale that would be 3.750 U/h; the pump was really doing 150 % of 0.500 U/h.
        assertEquals(150, record!!.percent)
        assertNull(record.rate)
        assertEquals(15, record.durationMinutes)
    }

    @Test
    fun `what a percentage record delivered needs no rate to be read`() {
        // The delivered field settles the reading on its own: 150 % of a scheduled 0.500 U/h is
        // 0.750 U/h, the temporary basal stood 4 min 21 s, and that is 0.054 U — raw 2 after the
        // pump's rounding down to a step.
        assertEquals(0.050, decode(PERCENTAGE)!!.deliveredUnits, 1e-9)
    }

    @Test
    fun `the amount asked reads differently in the two modes`() {
        assertEquals("2.0 U/h", decode(FIRST_OF_BURST)!!.amountAsked)
        assertEquals("150 %", decode(PERCENTAGE)!!.amountAsked)
    }

    @Test
    fun `a pump with no temporary basal behind it answers nothing at all`() {
        // The ten byte "nothing stored" answer every history object uses. It is a successful read
        // of no records, and it must not decode into a record of zeros.
        assertNull(decode(EMPTY))
    }

    @Test
    fun `refuses a frame of another object`() {
        // The last temporary basal command, object 0x0A: the same subject, a different layout.
        assertNull(decode(ACTIVE_TBR))
    }

    companion object {

        /**
         * First of a burst of ten answering a count of fifty.
         *
         * 2026-08-23 23:03, absolute 2.000 U/h for 30 minutes, 0.700 U given.
         */
        private const val FIRST_OF_BURST = "AA 16 32 A3 27 00 1A 08 17 17 03 00 01 00 02 00 50 00 1C 00 42 3E"

        /** Sixth of the same burst: 0.000 U/h for 120 minutes, nothing given. */
        private const val NOTHING_GIVEN = "AA 16 32 A3 27 05 1A 08 17 14 21 00 01 00 08 00 00 00 00 00 31 B3"

        /** The answer of a pump holding no temporary basal history. */
        private const val EMPTY = "AA 0A 00 A3 27 00 FF FF AF 11"

        /** 150 % for 15 minutes started 2026-09-04 17:22, 0.050 U given. */
        private const val PERCENTAGE = "AA 16 32 A3 27 00 1A 09 04 11 16 00 00 00 01 00 96 00 02 00 7E 9E"

        /** Object 0x0A, the last temporary basal command: 4.000 U/h from 2026-08-20 15:31. */
        private const val ACTIVE_TBR = """
            AA 1C 00 A3 0A 00 1A 08 14 0F 1F 00 00 1A 08 14
            0F 1F 00 01 04 00 A0 00 00 00 74 6D
            """
    }
}
