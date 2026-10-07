package app.aaps.pump.atc3.history

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
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
import app.aaps.pump.atc3.basal.Atc3BasalPeriod
import app.aaps.pump.atc3.basal.Atc3JournalShaping
import app.aaps.pump.atc3.basal.Atc3TbrAction
import app.aaps.pump.atc3.basal.Atc3TbrBook
import app.aaps.pump.atc3.basal.Atc3TbrTracker
import app.aaps.pump.atc3.basal.PumpTbr
import app.aaps.pump.atc3.clock.Atc3ClockWatch
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.pump.atc3.keys.Atc3StringNonKey
import app.aaps.pump.atc3.link.Atc3LinkWatch
import app.aaps.pump.atc3.protocol.Atc3BolusHistory
import app.aaps.pump.atc3.protocol.Atc3BolusRecord
import app.aaps.pump.atc3.protocol.Atc3FinishedTbr
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3StatusV1
import app.aaps.pump.atc3.protocol.Atc3TbrRecord
import app.aaps.pump.atc3.protocol.Atc3TbrStatus
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one writer of boluses and temporary basals into AAPS, in front of one ledger: a record of the
 * pump has no identity, so only one gatekeeper can stop the bolus AAPS started and the same bolus
 * read back from the pump becoming two treatments.
 *
 * A bolus is written the moment the pump accepts it, under a temporary id, so it counts while it is
 * delivered and stays counted if no record is ever read; the pump's record then makes it the real
 * row at what was delivered. Whatever the driver did not start is imported.
 */
