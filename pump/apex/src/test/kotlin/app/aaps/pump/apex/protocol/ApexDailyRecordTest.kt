package app.aaps.pump.apex.protocol

import app.aaps.pump.apex.protocol.CrcUtilTest.Companion.hexToBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Decodes the daily statistics, objects `0x06` and `0x26`. The frame is the one of the protocol
 * document, fed through the real parser so the check bytes are verified with the fields.
 */
class ApexDailyRecordTest {

    private fun decode(hex: String): ApexDailyRecord? {
        val frames = ApexResponseParser().feed(hexToBytes(hex))
        assertEquals(1, frames.size)
        return ApexDailyRecord.decode(frames[0])
    }

    @Test
    fun `a day decodes to its three kinds of insulin and its date`() {
        val record = decode(DAY_FRAME)
        assertNotNull(record)
        assertEquals(0, record!!.index)
        assertEquals(1.000, record.bolusUnits, 1e-9)
        assertEquals(15.950, record.basalUnits, 1e-9)
        assertEquals(0.775, record.tbrUnits, 1e-9)
        // The sum is what Status V1 counted that day: raw 709.
        assertEquals(709 * ApexProtocol.DOSE_SCALE, record.totalUnits, 1e-9)
        val day = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = record.dayClockSeconds * 1000L }
        assertEquals(2026, day.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, day.get(Calendar.MONTH))
        assertEquals(2, day.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, day.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `the record count of the answer is the frame's`() {
        val frames = ApexResponseParser().feed(hexToBytes(DAY_FRAME))
        assertEquals(23, frames[0].recordCount)
    }

    @Test
    fun `another object is not a day`() {
        // The same bytes under the temporary basal history object.
        assertNull(decode(DAY_FRAME.replace("A3 06 00", "A3 27 00").replace("B9 A2", crcOf("AA 12 17 A3 27 00 28 00 7E 02 1F 00 1A 09 02 00"))))
    }

    private fun crcOf(hexWithoutCrc: String): String {
        val crc = CrcUtil.crc16ModbusLeBytes(hexToBytes(hexWithoutCrc))
        return "%02X %02X".format(crc[0], crc[1])
    }

    companion object {

        /** 2026-09-02: 1.000 U bolus, 15.950 U basal, 0.775 U temporary basal, 23 days in the journal. */
        const val DAY_FRAME = "AA 12 17 A3 06 00 28 00 7E 02 1F 00 1A 09 02 00 B9 A2"
    }
}
