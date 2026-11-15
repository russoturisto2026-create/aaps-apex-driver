package app.aaps.pump.apex.account.bolus

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.account.AapsCarrier
import app.aaps.pump.apex.account.AapsToday
import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.basal.Delivered
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.Action
import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.account.store.Snapshot
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.protocol.ApexResponseParser
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.protocol.CrcUtilTest
import app.aaps.pump.apex.pulse.BolusGate
import app.aaps.pump.apex.pulse.JournalRead
import app.aaps.pump.apex.pulse.PictureReader
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import kotlinx.coroutines.runBlocking
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * The real [BolusJournal] over a pump that answers from a queue, for the tests of whole journal
 * pictures. Each read again takes the next answer queued; the rows carried to AAPS are collected
 * in [written]. The pump's clock stands at UTC: a record's key is its moment in seconds.
 */
class JournalBench {

    val store = FakeStore()
    val serial = "12345678"
    val written = ArrayList<AapsWrite>()
    private val again = ArrayDeque<ApexJournalAnswer<ApexBolusRecord>>()
    private val whole = ArrayDeque<ApexJournalAnswer<ApexBolusRecord>>()

    /** How many times the whole journal was asked for. */
    var wholeReads = 0

    /** How long the insulin in use acts, ms; null while AAPS has none. */
    var insulinActsMs: Long? = null

    private val manager: ApexManager = mock(ApexManager::class.java)
    private val reader: PictureReader = mock(PictureReader::class.java)
    private val carrier: AapsCarrier = mock(AapsCarrier::class.java)
    private val aapsToday: AapsToday = mock(AapsToday::class.java)
    private val journal: BolusJournal

    /** The basal the rates order over any span, U; null while AAPS cannot tell. */
    var basalOfSpan: Double? = 0.0

    init {
        doAnswer { again.removeFirstOrNull() }.`when`(manager).readBolusHistory()
        doAnswer { wholeReads++; whole.removeFirstOrNull() }.`when`(manager).readWholeBolusHistory()
        doAnswer { inv -> inv.getArgument<() -> ApexJournalAnswer<ApexBolusRecord>?>(1)()?.let { JournalRead(it, repeatedBroken = false) } }
            .`when`(reader).readJournal<ApexBolusRecord>(ArgumentMatchers.anyString(), anyArg(), anyArg())
        doAnswer { inv -> written.add(inv.getArgument(0)); null }.`when`(carrier).carry(anyArg(), ArgumentMatchers.anyString())
        runBlocking { whenever(aapsToday.basalBetween(any(), any())).thenAnswer { basalOfSpan } }
        runBlocking { whenever(aapsToday.insulinActsMs()).thenAnswer { insulinActsMs } }
        journal = BolusJournal(AAPSLoggerTest(), store, manager, reader, carrier, aapsToday, mock(Preferences::class.java), mock(ApexTrace::class.java))
    }

    /** The journal as it stands before the test: [records], newest first, taken as the past, and today's bolus field at [dayRaw]. */
    fun past(records: List<JournalSnapshot.Answered>, dayRaw: Int) {
        check(settle(records, dayRaw))
        written.clear()
    }

    /** The last status seen, with the reservoir at [reservoirUnits]: the bounds of `PlausibleRecordDeep`. */
    fun status(reservoirUnits: Double) = store.saveSnapshot(serial, SnapshotKind.STATUS, Snapshot(listOf(statusFrame(reservoirUnits)), NOW_MS))

    /** Our bolus accepted at [acceptedAtMs], closed by the pump at [deliveredUnits]. */
    fun ours(acceptedAtMs: Long, units: Double, deliveredUnits: Double, type: String = "SMB"): Action {
        val account = BolusAccount(store, serial)
        return account.commandAccepted(acceptedAtMs, units, type).also { account.finished(it, deliveredUnits, completed = true) }
    }

    /** Answers the pump gives when the journal is read again, in order. */
    fun thenAnswers(vararg answers: List<JournalSnapshot.Answered>) = answers.forEach { again.addLast(answerOf(it)) }

    /** How many times the settling asked for the basal of the span to be closed before the boluses. */
    var basalClosed = 0

    /** What the settling is told of the basal rows when it asks: false, they could not be made to stand. */
    var basalStands = true

    /** The whole journal of the pump, newest first, as it answers when asked for it. */
    fun thenWhole(records: List<JournalSnapshot.Answered>) = whole.addLast(answerOf(records))

    /**
     * One answer settled as a heartbeat does it: [answer], newest first, with today's bolus field at
     * [dayRaw], or no statistics when null. The day is kept when the answer is settled.
     */
    fun settle(answer: List<JournalSnapshot.Answered>, dayRaw: Int?, nowMs: Long = NOW_MS): Boolean {
        val days = dayRaw?.let { days(it) }
        val settled = journal.settle(serial, answerOf(answer), days?.let { BolusSpanEnd.DayRecord(it) }, offsetMs = 0L, nowMs = nowMs, closeBasal = { basalClosed++; basalStands })
        if (settled && days != null) BolusGate(store, serial).keep(JournalRead(days, repeatedBroken = false), nowMs)
        return settled
    }

    /** The journal as it stands before the test on a pump without the statistics: [records], newest first, taken as the past, the count of the day at [countUnits] kept at [atMs]. */
    fun pastByCount(records: List<JournalSnapshot.Answered>, countUnits: Double, atMs: Long = BEFORE_MS, reservoirUnits: Double? = null) {
        check(settleByCount(records, countUnits, atMs, reservoirUnits = reservoirUnits))
        written.clear()
    }

