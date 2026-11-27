package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.ApexConst
import app.aaps.pump.apex.aaps.ApexLimits
import app.aaps.pump.apex.basal.TbrFacts
import app.aaps.pump.apex.basal.TbrFactsReader
import app.aaps.pump.apex.clock.ApexClock
import app.aaps.pump.apex.clock.ApexPumpClock
import app.aaps.pump.apex.aaps.AapsCarrier
import app.aaps.pump.apex.aaps.AapsToday
import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.basal.BasalPeriodKeeper
import app.aaps.pump.apex.basal.ExactBasalMode
import app.aaps.pump.apex.bolus.BolusReconciliation
import app.aaps.pump.apex.store.ClockOffset
import app.aaps.pump.apex.store.FakeStore
import app.aaps.pump.apex.basal.TbrReconciliation
import app.aaps.pump.apex.command.ApexBolusDelivery
import app.aaps.pump.apex.basal.TbrCommand
import app.aaps.pump.apex.keys.ApexStringKey
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexFinishedTbr
import app.aaps.pump.apex.protocol.ApexFinishedTbrAnswer
import app.aaps.pump.apex.protocol.ApexLastTbrCommand
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.protocol.ApexResponseParser
import app.aaps.pump.apex.protocol.ApexSettings
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.protocol.ApexStatusV2
import app.aaps.pump.apex.protocol.ApexTbrStatus
import app.aaps.pump.apex.protocol.ApexStamp
import app.aaps.pump.apex.protocol.ApexVersion
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito.mockingDetails
import org.mockito.invocation.Invocation
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.coroutines.Continuation

/** What the driver did at one heartbeat. */
class BeatResult(
    val index: Int,
    val beat: ReplayBeat,
    /** The status the driver read at this heartbeat; null when none was given. */
    val status: ApexStatusV1?,
    /** The answer of `HeartbeatTick.run`; null when it threw. */
    val ran: Boolean?,
    val error: Throwable?,
    val writes: List<AapsWrite>,
    val logs: List<LogLine>,
    val trace: List<TraceLine>,
    val statusReads: Int,
    val journalReads: Int,
    /** The periods the grid asked the pump for at this heartbeat. */
    val periodCalls: List<Int>,
    /** The calls the basal keeper made on the sync layer: method name and the numbers it gave. */
    val syncCalls: List<String>,
    /** The temporary basal row the driver holds open after this heartbeat, as text; null when none. */
    val openAfter: String?
)

/**
 * The real heartbeat code over a pump that answers from a recorded exchange. The manager is the only
 * mock that matters: it hands back what the record holds for the heartbeat being fed, and nothing
 * where the record holds nothing. The store is in memory. A pump of protocol 4.[protocolMinor]:
 * from 4.11 it has Status V2, a 4.10 pump has not.
 */
