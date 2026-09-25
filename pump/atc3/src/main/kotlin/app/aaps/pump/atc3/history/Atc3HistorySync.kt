package app.aaps.pump.atc3.history

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.Atc3Pump
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.comm.Atc3Alarm
import app.aaps.pump.atc3.comm.Atc3AlarmRecord
import app.aaps.pump.atc3.comm.Atc3BolusHistory
import app.aaps.pump.atc3.comm.Atc3BolusRecord
import app.aaps.pump.atc3.comm.Atc3FinishedTbr
import app.aaps.pump.atc3.comm.Atc3RefillRecord
import app.aaps.pump.atc3.comm.Atc3StatusV1
import app.aaps.pump.atc3.comm.Atc3DailyStats
import app.aaps.pump.atc3.comm.Atc3TbrRecord
import app.aaps.pump.atc3.comm.Atc3TbrStatus
import app.aaps.pump.atc3.keys.Atc3LongNonKey
import app.aaps.pump.atc3.keys.Atc3StringNonKey
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import javax.inject.Inject
import kotlin.math.abs
import javax.inject.Singleton

/**
 * The only place in the driver that writes treatments into AAPS.
 *
 * Both halves of the problem this solves come from the same root: the pump gives a record no
 * identity, so nothing in the database can stop the bolus AAPS started and the same bolus read back
 * from the pump's history becoming two treatments. Keeping every [PumpSync] call behind one class,
 * in front of one ledger, is what makes that impossible.
 *
 * A bolus is written the moment the pump accepts the command, under a temporary id, so insulin is
 * counted while it is being delivered and stays counted if the confirming read never succeeds. When
 * the pump's own record turns up, the temporary row becomes the real one with the amount the pump
 * says it delivered. Anything in the history the driver did not start is imported as an ordinary
 * bolus.
 */
