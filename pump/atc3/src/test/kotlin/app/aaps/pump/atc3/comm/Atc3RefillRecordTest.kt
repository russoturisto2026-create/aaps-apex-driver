package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.comm.CrcUtilTest.Companion.hexToBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * Decodes the reservoir refill history, object `0x04`.
 *
 * The frames are fed through the parser, so the check bytes are verified too.
 *
 * The object matters for two things the reservoir figure cannot say on its own: whether a level
 * that went up was a real refill or a plunger drawn back, and whether a catheter prime happened —
 * a prime's insulin is recorded nowhere else at all, and it does leave the reservoir.
 */
class Atc3RefillRecordTest {

    private fun decode(hex: String): Atc3RefillRecord? {
        val frames = Atc3ResponseParser().feed(hexToBytes(hex))
        assertEquals(1, frames.size)
        return Atc3RefillRecord.decode(frames[0])
    }

    @Test
    fun `a refill decodes to what went in`() {
        val record = decode(FULL_REFILL)
        assertNotNull(record)
        assertEquals(275.825, record!!.amountUnits, 1e-9)
        assertTrue(record.isManualRefill)
        assertFalse(record.isPrime)
    }

    @Test
    fun `the amount is read little endian, as every other field of this protocol is`() {
        // This record's amount was once quoted as
        // "2B 19" in prose, which is the value in hex rather than the order the bytes arrive in:
        // the wire carries 19 2B. Taken the other way round the same record reads 160.7 U instead
        // of 275.825 U, and nothing else would notice.
        assertEquals(275.825, decode(FULL_REFILL)!!.amountUnits, 1e-9)
    }

    @Test
    fun `the date a reservoir was started is the one thing this object exists for`() {
        val record = decode(FULL_REFILL)!!
        val calendar = Calendar.getInstance().apply { timeInMillis = record.timestamp }
        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, calendar.get(Calendar.MONTH))
        assertEquals(23, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(17, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(17, calendar.get(Calendar.MINUTE))
    }

    @Test
    fun `a refill of nothing is a real record and not an error`() {
        val record = decode(EMPTY_REFILL)
        assertNotNull(record)
        assertEquals(0.0, record!!.amountUnits, 1e-9)
        assertTrue(record.isManualRefill)
    }

    @Test
    fun `a pump that has never been refilled answers nothing at all`() {
        // The ten byte "nothing stored" answer. A successful read of no records, and it must not
        // turn into a refill of zero at the epoch.
        assertNull(decode(NOTHING_STORED))
    }

    @Test
    fun `refuses a frame of another object`() {
        // A temporary basal history record: another history object.
        assertNull(decode(TBR_RECORD))
    }

    companion object {

        /** 275.825 U put in by hand at 2026-08-23 17:17. */
        private const val FULL_REFILL = """
            AA 12 01 A3 04 00 1A 08 17 11 11 00 19 2B 01 00
            D4 55
            """

        /** A manual refill of nothing at 2026-08-24 20:01. */
        private const val EMPTY_REFILL = """
            AA 12 02 A3 04 00 1A 08 18 14 01 00 00 00 01 00
            B7 D2
            """

        /** The answer of a pump holding no refill history. */
        private const val NOTHING_STORED = "AA 0A 00 A3 04 00 FF FF A4 95"

        /** Object 0x27, a temporary basal history record: another object. */
        private const val TBR_RECORD = "AA 16 32 A3 27 00 1A 08 17 17 03 00 01 00 02 00 50 00 1C 00 42 3E"
    }
}
