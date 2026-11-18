package app.aaps.pump.apex.deep

import app.aaps.pump.apex.account.bolus.BolusRecordFrames.record
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A bolus answer that shows nothing of the copy: read twice, started anew only when the two are alike. */
class JournalRestartDeepTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private val journal = JournalSnapshot(store, serial)
    private val offsetMs = 0L
    private val tenOClock = 1_760_000_000L / 60 * 60
    private val nowMs = tenOClock * 1000L + 90_000L

    private fun answer(records: List<JournalSnapshot.Answered>) =
        ApexJournalAnswer(records.map { ApexRead(it.frame, ApexBolusRecord.decode(it.frame)!!) }, records.size)

    /** Ten records a minute apart, newest first, from [newest] minutes after ten. */
    private fun ten(newest: Int) = (newest downTo newest - 9).map { record(tenOClock + it * 60L, 20, 20) }

    @Test
    fun `two answers alike start the journal anew, answers that differ do not`() {
        assertTrue(JournalRestartDeep.startsAnew(answer(ten(30)), answer(ten(30))))
        assertFalse(JournalRestartDeep.startsAnew(answer(ten(30)), answer(ten(31))))
        assertFalse(JournalRestartDeep.startsAnew(answer(ten(30)), answer(ten(30).take(9))))
    }

    @Test
    fun `a journal started anew takes the answer as the past, and the next answer lays over it`() {
        journal.take(ten(0), offsetMs, nowMs)
        assertNull(journal.take(ten(30), offsetMs, nowMs))

        val restart = journal.restart(ten(30), offsetMs, nowMs)
        assertTrue(restart.firstRead)
        assertEquals(10, restart.records.size)
        assertEquals(20L, journal.topSeq())

        val next = journal.take(listOf(record(tenOClock + 31 * 60L, 8, 8)) + ten(30), offsetMs, nowMs)!!
        assertFalse(next.firstRead)
        assertEquals(21L, next.records.single().seq)
    }

    @Test
    fun `the boluses last held longer ago than the insulin acts and five hours - it acts no more`() {
        val hour = 3_600_000L
        assertFalse(JournalRestartDeep.actsNoMore(nowMs - 5 * hour, nowMs, null))
        assertTrue(JournalRestartDeep.actsNoMore(nowMs - 5 * hour - 1, nowMs, null))
        assertFalse(JournalRestartDeep.actsNoMore(nowMs - 6 * hour, nowMs, 7 * hour))
        assertTrue(JournalRestartDeep.actsNoMore(nowMs - 7 * hour - 1, nowMs, 7 * hour))
        // An insulin that acts less than five hours: five hours stand.
        assertFalse(JournalRestartDeep.actsNoMore(nowMs - 4 * hour, nowMs, 3 * hour))
        assertTrue(JournalRestartDeep.actsNoMore(nowMs - 5 * hour - 1, nowMs, 3 * hour))
        // An insulin with no length is no insulin to tell: five hours stand.
        assertFalse(JournalRestartDeep.actsNoMore(nowMs - 4 * hour, nowMs, 0L))
        assertFalse(JournalRestartDeep.actsNoMore(nowMs - 4 * hour, nowMs, -1L))
        assertTrue(JournalRestartDeep.actsNoMore(null, nowMs, null))
    }

    @Test
    fun `an answer came whole when it brought as many records as the pump holds`() {
        val records = ten(9).map { ApexRead(it.frame, ApexBolusRecord.decode(it.frame)!!) }
        assertTrue(ApexJournalAnswer(records, 10).cameWhole)
        assertFalse(ApexJournalAnswer(records.dropLast(1), 10).cameWhole)
        assertFalse(ApexJournalAnswer(emptyList<ApexRead<ApexBolusRecord>>(), -1).cameWhole)
    }
}
