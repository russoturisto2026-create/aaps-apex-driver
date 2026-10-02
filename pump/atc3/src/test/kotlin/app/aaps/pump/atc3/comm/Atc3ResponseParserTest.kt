package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.comm.CrcUtilTest.Companion.hexToBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Atc3ResponseParserTest {

    private val ack = hexToBytes("AA 0A 00 A1 55 AA 00 00 EC 39")
    private val statusV1 = hexToBytes(CrcUtilTest.STATUS_V1_FRAME)

    @Test
    fun `a whole frame in one chunk is returned`() {
        val frames = Atc3ResponseParser().feed(ack)
        assertEquals(1, frames.size)
        assertEquals(0xA1.toByte(), frames[0].frameId)
        assertEquals(0x55.toByte(), frames[0].objectType)
    }

    @Test
    fun `a frame split across chunks is reassembled`() {
        val parser = Atc3ResponseParser()
        assertTrue(parser.feed(statusV1.copyOfRange(0, 20)).isEmpty())
        assertTrue(parser.feed(statusV1.copyOfRange(20, 60)).isEmpty())
        val frames = parser.feed(statusV1.copyOfRange(60, statusV1.size))
        assertEquals(1, frames.size)
        assertEquals(96, frames[0].raw.size)
    }

    @Test
    fun `two frames in one chunk are both returned`() {
        val frames = Atc3ResponseParser().feed(ack + statusV1)
        assertEquals(2, frames.size)
        assertEquals(0xA1.toByte(), frames[0].frameId)
        assertEquals(0xA3.toByte(), frames[1].frameId)
    }

    @Test
    fun `bytes before the marker are discarded`() {
        val frames = Atc3ResponseParser().feed(byteArrayOf(0x00, 0x11, 0x22) + ack)
        assertEquals(1, frames.size)
    }

    @Test
    fun `a corrupted frame is dropped and counted`() {
        val corrupted = ack.copyOf()
        corrupted[6] = 0x01
        val parser = Atc3ResponseParser()
        assertTrue(parser.feed(corrupted).isEmpty())
        assertEquals(1, parser.crcErrors)
    }

    @Test
    fun `a frame following a corrupted one is still parsed`() {
        val corrupted = ack.copyOf()
        corrupted[6] = 0x01
        val frames = Atc3ResponseParser().feed(corrupted + statusV1)
        assertEquals(1, frames.size)
        assertEquals(0xA3.toByte(), frames[0].frameId)
    }

    @Test
    fun `frame id 0xA5 adds two bytes to the declared length`() {
        // The length rule is expected = buffer[1] + (2 if buffer[3] == 0xA5 else 0).
        // Build a synthetic 0xA5 frame whose declared length is two less than its real size.
        val body = byteArrayOf(
            Atc3ResponseFrame.MARKER, 0x0A, 0x01, 0xA5.toByte(), 0x00, 0x00,
            0x01, 0x02, 0x03, 0x04
        )
        val frame = body + CrcUtil.crc16ModbusLeBytes(body)
        assertEquals(12, frame.size)
        assertEquals(0x0A, frame[Atc3ResponseFrame.LENGTH_OFFSET].toInt())

        val frames = Atc3ResponseParser().feed(frame)
        assertEquals(1, frames.size)
        assertEquals(12, frames[0].raw.size)
    }

    @Test
    fun `a record burst only counts as finished on its last frame`() {
        // Frames from a ten record bolus history, the first and the last of the burst.
        val first = Atc3ResponseParser().feed(
            hexToBytes("AA 16 0A A3 21 00 1A 08 14 10 0C 3B 50 00 12 00 28 00 00 00 93 80")
        )[0]
        val last = Atc3ResponseParser().feed(
            hexToBytes("AA 16 0A A3 21 09 1A 08 0F 0D 27 3B 2E 00 2E 00 00 00 00 00 EB 75")
        )[0]
        assertEquals(10, first.recordCount)
        assertFalse(first.isLastRecord)
        assertTrue(last.isLastRecord)
    }

    @Test
    fun `a single record answer is finished at once`() {
        val status = Atc3ResponseParser().feed(statusV1)[0]
        assertEquals(1, status.recordCount)
        assertTrue(status.isLastRecord)
    }

    @Test
    fun `an answer carrying record count zero is finished at once`() {
        // A temporary basal frame: the pump sends count 0 for this object.
        val frames = Atc3ResponseParser().feed(
            hexToBytes("AA 1C 00 A3 0A 00 1A 08 14 0F 1F 00 00 1A 08 14 0F 1F 00 01 04 00 A0 00 00 00 74 6D")
        )
        assertEquals(1, frames.size)
        assertEquals(0, frames[0].recordCount)
        assertTrue(frames[0].isLastRecord)
    }

    @Test
    fun `reset drops a partial frame`() {
        val parser = Atc3ResponseParser()
        parser.feed(statusV1.copyOfRange(0, 20))
        parser.reset()
        assertTrue(parser.feed(statusV1.copyOfRange(20, statusV1.size)).isEmpty())
    }
}
