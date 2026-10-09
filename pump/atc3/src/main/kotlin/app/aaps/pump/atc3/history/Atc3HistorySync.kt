package app.aaps.pump.atc3.history

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.basal.Atc3TbrAction
import app.aaps.pump.atc3.basal.Atc3TbrTracker
import app.aaps.pump.atc3.basal.PumpTbr
import app.aaps.pump.atc3.clock.Atc3ClockWatch
import app.aaps.pump.atc3.protocol.Atc3BolusHistory
import app.aaps.pump.atc3.protocol.Atc3BolusRecord
import app.aaps.pump.atc3.protocol.Atc3FinishedTbr
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3StatusV1
import app.aaps.pump.atc3.protocol.Atc3TbrStatus
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.store.Atc3Store
import app.aaps.pump.atc3.store.Atc3StoredState
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A bolus whose delivered amount the driver learned: when, how much, and when it began at the latest.
 * The comparison counts these as an interval's boluses: no window over the rows' minutes can say which
 * boluses the pump's count holds, but a bolus is learned only after its delivery, once.
 */
@Serializable
data class LearnedBolus(val learnedAtMs: Long, val units: Double, val startMs: Long)

/**
 * The one writer of boluses and temporary basals into AAPS, in front of one ledger.
 *
 * Bolus rows come from the pump's records only, whoever gave the bolus, see [Atc3BolusReconciler]:
 * one of ours is written when its record is read, not when the pump accepts it. Until then it is
 * expected, [ExpectedBolus], and the record is told from anyone else's by its dose and minute.
 */
