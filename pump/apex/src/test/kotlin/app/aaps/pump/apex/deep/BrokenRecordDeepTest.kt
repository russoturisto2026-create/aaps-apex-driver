package app.aaps.pump.apex.deep

import app.aaps.pump.apex.account.bolus.BolusRecordFrames
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexDailyRecordTest
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.protocol.ApexResponseParser
import app.aaps.pump.apex.protocol.CrcUtil
import app.aaps.pump.apex.protocol.CrcUtilTest.Companion.hexToBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The rule that tells a broken cell from a real record, on frames of each journal. */
class BrokenRecordDeepTest {

    private fun <T> answerOf(hex: String, decode: (app.aaps.pump.apex.protocol.ApexResponseFrame) -> T?): ApexJournalAnswer<T> {
        val frames = ApexResponseParser().feed(hexToBytes(hex))
        assertEquals(1, frames.size)
        val record = decode(frames[0])
        assertNotNull(record)
        return ApexJournalAnswer(listOf(ApexRead(frames[0], record!!)), frames[0].recordCount)
    }

    /** The frame with its two CRC bytes replaced by the right ones for its new content. */
    private fun withCrc(hexWithoutCrc: String): String {
        val crc = CrcUtil.crc16ModbusLeBytes(hexToBytes(hexWithoutCrc))
        return hexWithoutCrc + " %02X %02X".format(crc[0], crc[1])
    }

    /** A bolus answer of one record built by the fixture, with [change] applied to its bytes first. */
    private fun bolusAnswer(requestedRaw: Int, change: (ByteArray) -> Unit = {}): ApexJournalAnswer<ApexBolusRecord> {
        val raw = BolusRecordFrames.record(minuteKey = 1_790_000_000L / 60 * 60, requestedRaw = requestedRaw, deliveredRaw = requestedRaw).frame.raw.copyOf()
        change(raw)
        val frame = app.aaps.pump.apex.protocol.ApexResponseFrame(raw)
        val record = ApexBolusRecord.decode(frame)
        assertNotNull(record)
        return ApexJournalAnswer(listOf(ApexRead(frame, record!!)), 1)
    }

    @Test
    fun `a sound bolus record is not broken`() {
        assertNull(BrokenRecordDeep.brokenBolus(bolusAnswer(requestedRaw = 40)))
    }

    @Test
    fun `a bolus record with a zero month is broken`() {
        assertNotNull(BrokenRecordDeep.brokenBolus(bolusAnswer(requestedRaw = 40) { it[7] = 0 }))
    }

    @Test
    fun `a bolus record that asks for nothing is broken`() {
        assertNotNull(BrokenRecordDeep.brokenBolus(bolusAnswer(requestedRaw = 0)))
    }

    @Test
    fun `a bolus record that asked for a dose and delivered nothing is broken - the cell cut after the asked amount`() {
        // The record of 16:12:59 as the pump showed it once: asked 0.350, delivered 0; seven seconds later 0.350 delivered.
        val cut = bolusAnswer(requestedRaw = 14) { raw -> raw[14] = 0; raw[15] = 0 }
        assertNotNull(BrokenRecordDeep.brokenBolus(cut))
        assertEquals(0.0, cut.entries.single().record.deliveredUnits, 1e-9)
    }

    @Test
    fun `a day with a date is not broken, a day without one is`() {
        assertNull(BrokenRecordDeep.brokenDay(answerOf(ApexDailyRecordTest.DAY_FRAME, ApexDailyRecord::decode)))
        // The day frame with its day byte zeroed: AA 12 17 A3 06 00 | 28 00 7E 02 1F 00 | 1A 09 00 | 00.
        val noDay = withCrc("AA 12 17 A3 06 00 28 00 7E 02 1F 00 1A 09 00 00")
        assertNotNull(BrokenRecordDeep.brokenDay(answerOf(noDay, ApexDailyRecord::decode)))
    }

    @Test
    fun `the same answer twice is the same, a changed byte is not`() {
        val a = answerOf(ApexDailyRecordTest.DAY_FRAME, ApexDailyRecord::decode)
        val b = answerOf(ApexDailyRecordTest.DAY_FRAME, ApexDailyRecord::decode)
        assertTrue(BrokenRecordDeep.sameAnswer(a, b))
        val c = answerOf(withCrc("AA 12 17 A3 06 00 29 00 7E 02 1F 00 1A 09 02 00"), ApexDailyRecord::decode)
        assertFalse(BrokenRecordDeep.sameAnswer(a, c))
    }
}
