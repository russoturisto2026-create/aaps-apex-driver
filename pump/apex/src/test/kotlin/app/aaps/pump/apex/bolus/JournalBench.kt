package app.aaps.pump.apex.bolus

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.aaps.AapsCarrier
import app.aaps.pump.apex.aaps.AapsToday
import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.store.Action
import app.aaps.pump.apex.store.FakeStore
import app.aaps.pump.apex.store.Snapshot
import app.aaps.pump.apex.store.SnapshotKind
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
import app.aaps.pump.apex.statistics.StatisticsDays
import app.aaps.pump.apex.pulse.JournalRead
import app.aaps.pump.apex.pulse.PictureReader
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import kotlinx.coroutines.runBlocking
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * The real [BolusReconciliation] over a pump that answers from a queue, for the tests of whole journal
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

    /** When the newest bolus in AAPS was given; null while AAPS has none. */
    var newestBolusMs: Long? = null

    /** The rows of this pump AAPS holds: the time of each by its key. */
    var aapsRows: Map<Long, Long> = emptyMap()

    /** How long the insulin acts by the profile, ms; five hours by default; null while AAPS has no profile. */
    var insulinActsMs: Long? = 5 * 3_600_000L

    private val manager: ApexManager = mock(ApexManager::class.java)
    private val reader: PictureReader = mock(PictureReader::class.java)
    private val carrier: AapsCarrier = mock(AapsCarrier::class.java)
    private val aapsToday: AapsToday = mock(AapsToday::class.java)
    private val journal: BolusReconciliation

    /** True: the work stops right after the first row reaches AAPS, as when the phone kills AAPS there. */
    var faultAfterRow = false

    /** The journal of the last heartbeat settled, what the pump answers again when nothing else is queued. */
    private var lastAnswer: ApexJournalAnswer<ApexBolusRecord>? = null


    private val trace: ApexTrace = mock(ApexTrace::class.java)

    init {
        // A read with nothing queued answers the journal of the last heartbeat settled: the pump holds the same records.
        doAnswer { again.removeFirstOrNull() ?: lastAnswer }.`when`(manager).readBolusHistory()
        doAnswer { wholeReads++; whole.removeFirstOrNull() }.`when`(manager).readWholeBolusHistory()
        doAnswer { inv -> inv.getArgument<() -> ApexJournalAnswer<ApexBolusRecord>?>(1)()?.let { JournalRead(it, repeatedBroken = false) } }
            .`when`(reader).readBolusJournal(ArgumentMatchers.anyString(), anyArg(), ArgumentMatchers.anyString())
        doAnswer { inv ->
            written.add(inv.getArgument(0))
            if (faultAfterRow) throw IllegalStateException("the phone stopped right after the row went to AAPS")
            null
        }.`when`(carrier).carry(anyArg(), ArgumentMatchers.anyString())
        runBlocking { whenever(aapsToday.newestBolusMs(ArgumentMatchers.anyLong())).thenAnswer { newestBolusMs } }
        runBlocking { whenever(aapsToday.insulinActsMs()).thenAnswer { insulinActsMs } }
        runBlocking { whenever(aapsToday.pumpRowsMs(ArgumentMatchers.anyString(), ArgumentMatchers.anyLong())).thenAnswer { aapsRows } }
        journal = BolusReconciliation(AAPSLoggerTest(), store, manager, reader, carrier, aapsToday, mock(Preferences::class.java), mock(ApexPumpState::class.java), trace)
    }

    /**
     * The journal as it stands before the test: [records], newest first, taken as the past, and today's bolus field at
     * [dayRaw]. The pump holds a full journal, [BolusCells.CAPACITY] records, of which [records] are the newest.
     */
    fun past(records: List<BolusCells.Answered>, dayRaw: Int) {
        // The first answer of a pump reads the whole journal.
        thenWhole(records)
        newestBolusMs = Long.MAX_VALUE / 2
        check(settle(records, dayRaw))
        BolusCells(store, serial).countSeen(BolusCells.CAPACITY.toInt())
        newestBolusMs = null
        wholeReads = 0
        written.clear()
    }

    /** The last status seen, with the reservoir at [reservoirUnits]: the bound of `BolusReconciliation`. */
    fun status(reservoirUnits: Double) = store.saveSnapshot(serial, SnapshotKind.STATUS, Snapshot(listOf(statusFrame(reservoirUnits)), NOW_MS))

    /**
     * Our bolus accepted at [acceptedAtMs], its last word from the pump at [deliveredUnits]: completed when it
     * gave all, else stopped by the user's cancel the pump accepted. [seen] false: nothing of it was seen.
     */
    fun ours(acceptedAtMs: Long, units: Double, deliveredUnits: Double, type: String = "SMB", seen: Boolean = true): Action {
        val account = BolusAccount(store, serial)
        val action = account.commandAccepted(acceptedAtMs, units, type).first
        if (!seen) return action
        val full = deliveredUnits >= units - ApexProtocol.DOSE_SCALE / 2
        return account.finished(action, deliveredUnits, frames = 1, cancelPressed = !full, cancelAccepted = !full, completed = full)
    }

    /** Answers the pump gives when the journal is read again, in order. */
    fun thenAnswers(vararg answers: List<BolusCells.Answered>) = answers.forEach { again.addLast(answerOf(it)) }

    /** The whole journal of the pump, newest first, as it answers when asked for it. */
    fun thenWhole(records: List<BolusCells.Answered>, recordCount: Int = records.size) = whole.addLast(answerOf(records, recordCount))

    /**
     * One answer settled as a reconciliation with no bolus running does it, see `Reconciliation`; the line drawn is told after it: [answer],
     * newest first, with today's bolus field at [dayRaw]. The day is kept only when the reconciliation
     * succeeded: true then.
     */
    fun settle(answer: List<BolusCells.Answered>, dayRaw: Int, nowMs: Long = NOW_MS, recordCount: Int = answer.size): Boolean {
        val days = days(dayRaw)
        lastAnswer = answerOf(answer, recordCount)
        journal.settle(serial, answerOf(answer, recordCount), days, offsetMs = 0L, nowMs = nowMs)?.also { written += it.told } ?: return false
        StatisticsDays(store, serial).keepReconciled(days, nowMs)
        return true
    }

    /** [answer] settled with the daily statistics [days] read after it at [nowMs]. */
    fun settleWith(answer: List<BolusCells.Answered>, days: ApexJournalAnswer<ApexDailyRecord>, nowMs: Long, allDays: Boolean = false): Boolean {
        lastAnswer = answerOf(answer)
        journal.settle(serial, answerOf(answer), days, offsetMs = 0L, nowMs = nowMs, allDays = allDays)?.also { written += it.told } ?: return false
        StatisticsDays(store, serial).keepReconciled(days, nowMs)
        return true
    }

    /** The trace events of the settling, by name. */
    fun traced(): List<String> = mockingDetails(trace).invocations.filter { it.method.name == "event" }.map { it.arguments[1] as String }

    fun rows(): List<AapsWrite.Bolus> = written.filterIsInstance<AapsWrite.Bolus>()
    fun notices(): List<AapsWrite.Notice> = written.filterIsInstance<AapsWrite.Notice>()
    fun openCommands(): List<Action> = BolusAccount(store, serial).openActions()

    private fun answerOf(records: List<BolusCells.Answered>, recordCount: Int = records.size) =
        ApexJournalAnswer(records.map { ApexRead(it.frame, ApexBolusRecord.decode(it.frame)!!) }, recordCount)

    companion object {

        /** Today's day of the statistics, 03.11.2026, with the bolus field at [bolusRaw] steps. */
        fun days(bolusRaw: Int): ApexJournalAnswer<ApexDailyRecord> = ApexJournalAnswer(listOf(day(bolusRaw, 3, 0)), 1)

        /** A day of November 2026, [dayOfMonth], with the bolus field at [bolusRaw] steps, at [index] of its answer. */
        fun day(bolusRaw: Int, dayOfMonth: Int, index: Int): ApexRead<ApexDailyRecord> {
            val raw = ByteArray(ApexDailyRecord.RECORD_SIZE)
            raw[0] = ApexResponseFrame.MARKER
            raw[1] = 0x12
            raw[2] = 23
            raw[3] = 0xA3.toByte()
            raw[4] = ApexProtocol.HistoryOpcode.LATEST_DAILY_STATS
            raw[6] = (bolusRaw and 0xFF).toByte(); raw[7] = (bolusRaw shr 8).toByte()
            raw[5] = index.toByte()
            raw[12] = 26; raw[13] = 11; raw[14] = dayOfMonth.toByte()
            val frame = ApexResponseFrame(raw)
            return ApexRead(frame, ApexDailyRecord.decode(frame)!!)
        }

        /** 2026-11-03 14:00 UTC, a whole minute, in seconds. */
        const val TWO = 1_793_714_400L
        const val NOW_MS = TWO * 1000L + 15 * 60_000L


        /** Ten records a minute apart, newest first, the newest [minutesBeforeTwo] before two, each of [raw] steps. */
        fun ten(minutesBeforeTwo: Int = 1, raw: Int = 8) = (minutesBeforeTwo until minutesBeforeTwo + 10).map { BolusRecordFrames.record(TWO - it * 60L, raw, raw) }

        /** A record at [minutesAfterTwo] of [raw] steps, asked and delivered. */
        fun at(minutesAfterTwo: Int, raw: Int) = BolusRecordFrames.record(TWO + minutesAfterTwo * 60L, raw, raw)

        /** A record of [raw] steps whose stamp has no month and no day, as the pump has left in a cell. */
        fun undated(raw: Int): BolusCells.Answered {
            val bytes = at(0, raw).frame.raw.copyOf()
            bytes[7] = 0
            bytes[8] = 0
            val frame = ApexResponseFrame(bytes)
            return BolusCells.Answered(frame, ApexBolusRecord.decode(frame)!!.clockSeconds)
        }

        /** An old record in a new cell: 02.11 16:32:59, asked and delivered 0.675 U. */
        fun oldCell(): BolusCells.Answered {
            val frame = ApexResponseFrame("aa1680a321001a0b0210203b1b001b0000000000d869".chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            return BolusCells.Answered(frame, ApexBolusRecord.decode(frame)!!.clockSeconds)
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
