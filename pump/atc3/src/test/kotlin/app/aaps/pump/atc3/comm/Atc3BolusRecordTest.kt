package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.comm.CrcUtilTest.Companion.hexToBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * Decodes bolus records, including one for a bolus that was cancelled while running.
 */
class Atc3BolusRecordTest {

    private fun decode(hex: String): Atc3BolusRecord {
        val frames = Atc3ResponseParser().feed(hexToBytes(hex))
        assertEquals(1, frames.size)
        return Atc3BolusRecord.decode(frames[0]).also { assertNotNull(it) }!!
    }

    /**
     * The same frame with its object byte changed and the CRC recomputed.
     *
     * The full history answers with object `0x01` and the periodic search with `0x21`, and the
     * record layout is identical byte for byte, so a `0x01` frame is derived from a `0x21` one:
     * what it proves is that the decoder accepts the object.
     */
    private fun asFullHistoryObject(hex: String): ByteArray {
        val frame = hexToBytes(hex)
        frame[OBJECT_TYPE_OFFSET] = Atc3Const.ObjectType.BOLUS_RECORD
        val crc = CrcUtil.crc16ModbusLeBytes(frame, frame.size - 2)
        frame[frame.size - 2] = crc[0]
        frame[frame.size - 1] = crc[1]
        return frame
    }

    @Test
    fun `a record of the full history screen decodes exactly like the periodic search`() {
        val fromSearch = decode(CANCELLED_BOLUS)
        val frames = Atc3ResponseParser().feed(asFullHistoryObject(CANCELLED_BOLUS))
        assertEquals(1, frames.size)
        val fromFullHistory = Atc3BolusRecord.decode(frames[0])
        assertNotNull(fromFullHistory)
        assertEquals(fromSearch, fromFullHistory)
    }

    @Test
    fun `a cancelled bolus reports what was asked for and what was delivered`() {
        val record = decode(CANCELLED_BOLUS)
        // The pump was asked for 10.0 U and stopped after 0.300 U.
        assertEquals(10.0, record.requestedUnits, 1e-9)
        assertEquals(0.300, record.deliveredUnits, 1e-9)
        assertTrue(record.isIncomplete)
    }

    @Test
    fun `the record carries the time the bolus started`() {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = decode(CANCELLED_BOLUS).timestamp
        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(8, calendar.get(Calendar.MONTH) + 1)
        assertEquals(20, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(13, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(32, calendar.get(Calendar.MINUTE))
        assertEquals(59, calendar.get(Calendar.SECOND))
    }

    @Test
    fun `a completed bolus delivered exactly what was asked for`() {
        val record = decode(COMPLETED_BOLUS)
        assertEquals(1.15, record.requestedUnits, 1e-9)
        assertEquals(1.15, record.deliveredUnits, 1e-9)
        assertFalse(record.isIncomplete)
    }

    @Test
    fun `a cancelled extended bolus reports the extended part only`() {
        val record = decode(CANCELLED_EXTENDED_BOLUS)
        // 3.400 U asked for as an extended bolus, cancelled after the pump's smallest step.
        assertEquals(0.0, record.requestedUnits, 1e-9)
        assertEquals(0.0, record.deliveredUnits, 1e-9)
        assertEquals(3.400, record.extendedRequestedUnits, 1e-9)
        assertEquals(0.050, record.extendedDeliveredUnits, 1e-9)
        assertEquals(0.050, record.totalDeliveredUnits, 1e-9)
        assertTrue(record.carriesExtendedPart)
        assertTrue(record.isIncomplete)
    }

    @Test
    fun `a cancelled dual bolus keeps its two parts apart`() {
        val record = decode(CANCELLED_DUAL_BOLUS)
        // 2.000 U immediately and 1.000 U extended, cancelled after 0.450 U of the immediate part.
        assertEquals(2.000, record.requestedUnits, 1e-9)
        assertEquals(0.450, record.deliveredUnits, 1e-9)
        assertEquals(1.000, record.extendedRequestedUnits, 1e-9)
        assertEquals(0.0, record.extendedDeliveredUnits, 1e-9)
        assertEquals(3.000, record.totalRequestedUnits, 1e-9)
        assertEquals(0.450, record.totalDeliveredUnits, 1e-9)
        assertTrue(record.carriesExtendedPart)
        assertTrue(record.isIncomplete)
    }

    @Test
    fun `a standard bolus carries no extended part`() {
        val record = decode(COMPLETED_BOLUS)
        assertEquals(0.0, record.extendedRequestedUnits, 1e-9)
        assertEquals(0.0, record.extendedDeliveredUnits, 1e-9)
        assertFalse(record.carriesExtendedPart)
    }

    @Test
    fun `the record count never closes an answer the pump cut short`() {
        // Eleven records stored, ten sent: the last frame of the burst still looks unfinished, so
        // a read that only trusted the count would wait for a frame that never comes.
        val frames = Atc3ResponseParser().feed(hexToBytes(TENTH_OF_ELEVEN))
        assertEquals(11, frames[0].recordCount)
        assertEquals(9, frames[0].recordIndex)
        assertFalse(frames[0].isLastRecord)
    }

    @Test
    fun `records carry their position in the history`() {
        assertEquals(0, decode(CANCELLED_BOLUS).index)
        assertEquals(1, decode(COMPLETED_BOLUS).index)
    }

    companion object {

        /** Offset of the object type byte inside a response frame. */
        private const val OBJECT_TYPE_OFFSET = 4

        /** 10.0 U requested, cancelled after 0.300 U. */
        private const val CANCELLED_BOLUS = """
            AA 16 02 A3 21 00 1A 08 14 0D 20 3B 90 01 0C 00
            00 00 00 00 A9 55
            """

        /** 3.400 U extended bolus, cancelled after 0.050 U. */
        private const val CANCELLED_EXTENDED_BOLUS = """
            AA 16 09 A3 21 00 1A 08 14 10 0B 3B 00 00 00 00
            88 00 02 00 F7 30
            """

        /** Dual bolus of 2.000 U now and 1.000 U extended, cancelled after 0.450 U. */
        private const val CANCELLED_DUAL_BOLUS = """
            AA 16 0A A3 21 00 1A 08 14 10 0C 3B 50 00 12 00
            28 00 00 00 93 80
            """

        /** Record 9 of an answer whose count byte says 11, the shape that stalled a history read. */
        private const val TENTH_OF_ELEVEN = """
            AA 16 0B A3 21 09 1A 08 14 10 0C 3B 50 00 12 00
            28 00 00 00 0B 7A
            """

        /** 1.15 U delivered in full. */
        private const val COMPLETED_BOLUS = """
            AA 16 02 A3 21 01 1A 08 0F 0D 27 3B 2E 00 2E 00
            00 00 00 00 61 11
            """
    }
}
