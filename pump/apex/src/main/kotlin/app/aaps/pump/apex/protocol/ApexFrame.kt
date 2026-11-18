package app.aaps.pump.apex.protocol

/** Builds the request frames sent to the pump. */
object ApexFrame {

    /** The parameter of a request that takes none. */
    const val NO_PARAMETER: Byte = 0xAA.toByte()

    /** The bytes of a frame as the logs show them: two hex digits each, a space between. */
    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    val IDENTITY_PREFIX = String(byteArrayOf(0x41, 0x50, 0x45, 0x58), Charsets.US_ASCII)

    /** Digits in a pump serial number. */
    const val SERIAL_LENGTH = 8

    val IDENTITY_LENGTH = IDENTITY_PREFIX.length + SERIAL_LENGTH

    val HEADER_LENGTH = 6 + IDENTITY_LENGTH

    const val CRC_LENGTH = 2

    /** Whether [serialNumber] can address a pump at all: the pump refuses a request whose serial is wrong by a digit. */
    fun isValidSerial(serialNumber: String): Boolean {
        val serial = serialNumber.trim()
        return serial.length == SERIAL_LENGTH && serial.all { it.isDigit() }
    }

    /**
     * The identity block every request carries, built from the pump's serial number.
     *
     * @throws IllegalArgumentException if the serial is not exactly [SERIAL_LENGTH] digits
     */
    fun identityOf(serialNumber: String): ByteArray {
        val serial = serialNumber.trim()
        require(isValidSerial(serial)) { "Apex serial number must be $SERIAL_LENGTH digits" }
        return (IDENTITY_PREFIX + serial).toByteArray(Charsets.US_ASCII)
    }

    /**
     * A complete request frame.
     *
     * @param identity see [identityOf]
     * @param parameter [NO_PARAMETER] unless the opcode takes one
     */
    fun buildRequest(
        group: Byte,
        mode: Byte,
        opcode: Byte,
        identity: ByteArray,
        parameter: Byte = NO_PARAMETER,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        require(identity.size == IDENTITY_LENGTH) {
            "Apex identity must be $IDENTITY_LENGTH bytes, was ${identity.size}"
        }
        val totalLength = HEADER_LENGTH + payload.size + CRC_LENGTH
        val frame = ByteArray(totalLength)
        frame[0] = group
        frame[1] = (totalLength and 0xFF).toByte()
        frame[2] = ((totalLength shr 8) and 0xFF).toByte()
        frame[3] = mode
        frame[4] = opcode
        frame[5] = parameter
        identity.copyInto(frame, 6)
        payload.copyInto(frame, HEADER_LENGTH)
        CrcUtil.crc16ModbusLeBytes(frame, totalLength - CRC_LENGTH).copyInto(frame, totalLength - CRC_LENGTH)
        return frame
    }
}
