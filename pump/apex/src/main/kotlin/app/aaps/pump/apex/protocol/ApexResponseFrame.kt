package app.aaps.pump.apex.protocol

/**
 * A complete, CRC checked frame from the pump. Its accessors take offsets counted from
 * [DATA_BASE_OFFSET], the object byte.
 */
class ApexResponseFrame(val raw: ByteArray) {

    /** Echoes the mode of the request. */
    val frameId: Byte get() = raw[FRAME_ID_OFFSET]

    /** Or null for a frame too short to have one. */
    val objectType: Byte? get() = if (raw.size > OBJECT_OFFSET) raw[OBJECT_OFFSET] else null

    /** How many records the answer declares, 0 when there are none. */
    val recordCount: Int get() = raw[COUNT_OFFSET].toInt() and 0xFF

    val recordIndex: Int get() = raw[INDEX_OFFSET].toInt() and 0xFF

    /**
     * True when this frame closes its answer as far as [recordCount] can say. A shortcut only: an
     * answer can stop before its count, so whoever waits for a burst finishes without it too.
     */
    val isLastRecord: Boolean get() = recordCount == 0 || recordIndex >= recordCount - 1

    /**
     * What makes a journal record the same record in two answers: its bytes without the record count,
     * the object byte (the newest-records read and the whole journal name the same record under
     * different objects), the position in the answer, the seconds byte of the stamp (the record's
     * place in its minute, or a value the pump may rewrite) and the CRC. Two answers that repeat a
     * record agree on this.
     */
    fun recordIdentity(): ByteArray {
        val end = (raw.size - CRC_BYTES).coerceAtLeast(0)
        val id = raw.copyOfRange(0, end)
        for (offset in intArrayOf(COUNT_OFFSET, OBJECT_OFFSET, INDEX_OFFSET, STAMP_SECONDS_OFFSET)) {
            if (offset < id.size) id[offset] = 0
        }
        return id
    }

    fun byteAt(responseDataOffset: Int): Int = raw[DATA_BASE_OFFSET + responseDataOffset].toInt() and 0xFF

    fun u16le(responseDataOffset: Int): Int {
        val base = DATA_BASE_OFFSET + responseDataOffset
        return (raw[base].toInt() and 0xFF) or ((raw[base + 1].toInt() and 0xFF) shl 8)
    }

    fun u24le(responseDataOffset: Int): Int {
        val base = DATA_BASE_OFFSET + responseDataOffset
        return (raw[base].toInt() and 0xFF) or
            ((raw[base + 1].toInt() and 0xFF) shl 8) or
            ((raw[base + 2].toInt() and 0xFF) shl 16)
    }

    fun has(responseDataOffset: Int, byteCount: Int = 1): Boolean =
        DATA_BASE_OFFSET + responseDataOffset + byteCount <= raw.size

    override fun toString(): String =
        "ApexResponseFrame(frameId=0x%02X, object=%s, size=%d)".format(
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
        const val DATA_BASE_OFFSET = 4

        /** Every journal record carries its stamp at frame bytes 6..11; the seconds byte is the last of them. */
        const val STAMP_SECONDS_OFFSET = 11

        /** The frame ends with a two byte CRC. */
        const val CRC_BYTES = 2
    }
}
