package app.aaps.pump.atc3.comm

/**
 * Reassembles response frames from the byte chunks delivered by BLE notifications.
 *
 * A single pump response can arrive split across several notifications, and several responses can
 * arrive back to back inside one notification, so the parser keeps a buffer across calls.
 *
 * The frame length rule:
 *
 * ```
 * expected = buffer[1] + (2 if buffer[3] == 0xA5 else 0)
 * ```
 *
 * The `+ 2` for frame id 0xA5 is deliberate and must not be "simplified away": frames with that id
 * are two bytes longer than their length byte says, and removing it desynchronises the stream on
 * those frames.
 */
class Atc3ResponseParser {

    private val buffer = ArrayList<Byte>()

    /**
     * Guards [buffer].
     *
     * [feed] runs on Android's GATT callback thread while [reset] is called from the thread driving
     * the exchange. This is the one place where the two meet, and a half cleared buffer would
     * desynchronise the frame stream, which is exactly how answers start being dropped.
     */
    private val lock = Any()

    /** Frames whose CRC did not verify, counted for diagnostics. */
    var crcErrors: Int = 0
        private set

    /**
     * Feed one chunk of received bytes.
     *
     * @return every complete, CRC valid frame that became available, in arrival order
     */
    fun feed(chunk: ByteArray): List<Atc3ResponseFrame> = synchronized(lock) { feedLocked(chunk) }

    private fun feedLocked(chunk: ByteArray): List<Atc3ResponseFrame> {
        val frames = ArrayList<Atc3ResponseFrame>()
        for (byte in chunk) buffer.add(byte)

        while (true) {
            dropUntilMarker()
            if (buffer.size < MIN_FRAME_LENGTH) break

            val expected = expectedLength()
            if (expected < MIN_FRAME_LENGTH) {
                // Not a plausible frame, drop the marker and resynchronise on the next one.
                buffer.removeAt(0)
                continue
            }
            if (buffer.size < expected) break

            val raw = ByteArray(expected) { buffer[it] }
            repeat(expected) { buffer.removeAt(0) }

            if (CrcUtil.isFrameValid(raw)) {
                frames.add(Atc3ResponseFrame(raw))
            } else {
                crcErrors++
            }
        }
        return frames
    }

    /** Discard any buffered partial frame, for example after a disconnect. */
    fun reset() {
        synchronized(lock) { buffer.clear() }
    }

    private fun dropUntilMarker() {
        while (buffer.isNotEmpty() && buffer[0] != Atc3ResponseFrame.MARKER) {
            buffer.removeAt(0)
        }
    }

    private fun expectedLength(): Int {
        val declared = buffer[Atc3ResponseFrame.LENGTH_OFFSET].toInt() and 0xFF
        val frameId = buffer[Atc3ResponseFrame.FRAME_ID_OFFSET]
        return declared + if (frameId == FRAME_ID_WITH_EXTRA_LENGTH) EXTRA_LENGTH else 0
    }

    companion object {

        /** Marker, length, subtype and frame id must be present before the length can be evaluated. */
        private const val MIN_FRAME_LENGTH = 6

        private const val FRAME_ID_WITH_EXTRA_LENGTH: Byte = 0xA5.toByte()
        private const val EXTRA_LENGTH = 2
    }
}
