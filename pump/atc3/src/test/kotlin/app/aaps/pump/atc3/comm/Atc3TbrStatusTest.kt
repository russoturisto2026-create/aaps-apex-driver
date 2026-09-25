package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.comm.CrcUtilTest.Companion.hexToBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * Decodes the last temporary basal command, object `0x0A`.
 *
 * The one field the driver could not get anywhere else is the start time, so that is what most of
 * these pin down. The rest guard the rate field, which is two different quantities depending on the
 * mode byte before it.
 */
class Atc3TbrStatusTest {

    private fun decode(hex: String): Atc3TbrStatus? {
        val frames = Atc3ResponseParser().feed(hexToBytes(hex))
        assertEquals(1, frames.size)
        return Atc3TbrStatus.decode(frames[0])
    }

    @Test
    fun `the rate matches what the pump displayed`() {
        val status = decode(RUNNING_TBR)
        assertNotNull(status)
        assertEquals(4.000, status!!.rate!!, 1e-9)
        // An absolute command has no percentage to report, and saying so is the whole point of
        // splitting the field: a caller cannot pick the wrong one up by accident.
        assertNull(status.percent)
    }

    // The pump's other temporary basal mode, which its keypad offers and this driver never sends

    @Test
    fun `a percentage temporary basal is not passed off as a rate`() {
        val status = decode(PERCENTAGE_TBR)
        assertNotNull(status)
        // Raw 111 in the same two bytes an absolute command puts its rate in. Read on the 0.025
        // scale that is 2.775 U/h, nearly three times what the pump is really doing.
        assertEquals(111, status!!.percent)
        assertNull(status.rate)
    }

