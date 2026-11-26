package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.pulse.JournalRead
import app.aaps.pump.apex.statistics.StatisticsDays
import app.aaps.pump.apex.store.FakeStore
import app.aaps.pump.apex.store.SnapshotKind
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.protocol.ApexResponseFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The daily statistics decide whether the bolus journal is read at a heartbeat. */
class BolusGateTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private val gate = BolusGate(store, serial)

    private val nowMs = 1_760_000_000_000L

    /** A day of the statistics as the pump answers it, raw doses in steps of 0.025 U. */
    private fun day(bolusRaw: Int, basalRaw: Int, tbrRaw: Int, year: Int = 26, month: Int = 10, dayOfMonth: Int = 31): ApexRead<ApexDailyRecord> {
        val raw = ByteArray(ApexDailyRecord.RECORD_SIZE)
        raw[0] = ApexResponseFrame.MARKER
        raw[1] = 0x12
        raw[2] = 23
        raw[3] = 0xA3.toByte()
        raw[4] = ApexProtocol.HistoryOpcode.LATEST_DAILY_STATS
        raw[5] = 0
        raw[6] = (bolusRaw and 0xFF).toByte(); raw[7] = (bolusRaw shr 8).toByte()
        raw[8] = (basalRaw and 0xFF).toByte(); raw[9] = (basalRaw shr 8).toByte()
        raw[10] = (tbrRaw and 0xFF).toByte(); raw[11] = (tbrRaw shr 8).toByte()
        raw[12] = year.toByte(); raw[13] = month.toByte(); raw[14] = dayOfMonth.toByte()
        val frame = ApexResponseFrame(raw)
        return ApexRead(frame, ApexDailyRecord.decode(frame)!!)
    }

    private fun days(vararg records: ApexRead<ApexDailyRecord>, repeatedBroken: Boolean = false) =
        JournalRead(ApexJournalAnswer(records.toList(), records.size), repeatedBroken)

    /** The count of Status V1 that agrees with [day]: its three fields together. */
    private fun counterOf(day: ApexRead<ApexDailyRecord>): Double = day.record.totalUnits

    private fun keep(day: ApexRead<ApexDailyRecord>) = StatisticsDays(store, serial).keepReconciled(days(day).answer, nowMs - 180_000L)

    @Test
    fun `with no day kept the journal is read`() {
        val today = day(40, 400, 20)
        val decision = gate.decide(counterOf(today), days(today))
        assertTrue(decision.read)
        assertEquals("no day kept yet", decision.reason)
    }

    @Test
    fun `the same bolus field as at the last heartbeat leaves the journal alone, the basal moved is reported`() {
        keep(day(40, 400, 20))
        val today = day(40, 404, 20)
        val decision = gate.decide(counterOf(today), days(today))
        assertFalse(decision.read)
        assertEquals(0.0, decision.bolusMovedUnits!!, 1e-9)
        assertEquals(0.1, decision.basalMovedUnits!!, 1e-9)
        assertEquals(0.0, decision.tbrMovedUnits!!, 1e-9)
        assertEquals(0.0, decision.sumGapUnits!!, 1e-9)
    }

    @Test
    fun `a bolus field that moved reads the journal, by how much is reported`() {
        keep(day(40, 400, 20))
        val today = day(60, 402, 20)
        val decision = gate.decide(counterOf(today), days(today))
        assertTrue(decision.read)
        assertEquals("the bolus field moved", decision.reason)
        assertEquals(0.5, decision.bolusMovedUnits!!, 1e-9)
    }

    @Test
    fun `a field that stood still since the day kept leaves the journal alone`() {
        keep(day(40, 400, 20))
        keep(day(72, 400, 20))
        val decision = gate.decide(counterOf(day(72, 400, 20)), days(day(72, 400, 20)))
        assertFalse(decision.read)
        assertEquals("no bolus since the last heartbeat", decision.reason)
    }

    @Test
    fun `a new date on the pump reads the journal, the fields started over`() {
        keep(day(40, 400, 20, dayOfMonth = 30))
        val today = day(0, 4, 0, dayOfMonth = 31)
        val decision = gate.decide(counterOf(today), days(today))
        assertTrue(decision.read)
        assertEquals("a new day on the pump", decision.reason)
    }

    @Test
    fun `the count one step off the fields is a basal step between the reads, more is a count the fields do not explain`() {
        keep(day(40, 400, 20))
        val today = day(40, 400, 20)
        assertFalse(gate.decide(counterOf(today) + ApexProtocol.DOSE_SCALE, days(today)).read)
        val off = gate.decide(counterOf(today) + 2 * ApexProtocol.DOSE_SCALE, days(today))
        assertTrue(off.read)
        assertEquals("the day's fields do not add up to the count", off.reason)
        assertEquals(-0.05, off.sumGapUnits!!, 1e-9)
    }

    @Test
    fun `a bolus of ours not reconciled reads the journal even when the field stood still`() {
        keep(day(40, 400, 20))
        BolusAccount(store, serial).commandAccepted(nowMs - 60_000L, 0.3, "SMB")
        val today = day(40, 400, 20)
        val decision = gate.decide(counterOf(today), days(today))
        assertTrue(decision.read)
        assertEquals("a bolus of ours waits for the reconciliation", decision.reason)
    }

    @Test
    fun `while a bolus of ours is not reconciled no new day is kept, the field that holds it stays in the span`() {
        keep(day(40, 400, 20))
        BolusAccount(store, serial).commandAccepted(nowMs - 60_000L, 0.3, "SMB")
        keep(day(52, 400, 20))
        assertEquals(0.3, gate.bolusMovedSinceKept(days(day(52, 400, 20)).answer)!!, 1e-9)
    }

    @Test
    fun `a day that came the same at every read is taken as it is, the journal is left alone`() {
        keep(day(40, 400, 20))
        val today = day(40, 400, 20)
        assertFalse(gate.decide(counterOf(today), days(today, repeatedBroken = true)).read)
    }

    @Test
    fun `the statistics with no day at all read the journal`() {
        keep(day(40, 400, 20))
        val decision = gate.decide(10.0, days())
        assertTrue(decision.read)
        assertEquals("the statistics came with no day", decision.reason)
    }

    @Test
    fun `keep holds the days of the answer, today on top as the day counted from`() {
        StatisticsDays(store, serial).keepReconciled(days(day(40, 400, 20), day(80, 500, 0, dayOfMonth = 30)).answer, nowMs)
        val kept = store.snapshot(serial, SnapshotKind.DAILY)
        assertNotNull(kept)
        assertEquals(2, kept!!.frames.size)
        assertEquals(1.0, StatisticsDays(store, serial).active()!!.bolusUnits, 1e-9)
        assertEquals(nowMs, kept.readAtMs)
    }

    @Test
    fun `a copy of a day after the kept one is not counted twice`() {
        keep(day(40, 400, 20, dayOfMonth = 30))
        // 31.10 twice: the upper day holds the lower one in every field, the lower is a copy.
        val answer = days(day(100, 500, 0), day(60, 300, 0), day(80, 1000, 20, dayOfMonth = 30)).answer
        assertEquals(1.0 + 2.5, gate.bolusMovedSinceKept(answer)!!, 1e-9)
    }

    @Test
    fun `the bolus field moved since the kept day, also across the pump's midnight`() {
        keep(day(40, 400, 20))
        assertEquals(0.25, gate.bolusMovedSinceKept(days(day(50, 410, 20)).answer)!!, 1e-9)
        // After midnight: today 0.2 U, and the kept day ended at 1.5 U, 0.5 U more than when kept.
        assertEquals(0.7, gate.bolusMovedSinceKept(days(day(8, 4, 0, month = 11, dayOfMonth = 1), day(60, 420, 20)).answer)!!, 1e-9)
        // The kept day is not in the answer: no word.
        assertEquals(null, gate.bolusMovedSinceKept(days(day(8, 4, 0, month = 11, dayOfMonth = 2)).answer))
    }

    @Test
    fun `the battery lost on 04_11 - the kept day went on with no date, a new day of the date started over above it`() {
        keep(day(40, 400, 20, month = 11, dayOfMonth = 4))
        val undated = day(60, 420, 20, year = 0, month = 0, dayOfMonth = 0)
        val anew = day(12, 4, 0, month = 11, dayOfMonth = 4)
        val answer = days(anew, undated, day(80, 500, 0, month = 11, dayOfMonth = 3)).answer
        // 0.5 U went in before the loss, 0.3 U after it.
        assertEquals(0.8, gate.bolusMovedSinceKept(answer)!!, 1e-9)
    }

    @Test
    fun `the battery lost on 07_11 - the day kept its date and its fields, it is counted on as usual`() {
        keep(day(60, 7, 0, month = 11, dayOfMonth = 7))
        val answer = days(day(80, 171, 0, month = 11, dayOfMonth = 7), day(374, 255, 0, month = 11, dayOfMonth = 6)).answer
        assertEquals(0.5, gate.bolusMovedSinceKept(answer)!!, 1e-9)
    }

    @Test
    fun `a day of the kept date that went back with no day under it to go on in - no word`() {
        keep(day(40, 400, 20, month = 11, dayOfMonth = 4))
        assertEquals(null, gate.bolusMovedSinceKept(days(day(12, 4, 0, month = 11, dayOfMonth = 4), day(80, 500, 0, month = 11, dayOfMonth = 3)).answer))
    }

    @Test
    fun `today below the day kept reads the journal and says the day started over`() {
        keep(day(40, 400, 20, month = 11, dayOfMonth = 4))
        val anew = day(12, 4, 0, month = 11, dayOfMonth = 4)
        val decision = gate.decide(counterOf(anew), days(anew))
        assertTrue(decision.read)
        assertTrue(decision.reason.startsWith("today went back"))
    }
}
