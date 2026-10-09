package app.aaps.pump.atc3.check

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.basal.Atc3TbrTracker
import app.aaps.pump.atc3.clock.Atc3DayClock
import app.aaps.pump.atc3.events.EventAtc3PumpDataChanged
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.history.Atc3JournalFault
import app.aaps.pump.atc3.manager.Atc3Manager
import app.aaps.pump.atc3.protocol.Atc3BolusHistory
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.store.Atc3Store
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the pump's own count of delivered insulin agrees with what AAPS holds: read on every tick
 * and in front of every command. The count is compared from the start of the window under way, see
 * [app.aaps.pump.atc3.basal.Atc3BasalPeriod]. A disagreement is looked into once, in the pump's
 * bolus journal, for a bolus given elsewhere. What the journal does not explain is the pump's own
 * way of delivering: it is counted and shown, never written, and the window closed at the half hour
 * shows it whole.
 *
 * A command of the loop's is refused only for a bolus the loop did not know of; a temporary basal
 * or a cancel found on the pump is written, and the loop's decision goes.
 */
@Singleton
class Atc3Reconciliation @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val rxBus: RxBus,
    private val uiInteraction: UiInteraction,
    private val pumpState: Atc3PumpState,
    private val atc3Manager: Atc3Manager,
    private val atc3HistorySync: Atc3HistorySync,
    private val journalFault: Atc3JournalFault,
    private val aapsJournal: Atc3AapsJournal,
    private val store: Atc3Store,
    private val trace: Atc3Trace
) {

    /** The difference the bolus journal was read for and did not explain, U: counted, not read for again. */
    private var settledUnits = 0.0

    /** The window start [settledUnits] belongs to: a new window starts with nothing settled. */
    private var settledFromMs = 0L

    /** The report time of the last report that showed the pump delivering: a stop kept from earlier in the day is not the one now. */
    private var lastRunningSnapshotMs = 0L

    /** One tick's figures, for [noteMotion]. */
    private data class Tick(val startMs: Long, val readMs: Long, val counterUnits: Double, val aapsUnits: Double)

    private var lastTick: Tick? = null

    /** Ticks in a row where the journal owed a step of insulin and the count did not move. */
    private var motionlessTicks = 0

    /** True once the count has stood still for enough ticks in a row to stop the loop. */
    val deliveryStopped: Boolean get() = motionlessTicks >= NO_DELIVERY_TICKS_TO_STOP

    /**
     * Write what the pump runs into AAPS, from the status just read. Reads more only on a disagreement:
     * the running temporary basal, for its start, and the last finished one, for when it really ended.
     *
     * @return false when the running temporary basal was wanted and did not answer
     */
    suspend fun syncTbrFromStatus(): Boolean {
        val card = pumpState.statusCard ?: return false
        val openTbr = atc3HistorySync.openTbr()
        val tickAt = trace.now()
        val (pausedAtMs, resumedAtMs) = pumpMoments(card)
        val startRead = Atc3TbrTracker.needsStartRead(
            openTbr, card.notDelivering, card.tbrActive, card.tbrRate, card.tbrDurationMs, tickAt,
            elapsedMinutes = card.tbrElapsedMinutes.takeIf { card.tbrActive }
        )
        val pumpTbrStart = if (startRead) atc3Manager.readActiveTbr() else null
        val tbrRead = !startRead || pumpTbrStart != null
        val endedAtMs =
            if (Atc3TbrTracker.needsEndRead(openTbr, card.notDelivering, card.tbrActive, tickAt))
                atc3Manager.readFinishedTbr()?.let { atc3HistorySync.realEndOf(it) }
            else null
        atc3HistorySync.onStatus(
            card.notDelivering, card.tbrActive, card.tbrRate, card.tbrDurationMs, pumpTbrStart, endedAtMs,
            pausedAtMs = pausedAtMs, resumedAtMs = resumedAtMs
        )
        return tbrRead
    }

    /**
     * The moments of a stop and a resume as the pump keeps them, from the status just read. A stop is
     * to the minute, or to the second from the report the stop rebuilt; a stop the pump does not admit
     * to has only the report's time. A resume has its moment only in the report it rebuilt.
     */
    private fun pumpMoments(card: Atc3PumpState.StatusCard): Pair<Long?, Long?> {
        val status = card.status
        if (!card.notDelivering) {
            lastRunningSnapshotMs = status.snapshotTime
            return null to status.snapshotTime
        }
        // The second when a read since the stop caught the report the stop rebuilt, else the minute.
        val explicit = status.lastStopMoment()
            ?.let { minute -> card.stopSnapshotMs?.takeIf { it / 60_000L == minute / 60_000L } ?: minute }
            ?.takeIf { status.suspended && it > lastRunningSnapshotMs }
        if (explicit != null) trace.event(
            Atc3TraceCat.TBR, "stop_moment", "at" to explicit, "exact" to (explicit % 60_000L != 0L || status.snapshotIsTheStop())
        )
        return (explicit ?: status.snapshotTime) to null
    }

    /** What one comparison came to once the driver had looked for the cause. */
    data class Resolved(
        /** True when the bolus journal was read and taken in this time. */
        val journalRead: Boolean,
        /** True when the bolus journal was wanted and the pump did not answer. */
        val readFailed: Boolean,
        /** One word for the trace: balanced, bolus_found, differs, unknown, read_failed. */
        val outcome: String,
        /** When the newest bolus nobody had told AAPS of was given, or null. */
        val foreignBolusAtMs: Long? = null,
        val verdict: Atc3StateCheck.Verdict = Atc3StateCheck.Verdict.Unknown,
        /** False when the running temporary basal had to be read and did not answer. */
        val tbrRead: Boolean = true
    ) {

        /** Whether the data the loop decided on stood: the pump answered, and no bolus the loop did not know of turned up. */
        val stood: Boolean get() = !readFailed && foreignBolusAtMs == null
    }

    /** Compare the pump's count at the read with what the AAPS journal accounts for since the window began. */
    private suspend fun checkState(): Atc3StateCheck.Verdict {
        val card = pumpState.statusCard ?: return Atc3StateCheck.Verdict.Unknown
        val readMs = card.readAtMs
        val counter = card.deliveredTodayUnits
        val start = store.state.basalPeriod.start ?: return unknown("no_window", counter, readMs)
        if (start.readMs != settledFromMs) {
            settledUnits = 0.0
            settledFromMs = start.readMs
        }
        // Nothing is compared across midnight, a clock put back or a count begun anew.
        val why = when {
            readMs < start.readMs                                       -> "time_back"
            !Atc3DayClock.sameDay(start.readMs, readMs)                 -> "midnight"
            counter + Atc3Protocol.COUNT_EPSILON < start.counterUnits -> "count_reset"
            else                                                        -> null
        }
        if (why != null) return unknown(why, counter, readMs)
        val journal = aapsJournal.insulinBetween(start.readMs, readMs, atc3HistorySync.bolusesLearnedAfter(start.learnedAfterMs))
            ?: return unknown("no_profile", counter, readMs).also {
                aapsLogger.error(LTag.PUMP, "ATC3: no profile is running, the journal cannot be summed")
            }
        val pumpUnits = counter - start.counterUnits
        noteMotion(start.readMs, readMs, counter, journal.totalUnits)
        val verdict = Atc3StateCheck.compare(pumpUnits, journal.totalUnits, settledUnits, checkTolerance())
        // The row written from what we saw of our bolus, against the count once a read holds that bolus.
        ownFaultRow?.let { (atMs, units) ->
            if (readMs <= atMs) return@let
            ownFaultRow = null
            val beyond = pumpUnits - journal.totalUnits - settledUnits
            aapsLogger.warn(LTag.PUMP, "ATC3: with $units U written for our unrecorded bolus, the count holds ${"%.3f".format(beyond)} U beyond the journal")
            trace.event(Atc3TraceCat.HIST, "fault_count", "units" to units, "beyond" to beyond)
        }
        trace.event(
            Atc3TraceCat.HIST, "check",
            "state" to verdict.trace(),
            "s" to (readMs - start.readMs) / 1000,
            "pump" to pumpUnits,
            "aaps" to journal.totalUnits,
            "bolus" to journal.bolusUnits,
            "tbr" to journal.temporaryBasalUnits,
            "sched" to journal.scheduledUnits,
            "diff" to pumpUnits - journal.totalUnits,
            "settled" to settledUnits,
            "tolerance" to checkTolerance(),
            "snap" to card.status.snapshotTime,
            "counter" to counter
        )
        return verdict
    }

    /** The comparison has nothing to compare against, and says why. */
    private fun unknown(why: String, counter: Double, readMs: Long): Atc3StateCheck.Verdict {
        trace.event(Atc3TraceCat.HIST, "check", "state" to "unknown", "why" to why, "counter" to counter, "at" to readMs)
        return Atc3StateCheck.Verdict.Unknown
    }

    /**
     * The day's sum of the AAPS journal beside the pump's count of the day, for the driver's screen,
     * both from the pump's midnight. Decides nothing.
     */
    suspend fun noteDayAccount() {
        val card = pumpState.statusCard ?: return
        val readMs = card.readAtMs
        val dayStart = Atc3DayClock.dayStartOf(readMs)
        val journal = aapsJournal.insulinBetween(dayStart, readMs, aapsJournal.bolusesBetween(dayStart, readMs)) ?: return
        val pumpUnits = card.deliveredTodayUnits
        pumpState.dayAccount = Atc3PumpState.DayAccount(journal.totalUnits, pumpUnits, readMs)
        trace.event(
            Atc3TraceCat.HIST, "day",
            "aaps" to journal.totalUnits, "pump" to pumpUnits, "diff" to pumpUnits - journal.totalUnits,
            "bolus" to journal.bolusUnits, "tbr" to journal.temporaryBasalUnits, "sched" to journal.scheduledUnits
        )
        rxBus.send(EventAtc3PumpDataChanged())
    }

    /**
     * How far the count and the journal may sit apart since the window began before the bolus journal
     * is read: a minute's delivery at the highest basal rate the pump allows, never under two pulses.
     */
    private fun checkTolerance(): Double =
        maxOf(2 * Atc3Protocol.DOSE_SCALE, (pumpState.settings?.maxBasal ?: 0.0) / 60.0)

    /**
     * Read the bolus journal when the count asks for it, or when the caller does, and write what it
     * holds. What the journal does not explain is settled: counted, and not read for again.
     *
     * @param readJournal true to read the bolus journal whatever the count says
     */
    private suspend fun resolve(verdict: Atc3StateCheck.Verdict, readJournal: Boolean): Resolved {
        if (verdict is Atc3StateCheck.Verdict.Matches && !readJournal) {
            return Resolved(journalRead = false, readFailed = false, outcome = "balanced", verdict = verdict)
        }
        val startedAt = trace.now()
        val reconciled = readBoluses()
        if (reconciled == null) {
            trace.event(Atc3TraceCat.HIST, "resolve", "read" to false, "ms" to trace.since(startedAt))
            return Resolved(journalRead = false, readFailed = true, outcome = "read_failed", verdict = verdict)
        }
        // No status ever read: nothing is known of the pump, and the loop's command does not go.
        if (pumpState.statusCard == null) {
            trace.event(Atc3TraceCat.HIST, "resolve", "read" to true, "why" to "no_status", "ms" to trace.since(startedAt))
            return Resolved(journalRead = true, readFailed = true, outcome = "no_status", verdict = verdict)
        }
        var difference = (verdict as? Atc3StateCheck.Verdict.Differs)?.units ?: 0.0
        if (verdict is Atc3StateCheck.Verdict.Differs) {
            // The comparison again, with the bolus the journal may have added; what is left is the pump's.
            val again = checkState()
            difference = (again as? Atc3StateCheck.Verdict.Differs)?.units ?: 0.0
            if (again is Atc3StateCheck.Verdict.Differs) {
                settledUnits = again.units
                aapsLogger.debug(
                    LTag.PUMP,
                    "ATC3: the pump counted ${"%.3f".format(difference)} U against the AAPS journal since the window began; the window settles it"
                )
            }
        }
        val outcome = when {
            reconciled.newestImportedAtMs != null     -> "bolus_found"
            verdict is Atc3StateCheck.Verdict.Differs -> "differs"
            verdict is Atc3StateCheck.Verdict.Unknown -> "unknown"
            else                                      -> "balanced"
        }
        trace.event(
            Atc3TraceCat.HIST, "resolve",
            "state" to verdict.trace(),
            "units" to difference,
            "boluses" to reconciled.importedUnits,
            "outcome" to outcome,
            "ms" to trace.since(startedAt)
        )
        return Resolved(journalRead = true, readFailed = false, outcome = outcome, foreignBolusAtMs = reconciled.newestImportedAtMs, verdict = verdict)
    }

    /**
     * Note whether the count moved since the last tick while the journal owed a step of insulin. The
     * journal's figure is since the window began, so the last tick's is taken off it; when the window
     * began at the last tick's read, it is the whole of it.
     */
    private fun noteMotion(startMs: Long, readMs: Long, counterUnits: Double, aapsUnits: Double) {
        val last = lastTick
        if (last != null && last.readMs == readMs) return
        lastTick = Tick(startMs, readMs, counterUnits, aapsUnits)
        if (last == null) return
        val owed = when (startMs) {
            last.startMs -> aapsUnits - last.aapsUnits
            last.readMs  -> aapsUnits
            // A window begun anew elsewhere: the count starts over, and so does this.
            else         -> {
                motionlessTicks = 0
                return
            }
        }
        val moved = counterUnits - last.counterUnits
        if (moved < 0.0 || moved >= Atc3Protocol.DOSE_SCALE - Atc3Protocol.COUNT_EPSILON) {
            motionlessTicks = 0
            return
        }
        if (owed < Atc3Protocol.DOSE_SCALE) return
        motionlessTicks++
    }

    /** Say it when the pump owes insulin and its count has not moved, twice running; one more tick and the loop stops. */
    fun reportDeliveryStopped() {
        if (motionlessTicks < NO_DELIVERY_TICKS_TO_TELL) return
        aapsLogger.error(LTag.PUMP, "ATC3: the pump owes insulin and the reservoir has not moved for $motionlessTicks ticks")
        trace.event(Atc3TraceCat.DRV, "no_delivery", "times" to motionlessTicks)
        uiInteraction.addNotification(Notification.PUMP_ERROR, rh.gs(R.string.atc3_no_delivery), Notification.URGENT)
    }

    /** When the newest bolus nobody had told AAPS of was given, over every read so far: what refuses an SMB decided without it. */
    @Volatile var newestForeignBolusAtMs: Long? = null
        private set

    /** A row written from what we saw of our bolus, waiting for the next status read to say what the count holds of it. */
    @Volatile private var ownFaultRow: Pair<Long, Double>? = null

    /**
     * Read the pump's boluses and write what they hold, or null when the pump did not answer. A slot
     * the pump failed to write is taken out first; every fault not accounted for yet stands for the
     * bolus of ours waiting, if one is, else for what the count holds beyond the journal at this read.
     * A bolus found that way is one the loop decided without, like any imported bolus.
     */
    suspend fun readBoluses(): Atc3HistorySync.ReconcileResult? {
        val history = readBolusHistory() ?: return null
        val screened = journalFault.screen(history)
        var reconciled = atc3HistorySync.reconcileBoluses(screened.history.records, history.recordCount)
        reconciled.newestImportedAtMs?.let { newestForeignBolusAtMs = maxOf(newestForeignBolusAtMs ?: 0L, it) }
        val unsettled = atc3HistorySync.unsettledFaults()
        if (unsettled.isEmpty()) return reconciled
        val settled = journalFault.settleOwn(unsettled)
        settled.confirmed.entries.firstOrNull()?.let { ownFaultRow = it.key to it.value }
        if (settled.left.isNotEmpty()) {
            val readMs = pumpState.statusCard?.readAtMs ?: trace.now()
            val notedAt = journalFault.settleByCount(settled.left, unexplainedUnits(), readMs, pumpState.settings?.maxBolus)
            if (notedAt != null) {
                newestForeignBolusAtMs = maxOf(newestForeignBolusAtMs ?: 0L, notedAt)
                reconciled = reconciled.copy(newestImportedAtMs = maxOf(reconciled.newestImportedAtMs ?: 0L, notedAt))
            }
        }
        return reconciled.copy(confirmed = reconciled.confirmed + settled.confirmed)
    }

    /** What the pump's count holds beyond the AAPS journal and the part settled already since the window began, U; zero when they agree or nothing is known. */
    private suspend fun unexplainedUnits(): Double =
        (checkState() as? Atc3StateCheck.Verdict.Differs)?.let { it.units - settledUnits } ?: 0.0

    /** Read the pump's boluses, the whole history only when the usual answer left records out, see [app.aaps.pump.atc3.history.Atc3BolusReconciler.recordsMissing]. */
    fun readBolusHistory(): Atc3BolusHistory? {
        val latest = atc3Manager.readBolusHistory() ?: return null
        if (!atc3HistorySync.recordsMissing(latest)) return latest
        trace.event(Atc3TraceCat.DRV, "full_history", "held" to latest.recordCount, "sent" to latest.records.size)
        aapsLogger.debug(
            LTag.PUMP,
            "ATC3: the pump holds ${latest.recordCount} records but sent ${latest.records.size}, reading the full history"
        )
        return atc3Manager.readFullBolusHistory() ?: latest
    }

    /**
     * Compare the status just read with the AAPS journal, write what the pump runs into AAPS, and
     * read the bolus journal when the two disagree, or when the caller asks for it.
     *
     * @param statusRead false when the status could not be read: the bolus journal is read all the same
     * @param readJournal true in front of a command, and at a read the window began at: the bolus
     *   journal is read whatever the count says
     */
    suspend fun reconcile(statusRead: Boolean = true, readJournal: Boolean = false): Resolved {
        // The verdict before the record: what the pump did on its own since the window began is measured
        // against the journal the loop decided on, and only then written into it.
        val verdict = if (statusRead) checkState() else Atc3StateCheck.Verdict.Unknown
        val tbrRead = if (statusRead) syncTbrFromStatus() else true
        return resolve(verdict, readJournal).copy(tbrRead = tbrRead)
    }

    private companion object {

        /** Ticks with the count standing still while insulin was owed before the user is told. */
        const val NO_DELIVERY_TICKS_TO_TELL = 2

        /** One more, and the loop is stopped as well. */
        const val NO_DELIVERY_TICKS_TO_STOP = 3
    }
}
