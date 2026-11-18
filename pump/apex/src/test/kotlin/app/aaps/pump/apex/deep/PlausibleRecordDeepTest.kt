package app.aaps.pump.apex.deep

import app.aaps.pump.apex.account.bolus.BolusRecordFrames.record
import app.aaps.pump.apex.account.bolus.JournalBench
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.account.store.Snapshot
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.protocol.ApexResponseFrame
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The bounds of the last status seen, as the broken-record look applies them to a journal answer. */
class PlausibleRecordDeepTest {

    private val store = FakeStore()
    private val serial = "12345678"

    /** 2026-11-03 14:00 UTC, a whole minute. */
    private val two = 1_793_714_400L
    private val nowMs = two * 1000L

    private fun answerOf(vararg records: JournalSnapshot.Answered) =
        ApexJournalAnswer(records.map { ApexRead(it.frame, ApexBolusRecord.decode(it.frame)!!) }, records.size)

    private fun bounds(nowMs: Long = this.nowMs, newestReservoirUnits: Double? = null) = PlausibleRecordDeep(store, serial, nowMs, newestReservoirUnits)

    private fun keepStatus(reservoirUnits: Double) =
        store.saveSnapshot(serial, SnapshotKind.STATUS, Snapshot(listOf(JournalBench.statusFrame(reservoirUnits)), nowMs))

    @Test
    fun `a record stamped a minute past now is sound, one stamped an hour past now is not`() {
        assertNull(BrokenRecordDeep.brokenBolus(answerOf(record(two + 60L, 20, 20)), bounds = bounds()))
        assertNotNull(BrokenRecordDeep.brokenBolus(answerOf(record(two + 3600L, 20, 20)), bounds = bounds()))
    }

    @Test
    fun `the future is told by the moment, not by the key - a stamp of a clock eight hours ahead of UTC is now`() {
        val stampedNowAtPlusEight = record(two + 8 * 3600L, 20, 20)
        assertNull(BrokenRecordDeep.brokenBolus(answerOf(stampedNowAtPlusEight), bounds = bounds(), offsetMs = 8 * ApexClock.HOUR_MS))
        assertNotNull(BrokenRecordDeep.brokenBolus(answerOf(stampedNowAtPlusEight), bounds = bounds(), offsetMs = 0L))
    }

    @Test
    fun `a record of more than the reservoir held is not sound - with no status seen there is no bound`() {
        val twentyUnits = record(two - 60L, 800, 800)
        assertNull(BrokenRecordDeep.brokenBolus(answerOf(twentyUnits), bounds = bounds()))
        keepStatus(10.0)
        assertNotNull(BrokenRecordDeep.brokenBolus(answerOf(twentyUnits), bounds = bounds()))
        assertNull(BrokenRecordDeep.brokenBolus(answerOf(record(two - 60L, 400, 400)), bounds = bounds()), "the reservoir itself is within bounds")
    }

    @Test
    fun `a cartridge changed since the last reconciliation - the newest status read bounds the record`() {
        val twelveUnits = record(two - 60L, 480, 480)
        keepStatus(8.0)
        assertNotNull(BrokenRecordDeep.brokenBolus(answerOf(twelveUnits), bounds = bounds()))
        assertNull(BrokenRecordDeep.brokenBolus(answerOf(twelveUnits), bounds = bounds(newestReservoirUnits = 288.0)))
        assertNotNull(BrokenRecordDeep.brokenBolus(answerOf(twelveUnits), bounds = bounds(newestReservoirUnits = 6.0)), "a lower newest status does not lower the bound below the last reconciliation, nor raise it")
    }

    @Test
    fun `a record out of bounds inside the copy was judged when it came - only the records above the copy are held to them`() {
        keepStatus(10.0)
        val big = record(two - 120L, 800, 800)
        val copy = listOf(big, record(two - 180L, 20, 20))
        val answer = answerOf(record(two - 60L, 20, 20), big, record(two - 180L, 20, 20))
        assertNull(BrokenRecordDeep.brokenBolus(answer, kept = copy.map { it.frame.raw }, bounds = bounds()))
        assertNotNull(BrokenRecordDeep.brokenBolus(answer, kept = null, bounds = bounds()))
    }

    @Test
    fun `a record a day old is sound - there is no bound from the past`() {
        keepStatus(10.0)
        assertNull(BrokenRecordDeep.brokenBolus(answerOf(record(two - 86_400L, 20, 20)), bounds = bounds()))
    }

}
