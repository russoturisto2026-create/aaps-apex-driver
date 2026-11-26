package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexDailyRecordTest
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.protocol.ApexResponseParser
import app.aaps.pump.apex.protocol.CrcUtil
import app.aaps.pump.apex.protocol.CrcUtilTest.Companion.hexToBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The rule that tells a broken cell from a real record, on frames of each journal. */
class BrokenRecordTest {

    private fun <T> answerOf(hex: String, decode: (app.aaps.pump.apex.protocol.ApexResponseFrame) -> T?): ApexJournalAnswer<T> {
        val frames = ApexResponseParser().feed(hexToBytes(hex))
        assertEquals(1, frames.size)
        val record = decode(frames[0])
        assertNotNull(record)
        return ApexJournalAnswer(listOf(ApexRead(frames[0], record!!)), frames[0].recordCount)
    }

    /** The frame with its two CRC bytes replaced by the right ones for its new content. */
    private fun withCrc(hexWithoutCrc: String): String {
        val crc = CrcUtil.crc16ModbusLeBytes(hexToBytes(hexWithoutCrc))
        return hexWithoutCrc + " %02X %02X".format(crc[0], crc[1])
    }

    @Test
    fun `a day with a date is not broken, a day without one is`() {
        assertTrue(BrokenRecord.brokenDay(answerOf(ApexDailyRecordTest.DAY_FRAME, ApexDailyRecord::decode), emptyList(), closedToo = false).isEmpty())
        // The day frame with its day byte zeroed: AA 12 17 A3 06 00 | 28 00 7E 02 1F 00 | 1A 09 00 | 00.
        val noDay = withCrc("AA 12 17 A3 06 00 28 00 7E 02 1F 00 1A 09 00 00")
        assertTrue(BrokenRecord.brokenDay(answerOf(noDay, ApexDailyRecord::decode), emptyList(), closedToo = false).isNotEmpty())
    }

    @Test
    fun `the same answer twice is the same, a changed byte is not`() {
        val a = answerOf(ApexDailyRecordTest.DAY_FRAME, ApexDailyRecord::decode)
        val b = answerOf(ApexDailyRecordTest.DAY_FRAME, ApexDailyRecord::decode)
        assertTrue(BrokenRecord.sameAnswer(a, b))
        val c = answerOf(withCrc("AA 12 17 A3 06 00 29 00 7E 02 1F 00 1A 09 02 00"), ApexDailyRecord::decode)
        assertFalse(BrokenRecord.sameAnswer(a, c))
    }
}
