package app.aaps.pump.apex.deep

import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.protocol.CrcUtil

/**
 * The pump's heartbeat found inside another frame, and how the stream is read around it.
 *
 * The problem. The heartbeat is the one frame the pump sends on its own beat, and it does not wait
 * for the frame the pump is sending at that moment. When a heartbeat period command moves the
 * heartbeat onto a minute that has already passed, the pump sends the heartbeat at once, while it
 * is writing the answer to that command: what arrives is one notification of the answer's length,
 * the first two bytes of the answer and then the whole heartbeat frame over the rest. The answer
 * is lost, the heartbeat is sound. It happens on a command whose new period puts the next
 * heartbeat into the past. A heartbeat between two chunks
 * of one long answer is possible by the same token and is read the same way.
 *
 * The rule. A heartbeat frame has a fixed form, eight bytes with its own CRC, so it is found by
 * its form anywhere in the stream, not only where the length byte of the frame before it says a
 * frame begins. It is taken out first; the bytes around it are read as the stream they were. Bytes
 * before it that are too few to be a frame head are the overwritten head of an answer and are
 * dropped: no frame can be made of them, and left in place they would spoil the next frame. A
 * head long enough to be a frame is kept: its tail comes in the next chunk.
 *
 * The way out for the caller. The answer the heartbeat overwrote never comes. In every case seen it
 * was the acknowledgement of a control command, and the pump carried the command out: the exchange
 * takes a heartbeat found inside the answer of a control command as that acknowledgement. A read that
 * waits for a burst is not helped this way: its frames are records, and a lost one is lost.
 */
object HeartbeatOverAnswerDeep {

    /** The first bytes of every heartbeat frame: marker, declared length, count, frame id. */
    private val HEAD = byteArrayOf(ApexResponseFrame.MARKER, 0x06, 0x00, ApexProtocol.MODE_HEARTBEAT)

    /** Where in [buffer] a whole, CRC valid heartbeat frame starts, looking from [from]; null when there is none. */
    fun heartbeatAt(buffer: List<Byte>, from: Int): Int? {
        val size = ApexProtocol.HEARTBEAT_FRAME_SIZE
        var at = from
        while (at + size <= buffer.size) {
            if (isHeartbeat(buffer, at)) return at
            at++
        }
        return null
    }

    /** Whether the bytes before a heartbeat found at [at] are the overwritten head of an answer, to be dropped. */
    fun headIsLost(at: Int, minFrameLength: Int): Boolean = at < minFrameLength

    private fun isHeartbeat(buffer: List<Byte>, at: Int): Boolean {
        for (i in HEAD.indices) if (buffer[at + i] != HEAD[i]) return false
        val raw = ByteArray(ApexProtocol.HEARTBEAT_FRAME_SIZE) { buffer[at + it] }
        return CrcUtil.isFrameValid(raw)
    }
}
