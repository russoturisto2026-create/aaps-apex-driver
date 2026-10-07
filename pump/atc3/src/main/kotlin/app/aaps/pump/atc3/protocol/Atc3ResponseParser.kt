package app.aaps.pump.atc3.protocol

/**
 * Puts response frames back together from the chunks Bluetooth notifications deliver: one frame
 * can come in several chunks, and one chunk can carry several frames. Frames with id 0xA5 are two
 * bytes longer than their length byte says.
 */
class Atc3ResponseParser {

    private val buffer = ArrayList<Byte>()

    /** Guards [buffer]: [feed] runs on the Bluetooth callback thread, [reset] on the thread of the exchange. */
    private val lock = Any()

    /** Frames dropped for a wrong CRC. */
    var crcErrors: Int = 0
        private set

    /** @return every complete, CRC valid frame now available, in arrival order */
    fun feed(chunk: ByteArray): List<Atc3ResponseFrame> = synchronized(lock) { feedLocked(chunk) }

    private fun feedLocked(chunk: ByteArray): List<Atc3ResponseFrame> {
        val frames = ArrayList<Atc3ResponseFrame>()
        for (byte in chunk) buffer.add(byte)

        while (true) {
            dropUntilMarker()
            if (buffer.size < MIN_FRAME_LENGTH) break

            val expected = expectedLength()
            if (expected < MIN_FRAME_LENGTH) {
                // Not a plausible frame: drop the marker and look for the next one.
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

    /** Drop any partial frame, after a disconnect or a failed exchange. */
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

        /** Bytes needed before the length of a frame can be known. */
        private const val MIN_FRAME_LENGTH = 6
        private const val FRAME_ID_WITH_EXTRA_LENGTH: Byte = 0xA5.toByte()
        private const val EXTRA_LENGTH = 2
    }
}