    @Test
    fun `everything else in a percentage record decodes as usual`() {
        val status = decode(PERCENTAGE_TBR)!!
        assertEquals(75, status.durationMinutes)
        assertEquals(0.025, status.deliveredUnits, 1e-9)
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = status.startTimestamp
        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(9, calendar.get(Calendar.MONTH) + 1)
        assertEquals(3, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(11, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(36, calendar.get(Calendar.MINUTE))
    }

    @Test
    fun `logs say which of the two quantities the pump was given`() {
        // The unit is the difference between the two records, so a log line that dropped it would
        // be the same mistake in prose.
        assertEquals("111 %", decode(PERCENTAGE_TBR)!!.amountAsked)
        assertEquals("4.0 U/h", decode(RUNNING_TBR)!!.amountAsked)
    }

    @Test
    fun `the duration is read in steps of fifteen minutes`() {
        // Raw 4 in the frame, so an hour: the duration counts quarter hours.
        assertEquals(60, decode(RUNNING_TBR)!!.durationMinutes)
    }

    @Test
    fun `nothing has been delivered yet just after the start`() {
        assertEquals(0.0, decode(RUNNING_TBR)!!.deliveredUnits, 1e-9)
    }

    @Test
    fun `the start clock is decoded, which is the whole point of this object`() {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = decode(RUNNING_TBR)!!.startTimestamp
        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(8, calendar.get(Calendar.MONTH) + 1)
        assertEquals(20, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(15, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(31, calendar.get(Calendar.MINUTE))
        assertEquals(0, calendar.get(Calendar.SECOND))
    }

    @Test
    fun `an empty first start clock falls back to the copy that is filled`() {
        val status = decode(KEYPAD_TBR_OVER_RUNNING)
        assertNotNull(status)
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = status!!.startTimestamp
        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(9, calendar.get(Calendar.MONTH) + 1)
        assertEquals(2, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(17, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, calendar.get(Calendar.MINUTE))
        assertEquals(30, calendar.get(Calendar.SECOND))
        // The rest of the frame decodes as usual: this is a whole record, not a damaged one.
        assertEquals(0.625, status.rate!!, 1e-9)
        assertEquals(105, status.durationMinutes)
    }

    @Test
    fun `a start read from the second copy is plausible, so the record survives`() {
        val status = decode(KEYPAD_TBR_OVER_RUNNING)!!
        // Two minutes after the start on the pump's clock. Read from the empty first copy this
        // would be 1999-11-30 and AAPS would throw the whole temporary basal away.
        assertTrue(status.isStartPlausible(status.startTimestamp + 120_000))
    }

    @Test
    fun `the start is also read as an identity no timezone can move`() {
        val status = decode(RUNNING_TBR)!!
        // Whole seconds, and the same instant the local calendar decoded.
        assertEquals(status.startTimestamp / 1000L * 1000L, status.startTimestamp)
        assertEquals(0L, status.startUtcSeconds % 60L)
    }

    @Test
    fun `a record count of zero does not mean nothing is running`() {
        // The frame carries count 0 and a full record, which is why the decoder goes by
        // size. Reading the count as "nothing running" would lose every pump-started temporary
        // basal there is.
        val frames = Atc3ResponseParser().feed(hexToBytes(RUNNING_TBR))
        assertEquals(0, frames[0].recordCount)
        assertNotNull(Atc3TbrStatus.decode(frames[0]))
    }

    @Test
    fun `a short answer is nothing running rather than a record of zeroes`() {
        assertNull(decode(NO_TBR))
    }

    // What the pump does when it does not fill the start clock in

    @Test
    fun `a start clock the pump left empty is not believed`() {
        // Six zero bytes decode to 1999-11-30. Passed on, AAPS would refuse the record on age and
        // the temporary basal would never reach the insulin on board at all.
        val status = decode(withStartClock(RUNNING_TBR, ByteArray(6)))!!

        assertFalse(status.isStartPlausible(pumpNow = System.currentTimeMillis()))
    }

    @Test
    fun `the start the pump really gave is believed`() {
        val status = decode(RUNNING_TBR)!!

        assertTrue(status.isStartPlausible(pumpNow = status.startTimestamp + 60_000L))
    }

    @Test
    fun `a start older than a day is not believed either`() {
        val status = decode(RUNNING_TBR)!!

        assertFalse(status.isStartPlausible(pumpNow = status.startTimestamp + Atc3Const.RECONCILE_MAX_AGE_MS))
    }

    @Test
    fun `a start a little in the future is ordinary clock noise, not a wrong reading`() {
        // The difference between the two clocks is measured afresh on every read and moves by tens
        // of seconds between them, so a start slightly ahead of now has to stay usable.
        val status = decode(RUNNING_TBR)!!

        assertTrue(status.isStartPlausible(pumpNow = status.startTimestamp - 30_000L))
    }

    @Test
    fun `another object is not decoded as a temporary basal`() {
        assertNull(decode(withObjectType(RUNNING_TBR, Atc3Const.ObjectType.STATUS_V2)))
    }

    companion object {

        /**
         * 4.000 U/h started 2026-08-20 15:31:00, nothing delivered yet, count byte 0.
         *
         * The same frame the parser tests use as their multi field reference.
         */
        private const val RUNNING_TBR = """
            AA 1C 00 A3 0A 00 1A 08 14 0F 1F 00 00 1A 08 14
            0F 1F 00 01 04 00 A0 00 00 00 74 6D
            """

        /**
         * 0.625 U/h for 105 minutes started on the pump's keypad at 17:30:30 while another
         * temporary basal was already running.
         *
         * The first start clock is all zero and the second holds the start.
         */
        private const val KEYPAD_TBR_OVER_RUNNING = """
            AA 1C 00 A3 0A 00 00 00 00 00 00 00 00 1A 09 02
            11 1E 1E 01 07 00 19 00 00 00 8B 9F
            """

        /**
         * A temporary basal of 111 % for 75 minutes started at 11:36:00, with one raw unit
         * delivered.
         *
         * The mode byte at payload 13 is `00`, and the field an absolute command uses for the rate
         * holds 111 here — the percentage itself, not a rate on the 0.025 scale.
         */
        private val PERCENTAGE_TBR = withCrc(
            hexToBytes(
                """
                AA 1C 00 A3 0A 00 1A 09 03 0B 24 00 00 1A 09 03
                0B 24 00 00 05 00 6F 00 01 00
                """
            )
        )

        /** The shape of an answer with nothing running: the object, but no record behind it. */
        private val NO_TBR = shortAnswer()

        /** Build a valid but empty `0x0A` answer. */
        private fun shortAnswer(): String {
            val body = byteArrayOf(
                Atc3ResponseFrame.MARKER, 0x0A, 0x00, 0xA3.toByte(), 0x0A, 0x00, 0x00, 0x00
            )
            return withCrc(body)
        }

        /** The same frame with the six start clock bytes replaced. */
        private fun withStartClock(hex: String, clock: ByteArray): String {
            val frame = hexToBytes(hex)
            clock.copyInto(frame, START_CLOCK_OFFSET)
            return withCrc(frame.copyOfRange(0, frame.size - 2))
        }

        /** The same frame under a different object, to prove the decoder checks. */
        private fun withObjectType(hex: String, objectType: Byte): String {
            val frame = hexToBytes(hex)
            frame[OBJECT_TYPE_OFFSET] = objectType
            return withCrc(frame.copyOfRange(0, frame.size - 2))
        }

        private fun withCrc(body: ByteArray): String =
            (body + CrcUtil.crc16ModbusLeBytes(body)).joinToString(" ") { "%02X".format(it) }

        /** Offset of the object type byte inside a response frame. */
        private const val OBJECT_TYPE_OFFSET = 4

        /** Where the start clock sits in the raw frame, header included. */
        private const val START_CLOCK_OFFSET = 6
    }
}