class ReplayRig(
    private val file: ReplayFile,
    mode: ExactBasalMode,
    protocolMinor: Int = 11,
    /** Where the answers come from; without it only Status V1 and the bolus journal of the beat itself are answered. */
    private val source: ReplaySource? = null,
    /** Called after each heartbeat has been fed, with its index; the clock writes of the record are made here. */
    private val afterBeat: (Int) -> Unit = {}
) {

    val serial = "REPLAY01"
    private val firmware = "1.2.3"
    val store = FakeStore()
    val pumpState = ApexPumpState()
    val log = ReplayLog()

    private val trace: ApexTrace = mock()
    private val manager: ApexManager = mock()
    private val carrier: AapsCarrier = mock()
    private val pumpSync: PumpSync = mock()
    private val carried = ArrayList<AapsWrite>()
    private val ordered = ReplayOrderedBasal()
    private var feeding: ReplayBeat? = null
    private var statusReads = 0
    private var journalReads = 0
    private val tick: HeartbeatTick

    init {
        pumpState.serialNumber = serial
        pumpState.version = ApexVersion(firmware.split('.').map { it.toInt() }, 4, protocolMinor, listOf(0, 0))
        // The record is of a pump that writes its stamps plain; the pass of 4.10 takes away only Status V2.
        ApexStamp.digits = false
        store.saveClock(serial, ClockOffset(offsetMs = 0L, writtenAtMs = 0L, reason = "replay"), 0L)

        val preferences: Preferences = mock()
        whenever(preferences.get(ApexStringKey.ExactBasal)).thenReturn(mode.value)
        whenever(preferences.get(LongNonKey.ActivePumpChangeTimestamp)).thenReturn(0L)
        val persistence: PersistenceLayer = mock()
        runBlocking {
            whenever(persistence.getTemporaryBasalsActiveAt(any())).thenReturn(emptyList())
            whenever(persistence.getTemporaryBasalsStartingFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        }
        doAnswer { inv -> carried.add(inv.getArgument(0)); null }.whenever(carrier).carry(any(), any())
        doAnswer { statusOf(feeding) }.whenever(manager).readStatus()
        doAnswer { journalOf(feeding) }.whenever(manager).readBolusHistory()
        doAnswer { daysOf() }.whenever(manager).readDailyStats()
        doAnswer { statusV2Of() }.whenever(manager).readStatusV2()
        doAnswer { lastCommandOf() }.whenever(manager).readLastTbrCommand()
        doAnswer { finishedOf() }.whenever(manager).readFinishedTbr()
        // The manager gives the period the user chose, one of 2, 3 or 5 minutes; a bare mock gives 0, and the grid divides by it.
        whenever(manager.heartbeatPeriodMinutes()).thenReturn(ApexConst.HEARTBEAT_PERIOD_DEFAULT)

        // The bolus journal and the basal period are told the basal the replay's rows and the status rates order.
        val orderedToday: AapsToday = mock()
        runBlocking { whenever(orderedToday.basalBetween(any(), any())).thenAnswer { ordered.between(it.getArgument(0), it.getArgument(1)) } }
        runBlocking { whenever(orderedToday.pumpRowsMs(any(), any())).thenReturn(emptyMap()) }
        // The record has one status per heartbeat: the reads of the check whether a bolus runs get it again, with no pause.
        val runningBolus = RunningBolus(log, manager, mock<ApexBolusDelivery>(), trace).apply { pause = {} }
        val keeper = BasalPeriodKeeper(log, preferences, store, pumpSync, persistence, mock<ProfileFunction>(), orderedToday, trace)
        val reader = PictureReader(log, manager, store, pumpState, TbrFactsReader(log, manager), keeper, trace)
        val pumpClock: ApexPumpClock = mock {
            on { phoneOffsetMs() } doReturn 0L
            // The offset the clock was written in last, as the real one gives it.
            on { offsetMs(any()) } doAnswer { ApexClock.offsetMs(store, it.getArgument(0), 0L) }
        }
        val reconciliation = Reconciliation(
            log, store, BolusReconciliation(log, store, manager, reader, carrier, orderedToday, preferences, pumpState, trace), TbrReconciliation(log, store, carrier, trace),
            keeper, runningBolus, carrier, preferences, trace
        )
        tick = HeartbeatTick(
            log, reader, HeartbeatGrid(log, manager, trace), manager, pumpState, pumpClock, reconciliation,
            mock<TbrCommand>(), mock<ApexLimits>(), PictureReport(log, mock<RxBus>(), store, pumpState, pumpClock, trace), CodeFault(log, mock(), mock()), StartTries(log, mock(), mock())
        )
    }

    /** The answer of the record for [kind]: from the source when there is one, else from the beat itself (Status V1 and the journal only). */
    private fun answer(kind: Char, beat: ReplayBeat?): RecordedAnswer? {
        if (source != null) return source.next(kind)
        return when (kind) {
            'S'  -> beat?.statusHex?.let { RecordedAnswer(beat.statusMs ?: beat.heartbeatMs, listOf(it)) }
            'J'  -> beat?.journalHex?.takeIf { it.isNotEmpty() }?.let { RecordedAnswer(beat.journalMs ?: beat.heartbeatMs, it) }
            else -> null
        }
    }

    /** Status V1 as the manager fills the state: the frame goes through the parser, then the card is set. */
    private fun statusOf(beat: ReplayBeat?): ApexPumpState.StatusCard? {
        statusReads++
        val got = answer('S', beat) ?: return null
        val frame = frameOf(got.hexes.first()) ?: return null
        val status = ApexStatusV1.decode(frame) ?: return null
        pumpState.applyStatus(status, frame.raw, got.ms, ApexSettings.decode(frame, 0))
        ordered.noteSchedule(got.ms, status.scheduledBasalRate)
        return pumpState.statusCard
    }

    /** The journal answer as the manager builds it: every frame that passes the parser and decodes, newest first. */
    private fun journalOf(beat: ReplayBeat?): ApexJournalAnswer<ApexBolusRecord>? {
        journalReads++
        return journal(answer('J', beat)) { ApexBolusRecord.decode(it) }
    }

    private fun daysOf(): ApexJournalAnswer<ApexDailyRecord>? = journal(answer('D', feeding)) { ApexDailyRecord.decode(it) }

    private fun <T : Any> journal(got: RecordedAnswer?, decode: (ApexResponseFrame) -> T?): ApexJournalAnswer<T>? {
        val hexes = got?.hexes?.takeIf { it.isNotEmpty() } ?: return null
        val decoded = hexes.mapNotNull { hex -> frameOf(hex)?.let { f -> decode(f)?.let { ApexRead(f, it) } } }
        return ApexJournalAnswer(decoded, decoded.lastOrNull()?.frame?.recordCount ?: -1)
    }

    private fun statusV2Of(): ApexStatusV2? {
        val status = answer('V', feeding)?.let { frameOf(it.hexes.first()) }?.let { ApexStatusV2.decode(it) } ?: return null
        pumpState.applyStatusV2(status)
        return status
    }

    /** As the manager does: every answer is a state, an empty one too. */
    private fun lastCommandOf(): ApexLastTbrCommand? {
        val frame = answer('A', feeding)?.let { frameOf(it.hexes.first()) } ?: return null
        return ApexLastTbrCommand(ApexTbrStatus.decode(frame), frame.raw)
    }

    private fun finishedOf(): ApexFinishedTbrAnswer? {
        val frame = answer('B', feeding)?.let { frameOf(it.hexes.first()) } ?: return null
        return ApexFinishedTbrAnswer(ApexFinishedTbr.decode(frame))
    }

    private fun frameOf(hex: String): ApexResponseFrame? =
        ApexResponseParser().feed(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }).firstOrNull()

    /** Feeds every heartbeat of the record in order; a heartbeat that throws is kept as such and the run goes on. */
    fun run(): List<BeatResult> = file.beats.mapIndexed { i, beat -> feed(i, beat) }

    private fun feed(index: Int, beat: ReplayBeat): BeatResult {
        feeding = beat
        source?.begin(index)
        statusReads = 0
        journalReads = 0
        carried.clear()
        val logFrom = log.all.size
        val traceFrom = invocations(trace, "event").size
        val periodFrom = invocations(manager, "setHeartbeatPeriod").size
        val syncFrom = mockingDetails(pumpSync).invocations.size
        var ran: Boolean? = null
        var error: Throwable? = null
        try {
            ran = tick.run(beat.heartbeatMs)
        } catch (t: Throwable) {
            error = t
        }
        val status = beat.statusHex?.let { frameOf(it) }?.let { ApexStatusV1.decode(it) }
        val result = BeatResult(
            index, beat, status, ran, error, carried.toList(), log.all.drop(logFrom),
            invocations(trace, "event").drop(traceFrom).map { traceLine(it.arguments) },
            statusReads, journalReads,
            invocations(manager, "setHeartbeatPeriod").drop(periodFrom).map { it.arguments[0] as Int },
            mockingDetails(pumpSync).invocations.drop(syncFrom).map { syncText(it) },
            TbrReconciliation.lastOf(store, serial)?.status?.let { TbrFacts.runningOf(it)?.text }
        )
        ordered.note(result.writes)
        afterBeat(index)
        return result
    }

    private fun syncText(call: Invocation): String = call.method.name + " " + call.arguments.filter { it !is Continuation<*> }.joinToString(" ")

    private fun invocations(mock: Any, method: String) = mockingDetails(mock).invocations.filter { it.method.name == method }

    private fun traceLine(args: Array<Any?>): TraceLine {
        val fields = args.drop(2).filterIsInstance<Pair<*, *>>().associate { it.first.toString() to it.second }
        return TraceLine(args[1].toString(), fields)
    }
}
