package app.aaps.pump.atc3.comm

/**
 * Builder for outgoing ATC3 request frames.
 *
 * Layout:
 *
 * ```
 * offset 0     group          0x35 control and targeted queries, 0x55 full data set queries
 * offset 1..2  declaredLength little endian, equals the total frame length including CRC
 * offset 3     mode           0xA1 control, 0xA3 history and status
 * offset 4     opcode
 * offset 5     parameter      0xAA when unused, a real value for some opcodes
 * offset 6..17 identity       the identity prefix followed by the 8 digit serial number
 * offset 18..  payload
 * last 2       CRC-16/Modbus, little endian
 * ```
 *
 * Offset 5 is a parameter, not padding: the requests for opcodes 0x21 and 0x26 carry 0x01 there,
 * every other request 0xAA.
 */
object Atc3Frame {

    /** Value at offset 5 when the opcode takes no parameter. */
    const val NO_PARAMETER: Byte = 0xAA.toByte()

    /** Identity prefix that precedes the serial number. */
    val IDENTITY_PREFIX = String(byteArrayOf(0x41, 0x50, 0x45, 0x58), Charsets.US_ASCII)

    /** Number of digits in the pump serial number. */
    const val SERIAL_LENGTH = 8

    /** Length of the identity block that every request carries. */
    val IDENTITY_LENGTH = IDENTITY_PREFIX.length + SERIAL_LENGTH

    /** Number of bytes in a request before the payload starts. */
    val HEADER_LENGTH = 6 + IDENTITY_LENGTH

    /** Number of bytes added after the payload. */
    const val CRC_LENGTH = 2

    /**
     * Whether [serialNumber] can address a pump at all.
     *
     * The pump checks the identity block and refuses a request whose serial is off by a single
     * digit, so a serial that is not exactly [SERIAL_LENGTH] digits is not worth sending.
     */
    fun isValidSerial(serialNumber: String): Boolean {
        val serial = serialNumber.trim()
        return serial.length == SERIAL_LENGTH && serial.all { it.isDigit() }
    }

    /**
     * Build the 12 byte identity block from a pump serial number.
     *
     * The identity is plain ASCII: the four characters of the identity prefix followed by the
     * eight digit serial number.
     *
     * @throws IllegalArgumentException if the serial is not exactly [SERIAL_LENGTH] digits
     */
    fun identityOf(serialNumber: String): ByteArray {
        val serial = serialNumber.trim()
        require(isValidSerial(serial)) { "ATC3 serial number must be $SERIAL_LENGTH digits" }
        return (IDENTITY_PREFIX + serial).toByteArray(Charsets.US_ASCII)
    }

    /**
     * Build a complete request frame.
     *
     * @param identity the 12 byte identity block, see [identityOf]
     * @param parameter the byte at offset 5, [NO_PARAMETER] unless the opcode takes one
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
            "ATC3 identity must be $IDENTITY_LENGTH bytes, was ${identity.size}"
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
