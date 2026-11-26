package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.protocol.ApexResponseFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The hints of a flicker in an answer of the daily statistics, held against the driver's copy. */
class DayFlickerTest {

    /** A day, raw doses in steps of 0.025 U, its date as three bytes, year first; 0, 0, 0 is no date. */
    private class Day(val bolus: Int, val basal: Int, val tbr: Int, val year: Int = 26, val month: Int = 10, val day: Int)

    private fun frame(index: Int, d: Day): ApexResponseFrame {
        val raw = ByteArray(ApexDailyRecord.RECORD_SIZE)
        raw[0] = ApexResponseFrame.MARKER
        raw[1] = 0x12
        raw[2] = 10
        raw[3] = 0xA3.toByte()
        raw[4] = ApexProtocol.HistoryOpcode.LATEST_DAILY_STATS
        raw[5] = index.toByte()
        raw[6] = (d.bolus and 0xFF).toByte(); raw[7] = (d.bolus shr 8).toByte()
        raw[8] = (d.basal and 0xFF).toByte(); raw[9] = (d.basal shr 8).toByte()
        raw[10] = (d.tbr and 0xFF).toByte(); raw[11] = (d.tbr shr 8).toByte()
        raw[12] = d.year.toByte(); raw[13] = d.month.toByte(); raw[14] = d.day.toByte()
        return ApexResponseFrame(raw)
    }

    private fun answer(vararg newestFirst: Day) =
        ApexJournalAnswer(newestFirst.mapIndexed { i, d -> frame(i, d).let { ApexRead(it, ApexDailyRecord.decode(it)!!) } }, newestFirst.size)

    private fun copy(vararg newestFirst: Day): List<ByteArray> = newestFirst.mapIndexed { i, d -> frame(i, d).raw }

    /** The positions of the days that hint at a flicker. */
    private fun hinted(answer: ApexJournalAnswer<ApexDailyRecord>, kept: List<ByteArray>, closedToo: Boolean = false) =
        BrokenRecord.brokenDay(answer, kept, closedToo).map { it.frame.recordIndex }

    private val kept = copy(Day(40, 400, 0, day = 30), Day(80, 1248, 0, day = 29), Day(60, 1248, 0, day = 28))

    @Test
    fun `today sound and the closed days as in the copy - no hint`() {
        assertEquals(emptyList<Int>(), hinted(answer(Day(44, 410, 0, day = 30), Day(80, 1248, 0, day = 29), Day(60, 1248, 0, day = 28)), kept, closedToo = true))
    }

    @Test
    fun `today with no day, with the date not written, or with nothing in its fields - a hint each`() {
        assertEquals(listOf(0), hinted(answer(Day(44, 410, 0, day = 0), Day(80, 1248, 0, day = 29)), kept))
        assertEquals(listOf(0), hinted(answer(Day(44, 410, 0, 1, 1, 1), Day(80, 1248, 0, day = 29)), kept))
        assertEquals(listOf(0), hinted(answer(Day(0, 0, 0, day = 31), Day(40, 410, 0, day = 30)), kept))
    }

    @Test
    fun `a closed day not as in the copy - no hint at a heartbeat, a hint at the start`() {
        val answer = answer(Day(44, 410, 0, day = 30), Day(80, 1248, 0, 1, 1, 1), Day(60, 1248, 0, day = 28))
        assertEquals(emptyList<Int>(), hinted(answer, kept))
        assertEquals(listOf(1), hinted(answer, kept, closedToo = true))
    }

    @Test
    fun `today lost its date with the power and a new day of its date above - not a hint`() {
        assertEquals(emptyList<Int>(), hinted(answer(Day(4, 20, 0, day = 30), Day(44, 410, 0, 0, 0, 0), Day(80, 1248, 0, day = 29)), kept))
    }

    @Test
    fun `nothing kept - every day is new, a day with no date anywhere is a hint`() {
        assertEquals(listOf(2), hinted(answer(Day(44, 410, 0, day = 30), Day(80, 1248, 0, day = 29), Day(60, 1248, 0, 0, 0, 0)), emptyList()))
    }

    @Test
    fun `today with nothing in its fields read again as the copy holds it - not a hint, changed from the copy - a hint`() {
        val night = copy(Day(0, 0, 0, day = 30), Day(80, 1248, 0, day = 29))
        assertEquals(emptyList<Int>(), hinted(answer(Day(0, 0, 0, day = 30), Day(80, 1248, 0, day = 29)), night))
        assertEquals(listOf(0), hinted(answer(Day(0, 0, 0, day = 30), Day(80, 1248, 0, day = 29)), kept))
    }
}
