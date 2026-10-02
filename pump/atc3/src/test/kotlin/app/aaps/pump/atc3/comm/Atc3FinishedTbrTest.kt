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
 * Decodes the last finished temporary basal, object `0x0B`.
 *
 * The frames are fed through the real parser, so their check bytes are verified too.
 *
 * Two fields carry the whole value of this object, and each has its own tests below: the end clock,
 * which is what keeps a temporary basal from being recorded as having run until the poll that
 * noticed it was over, and the result byte, which is the only signal on the wire that somebody
 * stopped one by hand.
 */
class Atc3FinishedTbrTest {

    private fun decode(hex: String): Atc3FinishedTbr? {
        val frames = Atc3ResponseParser().feed(hexToBytes(hex))
        assertEquals(1, frames.size)
        return Atc3FinishedTbr.decode(frames[0])
    }

    private fun fieldsOf(timestamp: Long): List<Int> =
        Calendar.getInstance().apply { timeInMillis = timestamp }.let {
            listOf(
                it.get(Calendar.YEAR), it.get(Calendar.MONTH), it.get(Calendar.DAY_OF_MONTH),
                it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE), it.get(Calendar.SECOND)
            )
        }

    @Test
    fun `a temporary basal that ran its course decodes whole`() {
        val record = decode(COMPLETED)
        assertNotNull(record)
        assertTrue(record!!.completed)
        assertFalse(record.cancelledOnPump)
        assertEquals(2.000, record.rate!!, 1e-9)
        assertNull(record.percent)
        assertEquals(30, record.durationMinutes)
        assertEquals(0.975, record.deliveredUnits, 1e-9)
    }

    @Test
    fun `the end is the moment it ended, not the moment we looked`() {
        val record = decode(COMPLETED)!!
        // Started 17:38, ran 30 minutes, ended 18:09. This is the field that makes the difference
        // between a temporary basal recorded as it ran and one recorded up to a poll interval long.
        assertEquals(listOf(2026, Calendar.AUGUST, 23, 17, 38, 0), fieldsOf(record.startTimestamp))
        assertEquals(listOf(2026, Calendar.AUGUST, 23, 18, 9, 0), fieldsOf(record.endTimestamp))
    }

    @Test
    fun `an end clock carries seconds and they are kept`() {
        // The worked examples of this object all happen to fall on
        // whole minutes. This one does not, and truncating it would put the end fourteen seconds
        // early every time.
        val record = decode(CANCELLED_ON_PUMP)!!
        assertEquals(listOf(2026, Calendar.AUGUST, 23, 23, 34, 14), fieldsOf(record.endTimestamp))
    }

    @Test
    fun `a temporary basal stopped by hand says so`() {
        val record = decode(CANCELLED_ON_PUMP)
        assertNotNull(record)
        assertTrue(record!!.cancelledOnPump)
        assertFalse(record.completed)
        assertFalse(record.cancelledByCommand)
        // Started for 45 minutes at 2.000 U/h and stopped after nine, so 0.275 U went in rather
        // than the 1.500 U the duration would suggest. The pump's figure needs no reconstruction.
        assertEquals(45, record.durationMinutes)
        assertEquals(0.275, record.deliveredUnits, 1e-9)
    }

    @Test
    fun `the second start clock is used when the pump leaves the first empty`() {
        // The same trap object 0x0A has: the pump sometimes fills only the second copy. Read from
        // the first, this record starts in 1999 and AAPS refuses it as older than the pump itself.
        val record = decode(CANCELLED_ON_PUMP)!!
        assertEquals(listOf(2026, Calendar.AUGUST, 23, 23, 25, 0), fieldsOf(record.startTimestamp))
    }

    @Test
    fun `a temporary basal a command stopped is told from one stopped by hand`() {
        val record = decode(CANCELLED_BY_COMMAND)
        assertNotNull(record)
        assertTrue(record!!.cancelledByCommand)
        assertFalse(record.cancelledOnPump)
        // Which matters because only one of the two means a person walked up to the pump.
        assertEquals("cancelled by a command", record.resultText)
    }

    @Test
    fun `a temporary basal that delivered nothing is still a record`() {
        val record = decode(NOTHING_GIVEN)
        assertNotNull(record)
        assertEquals(0.0, record!!.rate!!, 1e-9)
        assertEquals(0.0, record.deliveredUnits, 1e-9)
        assertTrue(record.cancelledByCommand)
    }

    @Test
    fun `refuses a frame of another object`() {
        // Object 0x0A, the running command. It carries a start and a rate too, in other places.
        assertNull(decode(ACTIVE_TBR))
    }

    companion object {

        /** 2.000 U/h for 30 min from 2026-08-23 17:38, ran to its end, 0.975 U given. */
        private const val COMPLETED =
            "AA 22 00 A3 0B 00 01 1A 08 17 11 26 00 1A 08 17 11 26 00 01 02 00 50 00 1A 08 17 12 09 00 27 00 15 34"

        /**
         * Cancelled on the pump's keypad, 2026-08-23 23:25 to 23:34:14, 0.275 U given.
         *
         * Its first start clock is all zero and the second holds the start.
         */
        private const val CANCELLED_ON_PUMP =
            "AA 22 00 A3 0B 00 02 00 00 00 00 00 00 1A 08 17 17 19 00 01 03 00 50 00 1A 08 17 17 22 0E 0B 00 3E BA"

        /** 4.000 U/h stopped by a command a minute after it started, 0.025 U given. */
        private const val CANCELLED_BY_COMMAND =
            "AA 22 00 A3 0B 00 03 1A 08 14 0F 1F 00 1A 08 14 0F 1F 00 01 04 00 A0 00 1A 08 14 0F 20 00 01 00 13 09"

        /** Stopped by a command having delivered nothing at all. */
        private const val NOTHING_GIVEN =
            "AA 22 00 A3 0B 00 03 1A 08 16 16 0C 00 1A 08 16 16 0C 00 01 04 00 00 00 1A 08 16 16 24 00 00 00 C0 40"

        /** Object 0x0A, the last temporary basal command: 4.000 U/h from 2026-08-20 15:31. */
        private const val ACTIVE_TBR = """
            AA 1C 00 A3 0A 00 1A 08 14 0F 1F 00 00 1A 08 14
            0F 1F 00 01 04 00 A0 00 00 00 74 6D
            """
    }
}
