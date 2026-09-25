package app.aaps.pump.atc3.comm

/**
 * CRC-16/Modbus, used by every ATC3 frame in both directions.
 *
 * Parameters: polynomial 0xA001 (reflected 0x8005), initial value 0xFFFF, reflected in and out,
 * no final xor. Appended to a frame as two little endian bytes.
 */
object CrcUtil {

    private const val POLYNOMIAL = 0xA001
    private const val INITIAL = 0xFFFF

    /** Compute the CRC over [length] bytes of [data]. */
    fun crc16Modbus(data: ByteArray, length: Int = data.size): Int {
        var crc = INITIAL
        for (index in 0 until length) {
            crc = crc xor (data[index].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc shr 1) xor POLYNOMIAL else crc shr 1
            }
        }
        return crc and 0xFFFF
    }

    /** The CRC of [data] as the two little endian bytes that get appended to a frame. */
    fun crc16ModbusLeBytes(data: ByteArray, length: Int = data.size): ByteArray {
        val crc = crc16Modbus(data, length)
        return byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    /**
     * Verify a complete frame whose last two bytes are the little endian CRC over everything before them.
     */
    fun isFrameValid(frame: ByteArray): Boolean {
        if (frame.size < 3) return false
        val expected = (frame[frame.size - 2].toInt() and 0xFF) or ((frame[frame.size - 1].toInt() and 0xFF) shl 8)
        return crc16Modbus(frame, frame.size - 2) == expected
    }
}
