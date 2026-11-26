package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.bolus.BolusRecordFrames.record
import app.aaps.pump.apex.store.FakeStore
import app.aaps.pump.apex.clock.ApexClock
import app.aaps.pump.apex.protocol.ApexResponseFrame
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The driver's own bolus journal: a cell per record and per bolus of ours, filled by the reconciliation. */
class BolusCellsTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private val cells = BolusCells(store, serial)

    private val tenOClock = 1_760_000_000L / 60 * 60
    private val nowMs = tenOClock * 1000L + 90_000L

    /** [count] records a minute apart before ten o'clock, newest first. */
    private fun past(count: Int) = (1..count).map { record(tenOClock - it * 60L, 20, 20) }

    @Test
    fun `a journal started anew - a record stamped while the clock stood in another zone takes that zone`() {
        val hour = 3_600_000L
        val tenMs = tenOClock * 1000L
        ApexClock.clockWritten(store, serial, 8 * hour, tenMs - 3 * hour, "first write", bolusCount = 0)
        ApexClock.clockWritten(store, serial, 3 * hour, tenMs - 60 * 60_000L, "zone", bolusCount = 0)
        ApexClock.clockWritten(store, serial, 8 * hour, tenMs - 30 * 60_000L, "zone", bolusCount = 0)
        // Stamped forty minutes before ten, under the clock of three hours; then one five minutes before ten, under eight.
        val moscow = record(Math.floorDiv(tenMs - 40 * 60_000L + 3 * hour, 1000L) / 60 * 60, 20, 20)
        val back = record(Math.floorDiv(tenMs - 5 * 60_000L + 8 * hour, 1000L) / 60 * 60, 20, 20)
        val records = cells.restart(listOf(back, moscow), offsetMs = 8 * hour, nowMs = nowMs).records
        assertEquals(moscow.clockSeconds * 1000L - 3 * hour, records[0].atMs)
        assertEquals(back.clockSeconds * 1000L - 8 * hour, records[1].atMs)
    }

    @Test
    fun `a journal started anew - a record stamped under a clock an hour ahead stands before the record after it`() {
        val hour = 3_600_000L
        val tenMs = tenOClock * 1000L
        ApexClock.clockWritten(store, serial, 8 * hour, tenMs - 4 * hour, "first write", bolusCount = 0)
        ApexClock.clockWritten(store, serial, 9 * hour, tenMs - 3 * hour, "zone", bolusCount = 0)
        ApexClock.clockWritten(store, serial, 8 * hour, tenMs - 2 * hour, "zone", bolusCount = 0)
        // Stamped two and a half hours before ten under the clock of nine hours; its stamp read under eight
        // falls an hour later, inside the last span. The record after it, a hundred minutes before ten, under eight.
        val ahead = record(Math.floorDiv(tenMs - 150 * 60_000L + 9 * hour, 1000L) / 60 * 60, 20, 20)
        val after = record(Math.floorDiv(tenMs - 100 * 60_000L + 8 * hour, 1000L) / 60 * 60, 20, 20)
        val records = cells.restart(listOf(after, ahead), offsetMs = 8 * hour, nowMs = nowMs).records
        assertEquals(ahead.clockSeconds * 1000L - 9 * hour, records[0].atMs)
        assertEquals(after.clockSeconds * 1000L - 8 * hour, records[1].atMs)
    }

    @Test
    fun `a journal taken whole - each record stands at its own stamp, an old stamp above does not move the records below`() {
        val old = record(Math.floorDiv(tenOClock * 1000L - 20 * 3_600_000L, 1000L) / 60 * 60, 20, 20)
        val below = record(Math.floorDiv(tenOClock * 1000L - 10 * 60_000L, 1000L) / 60 * 60, 20, 20)
        val records = cells.restart(listOf(old, below), offsetMs = 0L, nowMs = nowMs).records
        assertEquals(below.clockSeconds * 1000L, records[0].atMs)
        assertEquals(old.clockSeconds * 1000L, records[1].atMs)
    }

    @Test
    fun `each new record gets a cell with its number, its bytes and the offset it was stamped in`() {
        cells.restart(past(3), offsetMs = 3_600_000L, nowMs = nowMs)
        val kept = store.cells(serial)
        assertEquals(listOf(1L, 2L, 3L), kept.map { it.seq })
        assertTrue(kept.all { it.offsetMs == 3_600_000L && it.open && !it.inAaps })
        assertArrayEquals(past(3).last().frame.raw, kept.first().bytes)
    }

    @Test
    fun `a record that delivered nothing, the same at every read, gets its cell - the next answer lays over it`() {
        cells.restart(past(3), 0L, nowMs)
        // The pump holds it so: asked 0.5 U, delivered 0. A flicker never comes here, it is read again, see BrokenRecord.
        val held = listOf(record(tenOClock, 20, 0)) + past(3)
        assertEquals(listOf(4L), cells.take(held, 0L, nowMs)!!.records.map { it.seq })
        val next = cells.take(listOf(record(tenOClock + 60, 20, 20)) + held, 0L, nowMs)!!
        assertEquals(listOf(5L), next.records.map { it.seq })
    }

    @Test
    fun `a record with no date, the same at every read, gets its cell - the next answer lays over it`() {
        cells.restart(past(3), 0L, nowMs)
        // The pump holds it so: the amounts there, the month and the day zero.
        val bytes = record(tenOClock, 20, 20).frame.raw.copyOf().also { it[7] = 0; it[8] = 0 }
        val held = listOf(BolusCells.Answered(ApexResponseFrame(bytes), 0L)) + past(3)
        assertEquals(listOf(4L), cells.take(held, 0L, nowMs)!!.records.map { it.seq })
        val next = cells.take(listOf(record(tenOClock + 60, 20, 20)) + held, 0L, nowMs)!!
        assertEquals(listOf(5L), next.records.map { it.seq })
    }

    @Test
    fun `the record of our bolus moves into its cell, a record of somebody else is filled in its own`() {
        cells.restart(past(2), 0L, nowMs)
        val action = BolusAccount(store, serial).commandAccepted(tenOClock * 1000L + 5_000L, 0.5, "SMB").first
        val added = cells.take(listOf(record(tenOClock + 60L, 40, 40), record(tenOClock, 20, 20)) + past(2), 0L, nowMs)!!
        val (ours, foreign) = added.records
        cells.settled(
            listOf(
                BolusCells.Filled(ours.seq, action.id, 0.5, action.acceptedAtMs, faulty = false, inAaps = true),
                BolusCells.Filled(foreign.seq, null, 1.0, foreign.atMs, faulty = false, inAaps = true)
            )
        )
        val kept = store.cells(serial)
        assertEquals(4, kept.size)
        val mine = kept.single { it.id == action.id }
        assertEquals(ours.seq, mine.seq)
        assertArrayEquals(ours.frame.raw, mine.bytes)
        assertEquals(0.5, mine.units)
        assertFalse(mine.open)
        assertEquals(1.0, kept.single { it.seq == foreign.seq }.units)
        // The record still lays the next answer over: it sits in our cell now.
        assertTrue(cells.take(listOf(record(tenOClock + 60L, 40, 40), record(tenOClock, 20, 20)) + past(2), 0L, nowMs)!!.records.isEmpty())
    }

    @Test
    fun `the cells keep as many records as the pump's journal holds`() {
        cells.restart(past(5), 0L, nowMs)
        val action = BolusAccount(store, serial).commandAccepted(tenOClock * 1000L, 0.5, "SMB").first
        cells.settled(listOf(BolusCells.Filled(null, action.id, 0.0, action.acceptedAtMs, faulty = false, inAaps = true)))
        cells.prune(capacity = 3)
        // Records 1 and 2 are three or more below the newest, 5; our cell with no record stands after 5 and stays.
        assertEquals(listOf(3L, 4L, 5L, null), store.cells(serial).map { it.seq })
        cells.take(listOf(record(tenOClock + 60L, 20, 20), record(tenOClock, 20, 20)) + past(5), 0L, nowMs)!!
        cells.prune(capacity = 3)
        // Our cell stands after 5, the place its record would have had: it goes with 5.
        assertEquals(listOf(5L, null, 6L, 7L), store.cells(serial).map { it.seq })
        cells.take(listOf(record(tenOClock + 120L, 20, 20), record(tenOClock + 60L, 20, 20), record(tenOClock, 20, 20)) + past(5), 0L, nowMs)!!
        cells.prune(capacity = 3)
        assertEquals(listOf(6L, 7L, 8L), store.cells(serial).map { it.seq })
    }

    @Test
    fun `a journal started anew is laid over without the records before it`() {
        cells.restart(past(5), 0L, nowMs)
        cells.restart(listOf(record(tenOClock + 600L, 20, 20)), 0L, nowMs)
        assertEquals(1, cells.keptFrames()!!.size)
        assertEquals(1, cells.take(listOf(record(tenOClock + 660L, 20, 20), record(tenOClock + 600L, 20, 20)), 0L, nowMs)!!.records.size)
    }

    @Test
    fun `nothing is kept before the first answer`() {
        assertNull(cells.keptFrames())
        assertNull(cells.readAtMs())
    }
}
