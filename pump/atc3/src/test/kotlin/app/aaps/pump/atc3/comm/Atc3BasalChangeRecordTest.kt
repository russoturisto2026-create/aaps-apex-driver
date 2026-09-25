package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.comm.CrcUtilTest.Companion.hexToBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * Decodes the basal change history, object `0x02`.
 *
 * The frames are fed through the parser, so the check bytes are verified with the fields.
 *
 * This is the only object that shows an edit to the rates of the profile already in use: Status V1
 * gives the active index, object `0x08` gives what the profiles hold now, and between them a rate
 * changed inside the profile being used leaves no trace. Here it does, with the minute it happened.
 */
class Atc3BasalChangeRecordTest {

    private fun decode(hex: String): Atc3BasalChangeRecord? {
        val frames = Atc3ResponseParser().feed(hexToBytes(hex))
        assertEquals(1, frames.size)
        return Atc3BasalChangeRecord.decode(frames[0])
    }

    @Test
    fun `a record carries the whole schedule as it stood after the change`() {
        val record = decode(SCHEDULE)
        assertNotNull(record)
        assertEquals(48, record!!.rates.size)
        assertEquals(0.400, record.rates[0], 1e-9)
        assertEquals(1.000, record.rates[20], 1e-9)
        assertEquals(0.800, record.rates[22], 1e-9)
    }

    @Test
    fun `the moment of the change is to the minute`() {
        val record = decode(SCHEDULE)!!
        val calendar = Calendar.getInstance().apply { timeInMillis = record.timestamp }
        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, calendar.get(Calendar.MONTH))
        assertEquals(15, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(51, calendar.get(Calendar.MINUTE))
    }

    @Test
    fun `the daily dose is the schedule's own total, which the pump does not store`() {
        // Each rate stands for half an hour, so the sum is halved. There is no field for it in the
        // record.
        assertEquals(16.0, decode(SCHEDULE)!!.dailyTotalUnits, 1e-9)
    }

    @Test
    fun `the rate in force is found by the time of day`() {
        val record = decode(SCHEDULE)!!
        // Slot 20 covers 10:00 to 10:30.
        assertEquals(1.000, record.rateAt(10 * 3600), 1e-9)
        assertEquals(1.000, record.rateAt(10 * 3600 + 1799), 1e-9)
        // Slot 22 covers 11:00 to 11:30.
        assertEquals(0.800, record.rateAt(11 * 3600), 1e-9)
        // A second past midnight and a second before it both land inside the schedule.
        assertEquals(record.rates[0], record.rateAt(0), 1e-9)
        assertEquals(record.rates[47], record.rateAt(24 * 3600 - 1), 1e-9)
    }

    @Test
    fun `a schedule of nothing at all is a real record`() {
        // The pump was stopped, so every slot is zero. Not an empty answer: the record is there.
        val record = decode(ALL_ZERO)
        assertNotNull(record)
        assertEquals(0.0, record!!.dailyTotalUnits, 1e-9)
        assertEquals(0.0, record.rates[0], 1e-9)
    }

    @Test
    fun `two records of the same schedule compare equal`() {
        // The rates are an array, which a generated equals would compare by reference. Two reads of
        // the same record must not look like two different changes.
        assertEquals(decode(ALL_ZERO), decode(ALL_ZERO))
        assertEquals(decode(ALL_ZERO).hashCode(), decode(ALL_ZERO).hashCode())
    }

    @Test
    fun `refuses a frame of another object`() {
        // A temporary basal history record: a history object like this one, laid out differently.
        assertNull(decode(TBR_RECORD))
    }

    companion object {

        /** The schedule as it stood after a change at 2026-08-15 12:51, 16.0 U a day. */
        private const val SCHEDULE = """
            AA 6E 01 A3 02 00 1A 08 0F 0C 33 00 10 00 10 00
            10 00 10 00 10 00 10 00 20 00 20 00 20 00 20 00
            24 00 24 00 24 00 24 00 20 00 20 00 20 00 20 00
            28 00 28 00 28 00 28 00 20 00 20 00 20 00 20 00
            20 00 20 00 20 00 20 00 1C 00 1C 00 1C 00 1C 00
            18 00 18 00 18 00 18 00 10 00 10 00 10 00 10 00
            10 00 10 00 10 00 10 00 10 00 10 00 16 4E
            """

        /** Every slot zero, recorded at 2026-08-15 13:37. */
        private const val ALL_ZERO = """
            AA 6E 04 A3 02 00 1A 08 0F 0D 25 00 00 00 00 00
            00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            00 00 00 00 00 00 00 00 00 00 00 00 DE 25
            """

        /** Object 0x27, a temporary basal history record: another object. */
        private const val TBR_RECORD = "AA 16 32 A3 27 00 1A 08 17 17 03 00 01 00 02 00 50 00 1C 00 42 3E"
    }
}