@Singleton
class Atc3HistorySync @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val uiInteraction: UiInteraction,
    private val preferences: Preferences,
    private val pumpSync: PumpSync,
    private val dateUtil: DateUtil,
    private val pumpState: Atc3PumpState,
    private val clockWatch: Atc3ClockWatch,
    private val trace: Atc3Trace,
    private val registration: Atc3PumpRegistration
) {

    private var loadedFor: String? = null

    private var ledger: Atc3HistoryLedger = Atc3HistoryLedger()

    /** A bolus whose delivered amount the driver learned: when, how much, and when it began at the latest. */
    private data class LearnedBolus(val learnedAtMs: Long, val units: Double, val startMs: Long)

    /**
     * The boluses whose amount the driver learned, which the comparison counts as an interval's boluses:
     * no window over the rows' minutes can say which boluses the pump's count holds, but a bolus is
     * learned only after its delivery, once. Kept on disk with the anchor, see [Atc3StringNonKey.CheckLearned].
     */
    private val learned = mutableListOf<LearnedBolus>()

    private var learnedLoaded = false

    /** Read [learned] back from disk, once, the first time it is wanted. */
    private fun loadLearned() {
        if (learnedLoaded) return
        learnedLoaded = true
        val stored: String? = preferences.get(Atc3StringNonKey.CheckLearned)
        stored.orEmpty().split('|').filter { it.isNotBlank() }.forEach { line ->
            val p = line.split(';')
            val at = p.getOrNull(0)?.toLongOrNull()
            val units = p.getOrNull(1)?.toDoubleOrNull()
            val start = p.getOrNull(2)?.toLongOrNull()
            if (at != null && units != null && start != null) learned += LearnedBolus(at, units, start)
        }
    }

    private fun storeLearned() {
        preferences.put(Atc3StringNonKey.CheckLearned, learned.joinToString("|") { "${it.learnedAtMs};${it.units};${it.startMs}" })
    }

    /** When the bolus history was last taken in, 0 until once; not kept across a restart, after which it is read once. */
    private var lastReconciledAtMs: Long = 0L

    /**
     * When the delivery state was last taken in, 0 until once: apart from [lastReconciledAtMs], which
     * every command moves, so that a busy loop does not hide a temporary basal set on the keypad.
     */
    private var lastStatusAtMs: Long = 0L

    private val serial: String get() = pumpState.serialNumber

    @Synchronized
    private fun ledger(): Atc3HistoryLedger {
        if (loadedFor != serial) {
            // Another pump: everything measured against the last one goes, the freshness marks first, so the
            // next command reads everything.
            if (loadedFor != null) {
                lastReconciledAtMs = 0L
                lastStatusAtMs = 0L
                learned.clear()
                storeLearned()
                // Nor the half hour under way: its count is the other pump's.
                preferences.put(Atc3StringNonKey.BasalPeriod, "")
                aapsLogger.debug(LTag.PUMP, "ATC3: the pump changed, nothing known of it is carried over")
            }
            ledger = Atc3HistoryLedger.decode(preferences.get(Atc3StringNonKey.HistoryLedger), serial) { line ->
                aapsLogger.error(LTag.PUMP, "ATC3: a ledger line could not be read and was dropped: $line")
            }
            loadedFor = serial
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: ledger loaded, ${ledger.seen.size} records known, ${ledger.pending.size} pending, " +
                    "first pass ${if (ledger.firstPassDone) "done" else "outstanding"}"
            )
        }
        return ledger
    }

    @Synchronized
    private fun store(updated: Atc3HistoryLedger) {
        ledger = updated
        loadedFor = updated.serial
        preferences.put(Atc3StringNonKey.HistoryLedger, updated.encode())
    }

    /**
     * Start counting a bolus the pump has just accepted. The ledger's note is written before the row:
     * a process killed between them comes back with the note, from which the record makes the row,
     * rather than with a row the record would be imported beside.
     *
     * @return the temporary id of the row, or 0 when none could be created
     */
    suspend fun registerPending(ackAtMs: Long, requestedUnits: Double, type: BS.Type): Long {
        var temporaryId = ackAtMs
        store(ledger().withPending(pendingUnder(temporaryId, ackAtMs, requestedUnits, type)))
        var created = addBolusRow(temporaryId, requestedUnits, type)
        if (!created) {
            // One millisecond along is another row; the matching does not care about a millisecond.
            aapsLogger.error(LTag.PUMP, "ATC3: temporary id $temporaryId already exists, retrying")
            store(ledger().withoutPending(temporaryId))
            temporaryId = ackAtMs + 1
            store(ledger().withPending(pendingUnder(temporaryId, ackAtMs, requestedUnits, type)))
            created = addBolusRow(temporaryId, requestedUnits, type)
        }
        if (!created) {
            aapsLogger.error(LTag.PUMP, "ATC3: could not create a bolus row, the bolus will be imported from history")
            store(ledger().withoutPending(temporaryId))
            return 0L
        }
        return temporaryId
    }

    private fun pendingUnder(temporaryId: Long, ackAtMs: Long, requestedUnits: Double, type: BS.Type) =
        PendingBolus(temporaryId = temporaryId, startedAtMs = ackAtMs, requestedUnits = requestedUnits, bolusType = type)

    private suspend fun addBolusRow(temporaryId: Long, units: Double, type: BS.Type): Boolean =
        pumpSync.addBolusWithTempId(
            timestamp = temporaryId,
            amount = units,
            temporaryId = temporaryId,
            type = type,
            pumpType = PumpType.ATC3,
            pumpSerial = serial
        )

    /** What the pump reported so far, for the screen only: what a bolus delivered comes from its record. */
    suspend fun onProgress(temporaryId: Long, deliveredUnits: Double) {
        if (temporaryId == 0L) return
        ledger = ledger().withPendingUpdated(temporaryId, deliveredUnits)
    }

    /**
     * Close a bolus the pump closed itself with its completion frame, at that frame's amount, under the
     * id its record will have, and keep it as settled so the record is known for it when it is read.
     *
     * @return false when there is no row of this bolus, and the history has to decide
     */
    suspend fun settleCompleted(temporaryId: Long, deliveredUnits: Double): Boolean {
        if (temporaryId == 0L) return false
        val pending = ledger().pending.firstOrNull { it.temporaryId == temporaryId } ?: return false
        val pumpId = ledger().assignOwnPumpId(pending.startUtcSeconds)
        val updated = pumpSync.syncBolusWithTempId(
            timestamp = pending.startedAtMs,
            amount = deliveredUnits,
            temporaryId = temporaryId,
            type = pending.bolusType,
            pumpId = pumpId,
            pumpType = PumpType.ATC3,
            pumpSerial = serial
        )
        if (!updated) {
            // The user removed the row: the bolus still has to reach AAPS, under the same id.
            aapsLogger.error(LTag.PUMP, "ATC3: no row for temporary id $temporaryId")
            if (!syncPumpBolus(pending.startedAtMs, deliveredUnits, pumpId, pending.bolusType)) {
                aapsLogger.error(
                    LTag.PUMP,
                    "ATC3: and AAPS created no row for it either, $deliveredUnits U at " +
                        "${pending.startedAtMs} is not recorded anywhere"
                )
            }
        }
        store(
            ledger().withoutPending(temporaryId).withSettled(
                SettledBolus(
                    startedAtMs = pending.startedAtMs,
                    requestedUnits = pending.requestedUnits,
                    units = deliveredUnits,
                    settledAtMs = dateUtil.now(),
                    pumpId = pumpId,
                    startUtcSeconds = pending.startUtcSeconds
                )
            )
        )
        learn(deliveredUnits, pending.startedAtMs)
        pumpState.lastBolus = Atc3PumpState.LastBolus(pending.startedAtMs, deliveredUnits)
        aapsLogger.debug(LTag.PUMP, "ATC3: the pump completed our bolus at $deliveredUnits U, id $pumpId")
        trace.event(Atc3TraceCat.HIST, "bolus_completed", "units" to deliveredUnits, "id" to pumpId)
        return true
    }

    /** Note a bolus whose delivered amount is known from now on. Zero is nothing to note. */
    @Synchronized
    private fun learn(units: Double, startMs: Long) {
        if (units == 0.0) return
        loadLearned()
        learned += LearnedBolus(dateUtil.now(), units, startMs)
        storeLearned()
    }

    /** True when a bolus learned after [sinceMs] began by [anchorReadMs]: part of it may be in the anchor's count. */
    @Synchronized
    fun bolusStraddles(sinceMs: Long, anchorReadMs: Long): Boolean {
        loadLearned()
        return learned.any { it.learnedAtMs > sinceMs && it.startMs <= anchorReadMs }
    }

    /** Where a record's bolus began at the latest: the start of the minute it stands in. */
    private fun minuteStartOf(timestamp: Long): Long = timestamp - Math.floorMod(timestamp, 60_000L)

    /** The boluses learned after [sinceMs], summed: the comparison's boluses since its anchor. */
    @Synchronized
    fun bolusesLearnedAfter(sinceMs: Long): Double {
        loadLearned()
        return learned.filter { it.learnedAtMs > sinceMs }.sumOf { it.units }
    }

    /** The comparison anchored at [uptoMs]: what was learned by then goes once no half hour still to close may count it, see [Atc3BasalPeriod]. */
    @Synchronized
    fun forgetBolusesLearnedUpTo(uptoMs: Long) {
        loadLearned()
        val keepFrom = dateUtil.now() - LEARNED_KEPT_MS
        learned.removeAll { it.learnedAtMs <= uptoMs && it.learnedAtMs < keepFrom }
        storeLearned()
    }

    /** True while a bolus of ours still waits for the pump's record of it. */
    @Synchronized
    fun hasPendingBolus(): Boolean = ledger().pending.isNotEmpty()

    /** What our boluses still without a record were asked for, summed: as much of the count as they can explain while they run. */
    @Synchronized
    fun pendingBolusUnits(): Double = ledger().pending.sumOf { it.requestedUnits }

    /** Our boluses answered as delivered whole while the link was gone; in memory, it decides who is told when the record says less. */
    private val answeredWhole = HashSet<Long>()

    /** The bolus under [temporaryId] was answered as delivered whole, on the pump's word still to come. */
    @Synchronized
    fun answeredWhole(temporaryId: Long) {
        if (temporaryId != 0L) answeredWhole.add(temporaryId)
    }

    /** Where the half hour under way began in the exact basal mode, 0 when off: nothing may be written before it. */
    internal fun closedBefore(): Long {
        if (!preferences.get(Atc3BooleanKey.ExactBasal)) return 0L
        val stored: String? = preferences.get(Atc3StringNonKey.BasalPeriod)
        return Atc3BasalPeriod.State.decode(stored.orEmpty()).start?.readMs ?: 0L
    }

    /** True when the boluses were read recently enough to act on without reading again. */
    @Synchronized
    fun historyFresh(now: Long): Boolean =
        lastReconciledAtMs != 0L && now - lastReconciledAtMs < Atc3Const.HISTORY_FRESH_MS

    /** What one reconciliation turned out to change. */
    data class ReconcileResult(
        /** The amount of the bolus AAPS was waiting to confirm, when a record was it. */
        val confirmedUnits: Double? = null,
        /** When the newest bolus nobody had told AAPS of was given: what refuses an SMB decided without it. */
        val newestImportedAtMs: Long? = null,
        /** Insulin imported from the records this pass, U. */
        val importedUnits: Double = 0.0
    )

    /** How long ago the delivery state was last taken in; two callers ask with different thresholds. */
    @Synchronized
    fun stateAgeMs(now: Long): Long =
        if (lastStatusAtMs == 0L) Long.MAX_VALUE else now - lastStatusAtMs

    /** Bring AAPS in line with the bolus records the pump returned. */
    suspend fun reconcileBoluses(records: List<Atc3BolusRecord>, recordCount: Int): ReconcileResult {
        // Not registered: the ledger is left alone, or the records would never be offered again.
        if (!registration.ensureRegistered()) {
            trace.event(Atc3TraceCat.HIST, "reconcile", "ok" to false, "why" to "not_registered")
            return ReconcileResult()
        }
        val outcome = Atc3BolusReconciler.reconcile(
            records = records,
            recordCount = recordCount,
            ledger = ledger(),
            phoneNow = dateUtil.now(),
            earliestAcceptedMs = preferences.get(LongNonKey.ActivePumpChangeTimestamp)
        )
        // What each settled bolus stood at: a record that says otherwise changes what was learned.
        val settledBefore = ledger().settled.associate { it.pumpId to it.units }
        store(outcome.ledger)
        outcome.clockShiftMinutes?.let { noteClockShift(it) }

        lastReconciledAtMs = dateUtil.now()
        var confirmed: Double? = null
        var newestImported: Long? = null
        var newest: Pair<Long, Double>? = null
        for (action in outcome.actions) {
            when (action) {
                is Atc3BolusAction.ResolvePending -> {
                    aapsLogger.debug(
                        LTag.PUMP,
                        "ATC3: the pump recorded our bolus as ${action.units} U, id ${action.pumpId}"
                    )
                    val updated = pumpSync.syncBolusWithTempId(
                        timestamp = action.timestamp,
                        amount = action.units,
                        temporaryId = action.pending.temporaryId,
                        type = action.pending.bolusType,
                        pumpId = action.pumpId,
                        pumpType = PumpType.ATC3,
                        pumpSerial = serial
                    )
                    if (!updated) {
                        // The user removed the row: the record still has to reach AAPS, under the same id.
                        aapsLogger.error(LTag.PUMP, "ATC3: no row for temporary id ${action.pending.temporaryId}")
                        if (!syncPumpBolus(action.timestamp, action.units, action.pumpId, action.pending.bolusType)) {
                            aapsLogger.error(
                                LTag.PUMP,
                                "ATC3: and AAPS created no row for it either, ${action.units} U at " +
                                    "${action.timestamp} is not recorded anywhere"
                            )
                        }
                    }
                    announceExtended(action.carriesExtendedPart, action.units, action.pumpId)
                    learn(action.units, action.pending.startedAtMs)
                    // Answered as whole when the link went, and the record says less: said now.
                    val wasAnsweredWhole = synchronized(this) { answeredWhole.remove(action.pending.temporaryId) }
                    if (wasAnsweredWhole && action.pending.requestedUnits - action.units >= Atc3Protocol.DOSE_SCALE - 1e-9) {
                        aapsLogger.error(
                            LTag.PUMP,
                            "ATC3: the pump recorded ${action.units} U of the ${action.pending.requestedUnits} U asked for, " +
                                "of a bolus answered as delivered while the link was down"
                        )
                        uiInteraction.addNotification(
                            Notification.PUMP_ERROR,
                            rh.gs(R.string.atc3_bolus_short, action.units, action.pending.requestedUnits),
                            Notification.URGENT
                        )
                    }
                    confirmed = action.units
                    newest = keepNewer(newest, action.timestamp to action.units)
                }

                is Atc3BolusAction.Import         -> {
                    aapsLogger.debug(
                        LTag.PUMP,
                        "ATC3: importing a bolus of ${action.units} U from the pump's history, id ${action.pumpId}"
                    )
                    // No new row: AAPS had it, the usual case on a re-read, or refused it, which is logged.
                    if (!syncPumpBolus(action.timestamp, action.units, action.pumpId)) {
                        aapsLogger.debug(
                            LTag.PUMP,
                            "ATC3: AAPS created no new row for bolus ${action.pumpId}, it already knew it"
                        )
                    }
                    announceExtended(action.carriesExtendedPart, action.units, action.pumpId)
                    learn(action.units, minuteStartOf(action.timestamp))
                    newest = keepNewer(newest, action.timestamp to action.units)
                    if (newestImported == null || action.timestamp > newestImported) {
                        newestImported = action.timestamp
                    }
                }

                is Atc3BolusAction.Rewrite        -> {
                    aapsLogger.debug(
                        LTag.PUMP,
                        "ATC3: bolus id ${action.pumpId} brought to the pump's record, ${action.units} U at ${action.timestamp}"
                    )
                    // No type, so an SMB stays one; false is what a correction gets.
                    syncPumpBolus(action.timestamp, action.units, action.pumpId, type = null)
                    // A row moved is nothing to the comparison; a changed amount is.
                    learn(action.units - (settledBefore[action.pumpId] ?: 0.0), minuteStartOf(action.timestamp))
                }

                is Atc3BolusAction.CountAttempt   -> {
                    aapsLogger.debug(
                        LTag.PUMP,
                        "ATC3: the pump still has no record of the bolus started at " +
                            "${action.pending.startedAtMs}, asked ${action.pending.confirmAttempts + 1} times"
                    )
                }

                is Atc3BolusAction.DropPending    -> {
                    aapsLogger.warn(
                        LTag.PUMP,
                        "ATC3: the history holds a later bolus but none for the one started at " +
                            "${action.pending.startedAtMs}, asked ${action.pending.confirmAttempts + 1} times; " +
                            "its ${action.pending.requestedUnits} U are taken out of AAPS"
                    )
                    // Zeroed, as PumpSync cannot remove a bolus row: it stops counting and stays visible.
                    val updated = pumpSync.syncBolusWithTempId(
                        timestamp = action.pending.startedAtMs,
                        amount = 0.0,
                        temporaryId = action.pending.temporaryId,
                        type = action.pending.bolusType,
                        pumpId = action.pumpId,
                        pumpType = PumpType.ATC3,
                        pumpSerial = serial
                    )
                    if (!updated) {
                        aapsLogger.error(
                            LTag.PUMP,
                            "ATC3: could not zero the unconfirmed bolus, no row for temporary id " +
                                "${action.pending.temporaryId}; it stays in AAPS at what it was started with"
                        )
                    }
                    // Nobody is told; nothing may now run on knowledge from before: the next command reads afresh.
                    lastReconciledAtMs = 0L
                    lastStatusAtMs = 0L
                }

                is Atc3BolusAction.Consume        ->
                    aapsLogger.debug(LTag.PUMP, "ATC3: record ${action.pumpId} not imported, ${action.reason}")
            }
        }
        newest?.let { pumpState.lastBolus = Atc3PumpState.LastBolus(it.first, it.second) }
        // The counts say whether the read was worth making.
        trace.event(
            Atc3TraceCat.HIST, "reconcile",
            "ok" to true,
            "sent" to records.size,
            "held" to recordCount,
            "imported" to outcome.actions.count { it is Atc3BolusAction.Import },
            "resolved" to outcome.actions.count { it is Atc3BolusAction.ResolvePending },
            "rewritten" to outcome.actions.count { it is Atc3BolusAction.Rewrite },
            "dropped" to outcome.actions.count { it is Atc3BolusAction.DropPending },
            "known" to outcome.actions.count { it is Atc3BolusAction.Consume }
        )
        return ReconcileResult(
            confirmedUnits = confirmed,
            newestImportedAtMs = newestImported,
            importedUnits = outcome.actions.filterIsInstance<Atc3BolusAction.Import>().sumOf { it.units }
        )
    }

    /** True when the pump holds records this answer left out, see [Atc3BolusReconciler.recordsMissing]. */
    @Synchronized
    fun recordsMissing(history: Atc3BolusHistory): Boolean =
        Atc3BolusReconciler.recordsMissing(history, ledger())

    /** True when one of these records is the bolus AAPS is waiting to confirm. */
    @Synchronized
    fun wouldResolve(records: List<Atc3BolusRecord>, temporaryId: Long): Boolean {
        val pending = ledger().pending.firstOrNull { it.temporaryId == temporaryId } ?: return false
        return Atc3BolusReconciler.wouldResolve(records, pending, ledger())
    }

    /** True when [records] hold what can be the record of our bolus started at [startedAtMs]. */
    fun holdsRecordOf(records: List<Atc3BolusRecord>, startedAtMs: Long, requestedUnits: Double): Boolean =
        Atc3BolusReconciler.holdsRecordOf(records, Atc3StatusV1.wallClockUtcSeconds(startedAtMs), requestedUnits)

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
        store(current.withWatermark(atUtcSeconds, atMs).withTbrWatermark(atUtcSeconds))
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
     * @param journal reads the temporary basal journal, asked only when one is closed here, so its row
     *   closes at what its record delivered
     */
    suspend fun onStatus(
        suspended: Boolean,
        tbrRunning: Boolean,
        rate: Double,
        durationMs: Long?,
        pumpTbrStart: Atc3TbrStatus?,
        endedAtMs: Long? = null,
        pausedAtMs: Long? = null,
        resumedAtMs: Long? = null,
        journal: (() -> List<Atc3TbrRecord>?)? = null
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
        val (stepped, stepActive) = Atc3TbrTracker.step(
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
        var active = stepActive
        val once = journal?.let { read -> lazy { read() } }
        val cached: (() -> List<Atc3TbrRecord>?)? = once?.let { { it.value } }
        // The row closed ends where the pump's record says, when it can be had.
        val actions = ArrayList<Atc3TbrAction>(stepped.size)
        var movedFrom: Long? = null
        var movedTo: Long? = null
        for (action in stepped) {
            if (action is Atc3TbrAction.Stop && before != null && action.endPumpId == Atc3PumpId.tbrEndOf(before.pumpId)) {
                var end = action.timestamp
                if (action.byStamp) {
                    val byRecord = shaping.endByRecord(before, end, phoneNow, cached)
                    if (byRecord != end) {
                        movedFrom = end
                        movedTo = byRecord
                        end = byRecord
                    }
                }
                actions.add(action.copy(timestamp = shaping.shapedClose(before, end, cached)))
            } else if (action is Atc3TbrAction.Start && movedFrom != null && action.timestamp == movedFrom) {
                // The one that replaced it begins where it ended.
                actions.add(action.copy(timestamp = movedTo!!))
            } else {
                actions.add(action)
            }
        }
        if (movedFrom != null && active != null && active.startedAtMs == movedFrom) active = active.copy(startedAtMs = movedTo!!)
        var updated = ledger().withActiveTbr(active)
        val closedAt = actions.filterIsInstance<Atc3TbrAction.Stop>().firstOrNull()?.timestamp
        if (before != null && closedAt != null) updated = updated.withOurTbrEnd(before.pumpId, closedAt)
        // Another's temporary basal is written from the pump's start and noted like ours, so the journal
        // finds it in AAPS rather than importing it beside itself.
        if (active != null && active !== before && !active.ours && !active.suspension) {
            active.pumpStartUtcSeconds?.let { utcSeconds ->
                val keepFromUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(phoneNow - Atc3Const.RECONCILE_MAX_AGE_MS)
                // Noted for the length the pump started it for, what its record will say.
                val startedForMinutes = begin?.durationMs?.let { (it / 60_000L).toInt() } ?: (active.ownDurationMs / 60_000L).toInt()
                updated = updated.withOurTbr(
                    utcSeconds, active.rate, startedForMinutes, keepFromUtcSeconds,
                    active.pumpId, pumpStart = true, rowMs = active.startedAtMs
                )
            }
        }
        store(updated)
        lastStatusAtMs = phoneNow
        actions.forEach { apply(it) }
    }

    /**
     * The temporary basal AAPS asked for.
     *
     * @param pumpStart the pump's own record of it, checked to be this command: it gives the note the
     *   identity the journal record will carry; null when it could not be had
     */
    suspend fun tbrStartedByAaps(
        ackAtMs: Long,
        rate: Double,
        durationMinutes: Int,
        pumpStart: Atc3TbrStatus? = null,
        type: PumpSync.TemporaryBasalType = PumpSync.TemporaryBasalType.NORMAL,
        journal: (() -> List<Atc3TbrRecord>?)? = null
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
        // Noted as ours first, so the journal cannot read it back as another's. Rate, duration and start
        // all have to agree, as the pump took them, on the scale of the journal's keys. The row itself
        // begins at the acknowledgement, when the temporary basal began.
        val startedUtcSeconds = pumpStart?.startUtcSeconds ?: Atc3StatusV1.wallClockUtcSeconds(ackAtMs)
        // The cut-off is on the same scale as the notes.
        val keepFromUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(dateUtil.now() - Atc3Const.RECONCILE_MAX_AGE_MS)
        // The one before ends where its record says, closed before the ledger is copied below.
        val closeAt = before?.let { shaping.shapedClose(it, entry.startedAtMs, journal) }
        // The one before is cut by AAPS where this one begins.
        var updated = ledger().withActiveTbr(entry)
            .withOurTbr(startedUtcSeconds, rate, durationMinutes, keepFromUtcSeconds, entry.pumpId, pumpStart != null, rowMs = entry.startedAtMs, asOrdered = true)
        if (before != null) updated = updated.withOurTbrEnd(before.pumpId, closeAt!!)
        store(updated)
        // Two of one minute begin at one start and AAPS would leave both open: the one before is closed here.
        if (before != null && entry.startedAtMs >= before.startedAtMs) {
            apply(Atc3TbrAction.Stop(closeAt!!, Atc3PumpId.tbrEndOf(before.pumpId)))
        }
        apply(action)
    }

    /**
     * Close AAPS's copy of the running temporary basal.
     *
     * @param journal reads the temporary basal journal, so the row closes at what its record delivered
     */
    suspend fun tbrStopped(atMs: Long, byStamp: Boolean = false, journal: (() -> List<Atc3TbrRecord>?)? = null) {
        val active = ledger().activeTbr
        val once = journal?.let { read -> lazy { read() } }
        val cached: (() -> List<Atc3TbrRecord>?)? = once?.let { { it.value } }
        val end = if (byStamp && active != null) shaping.endByRecord(active, atMs, dateUtil.now(), cached) else atMs
        val closeAt = active?.let { shaping.shapedClose(it, end, cached) } ?: end
        var updated = ledger().withActiveTbr(null)
        if (active != null) updated = updated.withOurTbrEnd(active.pumpId, closeAt)
        store(updated)
        val endPumpId = active?.let { Atc3PumpId.tbrEndOf(it.pumpId) }
            ?: Atc3PumpId.of(atMs, Atc3PumpId.KIND_TBR_END)
        apply(Atc3TbrAction.Stop(closeAt, endPumpId))
    }

    /**
     * A stop that began and ended between two ticks, from the moment the pump keeps for it, for the
     * length the pump's count shows; written only while the scheduled rate ran.
     */
    suspend fun recordDerivedStop(startMs: Long, durationMs: Long) {
        val pumpId = Atc3PumpId.of(startMs, Atc3PumpId.KIND_TBR_START)
        aapsLogger.warn(
            LTag.PUMP,
            "ATC3: a stop between two ticks, from $startMs for ${durationMs / 1000} s by the pump's count, recorded as id $pumpId"
        )
        trace.event(Atc3TraceCat.TBR, "pause_derived", "at" to startMs, "s" to durationMs / 1000, "id" to pumpId)
        syncTbr(startMs, 0.0, durationMs, PumpSync.TemporaryBasalType.PUMP_SUSPEND, pumpId)
    }

    /**
     * A stop between two ticks inside the open temporary basal: the row cut at the stop by the pump's
     * record of the part before it, a stop of the length the count shows, and the temporary basal going
     * on after it under its own identity, see [Atc3TbrBook].
     *
     * @return false when there is no such temporary basal open, or no such record yet
     */
    suspend fun recordDerivedStopInTbr(stopMs: Long, lengthMs: Long, records: List<Atc3TbrRecord>): Boolean {
        val active = ledger().activeTbr ?: return false
        if (active.suspension || active.rate < Atc3Protocol.DOSE_SCALE / 2) return false
        val own = active.pumpStartUtcSeconds ?: return false
        // No earlier than the pump's own stamp of the temporary basal.
        if (stopMs < active.anchorMs) return false
        val raw = rawOf(active.rate)
        if (records.none { r -> r.startUtcSeconds == own && r.rate?.let { rawOf(it) == raw } == true }) return false
        val (stepped, goingOn) = Atc3TbrTracker.splitByStop(active, stopMs, lengthMs)
        val actions = stepped.map { action ->
            if (action is Atc3TbrAction.Stop && action.endPumpId == Atc3PumpId.tbrEndOf(active.pumpId))
                action.copy(timestamp = shaping.shapedClose(active, action.timestamp, { records }))
            else action
        }
        val closedAt = actions.filterIsInstance<Atc3TbrAction.Stop>().first().timestamp
        var updated = ledger().withActiveTbr(goingOn).withOurTbrEnd(active.pumpId, closedAt)
        if (goingOn != null) {
            val keepFromUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(dateUtil.now() - Atc3Const.RECONCILE_MAX_AGE_MS)
            updated = updated.withOurTbr(
                own, goingOn.rate, (active.ownDurationMs / 60_000L).toInt(), keepFromUtcSeconds,
                goingOn.pumpId, pumpStart = true, rowMs = goingOn.startedAtMs
            )
        }
        store(updated)
        aapsLogger.warn(
            LTag.PUMP,
            "ATC3: a stop between two ticks inside temporary basal id ${active.pumpId}, from $stopMs for ${lengthMs / 1000} s " +
                "by the pump's count; the temporary basal goes on as id ${goingOn?.pumpId}"
        )
        trace.event(
            Atc3TraceCat.TBR, "pause_derived", "at" to stopMs, "s" to lengthMs / 1000, "in" to active.pumpId,
            "goes_on" to (goingOn?.pumpId ?: 0L)
        )
        actions.forEach { apply(it) }
        return true
    }

    /**
     * The stop a pump is held in for want of an answer, see [Atc3LinkWatch]: no basal from [startMs] on,
     * written again as the silence lasts, under one id. Not in the ledger: it is AAPS not knowing.
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
    fun ledgerSerial(): String? = Atc3HistoryLedger.serialOf(preferences.get(Atc3StringNonKey.HistoryLedger))

    /** Forget everything: another pump, or the same pump paired afresh. */
    fun forgetPump(newSerial: String) {
        lastReconciledAtMs = 0L
        lastStatusAtMs = 0L
        loadedFor = newSerial
        preferences.put(Atc3StringNonKey.BasalPeriod, "")
        preferences.put(Atc3StringNonKey.LastAnswer, "")
        preferences.put(Atc3StringNonKey.LinkStop, "")
        store(Atc3HistoryLedger(serial = newSerial))
        aapsLogger.debug(LTag.PUMP, "ATC3: history ledger cleared for a new pump")
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
                // A row ended inside a closed half hour would cut that period's row: the end is kept past it.
                val closedBefore = closedBefore()
                val at = if (closedBefore > 0L) maxOf(action.timestamp, closedBefore + MIN_SHAPED_SPAN_MS) else action.timestamp
                trace.event(Atc3TraceCat.TBR, "stop", "at" to at, "id" to action.endPumpId)
                val closed = pumpSync.syncStopTemporaryBasalWithPumpId(
                    timestamp = at,
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

            is Atc3TbrAction.Retime -> {
                // The row stays at the acknowledgement; the note takes the pump's start.
                aapsLogger.debug(
                    LTag.PUMP,
                    "ATC3: our temporary basal id ${action.pumpId} is known under the pump's start ${action.utcSeconds}"
                )
                trace.event(Atc3TraceCat.TBR, "retime", "at" to action.timestamp, "pumpStart" to action.utcSeconds, "id" to action.pumpId)
                store(ledger().withOurTbrOnPumpStart(action.pumpId, action.utcSeconds, rowMs = action.timestamp))
            }

            Atc3TbrAction.None      -> Unit
        }
    }

    /**
     * Bring the temporary basal rows to the pump's journal and import what ran unseen, see [Atc3JournalShaping].
     *
     * @return how many rows were written or brought to the journal
     */
    suspend fun reconcileTbrHistory(records: List<Atc3TbrRecord>): Int = shaping.reconcile(records)

    /** The shaping of rows by the pump's journal, in its own file; it reaches the ledger through the three below. */
    private val shaping = Atc3JournalShaping(this, aapsLogger, dateUtil, trace)

    internal fun ledgerNow(): Atc3HistoryLedger = ledger()

    internal fun storeLedger(updated: Atc3HistoryLedger) = store(updated)

    internal suspend fun syncTbrRow(timestamp: Long, rate: Double, durationMs: Long, type: PumpSync.TemporaryBasalType, pumpId: Long, update: Boolean = false) =
        syncTbr(timestamp, rate, durationMs, type, pumpId, update)

    internal companion object {

        /** How far the start in the pump's record may sit from the one closed and still be it: two clocks, corrected by a moving difference. */
        const val TBR_END_MATCH_MS = 120_000L

        /** How far the pump's start of one temporary basal may read apart in two of its objects, seconds. */
        const val TBR_SAME_START_SECONDS = 2L

        /** A row shorter than this keeps the rate it was set to rather than an average over nothing. */
        const val MIN_SHAPED_SPAN_MS = 1_000L

        /** How long a learned bolus is kept: longer than any half hour may wait to be closed. */
        const val LEARNED_KEPT_MS = 3 * 60 * 60_000L

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
        // A closed half hour holds one row, the count over it: a row reaching into it begins where it
        // closed, and one inside it is in that count already.
        val closedBefore = closedBefore()
        if (closedBefore > 0L && timestamp < closedBefore) {
            val left = durationMs - (closedBefore - timestamp)
            trace.event(Atc3TraceCat.TBR, "kept_out", "at" to timestamp, "id" to pumpId, "left_s" to left / 1000)
            if (left > 0L) syncTbr(closedBefore, rate, left, type, pumpId, update)
            return
        }
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

    /**
     * @param type null leaves the type of an existing row alone.
     * @return true when AAPS created a new row, false when it already had one or refused it
     */
    private suspend fun syncPumpBolus(timestamp: Long, units: Double, pumpId: Long, type: BS.Type? = BS.Type.NORMAL): Boolean =
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

    /** Tell the user when an extended bolus was recorded as given at once: AAPS gets no extended bolus. */
    private suspend fun announceExtended(carriesExtendedPart: Boolean, units: Double, pumpId: Long) {
        if (!carriesExtendedPart) return
        aapsLogger.warn(LTag.PUMP, "ATC3: extended or dual bolus of $units U recorded as a normal bolus")
        pumpSync.insertAnnouncement(
            error = rh.gs(R.string.atc3_extended_imported, units),
            pumpId = pumpId,
            pumpType = PumpType.ATC3,
            pumpSerial = serial
        )
    }

    private fun keepNewer(current: Pair<Long, Double>?, candidate: Pair<Long, Double>): Pair<Long, Double> =
        if (current == null || candidate.first > current.first) candidate else current
}