@Singleton
class Atc3HistorySync @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val uiInteraction: UiInteraction,
    private val preferences: Preferences,
    private val pumpSync: PumpSync,
    private val dateUtil: DateUtil,
    private val atc3Pump: Atc3Pump,
    private val clockWatch: Atc3ClockWatch,
    private val trace: Atc3Trace
) {

    private var loadedFor: String? = null
    private var ledger: Atc3HistoryLedger = Atc3HistoryLedger()

    /**
     * Phone clock at the last successful reconciliation, zero until one has happened.
     *
     * Deliberately not persisted: after a restart the driver knows nothing about what the pump did
     * while it was gone, and reading once is both cheap and exactly the right thing to do.
     */
    private var lastReconciledAtMs: Long = 0L

    /**
     * Phone clock at the last time the pump's delivery state was reconciled, zero until once.
     *
     * Deliberately *not* the same as [lastReconciledAtMs]. That one moves whenever the bolus
     * history is read, and the history is read on the way into every command; this one moves only
     * in [onStatus], which is the only place the temporary basal and the suspension are looked at.
     * Sharing one timestamp would let a busy loop suppress the very read that notices a temporary
     * basal started on the keypad — the more the loop did, the blinder the driver would get.
     */
    private var lastStatusAtMs: Long = 0L

    private val serial: String get() = atc3Pump.serialNumber

    @Synchronized
    private fun ledger(): Atc3HistoryLedger {
        if (loadedFor != serial) {
            // A different pump means everything measured against the last one is meaningless, and
            // the freshness marks most of all: they are what lets a command go ahead without
            // reading first, and read of the wrong pump they would wave through a command on
            // knowledge belonging to a machine that is no longer here. Zeroing them makes the next
            // command read everything, which is exactly what a new pump deserves.
            //
            // Done here rather than at a call site because this is where a pump change is actually
            // noticed.
            if (loadedFor != null) {
                lastReconciledAtMs = 0L
                lastStatusAtMs = 0L
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
     * Make sure AAPS has adopted this pump before anything is written for it.
     *
     * AAPS refuses treatments from a pump it has not registered, and it registers the pump on the
     * first call that carries a fresh timestamp. Without such a call before the first import, the
     * first bolus given on the pump keypad would be refused and, being already in the ledger, never
     * offered again. Registering here, once, keeps that from happening; the announcement also tells
     * the user where AAPS's knowledge of this pump begins.
     *
     * @return true when AAPS will now accept treatments for this pump
     */
    private suspend fun ensureRegistered(): Boolean {
        if (pumpSync.verifyPumpIdentification(PumpType.ATC3, serial)) return true
        aapsLogger.debug(LTag.PUMP, "ATC3: registering the pump with AAPS before importing anything")
        pumpSync.insertAnnouncement(
            error = rh.gs(R.string.atc3_history_start),
            pumpId = null,
            pumpType = PumpType.ATC3,
            pumpSerial = serial
        )
        // Registering fails when AAPS is still holding somebody else's pump, and then every write
        // that follows would be refused. Say so once here rather than letting each one fail alone.
        val registered = pumpSync.verifyPumpIdentification(PumpType.ATC3, serial)
        if (!registered) {
            aapsLogger.error(
                LTag.PUMP,
                "ATC3: AAPS has not adopted this pump, so it will refuse everything written for it. " +
                    "Nothing is imported and nothing is marked as counted, so the pump's history is " +
                    "still there once that is resolved."
            )
        }
        return registered
    }

    /**
     * Write the pump's alarms into the AAPS history, newest ones only.
     *
     * The pump announces nothing when an alarm fires, so this is the only way one reaches AAPS at
     * all. It is a record of an event, not a statement about delivery: an occlusion is raised by
     * the pressure a bolus builds and cancels that bolus, and what was actually delivered is
     * already reconciled from the bolus history. See [Atc3Alarm].
     *
     * The record carries no identity — no id, no sequence number, and an index that shifts as
     * records age out — so the timestamp is the only thing that says whether a record has been seen
     * before. The newest one written is kept on disk in [Atc3LongNonKey.LastAlarmSeconds].
     *
     * **The first read imports nothing.** The pump holds alarms going back weeks, and pouring them
     * into AAPS the first time the driver looks would date a month of occlusions to whenever the
     * user installed it. The first pass only sets the watermark, the same choice the bolus history
     * makes for the same reason.
     *
     * @param records the answer to an alarm history read, in the pump's own order
     */
    suspend fun recordAlarms(records: List<Atc3AlarmRecord>) {
        if (records.isEmpty()) return
        val newest = records.maxOf { it.pumpClockUtcSeconds }
        val watermark = preferences.get(Atc3LongNonKey.LastAlarmSeconds)
        if (watermark == 0L) {
            preferences.put(Atc3LongNonKey.LastAlarmSeconds, newest)
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: ${records.size} alarm(s) already on the pump, none imported; " +
                    "AAPS starts recording alarms from here"
            )
            return
        }
        if (newest <= watermark) return
        if (!ensureRegistered()) return

        val fresh = records.filter { it.pumpClockUtcSeconds > watermark }.sortedBy { it.pumpClockUtcSeconds }
        for (record in fresh) {
            val stored = pumpSync.insertTherapyEventIfNewWithTimestamp(
                timestamp = record.timestamp,
                type = therapyEventType(record.alarm),
                note = alarmNote(record),
                pumpId = null,
                pumpType = PumpType.ATC3,
                pumpSerial = serial
            )
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: alarm code ${record.code} at ${dateUtil.dateAndTimeString(record.timestamp)}" +
                    if (stored) " recorded" else " already in the history"
            )
        }
        preferences.put(Atc3LongNonKey.LastAlarmSeconds, newest)
        trace.event(Atc3TraceCat.HIST, "alarms", "new" to fresh.size, "newest" to newest)
    }

    /**
     * Which AAPS therapy event an alarm becomes.
     *
     * Three of the pump's alarms have an exact counterpart in AAPS and keep it, so they show up
     * where a user already looks for them. The rest have none and are recorded as a general note
     * rather than being bent into a type that means something else.
     */
    private fun therapyEventType(alarm: Atc3Alarm?): TE.Type = when (alarm) {
        Atc3Alarm.NO_DELIVERY     -> TE.Type.OCCLUSION
        Atc3Alarm.RESERVOIR_EMPTY -> TE.Type.RESERVOIR_EMPTY
        Atc3Alarm.LOW_BATTERY     -> TE.Type.BATTERY_EMPTY
        Atc3Alarm.DAILY_LIMIT     -> TE.Type.PUMP_STOPPED
        else                      -> TE.Type.NOTE
    }

    /**
     * Put the pump's refills into the AAPS history as insulin changes, the way the alarms go in.
     *
     * A refill record is written when a reservoir is put in and the set is filled; its amount is
     * the fill of the set, which leaves the reservoir and is counted nowhere else. The first read
     * imports nothing and only says where recording starts.
     */
    suspend fun recordRefills(records: List<Atc3RefillRecord>) {
        if (records.isEmpty()) return
        val newest = records.maxOf { it.utcSeconds }
        val watermark = preferences.get(Atc3LongNonKey.LastRefillSeconds)
        if (watermark == 0L) {
            preferences.put(Atc3LongNonKey.LastRefillSeconds, newest)
            aapsLogger.debug(LTag.PUMP, "ATC3: ${records.size} refill(s) already on the pump, none imported; AAPS starts recording refills from here")
            return
        }
        if (newest <= watermark) return
        if (!ensureRegistered()) return
        val fresh = records.filter { it.utcSeconds > watermark }.sortedBy { it.utcSeconds }
        for (record in fresh) {
            val stored = pumpSync.insertTherapyEventIfNewWithTimestamp(
                timestamp = record.timestamp,
                type = TE.Type.INSULIN_CHANGE,
                note = rh.gs(R.string.atc3_refill_note, record.amountUnits),
                pumpId = null,
                pumpType = PumpType.ATC3,
                pumpSerial = serial
            )
            aapsLogger.debug(LTag.PUMP, "ATC3: refill of ${record.amountUnits} U at ${dateUtil.dateAndTimeString(record.timestamp)}" + if (stored) " recorded" else " already in the history")
        }
        preferences.put(Atc3LongNonKey.LastRefillSeconds, newest)
        trace.event(Atc3TraceCat.HIST, "refills", "new" to fresh.size, "newest" to newest)
    }

    private fun alarmNote(record: Atc3AlarmRecord): String =
        "ATC3: " + (record.alarm?.name ?: "alarm code ${record.code}")

    /**
     * Start counting a bolus AAPS has just been told the pump accepted.
     *
     * @return the temporary id the row was created under, or 0 when no row could be created
     */
    suspend fun registerPending(ackAtMs: Long, requestedUnits: Double, type: BS.Type): Long {
        var temporaryId = ackAtMs
        var created = addBolusRow(temporaryId, requestedUnits, type)
        if (!created) {
            // That id is already in the database. One millisecond along is a different row and the
            // matcher does not care about a millisecond.
            aapsLogger.error(LTag.PUMP, "ATC3: temporary id $temporaryId already exists, retrying")
            temporaryId = ackAtMs + 1
            created = addBolusRow(temporaryId, requestedUnits, type)
        }
        if (!created) {
            aapsLogger.error(LTag.PUMP, "ATC3: could not create a bolus row, the bolus will be imported from history")
            return 0L
        }
        store(
            ledger().withPending(
                PendingBolus(
                    temporaryId = temporaryId,
                    startedAtMs = ackAtMs,
                    requestedUnits = requestedUnits,
                    bolusType = type
                )
            )
        )
        return temporaryId
    }

    private suspend fun addBolusRow(temporaryId: Long, units: Double, type: BS.Type): Boolean =
        pumpSync.addBolusWithTempId(
            timestamp = temporaryId,
            amount = units,
            temporaryId = temporaryId,
            type = type,
            pumpType = PumpType.ATC3,
            pumpSerial = serial
        )

    /**
     * Remember how much the pump has reported so far, for the screen while the bolus runs.
     *
     * In memory only, and deliberately: nothing is decided from this number. What a bolus
     * delivered comes from the pump's own record, and when that record cannot be had the row is
     * removed rather than closed at a figure watched off progress frames.
     */
    suspend fun onProgress(temporaryId: Long, deliveredUnits: Double) {
        if (temporaryId == 0L) return
        ledger = ledger().withPendingUpdated(temporaryId, deliveredUnits)
    }

    /**
     * Close a bolus the pump closed itself, with its completion frame `A1/AA`, without reading the
     * history.
     *
     * The row is given the amount that frame carried and stays at the moment the pump accepted the
     * bolus. It is filed under the id its record will have — the minute the bolus started, second
     * 59 — and the bolus is kept as settled, so that the record is recognised as this bolus when the
     * history is next read and never imported beside it.
     *
     * @return false when there is no row of this bolus to close, and the history has to decide
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
            // The row is gone, the user having removed the treatment. The bolus still has to reach
            // AAPS, and under the same id so its record cannot bring it a second time.
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
        atc3Pump.lastBolusTime = pending.startedAtMs
        atc3Pump.lastBolusAmount = deliveredUnits
        aapsLogger.debug(LTag.PUMP, "ATC3: the pump completed our bolus at $deliveredUnits U, id $pumpId")
        trace.event(Atc3TraceCat.HIST, "bolus_completed", "units" to deliveredUnits, "id" to pumpId)
        return true
    }

    /**
     * True when the pump's boluses were read recently enough to act on without reading again.
     *
     * What "recently enough" buys is the barrier: AAPS cannot deliver insulin without connecting,
     * so a driver that reconciles on every connection never delivers on knowledge older than this.
     */
    @Synchronized
    fun historyFresh(now: Long): Boolean =
        lastReconciledAtMs != 0L && now - lastReconciledAtMs < Atc3Const.HISTORY_FRESH_MS

    /** What one reconciliation turned out to change. */
    data class ReconcileResult(
        /** Amount of the bolus AAPS was waiting to confirm, when one of the records was it. */
        val confirmedUnits: Double? = null,
        /**
         * When the newest bolus nobody had told AAPS about was given, if there was one.
         *
         * This is what says "somebody delivered insulin behind the loop's back", which is the one
         * thing that must stop an SMB the loop decided on without knowing about it.
         */
        val newestImportedAtMs: Long? = null,
        /**
         * Insulin taken in from the pump's records this pass, units.
         *
         * What the caller compares against the disagreement the reservoir showed: if the boluses
         * account for it, there is nothing left to look for and the basal journal need not be read.
         */
        val importedUnits: Double = 0.0
    )

    /**
     * How long ago the pump's delivery state was last reconciled, milliseconds.
     *
     * An age rather than a yes or no, because two callers ask the same question with different
     * thresholds: the loop having decided is reason to re-read after a cycle, a glucose value on
     * its own only after a good deal longer. See [lastStatusAtMs] for why this is not the same
     * question as [historyFresh].
     */
    @Synchronized
    fun stateAgeMs(now: Long): Long =
        if (lastStatusAtMs == 0L) Long.MAX_VALUE else now - lastStatusAtMs

    /** True when the state is fresh enough to act on without reading it again. */
    @Synchronized
    fun statusFresh(now: Long): Boolean = stateAgeMs(now) < Atc3Const.STATUS_FRESH_MS

    /** Bring AAPS in line with the bolus records the pump returned. */
    suspend fun reconcileBoluses(records: List<Atc3BolusRecord>, recordCount: Int): ReconcileResult {
        // Leaving the ledger alone is the whole point of stopping here: mark these records as
        // counted now and they would never be offered again, having never reached AAPS at all.
        if (!ensureRegistered()) {
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
                        // The row is gone, the user having removed the treatment. The pump's record
                        // still has to reach AAPS, and under the same id so it cannot arrive twice.
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
                    confirmed = action.units
                    newest = keepNewer(newest, action.timestamp to action.units)
                }

                is Atc3BolusAction.Import         -> {
                    aapsLogger.debug(
                        LTag.PUMP,
                        "ATC3: importing a bolus of ${action.units} U from the pump's history, id ${action.pumpId}"
                    )
                    // False means no new row: either AAPS already had this bolus, which is the
                    // normal case on a re-read, or it refused it. Everything AAPS refuses on age is
                    // filtered out before we get here, so anything left is worth a look in the log.
                    if (!syncPumpBolus(action.timestamp, action.units, action.pumpId)) {
                        aapsLogger.debug(
                            LTag.PUMP,
                            "ATC3: AAPS created no new row for bolus ${action.pumpId}, it already knew it"
                        )
                    }
                    announceExtended(action.carriesExtendedPart, action.units, action.pumpId)
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
                    // No type: the row already has one, and this correction knows nothing about it.
                    // Passing NORMAL here would turn an SMB into a meal bolus behind the loop's back.
                    //
                    // The answer is not checked because false is what a correction is supposed to
                    // get: it updates the row that is already there rather than creating one.
                    syncPumpBolus(action.timestamp, action.units, action.pumpId, type = null)
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
                    // Zeroed rather than deleted, because PumpSync gives a driver no way to remove
                    // a bolus row: it can invalidate a temporary basal by temporary id and not a
                    // bolus. Zero is what matters for the arithmetic -- the row stops counting
                    // towards insulin on board -- and it leaves the user something to see and
                    // correct rather than a bolus that silently vanished.
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
                    // The driver has done all it can: the pump has been asked and asked and does
                    // not admit to this bolus. Whether insulin went in is now a question only the
                    // person holding the pump can answer, so ask them.
                    uiInteraction.addNotification(
                        Notification.PUMP_SYNC_ERROR,
                        rh.gs(R.string.atc3_bolus_unconfirmed_dropped, action.pending.requestedUnits),
                        Notification.URGENT
                    )
                    // Nothing after this may run on knowledge from before it. The next command has
                    // to read the state and the history afresh, and find the bolus if it is there
                    // after all.
                    lastReconciledAtMs = 0L
                    lastStatusAtMs = 0L
                }

                is Atc3BolusAction.Consume        ->
                    aapsLogger.debug(LTag.PUMP, "ATC3: record ${action.pumpId} not imported, ${action.reason}")
            }
        }
        newest?.let {
            atc3Pump.lastBolusTime = it.first
            atc3Pump.lastBolusAmount = it.second
        }
        // The counts are what say whether a read was worth making. A connection whose reconcile
        // line is all zeroes but for `sent` taught the driver nothing it did not already know.
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

    /**
     * True when the pump is holding bolus records this answer left out and nobody has seen them.
     *
     * Asked before deciding whether the cheap periodic search was enough or the full history has to
     * be read as well. See [Atc3BolusReconciler.recordsMissing].
     */
    @Synchronized
    fun recordsMissing(history: Atc3BolusHistory): Boolean =
        Atc3BolusReconciler.recordsMissing(history, ledger())

    /**
     * Record the pump's own daily totals.
     *
     * Kept here with everything else that writes to AAPS, so the rule that this class is the only
     * door into [PumpSync] still holds.
     *
     * @return how many of the days AAPS did not already have
     */
    suspend fun recordDailyTotals(days: List<Atc3DailyStats>): Int {
        if (!ensureRegistered()) return 0
        var written = 0
        for (day in days) {
            val stored = pumpSync.createOrUpdateTotalDailyDose(
                timestamp = day.startOfDayMillis(),
                bolusAmount = day.bolusUnits,
                // AAPS wants what basal delivered altogether, and this pump counts a temporary
                // basal apart from the scheduled rate.
                basalAmount = day.basalWithTbrUnits,
                // Zero leaves AAPS to add the two up itself, which is what it does when told to.
                totalAmount = 0.0,
                pumpId = null,
                pumpType = PumpType.ATC3,
                pumpSerial = serial
            )
            if (stored) written++
        }
        return written
    }

    /** True when one of these records is the bolus AAPS is waiting to confirm. */
    @Synchronized
    fun wouldResolve(records: List<Atc3BolusRecord>, temporaryId: Long): Boolean {
        val pending = ledger().pending.firstOrNull { it.temporaryId == temporaryId } ?: return false
        return Atc3BolusReconciler.wouldResolve(records, pending, ledger())
    }

    /** True when AAPS is holding a temporary basal record open. */
    @Synchronized
    fun hasOpenTbr(): Boolean = ledger().activeTbr != null

    /** The temporary basal the ledger believes is running, or null. */
    @Synchronized
    fun openTbr(): ActiveTbr? = ledger().activeTbr

    /**
     * The pump's clock has just been written.
     *
     * The clock is only written after the history has been read and taken in, and everything the
     * pump records after the write is stamped on the new clock. So the place imports start from moves
     * to the moment of the write, for the bolus history and the temporary basal journal alike. Left
     * where it was, a clock put back would leave the records that follow behind the old mark, and
     * they would never be imported.
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
     * The moment the temporary basal AAPS has open really ended, where the pump says so.
     *
     * Object `0x0B` holds **the last temporary basal that ended**, and a temporary basal replaced by
     * another does not update it at all, so it can hold a record from hours before. It is believed
     * only when it can be the rate AAPS has open: the same rate, a start no earlier than the open
     * one's, and an end after it began. Whose temporary basal it was does not matter: when the one
     * AAPS has open is replaced unseen by a stranger's of the same rate, the end the pump gives for
     * the stranger's is where the rate AAPS had open stopped.
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

    /** The loop's last word on the temporary basal, or null when none has been seen. */
    @Synchronized
    fun loopTbr(): LoopTbr? = ledger().loopTbr

    /** AAPS cancelled the temporary basal: from now the loop expects none. */
    @Synchronized
    fun loopCancelledTbr(atMs: Long) {
        store(ledger().withLoopTbr(LoopTbr(null, 0, atMs)))
    }

    /**
     * Follow what the pump is delivering, whoever set it.
     *
     * A suspended pump is reported to AAPS as a temporary basal of zero, which is the only way the
     * insulin on board stops counting basal the pump is not giving; see [Atc3TbrTracker].
     *
     * @param pumpTbrStart what object `0x0A` said, when it was read, so the record can be anchored
     *   at the instant the pump began rather than the instant the driver noticed
     * @param pausedAtMs when the pump says it stopped, or null when it gave no moment; see
     *   [Atc3TbrTracker.step]
     * @param resumedAtMs when the pump says it started again, or null
     * @param journal reads the pump's temporary basal journal, object 0x27; asked only when a
     *   temporary basal is being closed here, so that its row is closed at the length its record
     *   delivered rather than at the tick. See [shapedClose].
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
            // A start the pump did not fill in is worse than no start at all: AAPS refuses the
            // whole record on age and the temporary basal never reaches the insulin on board. See
            // Atc3TbrStatus.isStartPlausible. Dropping it here falls back to anchoring the record
            // where the driver noticed the temporary basal, which is what happens anyway when the
            // pump does not answer object 0x0A.
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
                    // What the command asked for, which is a percentage when it came from the
                    // keypad that way. The rate actually in force is the `rate` argument above,
                    // read from Status V1.
                    "asked" to it.amountAsked,
                    "min" to it.durationMinutes
                )
                // Only the start is disbelieved. The duration came through the same answer and is
                // as good as ever, and keeping it is what stops the record being pushed out on
                // every poll past the end the pump is actually going to stop at.
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
        // The row being closed ends where the pump's record says, when the record can be had:
        // its own end when the pump gave only a stamp of it, and its rate over its time.
        val actions = ArrayList<Atc3TbrAction>(stepped.size)
        var movedFrom: Long? = null
        var movedTo: Long? = null
        for (action in stepped) {
            if (action is Atc3TbrAction.Stop && before != null && action.endPumpId == Atc3PumpId.tbrEndOf(before.pumpId)) {
                var end = action.timestamp
                if (action.byStamp) {
                    val byRecord = endByRecord(before, end, phoneNow, cached)
                    if (byRecord != end) {
                        movedFrom = end
                        movedTo = byRecord
                        end = byRecord
                    }
                }
                actions.add(action.copy(timestamp = shapedClose(before, end, cached)))
            } else if (action is Atc3TbrAction.Start && movedFrom != null && action.timestamp == movedFrom) {
                // The one that replaced it begins where it ended.
                actions.add(action.copy(timestamp = movedTo!!))
            } else actions.add(action)
        }
        if (movedFrom != null && active != null && active.startedAtMs == movedFrom) active = active.copy(startedAtMs = movedTo!!)
        var updated = ledger().withActiveTbr(active)
        val closedAt = actions.filterIsInstance<Atc3TbrAction.Stop>().firstOrNull()?.timestamp
        if (before != null && closedAt != null) updated = updated.withOurTbrEnd(before.pumpId, closedAt)
        // A temporary basal somebody else set is written from the pump's own start as well, and
        // noted the same way as ours: the journal then finds it already in AAPS, under its own id,
        // rather than importing it a second time beside itself.
        if (active != null && active !== before && !active.ours && !active.suspension) {
            active.pumpStartUtcSeconds?.let { utcSeconds ->
                val keepFromUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(phoneNow - Atc3Const.RECONCILE_MAX_AGE_MS)
                // The note carries the length the pump started the temporary basal for, which is
                // what its journal record will say: the continuation of one a stop interrupted is
                // written for what is left of that length, and is still that temporary basal.
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
     * The temporary basal AAPS itself asked for.
     *
     * @param pumpStart the pump's own record of it, object `0x0A` read right after the command and
     *   already checked to be this command; the record is then written from the pump's start, to
     *   the second, and the note says so. Null when it could not be had: the record is written from
     *   the moment the pump acknowledged the command.
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
        // Noted as ours before anything else, so that the journal cannot later read this temporary
        // basal back as a stranger's and make AAPS rewrite the record it is about to write here.
        //
        // The note carries the rate and the duration as well as the start, and all three have to
        // agree before a journal record is taken for this one. The start alone cannot decide it:
        // the journal keeps its starts to the whole minute while this note is written to the
        // second, so the window that has to cover our own record also covers a stranger's set in
        // the same minute.
        //
        // Both figures are the pump's own, as it reported them back through `Atc3TbrResult`, not
        // what the loop asked for: the pump takes whole quarter hours, and the journal record will
        // carry what the pump took.
        //
        // The note is kept on the same scale as the journal record it will be compared with: the
        // pump's wall-clock digits read as UTC, not the real epoch second. Written as a plain epoch
        // second it would sit a whole timezone offset away from every record, and the driver would
        // import its own temporary basals as strangers'.
        //
        // With the pump's own start the note carries the identity the journal record will have.
        // The AAPS row itself begins at the acknowledgement, which is when the temporary basal
        // began: the pump's stamp is the minute of its last snapshot, up to a minute earlier, and
        // a row begun there would count insulin the pump had not given.
        val startedUtcSeconds = pumpStart?.startUtcSeconds ?: Atc3StatusV1.wallClockUtcSeconds(ackAtMs)
        // The cut-off is filtered against the stored notes, so it has to be converted too.
        val keepFromUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(dateUtil.now() - Atc3Const.RECONCILE_MAX_AGE_MS)
        // The one before ends where its record says it delivered up to, when the record can be
        // had, and where this one begins otherwise. Closed before the ledger is copied below:
        // the close writes what the row was shaped to, and a copy taken earlier would put the
        // ledger back without it, making the mirror write those rows again with the very values
        // they hold.
        val closeAt = before?.let { shapedClose(it, entry.startedAtMs, journal) }
        // The one before is cut by AAPS where this one begins.
        var updated = ledger().withActiveTbr(entry)
            .withOurTbr(startedUtcSeconds, rate, durationMinutes, keepFromUtcSeconds, entry.pumpId, pumpStart != null, rowMs = entry.startedAtMs, asOrdered = true)
            .withLoopTbr(LoopTbr(rawOf(rate), durationMinutes, ackAtMs))
        if (before != null) updated = updated.withOurTbrEnd(before.pumpId, closeAt!!)
        store(updated)
        // AAPS cuts the running record at a new one's start only when the new one begins strictly
        // later, and two temporary basals of one minute begin at the same start, which would leave
        // both open, one on top of the other. So the one before is closed here.
        if (before != null && entry.startedAtMs >= before.startedAtMs) {
            apply(Atc3TbrAction.Stop(closeAt!!, Atc3PumpId.tbrEndOf(before.pumpId)))
        }
        apply(action)
    }

    /**
     * Close AAPS's copy of the running temporary basal.
     *
     * @param journal reads the pump's temporary basal journal, so that the row closes at the
     *   length its record delivered; see [shapedClose]
     */
    suspend fun tbrStopped(atMs: Long, byStamp: Boolean = false, journal: (() -> List<Atc3TbrRecord>?)? = null) {
        val active = ledger().activeTbr
        val once = journal?.let { read -> lazy { read() } }
        val cached: (() -> List<Atc3TbrRecord>?)? = once?.let { { it.value } }
        val end = if (byStamp && active != null) endByRecord(active, atMs, dateUtil.now(), cached) else atMs
        val closeAt = active?.let { shapedClose(it, end, cached) } ?: end
        var updated = ledger().withActiveTbr(null)
        if (active != null) updated = updated.withOurTbrEnd(active.pumpId, closeAt)
        store(updated)
        val endPumpId = active?.let { Atc3PumpId.tbrEndOf(it.pumpId) }
            ?: Atc3PumpId.of(atMs, Atc3PumpId.KIND_TBR_END)
        apply(Atc3TbrAction.Stop(closeAt, endPumpId))
    }

    /**
     * Where the row of a temporary basal that has just finished closes: at the length its journal
     * record says it delivered, to within one pulse, and at [proposedEndMs] when the journal cannot
     * say.
     *
     * The pump writes the record of a temporary basal the moment it finishes -- by replacement,
     * cancel, stop or its own end -- so the record is there to be read while the row is still open,
     * and the row is closed right the first time rather than reshaped later from the count. The
     * journal is asked only for a row that a record can shape: a rate above zero that is not a
     * stop. The record is the newest of that temporary basal, by the pump's own start when the row
     * carries it; with the pump's start also known to the second, a rate and a length are enough.
     *
     * The row keeps its time and takes the average rate the record makes of it, as the book does,
     * [Atc3TbrBook]. A read that fails costs nothing: the row closes where it would have, and the
     * book shapes it on the next comparison that reads the journal.
     */
    private suspend fun shapedClose(row: ActiveTbr, proposedEndMs: Long, journal: (() -> List<Atc3TbrRecord>?)?): Long {
        if (journal == null || row.ours || row.suspension || row.rate < Atc3Const.DOSE_SCALE / 2) return proposedEndMs
        val records = journal() ?: return proposedEndMs
        // The records are those of this temporary basal's minute: the pump's start is the minute's
        // identity, and a row without it is not closed from the journal here -- the tick learns
        // the pump's start when object 0x0A answers, and the book shapes the row then.
        val raw = rawOf(row.rate)
        val own = row.pumpStartUtcSeconds ?: return proposedEndMs
        // A row of under a second -- two temporary basals of one minute share the pump's start --
        // is left at its rate, and holds no insulin of the minute; see Atc3TbrBook.Row.hasTime.
        val span = proposedEndMs - row.startedAtMs
        if (span < MIN_SHAPED_SPAN_MS) {
            trace.event(Atc3TraceCat.TBR, "close", "id" to row.pumpId, "record" to false, "s" to span / 1000)
            return proposedEndMs
        }
        // What the minute gives this row, by the one rule the book applies to every minute: every
        // record of the minute, not the newest of the rate alone -- a stop cuts one temporary basal
        // into two records of one minute -- and whatever a row of the minute AAPS cut to nothing
        // hands over. The book then finds the row already right and writes nothing.
        val minuteRecords = records.filter { it.startUtcSeconds == own }.sortedByDescending { it.index }
        val delivered = Atc3TbrBook.unitsGivenTo(minuteRecords, minuteRowsOf(own, row, proposedEndMs), row.pumpId)
        if (delivered == null) {
            trace.event(Atc3TraceCat.TBR, "close", "id" to row.pumpId, "record" to false)
            return proposedEndMs
        }
        // The row keeps its time and takes the average rate the records evidence, so that rate
        // times duration is what the pump delivered; see Atc3TbrBook for why the time is not cut
        // instead.
        val average = delivered / (span / 3_600_000.0)
        trace.event(
            Atc3TraceCat.TBR, "close",
            "id" to row.pumpId, "record" to true, "units" to delivered, "records" to minuteRecords.size,
            "s" to span / 1000, "rate" to row.rate, "avg" to average
        )
        if (rawOf(average) != raw) {
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: temporary basal id ${row.pumpId} ran ${span / 1000} s and delivered $delivered U, " +
                    "recorded at $average U/h rather than the ${row.rate} U/h it was set to"
            )
            syncTbr(row.startedAtMs, average, span, PumpSync.TemporaryBasalType.NORMAL, row.pumpId, update = true)
        }
        // Remembered whether or not the rate moved: the row is the record's, and the mirror need
        // not write it again -- nor cut it down when only a later part of it is left to read.
        store(ledger().withOurTbrShaped(row.pumpId, delivered, proposedEndMs))
        return proposedEndMs
    }

    /**
     * Where our own temporary basal ended, when the pump gives its end only as a stamp.
     *
     * The stamp -- the end object 0x0B carries, or the start of the temporary basal that replaced
     * ours -- is the minute of the pump's last snapshot, up to a minute before the moment itself.
     * Our row's start is exact, its rate is exact, and its record says what it delivered, so the
     * record gives a second reckoning of the end: start plus delivered over rate, short of the
     * real end by at most one pulse's interval. Both are lower bounds of the real end, so the
     * later of the two is the nearer, and neither can be past the tick that noticed the end. A row
     * cut at the stamp alone would miss up to a minute of its rate, and the next comparison would
     * see a shortfall the pump never had.
     *
     * At a low rate a pulse's interval is long -- a quarter of an hour at 0.1 U/h -- and the
     * record's reckoning falls far short; there the stamp is the later of the two and wins. For a
     * stranger's row the start itself is a stamp, and nothing here is known well enough to move.
     */
    private suspend fun endByRecord(row: ActiveTbr, stampMs: Long, phoneNow: Long, journal: (() -> List<Atc3TbrRecord>?)?): Long {
        if (journal == null || !row.ours || row.suspension || row.rate < Atc3Const.DOSE_SCALE / 2) return stampMs
        val own = row.pumpStartUtcSeconds ?: return stampMs
        val records = journal() ?: return stampMs
        val minuteRecords = records.filter { it.startUtcSeconds == own }.sortedByDescending { it.index }
        val delivered = Atc3TbrBook.unitsGivenTo(minuteRecords, minuteRowsOf(own, row, stampMs), row.pumpId) ?: return stampMs
        val byRecord = row.startedAtMs + (delivered / row.rate * 3_600_000.0).toLong()
        val end = maxOf(stampMs, byRecord).coerceAtMost(phoneNow)
        trace.event(
            Atc3TraceCat.TBR, "end_by_record",
            "id" to row.pumpId, "stamp" to stampMs, "byRecord" to byRecord, "end" to end, "units" to delivered
        )
        return end
    }

    /**
     * The rows AAPS holds of one minute, as the book sees them, with the row being closed closed
     * at [closingEndMs]. The closing row is there whether or not a note of it was written yet.
     */
    private fun minuteRowsOf(startUtcSeconds: Long, closing: ActiveTbr, closingEndMs: Long): List<Atc3TbrBook.Row> {
        val noted = ledger().ourTbrs.mapNotNull { note ->
            if (note.startUtcSeconds != startUtcSeconds || !note.pumpStart) return@mapNotNull null
            val pumpId = note.pumpId ?: return@mapNotNull null
            val rowMs = note.rowMs ?: return@mapNotNull null
            val rawRate = note.rawRate ?: return@mapNotNull null
            val endMs = if (pumpId == closing.pumpId) closingEndMs else note.endMs
            Atc3TbrBook.Row(pumpId, note.startUtcSeconds, rowMs, rawRate, endMs, note.shapedUnits, note.carriedUnits)
        }
        if (noted.any { it.pumpId == closing.pumpId }) return noted
        return noted + Atc3TbrBook.Row(closing.pumpId, startUtcSeconds, closing.startedAtMs, rawOf(closing.rate), closingEndMs, null, null)
    }

    /**
     * A stop that began and ended between two ticks, recorded from the moment the pump keeps for it
     * and for the length the pump's count of delivered insulin evidences.
     *
     * Its length is worked out, not observed -- the pump keeps no moment of resuming -- and it is
     * written only when the scheduled rate was running: a temporary basal's own journal records
     * account for a stop inside it. AAPS counting insulin the pump did not deliver is worse than a
     * worked-out length.
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
     * A stop that began and ended between two ticks inside the temporary basal the ledger holds
     * open, recorded the way a stop the ticks saw would be: the row cut at the stop by the record
     * the pump wrote of the part before it, a stop of the length the pump's count evidences, and
     * the temporary basal going on from the end of the stop under its own identity, so that the
     * record the pump writes of the part after it finds that continuation. See [Atc3TbrBook].
     *
     * Written only when the pump's journal already holds the record of the part before the stop:
     * that record is what shows the temporary basal was interrupted at all. Without the cut, the
     * shortfall of the stop stays unexplained, because the open row cannot be cut by a record.
     *
     * @return false when the ledger holds no such temporary basal, or the journal no such record
     */
    suspend fun recordDerivedStopInTbr(stopMs: Long, lengthMs: Long, records: List<Atc3TbrRecord>): Boolean {
        val active = ledger().activeTbr ?: return false
        if (active.suspension || active.rate < Atc3Const.DOSE_SCALE / 2) return false
        val own = active.pumpStartUtcSeconds ?: return false
        // No earlier than the pump's own stamp of the temporary basal; the split itself begins no
        // earlier than the row, see Atc3TbrTracker.splitByStop.
        if (stopMs < active.anchorMs) return false
        val raw = rawOf(active.rate)
        if (records.none { r -> r.startUtcSeconds == own && r.rate?.let { rawOf(it) == raw } == true }) return false
        val (stepped, goingOn) = Atc3TbrTracker.splitByStop(active, stopMs, lengthMs)
        val actions = stepped.map { action ->
            if (action is Atc3TbrAction.Stop && action.endPumpId == Atc3PumpId.tbrEndOf(active.pumpId))
                action.copy(timestamp = shapedClose(active, action.timestamp, { records }))
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

    /** Forget everything: another pump, or the same pump paired afresh. */
    fun forgetPump(newSerial: String) {
        lastReconciledAtMs = 0L
        lastStatusAtMs = 0L
        loadedFor = newSerial
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
                trace.event(Atc3TraceCat.TBR, "stop", "at" to action.timestamp, "id" to action.endPumpId)
                val closed = pumpSync.syncStopTemporaryBasalWithPumpId(
                    timestamp = action.timestamp,
                    endPumpId = action.endPumpId,
                    pumpType = PumpType.ATC3,
                    pumpSerial = serial
                )
                // AAPS answers false when it had already cut this one, which is how it stays
                // idempotent, and also when it refused the write outright. The second would leave
                // AAPS believing a temporary basal is still running, so it must not pass in silence.
                if (!closed) {
                    aapsLogger.debug(
                        LTag.PUMP,
                        "ATC3: AAPS closed no temporary basal for id ${action.endPumpId}, it was already closed"
                    )
                }
            }

            is Atc3TbrAction.Retime -> {
                // The row stays at the acknowledgement; the note takes the pump's start as the
                // identity its journal record will carry. Nothing to write into AAPS.
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
     * Bring the AAPS temporary basal rows to what the pump's journal says, minute by minute, and
     * take in the temporary basals the pump ran while nobody was watching. See [Atc3TbrBook].
     *
     * Called when the pump's count of delivered insulin and the AAPS journal disagree, and after
     * a temporary basal was changed by hand.
     *
     * @return how many rows were written or brought to the journal
     */
    suspend fun reconcileTbrHistory(records: List<Atc3TbrRecord>): Int {
        val current = ledger()
        // Only rows on the pump's own start take part: a row still waiting for object 0x0A has no
        // minute to belong to yet, and the tick moves it onto the pump's start when 0x0A answers.
        val rows = current.ourTbrs.mapNotNull { note ->
            val pumpId = note.pumpId ?: return@mapNotNull null
            val rowMs = note.rowMs ?: return@mapNotNull null
            val rawRate = note.rawRate ?: return@mapNotNull null
            if (!note.pumpStart) return@mapNotNull null
            Atc3TbrBook.Row(pumpId, note.startUtcSeconds, rowMs, rawRate, note.endMs, note.shapedUnits, note.carriedUnits)
        }
        val outcome = Atc3TbrBook.account(
            records = records,
            rows = rows,
            bookStartUtcSeconds = current.tbrImportUtcSeconds,
            phoneNow = dateUtil.now(),
            takenIds = rows.mapTo(HashSet()) { it.pumpId }
        )
        for (carry in outcome.carries) {
            aapsLogger.debug(LTag.PUMP, "ATC3: ${carry.units} U of a row of its minute cut to nothing, kept for id ${carry.toPumpId} to take when it closes")
            trace.event(Atc3TraceCat.TBR, "tbr_minute", "into" to carry.toPumpId, "units" to carry.units)
            store(ledger().withOurTbrCarried(carry.toPumpId, carry.units))
        }
        // A row AAPS asked for stays as it was ordered; its record only keeps the journal from
        // taking it for a stranger's.
        val asOrdered = current.ourTbrs.filter { it.asOrdered }.mapNotNullTo(HashSet()) { it.pumpId }
        for (shape in outcome.shapes.filterNot { it.pumpId in asOrdered }) {
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: temporary basal id ${shape.pumpId} from ${shape.rowMs} for ${shape.durationMs / 1000} s holds " +
                    "${shape.units} U by the journal, ${shape.rateUnitsPerHour} U/h"
            )
            trace.event(Atc3TraceCat.TBR, "reshape", "id" to shape.pumpId, "rate" to shape.rateUnitsPerHour, "s" to shape.durationMs / 1000, "units" to shape.units)
            // The type stays NORMAL for a temporary basal that was set at a rate: an average of
            // nothing over its span is still a rate it was set to, not a stop.
            syncTbr(shape.rowMs, shape.rateUnitsPerHour, shape.durationMs, PumpSync.TemporaryBasalType.NORMAL, shape.pumpId, update = true)
            store(ledger().withOurTbrShaped(shape.pumpId, shape.units, shape.rowMs + shape.durationMs))
        }
        for (import in outcome.imports) {
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: the pump ran a temporary basal from ${import.timestamp} for ${import.durationMs / 1000} s, " +
                    "${import.units} U, that AAPS did not know; recorded at ${import.rateUnitsPerHour} U/h, id ${import.pumpId}"
            )
            trace.event(Atc3TraceCat.TBR, "import", "at" to import.timestamp, "s" to import.durationMs / 1000, "units" to import.units, "id" to import.pumpId)
            syncTbr(
                import.timestamp, import.rateUnitsPerHour, import.durationMs,
                if (import.units <= 0.0 && import.rateUnitsPerHour <= 0.0) PumpSync.TemporaryBasalType.PUMP_SUSPEND else PumpSync.TemporaryBasalType.NORMAL,
                import.pumpId
            )
        }
        store(ledger().withTbrWatermark(outcome.newestUtcSeconds))
        trace.event(
            Atc3TraceCat.HIST, "tbr_history",
            "held" to records.size,
            "minutes" to outcome.minutes,
            "with_rows" to outcome.minutesWithRows,
            "stale" to outcome.stale,
            "shaped" to outcome.shapes.size,
            "carried" to outcome.carries.size,
            "imported" to outcome.imports.size
        )
        return outcome.shapes.count { it.pumpId !in asOrdered } + outcome.imports.size + outcome.carries.size
    }


    private companion object {

        /**
         * How far the start in the pump's record may sit from the one being closed and still be it.
         *
         * The two are measured on different clocks and corrected by a difference that itself moves
         * by tens of seconds between reads, so they are never expected to agree exactly. Two
         * minutes is well inside the fifteen the pump's own duration steps in, so no two temporary
         * basals of ours can be confused at this distance.
         */
        const val TBR_END_MATCH_MS = 120_000L

        /** How far the pump's start of one temporary basal may read apart in two of its objects, seconds. */
        const val TBR_SAME_START_SECONDS = 2L

        /** A row shorter than this keeps the rate it was set to rather than an average over nothing. */
        const val MIN_SHAPED_SPAN_MS = 1_000L

        /** A rate in the pump's own raw steps, the way every amount here is compared. */
        fun rawOf(rate: Double): Int = Math.round(rate / Atc3Const.DOSE_SCALE).toInt()
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
        // Not belt and braces: blast radius. `TB` requires a duration above zero and throws on
        // anything else, and this runs inside the command AAPS is executing, so an exception here
        // does not cost the one record -- it costs the whole tick, including the watermark that
        // would have moved with it, which is how the same record comes back on the next read.
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
        // This one is not idempotency: AAPS returns false only when it refused the write, and then
        // it believes the pump is delivering the profile while it is delivering something else.
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

    /**
     * Hand the clock watch what this read found: how many minutes the records of our own boluses
     * sat from the minutes those boluses started in. See [Atc3ClockWatch].
     */
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
     * Tell the user when a bolus that was spread over time has been recorded as if it were given
     * at once. AAPS is not being told about an extended bolus, deliberately, and the amount it does
     * carry would otherwise be unexplainable.
     */
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
