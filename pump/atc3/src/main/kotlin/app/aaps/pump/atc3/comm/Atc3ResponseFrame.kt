package app.aaps.pump.atc3.comm

/**
 * A complete, CRC verified response frame received from the pump.
 *
 * Layout:
 *
 * ```
 * offset 0    marker    always 0xAA
 * offset 1    length    total frame length including CRC, a single byte
 * offset 2    count     number of records the pump is sending in this answer
 * offset 3    frameId   echoes the request mode, 0xA1 control, 0xA3 history
 * offset 4    object    object type, normally echoing the requested opcode
 * offset 5    index     zero based index of this record within the answer
 * offset 6..  data
 * last 2      CRC-16/Modbus, little endian
 * ```
 *
 * A basal profile read answers with eight 104 byte frames carrying count 8 and indices 0..7, and a
 * status read with a single frame carrying count 1 and index 0. Objects with no stored record, such
 * as a temporary basal that is not running, answer with count 0.
 *
 * Control acknowledgements on frame id 0xA1 use a different shape and carry 0xAA at offset 5.
 *
 * Two offset bases are used. "Frame offset" counts from [raw] index 0. "Response data offset"
 * counts from frame offset 4, so `responseDataOffset = frameOffset - 4`. [data] is exposed on the
 * response data base and the accessors below take response data offsets.
 */
class Atc3ResponseFrame(val raw: ByteArray) {

    /** Frame id, echoes the mode byte of the request that triggered it. */
    val frameId: Byte get() = raw[FRAME_ID_OFFSET]

    /** Object type carried by this frame, null for frames too short to have one. */
    val objectType: Byte? get() = if (raw.size > OBJECT_OFFSET) raw[OBJECT_OFFSET] else null

    /** How many records the pump is sending in answer to the request, 0 when it has none. */
    val recordCount: Int get() = raw[COUNT_OFFSET].toInt() and 0xFF

    /** Zero based index of this record within the answer. */
    val recordIndex: Int get() = raw[INDEX_OFFSET].toInt() and 0xFF

    /**
     * True when this frame closes the answer it belongs to, as far as [recordCount] can say.
     *
     * An answer of several records arrives as a burst of frames carrying the same [recordCount]
     * and rising [recordIndex]. Treating the first of them as the whole answer leaves the rest of
     * the burst in flight, and the pump ignores a request that arrives while it is still talking.
     *
     * This is a shortcut, not a guarantee. For the bolus history the count is how many records the
     * pump holds altogether while the answer itself stops at ten, so with eleven records stored
     * this never becomes true. Whoever waits for a burst has to be able to finish without it.
     *
     * An answer with nothing stored carries count 0 and is complete as it stands.
     */
    val isLastRecord: Boolean get() = recordCount == 0 || recordIndex >= recordCount - 1

    /** Payload on the response data offset base. */
    val data: ByteArray get() = raw.copyOfRange(DATA_BASE_OFFSET, raw.size)

    /** Unsigned byte at a response data offset. */
    fun byteAt(responseDataOffset: Int): Int = raw[DATA_BASE_OFFSET + responseDataOffset].toInt() and 0xFF

    /** Unsigned 16 bit little endian value at a response data offset. */
    fun u16le(responseDataOffset: Int): Int {
        val base = DATA_BASE_OFFSET + responseDataOffset
        return (raw[base].toInt() and 0xFF) or ((raw[base + 1].toInt() and 0xFF) shl 8)
    }

    /** Unsigned 24 bit little endian value at a response data offset. */
    fun u24le(responseDataOffset: Int): Int {
        val base = DATA_BASE_OFFSET + responseDataOffset
        return (raw[base].toInt() and 0xFF) or
            ((raw[base + 1].toInt() and 0xFF) shl 8) or
            ((raw[base + 2].toInt() and 0xFF) shl 16)
    }

    /** True if this frame is long enough to expose a response data offset. */
    fun has(responseDataOffset: Int, byteCount: Int = 1): Boolean =
        DATA_BASE_OFFSET + responseDataOffset + byteCount <= raw.size

    override fun toString(): String =
        "Atc3ResponseFrame(frameId=0x%02X, object=%s, size=%d)".format(
            frameId,
            objectType?.let { "0x%02X".format(it) } ?: "none",
            raw.size
        )

    companion object {

        const val MARKER: Byte = 0xAA.toByte()

        const val LENGTH_OFFSET = 1
        const val COUNT_OFFSET = 2
        const val FRAME_ID_OFFSET = 3
        const val OBJECT_OFFSET = 4
        const val INDEX_OFFSET = 5

        /** Frame offset that response data offset 0 refers to. */
        const val DATA_BASE_OFFSET = 4
    }
}
