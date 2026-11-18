package app.aaps.pump.apex.account.time

import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.protocol.ApexResponseFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.TimeZone

/** The phone moves from zone +8 to zone +9 at noon; records stamped by the pump before the clock write keep the old offset. */
class ApexClockTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private val journal = JournalSnapshot(store, serial)
    private val plus8 = 8 * 3600_000L
    private val plus9 = 9 * 3600_000L

    /** 2026-10-20 00:00 UTC. */
    private val dayUtcMs = 1_792_540_800_000L

    /** A bolus record frame whose stamp reads [hour]:[minute] on the pump's wall clock, with a dose byte so that records differ. */
    private fun record(hour: Int, minute: Int, dose: Int): JournalSnapshot.Answered {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dayUtcMs; set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute) }
        val raw = ByteArray(22)
        raw[0] = ApexResponseFrame.MARKER; raw[1] = 0x16; raw[3] = 0xA3.toByte(); raw[4] = 0x21
        raw[6] = (c.get(Calendar.YEAR) - 2000).toByte(); raw[7] = (c.get(Calendar.MONTH) + 1).toByte(); raw[8] = c.get(Calendar.DAY_OF_MONTH).toByte()
        raw[9] = hour.toByte(); raw[10] = minute.toByte(); raw[11] = 59
        raw[12] = dose.toByte(); raw[14] = dose.toByte()
        return JournalSnapshot.Answered(ApexResponseFrame(raw), c.timeInMillis / 1000L + 59)
    }

    private fun wallMs(hour: Int, minute: Int) = dayUtcMs + hour * 3600_000L + minute * 60_000L

    /** The moment a record stamped [hour]:[minute] (seconds byte 59) stands for under [offsetMs]. */
    private fun recordMs(hour: Int, minute: Int, offsetMs: Long) = wallMs(hour, minute) + 59_000L - offsetMs

    @Test
    fun `a record stamped before the clock write keeps the offset of its time`() {
        // Before noon, zone +8: the clock written at link up, then the first read, one record stamped 11:30 on the pump.
        ApexClock.clockWritten(store, serial, plus8, wallMs(8, 0) - plus8, "link up", 0L)
        val first = journal.take(listOf(record(11, 30, 20)), plus8, wallMs(11, 30) - plus8)!!
        assertEquals(recordMs(11, 30, plus8), first.records.single().atMs)

        // The phone moves to +9. The pump still stands in +8 and stamps a bolus 12:02. The tick that will write
        // the clock reads the journal first, still under +8, and then writes the clock with the journal counted at two.
        val read = journal.take(listOf(record(12, 2, 25), record(11, 30, 20)), plus8, wallMs(13, 3) - plus9)!!
        assertEquals(recordMs(12, 2, plus8), read.records.single().atMs)
        ApexClock.clockWritten(store, serial, plus9, wallMs(13, 3) - plus9, "phone time changed", 2L)

        // The next read, under +9: a 13:10 record (number 3, after the write); the record before it (number 2) keeps +8.
        val next = journal.take(listOf(record(13, 10, 30), record(12, 2, 25), record(11, 30, 20)), plus9, wallMs(13, 12) - plus9)!!
        assertEquals(listOf(3L), next.records.map { it.seq })
        assertEquals(recordMs(13, 10, plus9), next.records.single().atMs)
        assertEquals(recordMs(12, 2, plus8), next.previousTopAtMs)
    }

    @Test
    fun `a stamp without a number is placed in the span of the clock log it falls in`() {
        ApexClock.clockWritten(store, serial, plus8, wallMs(8, 0) - plus8, "link up", 0L)
        ApexClock.clockWritten(store, serial, plus9, wallMs(13, 3) - plus9, "phone time changed", 1L)
        // A stamp 12:00 fits the +8 span (08:00 to 04:03 UTC) and not the +9 span.
        assertEquals(wallMs(12, 0) - plus8, ApexClock.momentOfStamp(store, serial, wallMs(12, 0) / 1000L, plus9, wallMs(14, 0) - plus9))
        // A stamp 13:10 fits the +9 span.
        assertEquals(wallMs(13, 10) - plus9, ApexClock.momentOfStamp(store, serial, wallMs(13, 10) / 1000L, plus9, wallMs(14, 0) - plus9))
        // A stamp before the log takes the current offset.
        assertEquals(wallMs(7, 0) - plus9, ApexClock.momentOfStamp(store, serial, wallMs(7, 0) / 1000L, plus9, wallMs(14, 0) - plus9))
    }

    @Test
    fun `a stamp in the minute of the write after it belongs to the new span`() {
        ApexClock.clockWritten(store, serial, plus8, wallMs(8, 0) - plus8, "link up", 0L)
        // The clock is written at 13:03:40 in +9; a temporary basal started 13:03:50 is stamped 13:03:00.
        ApexClock.clockWritten(store, serial, plus9, wallMs(13, 3) + 40_000L - plus9, "phone time changed", 1L)
        assertEquals(wallMs(13, 3) - plus9, ApexClock.momentOfStamp(store, serial, wallMs(13, 3) / 1000L, plus9, wallMs(14, 0) - plus9))
        // A stamp of the old clock in the minute of the write, before it, is still the old span.
        assertEquals(wallMs(12, 3) - plus8, ApexClock.momentOfStamp(store, serial, wallMs(12, 3) / 1000L, plus9, wallMs(14, 0) - plus9))
    }

    @Test
    fun `after the zone moved back an old stamp ahead of now in the new offset belongs to the span before`() {
        ApexClock.clockWritten(store, serial, plus8, wallMs(2, 0) - plus8, "link up", 0L)
        // At 07:20 in +8 the clock goes back from +9; a temporary basal started 08:18 in +9 is read at 07:25.
        ApexClock.clockWritten(store, serial, plus9, wallMs(3, 50) - plus9, "phone time changed", 1L)
        ApexClock.clockWritten(store, serial, plus8, wallMs(7, 20) - plus8, "phone time changed", 2L)
        val nowMs = wallMs(7, 25) - plus8
        assertEquals(wallMs(8, 18) - plus9, ApexClock.momentOfStamp(store, serial, wallMs(8, 18) / 1000L, plus8, nowMs))
        // A stamp of the new clock before now stays in the new span.
        assertEquals(wallMs(7, 22) - plus8, ApexClock.momentOfStamp(store, serial, wallMs(7, 22) / 1000L, plus8, nowMs))
    }
}
