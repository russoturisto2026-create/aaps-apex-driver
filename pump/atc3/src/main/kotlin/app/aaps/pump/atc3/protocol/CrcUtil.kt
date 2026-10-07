package app.aaps.pump.atc3.protocol

/** CRC-16/Modbus, which every frame in both directions carries, little endian, at its end. */
object CrcUtil {

    private const val POLYNOMIAL = 0xA001
    private const val INITIAL = 0xFFFF

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

    /** The CRC as the two bytes appended to a frame. */
    fun crc16ModbusLeBytes(data: ByteArray, length: Int = data.size): ByteArray {
        val crc = crc16Modbus(data, length)
        return byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    /** Whether a frame's last two bytes are the CRC of everything before them. */
    fun isFrameValid(frame: ByteArray): Boolean {
        if (frame.size < 3) return false
        val expected = (frame[frame.size - 2].toInt() and 0xFF) or ((frame[frame.size - 1].toInt() and 0xFF) shl 8)
        return crc16Modbus(frame, frame.size - 2) == expected
    }
}
