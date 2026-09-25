package app.aaps.pump.atc3.comm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CRC-16/Modbus is a well known standard algorithm, so it can be checked against published test
 * vectors independently of anything ATC3 specific, and then against protocol frames.
 */
class CrcUtilTest {

    @Test
    fun `standard test vector`() {
        // The canonical CRC-16/Modbus check value for the ASCII string "123456789" is 0x4B37.
        assertEquals(0x4B37, CrcUtil.crc16Modbus("123456789".toByteArray()))
    }

    @Test
    fun `empty input returns the initial value`() {
        assertEquals(0xFFFF, CrcUtil.crc16Modbus(ByteArray(0)))
    }

    @Test
    fun `little endian bytes are low byte first`() {
        val bytes = CrcUtil.crc16ModbusLeBytes("123456789".toByteArray())
        assertEquals(0x37.toByte(), bytes[0])
        assertEquals(0x4B.toByte(), bytes[1])
    }

    @Test
    fun `a profile switch acknowledgement verifies`() {
        // The acknowledgement of a profile switch.
        val ack = hexToBytes("AA 0A 00 A1 55 AA 00 00 EC 39")
        assertTrue(CrcUtil.isFrameValid(ack))
    }

    @Test
    fun `a status V2 response verifies`() {
        val statusV2 = hexToBytes(
            """
            AA 1C 01 A3 0C AA 11 00 01 95 00 00 00 00 00 00
            00 00 00 00 00 00 00 00 00 00 85 87
            """
        )
        assertTrue(CrcUtil.isFrameValid(statusV2))
    }

    @Test
    fun `a status V1 response verifies`() {
        assertTrue(CrcUtil.isFrameValid(hexToBytes(STATUS_V1_FRAME)))
    }

    @Test
    fun `a corrupted frame is rejected`() {
        val ack = hexToBytes("AA 0A 00 A1 55 AA 00 00 EC 39")
        ack[6] = 0x01
        assertFalse(CrcUtil.isFrameValid(ack))
    }

    @Test
    fun `a frame shorter than a CRC is rejected`() {
        assertFalse(CrcUtil.isFrameValid(byteArrayOf(0xAA.toByte(), 0x01)))
    }

    companion object {

        /**
         * A Status V1 response at pump time 17:27:34: 300.725 U in the reservoir, 0.600 U/h
         * scheduled basal.
         */
        const val STATUS_V1_FRAME = """
            AA 60 01 A3 00 AA 04 00 01 02 00 00 00 01 14 04
            01 00 00 00 2C 01 D0 00 00 00 64 00 00 00 A0 00
            90 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            00 00 1A 08 0F 11 1B 22 00 00 B5 96 04 00 00 00
            00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            00 00 18 00 00 00 00 00 00 00 00 00 00 00 13 B0
            """

        fun hexToBytes(hex: String): ByteArray =
            hex.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .map { it.toInt(16).toByte() }
                .toByteArray()
    }
}
