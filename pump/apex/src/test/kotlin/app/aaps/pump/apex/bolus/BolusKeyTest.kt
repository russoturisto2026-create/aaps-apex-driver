package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.bolus.BolusRecordFrames.record
import app.aaps.pump.apex.store.FakeStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The key of a bolus row comes from the record itself: the same record gives the same key however it came. */
class BolusKeyTest {

    private val serial = "12345678"
    private val tenOClock = 1_760_000_000L / 60 * 60 // a whole minute
    private val nowMs = tenOClock * 1000L + 90_000L

    /** [count] records a minute apart, newest first, the newest at ten o'clock; each a different dose. */
    private fun journalOf(count: Int) = (0 until count).map { record(tenOClock - it * 60L, 20 + it % 7, 20 + it % 7) }

    /** The keys of a fresh base that took [answer] as its first, whole journal, by stamp: what a lost base gives. */
    private fun keysOf(answer: List<BolusCells.Answered>): Map<Long, Long> {
        val cells = BolusCells(FakeStore(), serial)
        val added = cells.restart(answer, 0L, nowMs)
        val keys = cells.keys()
        return added.records.associate { it.clockSeconds to keys.getValue(it.seq) }
    }

    @Test
    fun `one record, ten or a whole journal give the newest record the same key, and a base lost gives it again`() {
        val whole = keysOf(journalOf(128))
        val ten = keysOf(journalOf(10))
        val one = keysOf(journalOf(1))
        val newest = tenOClock + 59
        assertEquals(whole[newest], ten[newest])
        assertEquals(whole[newest], one[newest])
        // Every record of the ten has the key the whole journal gave it.
        ten.forEach { (stamp, key) -> assertEquals(whole[stamp], key) }
    }

    @Test
    fun `a record laid over the cells later keeps the key it would have from a whole journal`() {
        val cells = BolusCells(FakeStore(), serial)
        cells.restart(journalOf(10).drop(1), 0L, nowMs)
        val added = cells.take(journalOf(10), 0L, nowMs)!!
        assertEquals(keysOf(journalOf(10))[tenOClock + 59], cells.keys()[added.records.single().seq])
    }

    @Test
    fun `two records alike in one minute differ by their repeat, the older is 0`() {
        val cells = BolusCells(FakeStore(), serial)
        val added = cells.restart(listOf(record(tenOClock, 20, 20), record(tenOClock, 20, 20)), 0L, nowMs)
        val (older, newer) = added.records.map { cells.keys().getValue(it.seq) }
        assertEquals(1L, newer - older)
        assertEquals(0L, older % 10)
    }

    @Test
    fun `the seconds of the stamp are not in the key, the minute and both doses are`() {
        fun keyOf(vararg answer: BolusCells.Answered): Long {
            val cells = BolusCells(FakeStore(), serial)
            val seq = cells.restart(answer.toList(), 0L, nowMs).records.last().seq
            return cells.keys().getValue(seq)
        }
        val base = keyOf(record(tenOClock, 40, 32, seconds = 59))
        assertEquals(base, keyOf(record(tenOClock, 40, 32, seconds = 3)))
        assertNotEquals(base, keyOf(record(tenOClock + 60, 40, 32)))
        assertNotEquals(base, keyOf(record(tenOClock, 41, 32)))
        assertNotEquals(base, keyOf(record(tenOClock, 40, 33)))
    }

    @Test
    fun `a number no cell holds has no key`() {
        val cells = BolusCells(FakeStore(), serial)
        cells.restart(journalOf(3), 0L, nowMs)
        assertNull(cells.keys()[99L])
    }
}
