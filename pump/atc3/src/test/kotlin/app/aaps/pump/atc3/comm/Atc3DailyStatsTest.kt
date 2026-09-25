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
 * The pump's own daily totals, object `0x06`.
 *
 * The date is pinned only as far as "this reading is self consistent"; what stops a wrong reading
 * from reaching AAPS is the check in the plugin, not this decoder.
 */
class Atc3DailyStatsTest {

    private fun decode(hex: String): Atc3DailyStats? {
        val frames = Atc3ResponseParser().feed(hexToBytes(hex))
        assertEquals(1, frames.size)
        return Atc3DailyStats.decode(frames[0])
    }

    @Test
    fun `the three amounts match what the statistics screen showed`() {
        val day = decode(ONE_DAY)
        assertNotNull(day)
        // Raw 46, 25 and 62, a total of 3.325 U.
        assertEquals(1.150, day!!.bolusUnits, 1e-9)
        assertEquals(0.625, day.basalUnits, 1e-9)
        assertEquals(1.550, day.tbrUnits, 1e-9)
        assertEquals(3.325, day.totalUnits, 1e-9)
    }

    @Test
    fun `basal as AAPS counts it includes what the temporary basal changed`() {
        val day = decode(ONE_DAY)!!
        assertEquals(0.625 + 1.550, day.basalWithTbrUnits, 1e-9)
        assertEquals(day.bolusUnits + day.basalWithTbrUnits, day.totalUnits, 1e-9)
    }

    @Test
    fun `the date is read as year month day`() {
        val day = decode(ONE_DAY)!!
        assertEquals(2026, day.year)
        assertEquals(8, day.month)
        assertEquals(20, day.day)
        assertTrue(day.isDatePlausible)
    }

    @Test
    fun `a day knows whether it is the same day as an instant`() {
        val day = decode(ONE_DAY)!!
        val sameDay = Calendar.getInstance().apply { clear(); set(2026, 7, 20, 13, 45, 0) }.timeInMillis
        val nextDay = Calendar.getInstance().apply { clear(); set(2026, 7, 21, 13, 45, 0) }.timeInMillis
        assertTrue(day.isSameDayAs(sameDay))
        assertFalse(day.isSameDayAs(nextDay))
    }

    @Test
    fun `midnight of that day is where the total belongs`() {
        val start = Calendar.getInstance().apply { timeInMillis = decode(ONE_DAY)!!.startOfDayMillis() }
        assertEquals(2026, start.get(Calendar.YEAR))
        assertEquals(8, start.get(Calendar.MONTH) + 1)
        assertEquals(20, start.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, start.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, start.get(Calendar.MINUTE))
    }

    @Test
    fun `a day before the pump was used carries nothing and says so`() {
        val day = decode(UNUSED_DAY)!!
        assertTrue(day.isEmpty)
        assertEquals(0.0, day.totalUnits, 1e-9)
    }

    @Test
    fun `nonsense in the date bytes is recognised rather than believed`() {
        val day = decode(IMPOSSIBLE_DATE)!!
        assertFalse(day.isDatePlausible)
    }

    @Test
    fun `another object is not decoded as a daily total`() {
        val frame = hexToBytes(ONE_DAY)
        frame[OBJECT_TYPE_OFFSET] = Atc3Const.ObjectType.STATUS_V2
        val body = frame.copyOfRange(0, frame.size - 2)
        val hex = (body + CrcUtil.crc16ModbusLeBytes(body)).joinToString(" ") { "%02X".format(it) }
        assertNull(decode(hex))
    }

    companion object {

        private const val OBJECT_TYPE_OFFSET = 4

        /** Raw 46 bolus, 25 basal, 62 temporary basal on 2026-08-20. */
        private val ONE_DAY = dayFrame(bolus = 46, basal = 25, tbr = 62, year = 26, month = 8, day = 20)

        /** A day from before the pump was in use: the pump sends zeroes. */
        private val UNUSED_DAY = dayFrame(bolus = 0, basal = 0, tbr = 0, year = 0, month = 0, day = 0)

        /** Month 99 cannot be a month, whatever the bytes are supposed to mean. */
        private val IMPOSSIBLE_DATE = dayFrame(bolus = 46, basal = 25, tbr = 62, year = 26, month = 99, day = 20)

        /**
         * Build a statistics frame in the documented layout.
         *
         * The frame is assembled from the offsets of [Atc3Const.DailyStats].
         */
        private fun dayFrame(bolus: Int, basal: Int, tbr: Int, year: Int, month: Int, day: Int): String {
            val body = byteArrayOf(
                Atc3ResponseFrame.MARKER, 0x11, 0x01, 0xA3.toByte(), Atc3Const.ObjectType.DAILY_STATS, 0x00,
                (bolus and 0xFF).toByte(), ((bolus shr 8) and 0xFF).toByte(),
                (basal and 0xFF).toByte(), ((basal shr 8) and 0xFF).toByte(),
                (tbr and 0xFF).toByte(), ((tbr shr 8) and 0xFF).toByte(),
                year.toByte(), month.toByte(), day.toByte()
            )
            return (body + CrcUtil.crc16ModbusLeBytes(body)).joinToString(" ") { "%02X".format(it) }
        }
    }
}
