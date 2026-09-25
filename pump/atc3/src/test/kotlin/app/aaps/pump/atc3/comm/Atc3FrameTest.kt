package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Atc3FrameTest {

    /** A serial of the expected shape: the identity prefix then eight digits. */
    private val identity = Atc3Frame.identityOf(SERIAL)

    @Test
    fun `identity is the prefix followed by the serial number`() {
        assertEquals(12, identity.size)
        assertEquals(Atc3Frame.IDENTITY_PREFIX + SERIAL, String(identity, Charsets.US_ASCII))
    }

    @Test
    fun `a serial of the wrong length is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Atc3Frame.identityOf("1234567") }
        assertThrows(IllegalArgumentException::class.java) { Atc3Frame.identityOf("123456789") }
    }

    @Test
    fun `a non numeric serial is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Atc3Frame.identityOf("1234567X") }
    }

    @Test
    fun `only an eight digit serial is usable`() {
        assertTrue(Atc3Frame.isValidSerial(SERIAL))
        assertTrue(Atc3Frame.isValidSerial(" $SERIAL "))
        assertFalse(Atc3Frame.isValidSerial("1234567"))
        assertFalse(Atc3Frame.isValidSerial("123456789"))
        assertFalse(Atc3Frame.isValidSerial("1234567X"))
        assertFalse(Atc3Frame.isValidSerial(""))
    }

    @Test
    fun `a read request has the layout seen on the wire`() {
        // Status read: 35 14 00 A3 00 AA <identity> <CRC>
        val frame = Atc3Frame.buildRequest(
            group = Atc3Const.GROUP_CONTROL,
            mode = Atc3Const.MODE_HISTORY,
            opcode = Atc3Const.ReadOpcode.STATUS_V1,
            identity = identity
        )
        assertEquals(20, frame.size)
        assertEquals(0x35.toByte(), frame[0])
        assertEquals(0x14.toByte(), frame[1]) // declared length 20, low byte
        assertEquals(0x00.toByte(), frame[2])
        assertEquals(0xA3.toByte(), frame[3])
        assertEquals(0x00.toByte(), frame[4])
        assertEquals(0xAA.toByte(), frame[5]) // no parameter
        assertArrayEquals(identity, frame.copyOfRange(6, 18))
        assertTrue(CrcUtil.isFrameValid(frame))
    }

    @Test
    fun `the declared length always equals the real frame length`() {
        for (payloadSize in intArrayOf(0, 1, 4, 96)) {
            val frame = Atc3Frame.buildRequest(
                group = Atc3Const.GROUP_CONTROL,
                mode = Atc3Const.MODE_CONTROL,
                opcode = Atc3Const.ControlOpcode.WRITE_BASAL_PROFILE,
                identity = identity,
                payload = ByteArray(payloadSize)
            )
            val declared = (frame[1].toInt() and 0xFF) or ((frame[2].toInt() and 0xFF) shl 8)
            assertEquals(frame.size, declared)
            assertTrue(CrcUtil.isFrameValid(frame))
        }
    }

    @Test
    fun `a profile switch request has the expected shape`() {
        // 35 15 00 A1 04 AA <identity> 01 <CRC>, total 21 bytes.
        val frame = Atc3Frame.buildRequest(
            group = Atc3Const.GROUP_CONTROL,
            mode = Atc3Const.MODE_CONTROL,
            opcode = Atc3Const.ControlOpcode.SWITCH_PROFILE,
            identity = identity,
            payload = byteArrayOf(0x01)
        )
        assertEquals(21, frame.size)
        assertEquals(0x15.toByte(), frame[1])
        assertEquals(0xA1.toByte(), frame[3])
        assertEquals(0x04.toByte(), frame[4])
        assertEquals(0x01.toByte(), frame[18])
        assertTrue(CrcUtil.isFrameValid(frame))
    }

    @Test
    fun `the parameter byte is carried when an opcode takes one`() {
        // The latest bolus read carries 0x01 at offset 5: 35/A3/21/01.
        val frame = Atc3Frame.buildRequest(
            group = Atc3Const.GROUP_CONTROL,
            mode = Atc3Const.MODE_HISTORY,
            opcode = Atc3Const.HistoryOpcode.LATEST_BOLUS,
            identity = identity,
            parameter = Atc3Const.PARAMETER_LATEST
        )
        assertEquals(20, frame.size)
        assertEquals(0x21.toByte(), frame[4])
        assertEquals(0x01.toByte(), frame[5])
        assertTrue(CrcUtil.isFrameValid(frame))
    }

    @Test
    fun `a bolus request carries the raw amount little endian`() {
        // Payload 2E 00 00, raw 46, exactly 1.15 U.
        // Doses must be rounded, never truncated: 1.15 / 0.025 evaluates to 45.999... in binary
        // floating point, so toInt() would send 1.125 U instead of the requested 1.15 U.
        val raw = Math.round(1.15 / Atc3Const.DOSE_SCALE).toInt()
        assertEquals(46, raw)
        assertEquals(45, (1.15 / Atc3Const.DOSE_SCALE).toInt(), "truncation is wrong here, kept as a warning")
        val frame = Atc3Frame.buildRequest(
            group = Atc3Const.GROUP_CONTROL,
            mode = Atc3Const.MODE_CONTROL,
            opcode = Atc3Const.ControlOpcode.BOLUS,
            identity = identity,
            payload = byteArrayOf((raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte(), 0x00)
        )
        assertArrayEquals(byteArrayOf(0x2E, 0x00, 0x00), frame.copyOfRange(18, 21))
        assertTrue(CrcUtil.isFrameValid(frame))
    }

    @Test
    fun `an identity of the wrong size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Atc3Frame.buildRequest(
                Atc3Const.GROUP_CONTROL, Atc3Const.MODE_HISTORY, Atc3Const.ReadOpcode.STATUS_V1, ByteArray(11)
            )
        }
    }

    companion object {

        /** Placeholder serial, not a real device. */
        private const val SERIAL = "12345678"
    }
}
