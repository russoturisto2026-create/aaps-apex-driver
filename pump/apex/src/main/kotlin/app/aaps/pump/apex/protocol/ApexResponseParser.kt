package app.aaps.pump.apex.protocol

import app.aaps.pump.apex.deep.HeartbeatOverAnswerDeep

/**
 * Puts response frames back together from the chunks Bluetooth notifications deliver: one frame
 * can come in several chunks, and one chunk can carry several frames. Frames with id 0xA5 are two
 * bytes longer than their length byte says. The heartbeat is found by its form before the stream
 * is cut by length, because it can lie inside another frame, see [HeartbeatOverAnswerDeep].
 */
class ApexResponseParser {

    private val buffer = ArrayList<Byte>()

    /** Guards [buffer]: [feed] runs on the Bluetooth callback thread, [reset] on the thread of the exchange. */
    private val lock = Any()

    /** Frames dropped for a wrong CRC. */
    var crcErrors: Int = 0
        private set

    /** Heartbeats taken out of the middle of the stream, see [HeartbeatOverAnswerDeep]. */
    var heartbeatsInside: Int = 0
        private set

    /** @return every complete, CRC valid frame now available, in arrival order */
    fun feed(chunk: ByteArray): List<ApexResponseFrame> = synchronized(lock) { feedLocked(chunk) }

    private fun feedLocked(chunk: ByteArray): List<ApexResponseFrame> {
        val frames = ArrayList<ApexResponseFrame>()
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
            if (buffer.size < expected) {
                // Not whole yet: a heartbeat inside the waiting bytes is taken out, the rest keeps waiting.
                if (takeHeartbeatInside(frames, expected)) continue
                break
            }

            val raw = ByteArray(expected) { buffer[it] }
            if (CrcUtil.isFrameValid(raw)) {
                repeat(expected) { buffer.removeAt(0) }
                frames.add(ApexResponseFrame(raw))
                continue
            }
            // Wrong CRC: a heartbeat written over this frame is taken out; otherwise the frame is dropped.
            if (takeHeartbeatInside(frames, expected)) continue
            repeat(expected) { buffer.removeAt(0) }
            crcErrors++
        }
        return frames
    }

    /**
     * A heartbeat frame lying inside the first [within] bytes of the buffer, but not at its start,
     * is added to [frames] and removed. The bytes before it are dropped when they cannot be a frame
     * head. @return whether a heartbeat was taken out
     */
    private fun takeHeartbeatInside(frames: MutableList<ApexResponseFrame>, within: Int): Boolean {
        val at = HeartbeatOverAnswerDeep.heartbeatAt(buffer, from = 1) ?: return false
        if (at >= within) return false
        val size = ApexProtocol.HEARTBEAT_FRAME_SIZE
        frames.add(ApexResponseFrame(ByteArray(size) { buffer[at + it] }))
        repeat(size) { buffer.removeAt(at) }
        if (HeartbeatOverAnswerDeep.headIsLost(at, MIN_FRAME_LENGTH)) repeat(at) { buffer.removeAt(0) }
        heartbeatsInside++
        return true
    }

    /** Drop any partial frame, after a disconnect or a failed exchange. */
    fun reset() {
        synchronized(lock) { buffer.clear() }
    }

    private fun dropUntilMarker() {
        while (buffer.isNotEmpty() && buffer[0] != ApexResponseFrame.MARKER) {
            buffer.removeAt(0)
        }
    }

    private fun expectedLength(): Int {
        val declared = buffer[ApexResponseFrame.LENGTH_OFFSET].toInt() and 0xFF
        val frameId = buffer[ApexResponseFrame.FRAME_ID_OFFSET]
        return declared + if (frameId == FRAME_ID_WITH_EXTRA_LENGTH) EXTRA_LENGTH else 0
    }

    companion object {

        /** Bytes needed before the length of a frame can be known. */
        private const val MIN_FRAME_LENGTH = 6
        private const val FRAME_ID_WITH_EXTRA_LENGTH: Byte = 0xA5.toByte()
        private const val EXTRA_LENGTH = 2
    }
}