    /**
     * One answer settled as a heartbeat of a pump without the statistics does it: the count of the
     * day at [countUnits] on the pump's day [pumpDay] with the reservoir at [reservoirUnits], read at [atMs]. The mark moves when the
     * answer is settled, and is kept when none is, as the heartbeat does it.
     */
    fun settleByCount(answer: List<JournalSnapshot.Answered>, countUnits: Double, atMs: Long = NOW_MS, pumpDay: Long = PUMP_DAY, reservoirUnits: Double? = null): Boolean {
        val end = BolusSpanEnd.Count(atMs, Delivered.Count(countUnits, pumpDay), reservoirUnits)
        val settled = journal.settle(serial, answerOf(answer), end, offsetMs = 0L, nowMs = atMs, closeBasal = { basalClosed++; basalStands })
        if (settled) {
            BolusAccount(store, serial).clearSettledSinceHeartbeat()
            BolusCountMark(store, serial).move(end)
        } else {
            BolusCountMark(store, serial).moveIfAbsent(end)
        }
        return settled
    }

    /** One answer settled right after our own bolus, which has no count to give: as `BolusCommand` does it on a pump without the statistics. */
    fun settleAfterOurBolus(answer: List<JournalSnapshot.Answered>): Boolean = journal.settle(serial, answerOf(answer), end = null, offsetMs = 0L, nowMs = NOW_MS)

    fun rows(): List<AapsWrite.Bolus> = written.filterIsInstance<AapsWrite.Bolus>()
    fun notices(): List<AapsWrite.Notice> = written.filterIsInstance<AapsWrite.Notice>()
    fun openCommands(): List<Action> = BolusAccount(store, serial).openActions()

    private fun answerOf(records: List<JournalSnapshot.Answered>) =
        ApexJournalAnswer(records.map { ApexRead(it.frame, ApexBolusRecord.decode(it.frame)!!) }, records.size)

    /** Today's day of the statistics, 03.11.2026, with the bolus field at [bolusRaw] steps. */
    private fun days(bolusRaw: Int): ApexJournalAnswer<ApexDailyRecord> {
        val raw = ByteArray(ApexDailyRecord.RECORD_SIZE)
        raw[0] = ApexResponseFrame.MARKER
        raw[1] = 0x12
        raw[2] = 23
        raw[3] = 0xA3.toByte()
        raw[4] = ApexProtocol.HistoryOpcode.LATEST_DAILY_STATS
        raw[6] = (bolusRaw and 0xFF).toByte(); raw[7] = (bolusRaw shr 8).toByte()
        raw[12] = 26; raw[13] = 11; raw[14] = 3
        val frame = ApexResponseFrame(raw)
        return ApexJournalAnswer(listOf(ApexRead(frame, ApexDailyRecord.decode(frame)!!)), 1)
    }

    companion object {

        /** 2026-11-03 14:00 UTC, a whole minute, in seconds. */
        const val TWO = 1_793_714_400L
        const val NOW_MS = TWO * 1000L + 15 * 60_000L

        /** The heartbeat before, five minutes earlier. */
        const val BEFORE_MS = NOW_MS - 5 * 60_000L

        /** The pump's calendar day of [NOW_MS]. */
        const val PUMP_DAY = TWO / 86_400L

        /** Ten records a minute apart, newest first, the newest [minutesBeforeTwo] before two, each of [raw] steps. */
        fun ten(minutesBeforeTwo: Int = 1, raw: Int = 8) = (minutesBeforeTwo until minutesBeforeTwo + 10).map { BolusRecordFrames.record(TWO - it * 60L, raw, raw) }

        /** A record at [minutesAfterTwo] of [raw] steps, asked and delivered. */
        fun at(minutesAfterTwo: Int, raw: Int) = BolusRecordFrames.record(TWO + minutesAfterTwo * 60L, raw, raw)

        /** A record of [raw] steps whose stamp has no month and no day, as the pump has left in a cell. */
        fun undated(raw: Int): JournalSnapshot.Answered {
            val bytes = at(0, raw).frame.raw.copyOf()
            bytes[7] = 0
            bytes[8] = 0
            val frame = ApexResponseFrame(bytes)
            return JournalSnapshot.Answered(frame, ApexBolusRecord.decode(frame)!!.clockSeconds)
        }

        /** An old record in a new cell: 02.11 16:32:59, asked and delivered 0.675 U. */
        fun oldCell(): JournalSnapshot.Answered {
            val frame = ApexResponseFrame("aa1680a321001a0b0210203b1b001b0000000000d869".chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            return JournalSnapshot.Answered(frame, ApexBolusRecord.decode(frame)!!.clockSeconds)
        }

        /** The Status V1 frame of the fixture with the reservoir at [reservoirUnits]. */
        fun statusFrame(reservoirUnits: Double): ByteArray {
            val raw = ApexResponseParser().feed(CrcUtilTest.hexToBytes(CrcUtilTest.STATUS_V1_FRAME))[0].raw.copyOf()
            val at = ApexResponseFrame.DATA_BASE_OFFSET + ApexStatusV1.Offset.RESERVOIR
            val steps = Math.round(reservoirUnits / ApexProtocol.RESERVOIR_SCALE).toInt()
            raw[at] = (steps and 0xFF).toByte()
            raw[at + 1] = (steps shr 8 and 0xFF).toByte()
            raw[at + 2] = (steps shr 16 and 0xFF).toByte()
            return raw
        }

        /** Any argument, typed for Kotlin's non-null parameters. */
        @Suppress("UNCHECKED_CAST")
        fun <T> anyArg(): T {
            ArgumentMatchers.any<T>()
            return null as T
        }
    }
}