@Singleton
class Atc3HistorySync @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val uiInteraction: UiInteraction,
    private val preferences: Preferences,
    private val store: Atc3Store,
    private val pumpSync: PumpSync,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val pumpState: Atc3PumpState,
    private val clockWatch: Atc3ClockWatch,
    private val trace: Atc3Trace,
    private val registration: Atc3PumpRegistration
) {

    /** When the delivery state was last taken in, 0 until once; not kept across a restart. */
    @Volatile private var lastStatusAtMs: Long = 0L

    /** The newest record of the last bolus history answer, 0 before one: an answer without its minute left records out. */
    @Volatile private var lastAnswerNewestUtcSeconds: Long = 0L

    /** The records passed over and named in the log already, by their pump clock key; not kept across a restart. */
    private val passedOver = HashSet<Long>()

    private val serial: String get() = pumpState.serialNumber

    /** The ledger of the pump in use, as kept in [store]: there is no second copy to disagree with it. Another pump's ledger is not this one's. */
    @Synchronized
    private fun ledger(): Atc3HistoryLedger =
        store.state.ledger.takeIf { it.serial == serial } ?: Atc3HistoryLedger(serial = serial)

    /**
     * Keep a ledger worked out from [ledger]. One worked out for a pump forgotten since, by a history
     * read the user disconnected the pump in the middle of, is dropped: kept, it would bring that pump back.
     */
    @Synchronized
    private fun store(updated: Atc3HistoryLedger) {
        if (updated.serial != serial) {
            aapsLogger.debug(LTag.PUMP, "ATC3: a ledger of pump ${updated.serial} is not kept, that pump was forgotten")
            return
        }
        store.update { it.copy(ledger = updated) }
    }

    /**
     * The pump accepted a bolus of ours: its record is expected, and the row made of it takes [type].
     * On disk before anything is delivered, so a process killed meanwhile still knows the bolus.
     */
    @Synchronized
    fun expect(acceptedAtMs: Long, units: Double, type: BS.Type) {
        store(ledger().withExpected(ExpectedBolus(acceptedAtMs, units, type)))
        trace.event(Atc3TraceCat.HIST, "bolus_expected", "units" to units, "at" to acceptedAtMs)
    }

    /** The bolus of ours accepted at [acceptedAtMs] was last seen delivered to [units]: its row when the pump writes no record. */
    @Synchronized
    fun seen(acceptedAtMs: Long, units: Double) {
        val current = ledger()
        store(current.copy(expected = current.expected.map { if (it.acceptedAtMs == acceptedAtMs) it.copy(seenUnits = units) else it }))
    }

    /** The newest bolus of ours waiting for its record that the pump accepted after [sinceMs] and was seen delivering, or null. */
    @Synchronized
    fun expectedSeenAfter(sinceMs: Long): ExpectedBolus? =
        ledger().expected.filter { it.acceptedAtMs >= sinceMs && it.seenUnits >= Atc3Protocol.DOSE_SCALE }.maxByOrNull { it.acceptedAtMs }

    /** The bolus of ours accepted at [acceptedAtMs] waits for no record any longer: it is accounted for another way. */
    @Synchronized
    fun dropExpected(acceptedAtMs: Long) {
        val current = ledger()
        store(current.copy(expected = current.expected.filterNot { it.acceptedAtMs == acceptedAtMs }))
    }

    /** The slots of the pump's journal known not to have been written, see [Atc3JournalFault]. */
    @Synchronized
    fun knownFaults(): List<JournalFault> = ledger().faults

    /** The faults whose bolus is not accounted for yet. */
    @Synchronized
    fun unsettledFaults(): List<JournalFault> = ledger().faults.filterNot { it.settled }

    /** Keep [found] with the faults known, once each; the ones found so long ago that the journal has dropped them are let go. */
    @Synchronized
    fun rememberFaults(found: List<JournalFault>) {
        val keepFrom = dateUtil.now() - FAULTS_KEPT_MS
        val kept = ledger().faults.filter { it.foundAtMs >= keepFrom }
        val new = found.filterNot { entry -> kept.any { it.sameAs(entry) } }
        store(ledger().withFaults(kept + new))
    }

    /** The bolus [fault] was taken for is accounted for, or the user was told it cannot be. */
    @Synchronized
    fun markFaultSettled(fault: JournalFault) {
        store(ledger().withFaults(ledger().faults.map { if (it.sameAs(fault)) it.copy(settled = true) else it }))
    }

    /**
     * Write a bolus the pump gave and did not write down, as the rows of records are written: at the
     * start of its minute, numbered after the rows of that minute, counted from [startMs].
     *
     * @return the row's pump id, or null when nothing was written
     */
    suspend fun writeUnrecorded(startMs: Long, units: Double, type: BS.Type): Long? {
        if (!registration.ensureRegistered()) return null
        val minute = Atc3BolusReconciler.minuteStartOf(startMs)
        val held = rowsOfLastDay(dateUtil.now()).count { it.timestamp in minute until minute + 60_000L }
        val pumpId = minute + held
        if (!syncPumpBolus(minute, units, pumpId, type)) {
            aapsLogger.error(LTag.PUMP, "ATC3: AAPS refused the unrecorded bolus of $units U at $minute, id $pumpId")
            return null
        }
        aapsLogger.debug(LTag.PUMP, "ATC3: unrecorded bolus of $units U at $minute written as ${type.name}, id $pumpId")
        learn(units, startMs)
        pumpState.lastBolus = Atc3PumpState.LastBolus(minute, units)
        return pumpId
    }

    /** Note a bolus whose delivered amount is known from now on. Zero is nothing to note. */
    @Synchronized
    private fun learn(units: Double, startMs: Long) {
        if (units == 0.0) return
        val bolus = LearnedBolus(dateUtil.now(), units, startMs)
        store.update { it.copy(learned = it.learned + bolus) }
    }

    /** True when a bolus learned after [sinceMs] began by [readMs]: part of it may be in the count at that read. */
    @Synchronized
    fun bolusStraddles(sinceMs: Long, readMs: Long): Boolean {
        return store.state.learned.any { it.learnedAtMs > sinceMs && it.startMs <= readMs }
    }

    /** The boluses learned after [sinceMs], summed: the boluses of a window begun then. */
    @Synchronized
    fun bolusesLearnedAfter(sinceMs: Long): Double {
        return store.state.learned.filter { it.learnedAtMs > sinceMs }.sumOf { it.units }
    }

    /** Let go of the boluses learned so long ago that no window still to close may count them. */
    @Synchronized
    fun pruneLearned() {
        val keepFrom = dateUtil.now() - LEARNED_KEPT_MS
        store.update { state -> state.copy(learned = state.learned.filter { it.learnedAtMs >= keepFrom }) }
    }

    /** True while a bolus of ours waits for the pump's record of it: no clock write and no closed window meanwhile. */
    @Synchronized
    fun hasExpectedBolus(): Boolean = ledger().expected.isNotEmpty()

    /** What one reconciliation turned out to change. */
    data class ReconcileResult(
        /** The boluses of ours whose records came, by when the pump accepted them: what each delivered, U. */
        val confirmed: Map<Long, Double> = emptyMap(),
        /** When the newest bolus nobody had told AAPS of was given: what refuses an SMB decided without it. */
        val newestImportedAtMs: Long? = null,
        /** Insulin imported from the records this pass, U. */
        val importedUnits: Double = 0.0
    )

    /** How long ago the delivery state was last taken in; two callers ask with different thresholds. */
    fun stateAgeMs(now: Long): Long =
        if (lastStatusAtMs == 0L) Long.MAX_VALUE else now - lastStatusAtMs

    /** Bring AAPS in line with the bolus records the pump returned, see [Atc3BolusReconciler]. */
    suspend fun reconcileBoluses(records: List<Atc3BolusRecord>, recordCount: Int): ReconcileResult {
        // Not registered: nothing is written, and the ledger is left alone so the records are offered again.
        if (!registration.ensureRegistered()) {
            trace.event(Atc3TraceCat.HIST, "reconcile", "ok" to false, "why" to "not_registered")
            return ReconcileResult()
        }
        val now = dateUtil.now()
        val outcome = Atc3BolusReconciler.reconcile(
            records = records,
            rows = rowsOfLastDay(now),
            ledger = ledger(),
            phoneNow = now,
            earliestAcceptedMs = preferences.get(LongNonKey.ActivePumpChangeTimestamp)
        )
        // A day without the record: nothing is coming, and nothing is held for it any longer.
        store(outcome.ledger.copy(expected = outcome.ledger.expected.filter { now - it.acceptedAtMs < Atc3Const.RECONCILE_MAX_AGE_MS }))
        records.maxOfOrNull { it.pumpClockUtcSeconds }?.let { lastAnswerNewestUtcSeconds = it }
        // Each record passed over is named once, with its date: nothing the pump sent goes unseen in the log.
        for (record in outcome.skipped) {
            if (!passedOver.add(record.pumpClockUtcSeconds)) continue
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: record at ${dateUtil.dateAndTimeString(record.timestamp)}, ${record.requestedUnits}/${record.deliveredUnits} U, " +
                    "passed over: the pump's past, older than a day, or empty"
            )
        }
        outcome.clockShiftMinutes?.let { noteClockShift(it) }

        for (write in outcome.writes) {
            val type = write.expected?.type ?: BS.Type.NORMAL
            aapsLogger.debug(LTag.PUMP, "ATC3: bolus of ${write.units} U at ${write.timestamp} written as ${type.name}, id ${write.pumpId}")
            if (!syncPumpBolus(write.timestamp, write.units, write.pumpId, type)) {
                aapsLogger.error(LTag.PUMP, "ATC3: AAPS refused the bolus of ${write.units} U at ${write.timestamp}, id ${write.pumpId}")
            }
            // Counted for the comparison from now on; ours began when the pump accepted it, another's within its minute.
            learn(write.units, write.expected?.acceptedAtMs ?: write.timestamp)
        }
        outcome.extended.forEach { announceExtended(it.timestamp, it.units) }
        outcome.writes.maxByOrNull { it.timestamp }?.let { pumpState.lastBolus = Atc3PumpState.LastBolus(it.timestamp, it.units) }

        val imported = outcome.writes.filter { it.expected == null }
        trace.event(
            Atc3TraceCat.HIST, "reconcile",
            "ok" to true,
            "sent" to records.size,
            "held" to recordCount,
            "written" to outcome.writes.size,
            "ours" to outcome.writes.size - imported.size,
            "extended" to outcome.extended.size,
            "beyond" to outcome.rowsBeyond,
            "expected" to outcome.ledger.expected.size
        )
        return ReconcileResult(
            confirmed = outcome.writes.mapNotNull { write -> write.expected?.let { it.acceptedAtMs to write.units } }.toMap(),
            newestImportedAtMs = imported.maxOfOrNull { it.timestamp },
            importedUnits = imported.sumOf { it.units }
        )
    }

    /** The rows AAPS holds of this pump over the last day, deleted ones included: a deleted row is still its record's row. */
    private fun rowsOfLastDay(now: Long): List<Atc3BolusRow> =
        persistenceLayer.getBolusesFromTimeIncludingInvalid(Atc3BolusReconciler.minuteStartOf(now - Atc3Const.RECONCILE_MAX_AGE_MS), true)
            .blockingGet()
            .filter { it.ids.pumpSerial == serial }
            .map { Atc3BolusRow(it.timestamp, Atc3HistoryLedger.raw(it.amount)) }

    /** True when the pump holds records this answer left out, see [Atc3BolusReconciler.recordsMissing]. */
    fun recordsMissing(history: Atc3BolusHistory): Boolean =
        Atc3BolusReconciler.recordsMissing(history, lastAnswerNewestUtcSeconds)

    /** The temporary basal the ledger believes is running, or null. */
    @Synchronized
    fun openTbr(): ActiveTbr? = ledger().activeTbr

    /**
     * The pump's clock was just written, after the history was taken in: where imports start from moves
     * to the moment of the write, or records stamped on a clock put back would never be imported.
     */
    @Synchronized
    fun onPumpClockWritten(atMs: Long) {
        val current = ledger()
        if (!current.firstPassDone) return
        val atUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(atMs)
        store(current.withWatermark(atUtcSeconds, atMs))
        aapsLogger.debug(LTag.PUMP, "ATC3: the pump clock was written, history imports start from $atMs")
    }

    /**
     * When the temporary basal AAPS has open really ended, by the pump's last finished one, or null.
     * That record can be hours old, so it is believed only at the same rate, starting no earlier than
     * the open one, ending after it began.
     */
    @Synchronized
    fun realEndOf(finished: Atc3FinishedTbr?): Long? {
        val open = ledger().activeTbr ?: return null
        val record = finished ?: return null
        val sameRate = record.rate?.let { rawOf(it) == rawOf(open.rate) } ?: false
        val own = open.pumpStartUtcSeconds
        val notOlder = if (own != null) record.startUtcSeconds >= own - TBR_SAME_START_SECONDS
        else record.startTimestamp >= open.startedAtMs - TBR_END_MATCH_MS
        if (!sameRate || !notOlder || record.endTimestamp < open.startedAtMs) {
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: the last finished temporary basal, ${record.rate} U/h from ${record.startTimestamp}, " +
                    "is not the ${open.rate} U/h open since ${open.startedAtMs}; closing at the poll"
            )
            return null
        }
        return record.endTimestamp
    }

    /**
     * Follow what the pump delivers, whoever set it; a stopped pump goes to AAPS as a temporary basal of
     * zero, see [Atc3TbrTracker].
     *
     * @param pumpTbrStart the running temporary basal as the pump records it, when read, for its start
     * @param pausedAtMs when the pump says it stopped, or null; see [Atc3TbrTracker.step]
     * @param resumedAtMs when the pump says it started again, or null
     */
    suspend fun onStatus(
        suspended: Boolean,
        tbrRunning: Boolean,
        rate: Double,
        durationMs: Long?,
        pumpTbrStart: Atc3TbrStatus?,
        endedAtMs: Long? = null,
        pausedAtMs: Long? = null,
        resumedAtMs: Long? = null
    ) {
        val phoneNow = dateUtil.now()
        val begin = pumpTbrStart?.let {
            // The start the pump gives, as it gives it: its clock is written from the phone's.
            val startedAt = it.startTimestamp
            // A start the pump did not fill in would make AAPS refuse the whole record: the row starts where
            // the driver noticed it instead.
            val runsFor = it.durationMinutes.takeIf { minutes -> minutes > 0 }?.let { minutes ->
                minutes * 60_000L
            }
            if (!it.isStartPlausible(pumpNow = phoneNow)) {
                aapsLogger.error(
                    LTag.PUMP,
                    "ATC3: the pump gave ${it.startTimestamp} as the start of the temporary basal it " +
                        "is running, which cannot be right; recording it from now instead"
                )
                trace.event(
                    Atc3TraceCat.TBR, "start_implausible",
                    "pumpAt" to it.startTimestamp,
                    // What was asked, a percentage when set so on the keypad; the rate in force is `rate`.
                    "asked" to it.amountAsked,
                    "min" to it.durationMinutes
                )
                // Only the start is disbelieved; the duration keeps the row from being pushed on at every look.
                return@let PumpTbr(atMs = phoneNow, utcSeconds = null, durationMs = runsFor)
            }
            PumpTbr(atMs = startedAt, utcSeconds = it.startUtcSeconds, durationMs = runsFor, rawRate = it.rate?.let { r -> rawOf(r) })
        }
        val before = ledger().activeTbr
        val (actions, active) = Atc3TbrTracker.step(
            active = before,
            suspended = suspended,
            tbrRunning = tbrRunning,
            rate = rate,
            durationMs = durationMs,
            pumpStart = begin,
            phoneNow = phoneNow,
            endedAtMs = endedAtMs,
            pausedAtMs = pausedAtMs,
            resumedAtMs = resumedAtMs
        )
        store(ledger().withActiveTbr(active))
        lastStatusAtMs = phoneNow
        // Ours, now known under the pump's start: the row stays where it began, the ledger holds the identity.
        if (active != null && active.ours && active.pumpStartUtcSeconds != null && before?.pumpId == active.pumpId && before.pumpStartUtcSeconds == null) {
            aapsLogger.debug(LTag.PUMP, "ATC3: our temporary basal id ${active.pumpId} is known under the pump's start ${active.pumpStartUtcSeconds}")
            trace.event(Atc3TraceCat.TBR, "retime", "at" to active.startedAtMs, "pumpStart" to active.pumpStartUtcSeconds, "id" to active.pumpId)
        }
        actions.forEach { apply(it) }
    }

    /**
     * The temporary basal AAPS asked for.
     *
     * @param pumpStart the pump's own record of it, checked to be this command: its stamp is the identity
     *   a renewed temporary basal is told from the running one by; null when it could not be had
     */
    suspend fun tbrStartedByAaps(
        ackAtMs: Long,
        rate: Double,
        durationMinutes: Int,
        pumpStart: Atc3TbrStatus? = null,
        type: PumpSync.TemporaryBasalType = PumpSync.TemporaryBasalType.NORMAL
    ) {
        val begin = pumpStart?.let {
            PumpTbr(
                atMs = it.startTimestamp, utcSeconds = it.startUtcSeconds, durationMs = it.durationMinutes * 60_000L,
                rawRate = it.rate?.let { r -> rawOf(r) }
            )
        }
        val before = ledger().activeTbr
        val (action, entry) = Atc3TbrTracker.startedByAaps(
            ackAtMs = ackAtMs,
            rate = rate,
            durationMs = durationMinutes * 60_000L,
            pumpStart = begin,
            previous = before,
            type = type
        )
        // The row begins at the acknowledgement, when the temporary basal began; the one before ends there.
        store(ledger().withActiveTbr(entry))
        // Two of one minute begin at one start and AAPS would leave both open: the one before is closed here.
        if (before != null && entry.startedAtMs >= before.startedAtMs) {
            apply(Atc3TbrAction.Stop(entry.startedAtMs, Atc3PumpId.tbrEndOf(before.pumpId)))
        }
        apply(action)
    }

    /** Close AAPS's copy of the running temporary basal at [atMs]. */
    suspend fun tbrStopped(atMs: Long) {
        val active = ledger().activeTbr
        store(ledger().withActiveTbr(null))
        val endPumpId = active?.let { Atc3PumpId.tbrEndOf(it.pumpId) }
            ?: Atc3PumpId.of(atMs, Atc3PumpId.KIND_TBR_END)
        apply(Atc3TbrAction.Stop(atMs, endPumpId))
    }

    /**
     * The stop a pump is held in for want of an answer, see [app.aaps.pump.atc3.link.Atc3LinkWatch]:
     * no basal from [startMs] on, written again as the silence lasts and once more, to its length,
     * when the pump answers; one id throughout. Not in the ledger: it is AAPS not knowing. What the
     * pump delivered meanwhile shows in the window closed at the answer, and is not written here.
     */
    suspend fun recordLinkStop(startMs: Long, durationMs: Long) {
        if (durationMs <= 0L || !registration.ensureRegistered()) return
        val pumpId = Atc3PumpId.of(startMs, Atc3PumpId.KIND_LINK_STOP)
        trace.event(Atc3TraceCat.TBR, "link_stop", "at" to startMs, "s" to durationMs / 1000, "id" to pumpId)
        pumpSync.syncTemporaryBasalWithPumpId(
            timestamp = startMs, rate = 0.0, duration = durationMs, isAbsolute = true,
            type = PumpSync.TemporaryBasalType.PUMP_SUSPEND, pumpId = pumpId, pumpType = PumpType.ATC3, pumpSerial = serial
        )
    }

    /** The serial of the pump the stored ledger belongs to. */
    fun ledgerSerial(): String? = store.state.ledger.serial.takeIf { it.isNotBlank() }

    /**
     * Forget everything kept of the pump: another pump, the pump disconnected, or paired afresh. The
     * freshness marks go too, so the next command reads everything.
     *
     * @param newSerial the pump that follows, empty when none
     */
    @Synchronized
    fun forgetPump(newSerial: String) {
        lastStatusAtMs = 0L
        lastAnswerNewestUtcSeconds = 0L
        store.update { Atc3StoredState(ledger = Atc3HistoryLedger(serial = newSerial)) }
        aapsLogger.debug(LTag.PUMP, "ATC3: everything kept of the pump is forgotten")
    }

    private suspend fun apply(action: Atc3TbrAction) {
        when (action) {
            is Atc3TbrAction.Start  -> {
                aapsLogger.debug(LTag.PUMP, "ATC3: temporary basal ${action.rate} U/h recorded, id ${action.pumpId}")
                trace.event(
                    Atc3TraceCat.TBR, "start",
                    "rate" to action.rate,
                    "min" to action.durationMs / 60_000L,
                    "type" to action.type.name,
                    "at" to action.timestamp,
                    "id" to action.pumpId
                )
                syncTbr(action.timestamp, action.rate, action.durationMs, action.type, action.pumpId)
            }

            is Atc3TbrAction.Extend -> {
                trace.event(
                    Atc3TraceCat.TBR, "extend",
                    "rate" to action.rate,
                    "min" to action.durationMs / 60_000L,
                    "type" to action.type.name,
                    "id" to action.pumpId
                )
                syncTbr(action.timestamp, action.rate, action.durationMs, action.type, action.pumpId, update = true)
            }

            is Atc3TbrAction.Stop   -> {
                aapsLogger.debug(LTag.PUMP, "ATC3: temporary basal ended, id ${action.endPumpId}")
                trace.event(Atc3TraceCat.TBR, "stop", "at" to action.timestamp, "id" to action.endPumpId)
                val closed = pumpSync.syncStopTemporaryBasalWithPumpId(
                    timestamp = action.timestamp,
                    endPumpId = action.endPumpId,
                    pumpType = PumpType.ATC3,
                    pumpSerial = serial
                )
                // False is also a refusal, which would leave AAPS believing a temporary basal still runs.
                if (!closed) {
                    aapsLogger.debug(
                        LTag.PUMP,
                        "ATC3: AAPS closed no temporary basal for id ${action.endPumpId}, it was already closed"
                    )
                }
            }

            Atc3TbrAction.None      -> Unit
        }
    }

    internal companion object {

        /** How far the start in the pump's record may sit from the one closed and still be it: two clocks, corrected by a moving difference. */
        const val TBR_END_MATCH_MS = 120_000L

        /** How far the pump's start of one temporary basal may read apart in two of its objects, seconds. */
        const val TBR_SAME_START_SECONDS = 2L

        /** How long a learned bolus is kept: longer than any window may wait to be closed. */
        const val LEARNED_KEPT_MS = 3 * 60 * 60_000L

        /** How long a fault of the journal is remembered: longer than the ring takes to drop it, even on a pump used little. */
        const val FAULTS_KEPT_MS = 60 * 24 * 60 * 60_000L

        /** A rate in the pump's raw steps, as every amount here is compared. */
        fun rawOf(rate: Double): Int = Math.round(rate / Atc3Protocol.DOSE_SCALE).toInt()
    }

    private suspend fun syncTbr(
        timestamp: Long,
        rate: Double,
        durationMs: Long,
        type: PumpSync.TemporaryBasalType,
        pumpId: Long,
        /** The record exists and is being changed: AAPS answers false for that, and it is no refusal. */
        update: Boolean = false
    ) {
        // A zero duration throws in AAPS, and would cost the whole tick.
        if (durationMs <= 0L) {
            aapsLogger.error(
                LTag.PUMP,
                "ATC3: not writing a temporary basal of no length, $rate U/h at $timestamp, id $pumpId"
            )
            return
        }
        val written = pumpSync.syncTemporaryBasalWithPumpId(
            timestamp = timestamp,
            rate = rate,
            duration = durationMs,
            isAbsolute = true,
            type = type,
            pumpId = pumpId,
            pumpType = PumpType.ATC3,
            pumpSerial = serial
        )
        // A refused update leaves AAPS believing in a rate the pump does not deliver.
        if (!written && update) {
            aapsLogger.debug(LTag.PUMP, "ATC3: temporary basal id $pumpId updated to $rate U/h at $timestamp")
        } else if (!written) {
            aapsLogger.error(
                LTag.PUMP,
                "ATC3: AAPS refused the temporary basal $rate U/h at $timestamp, id $pumpId"
            )
        }
    }

    /** @return true when AAPS created a new row, false when it already had one or refused it */
    private suspend fun syncPumpBolus(timestamp: Long, units: Double, pumpId: Long, type: BS.Type): Boolean =
        pumpSync.syncBolusWithPumpId(
            timestamp = timestamp,
            amount = units,
            type = type,
            pumpId = pumpId,
            pumpType = PumpType.ATC3,
            pumpSerial = serial
        )

    /** Hand the clock watch how many minutes our boluses' records sat from their starts, see [Atc3ClockWatch]. */
    private fun noteClockShift(shiftMinutes: Int) {
        clockWatch.matched(shiftMinutes, dateUtil.now())
        aapsLogger.debug(
            LTag.PUMP,
            "ATC3: our boluses matched the pump's history ${if (shiftMinutes == 0) "exactly" else "$shiftMinutes min off"}, " +
                "clock set ${if (clockWatch.needsSetting(dateUtil.now())) "wanted" else "not wanted"}"
        )
        trace.event(
            Atc3TraceCat.HIST, "clock_matched",
            "shiftMin" to shiftMinutes
        )
    }

    /**
     * An extended or dual bolus the pump gave: no row, as the loop cannot count it. Said once, as a note
     * in the journal and aloud, and the user enters the insulin themselves. The journal keeps it from
     * being said again: a note at that minute is not inserted twice.
     */
    private fun announceExtended(timestamp: Long, units: Double) {
        val text = rh.gs(R.string.atc3_extended_not_counted, units, dateUtil.timeString(timestamp))
        if (!pumpSync.insertTherapyEventIfNewWithTimestamp(timestamp, TE.Type.NOTE, text, timestamp, PumpType.ATC3, serial)) return
        aapsLogger.warn(LTag.PUMP, "ATC3: extended or dual bolus of $units U at $timestamp, which the loop does not count")
        trace.event(Atc3TraceCat.HIST, "extended_bolus", "units" to units, "at" to timestamp)
        uiInteraction.addNotification(Notification.PUMP_ERROR, text, Notification.URGENT)
    }
}
