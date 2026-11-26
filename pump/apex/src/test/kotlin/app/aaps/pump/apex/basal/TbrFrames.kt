package app.aaps.pump.apex.basal

import app.aaps.pump.apex.protocol.ApexFinishedTbr
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.protocol.ApexResponseParser
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.protocol.ApexTbrStatus
import app.aaps.pump.apex.protocol.CrcUtilTest
import app.aaps.pump.apex.protocol.CrcUtilTest.Companion.hexToBytes
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The temporary basal frames of the pump as the tests build them: Status V1 with a temporary
 * basal written over the fixture, the last command (0x0A), a journal record (0x27) and the last
 * finished temporary basal (0x0B). Every stamp is a wall clock of 2026-10-31; the pump's clock is
 * taken to stand at UTC, so a stamp's key is its moment in seconds.
 */
object TbrFrames {

    /** The key of [hour]:[minute] on 2026-10-31, and its moment with the clock at UTC. */
    fun key(hour: Int, minute: Int, second: Int = 0): Long = LocalDateTime.of(2026, 10, 31, hour, minute, second).toEpochSecond(ZoneOffset.UTC)
    fun ms(hour: Int, minute: Int, second: Int = 0): Long = key(hour, minute, second) * 1000L

    /** The Status V1 frame of the fixture, with a temporary basal of [rateRaw] steps for [minutes] written over it when one runs, and its clock set to the key [clock] when given. */
    fun status(running: Boolean, rateRaw: Int = 48, minutes: Int = 30, elapsed: Int = 0, percent: Boolean = false, clock: Long? = null): ApexResponseFrame {
        val raw = ApexResponseParser().feed(hexToBytes(CrcUtilTest.STATUS_V1_FRAME))[0].raw.copyOf()
        val base = ApexResponseFrame.DATA_BASE_OFFSET
        raw[base + ApexStatusV1.Offset.TBR_ACTIVE] = if (running) 1 else 0
        putU16(raw, base + ApexStatusV1.Offset.TBR_RATE, rateRaw)
        putU16(raw, base + ApexStatusV1.Offset.TBR_DURATION, minutes)
        putU16(raw, base + ApexStatusV1.Offset.TBR_ELAPSED, elapsed)
        raw[base + ApexStatusV1.Offset.TBR_MODE] = if (percent) MODE_PERCENT else ApexProtocol.TbrPayload.MODE_ABSOLUTE
        clock?.let { ApexStatusV1.encodeClock(it).copyInto(raw, base + ApexStatusV1.Offset.CLOCK) }
        return ApexResponseFrame(raw)
    }

    fun statusOf(frame: ApexResponseFrame): ApexStatusV1 = ApexStatusV1.decode(frame)!!

    /** The last command: started at [hour]:[minute], [rateRaw] steps (or a percentage) for [quarters] quarter hours, [deliveredRaw] steps so far. */
    fun command(hour: Int, minute: Int, rateRaw: Int = 48, quarters: Int = 2, deliveredRaw: Int = 0, percent: Boolean = false, second: Int = 0): ApexResponseFrame {
        val raw = ByteArray(ApexTbrStatus.FRAME_SIZE)
        head(raw, ApexProtocol.ReadOpcode.TBR_ACTIVE)
        val base = ApexResponseFrame.DATA_BASE_OFFSET
        putClock(raw, base + ApexTbrStatus.Offset.START_CLOCK, hour, minute, second)
        putClock(raw, base + ApexTbrStatus.Offset.START_CLOCK_REPEAT, hour, minute, second)
        raw[base + ApexTbrStatus.Offset.MODE] = if (percent) MODE_PERCENT else ApexProtocol.TbrPayload.MODE_ABSOLUTE
        raw[base + ApexTbrStatus.Offset.DURATION] = quarters.toByte()
        putU16(raw, base + ApexTbrStatus.Offset.RATE, rateRaw)
        putU16(raw, base + ApexTbrStatus.Offset.DELIVERED, deliveredRaw)
        return ApexResponseFrame(raw)
    }

    /** The whole frame of zeros: no command since a reset. */
    fun noCommand(): ApexResponseFrame = ByteArray(ApexTbrStatus.FRAME_SIZE).also { head(it, ApexProtocol.ReadOpcode.TBR_ACTIVE) }.let { ApexResponseFrame(it) }

    /** The last finished temporary basal: started at [startHour]:[startMinute], ended at [endHour]:[endMinute] with [result]. */
    fun finished(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int, result: Int = ApexFinishedTbr.Offset.RESULT_CANCELLED_ON_PUMP, rateRaw: Int = 48, quarters: Int = 2, startSecond: Int = 0): ApexResponseFrame {
        val raw = ByteArray(ApexFinishedTbr.FRAME_SIZE)
        head(raw, ApexProtocol.ReadOpcode.TBR_FINISHED)
        val base = ApexResponseFrame.DATA_BASE_OFFSET
        raw[base + ApexFinishedTbr.Offset.RESULT] = result.toByte()
        putClock(raw, base + ApexFinishedTbr.Offset.START_CLOCK, startHour, startMinute, startSecond)
        putClock(raw, base + ApexFinishedTbr.Offset.START_CLOCK_REPEAT, startHour, startMinute, startSecond)
        raw[base + ApexFinishedTbr.Offset.MODE] = ApexProtocol.TbrPayload.MODE_ABSOLUTE
        putU16(raw, base + ApexFinishedTbr.Offset.DURATION, quarters)
        putU16(raw, base + ApexFinishedTbr.Offset.RATE, rateRaw)
        putClock(raw, base + ApexFinishedTbr.Offset.END_CLOCK, endHour, endMinute)
        return ApexResponseFrame(raw)
    }

    /** Any mode byte but the absolute one: the pump's percentage mode. */
    private const val MODE_PERCENT: Byte = 0x00

    private fun head(raw: ByteArray, objectType: Byte, count: Int = 0, index: Int = 0) {
        raw[0] = ApexResponseFrame.MARKER
        raw[ApexResponseFrame.LENGTH_OFFSET] = (raw.size - 2).toByte()
        raw[ApexResponseFrame.COUNT_OFFSET] = count.toByte()
        raw[ApexResponseFrame.FRAME_ID_OFFSET] = ApexProtocol.MODE_HISTORY
        raw[ApexResponseFrame.OBJECT_OFFSET] = objectType
        raw[ApexResponseFrame.INDEX_OFFSET] = index.toByte()
    }

    private fun putClock(raw: ByteArray, at: Int, hour: Int, minute: Int, second: Int = 0) {
        raw[at] = 26; raw[at + 1] = 10; raw[at + 2] = 31
        raw[at + 3] = hour.toByte(); raw[at + 4] = minute.toByte(); raw[at + 5] = second.toByte()
    }

    private fun putU16(raw: ByteArray, at: Int, value: Int) {
        raw[at] = (value and 0xFF).toByte()
        raw[at + 1] = (value shr 8 and 0xFF).toByte()
    }
}
