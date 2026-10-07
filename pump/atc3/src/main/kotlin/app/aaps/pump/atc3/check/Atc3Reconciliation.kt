package app.aaps.pump.atc3.check

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.basal.Atc3TbrTracker
import app.aaps.pump.atc3.clock.Atc3DayClock
import app.aaps.pump.atc3.events.EventAtc3PumpDataChanged
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.link.Atc3LinkKeeper
import app.aaps.pump.atc3.manager.Atc3Manager
import app.aaps.pump.atc3.protocol.Atc3BolusHistory
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3StatusV1
import app.aaps.pump.atc3.protocol.Atc3TbrRecord
import app.aaps.pump.atc3.protocol.Atc3TbrStatus
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Whether the pump's own count of delivered insulin agrees with what AAPS holds, and what the
 * pump's journals say when it does not: read on every tick and in front of every command, so
 * that a decision of the loop's is applied only to data that was the pump's.
 */
@Singleton
class Atc3Reconciliation @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val rxBus: RxBus,
    private val uiInteraction: UiInteraction,
    private val dateUtil: DateUtil,
    private val pumpState: Atc3PumpState,
    private val atc3Manager: Atc3Manager,
    private val atc3HistorySync: Atc3HistorySync,
    private val aapsJournal: Atc3AapsJournal,
    private val stateCheck: Atc3StateCheck,
    private val linkKeeper: Atc3LinkKeeper,
    private val trace: Atc3Trace
) {

    /** Why a comparison had nothing to compare against, and whether that is a beginning, see [checkState]. */
    private enum class NoBase(val trace: String, val begins: Boolean) {

        FIRST("first", true),
        REFILL("refill", true),
        LINK_BACK("link_back", true),
        COUNT_RESET("count_reset", true),
        TIME_BACK("time_back", true),
        MIDNIGHT("midnight", true),
        BOLUS_STRADDLES("bolus_straddles_anchor", false),
        NO_PROFILE("no_profile", false)
    }

    /** Set by the comparison that came back with nothing to compare against, for [noteBeginning]. */
    private var noBase: NoBase? = null

    /** True from a refill until the comparison has started anew from it. */
    private var refilled = false

    /** The read the day's account on the screen is counted from, when that is not the pump's midnight. */
    private data class DayBase(val readMs: Long, val counterUnits: Double)

    private var dayBase: DayBase? = null

    /** The report time of the last report that showed the pump delivering: a stop kept from earlier in the day is not the one now. */
    private var lastRunningSnapshotMs = 0L

    /**
     * When the last comparison found the two sides agreeing, or brought them to: a command within
     * [Atc3Const.HISTORY_FRESH_MS] of it needs no bolus history read of its own.
     */
    private var lastBalancedAtMs = 0L

    /** True once the running temporary basal has been read this tick. */
    private var activeTbrReadThisTick = false

    /** What that read returned. */
    private var activeTbrThisTick: Atc3TbrStatus? = null

    /** The last stop the pump reported at the previous tick, for noticing one the ticks missed. */
    private var lastStopSeen: Atc3StatusV1.LastStop? = null

    /** The moment of a stop that began and ended between two ticks, or null; see [pumpMoments]. */
    private var unseenStopMs: Long? = null

    /** Ticks in a row that found the pump not delivering. */
    private var deliveryStoppedReports: Int = 0

    /** True once that has been found on enough ticks in a row to stop the loop. */
    val deliveryStopped: Boolean get() = deliveryStoppedReports >= NO_DELIVERY_REPORTS_BEFORE_STOP

    /** The pump's temporary basal journal, as the history sync asks for it. */
    val journal: () -> List<Atc3TbrRecord>? = { atc3Manager.readTbrHistory() }

    /**
     * Write what the pump runs into AAPS, from the status just read. Reads more only on a disagreement:
     * the running temporary basal, for its start, and the last finished one, for when it really ended.
     *
     * @return false when the running temporary basal was wanted and did not answer
     */
    suspend fun syncTbrFromStatus(): Boolean {
        val card = pumpState.statusCard ?: return false
        val openTbr = atc3HistorySync.openTbr()
        val tickAt = dateUtil.now()
        val tbrDurationMs = card.tbrDurationMinutes.takeIf { it > 0 }?.let { it * 60_000L }
        val (pausedAtMs, resumedAtMs) = pumpMoments(card)
        val startRead = Atc3TbrTracker.needsStartRead(
            openTbr, card.notDelivering, card.tbrActive, card.tbrRate, tbrDurationMs, tickAt,
            elapsedMinutes = card.tbrElapsedMinutes.takeIf { card.tbrActive }
        )
        val pumpTbrStart = if (startRead) atc3Manager.readActiveTbr() else null
        val tbrRead = !startRead || pumpTbrStart != null
        if (pumpTbrStart != null) {
            activeTbrReadThisTick = true
            activeTbrThisTick = pumpTbrStart
        }
        val endedAtMs =
            if (Atc3TbrTracker.needsEndRead(openTbr, card.notDelivering, card.tbrActive, tickAt))
                atc3Manager.readFinishedTbr()?.let { atc3HistorySync.realEndOf(it) }
            else null
        atc3HistorySync.onStatus(
            card.notDelivering, card.tbrActive, card.tbrRate, tbrDurationMs, pumpTbrStart, endedAtMs,
            pausedAtMs = pausedAtMs, resumedAtMs = resumedAtMs, journal = journal
        )
        return tbrRead
    }

    /** The running temporary basal as the pump records it, read at most once a tick. */
    fun runningTbrThisTick(): Atc3TbrStatus? {
        if (!activeTbrReadThisTick) {
            activeTbrThisTick = atc3Manager.readActiveTbr()
            activeTbrReadThisTick = true
        }
        return activeTbrThisTick
    }

    /**
     * The moments of a stop and a resume as the pump keeps them, from the status just read. A stop is
     * to the minute, or to the second from the report the stop rebuilt; a stop the pump does not admit
     * to has only the report's time. A resume has its moment only in the report it rebuilt.
     */
    private fun pumpMoments(card: Atc3PumpState.StatusCard): Pair<Long?, Long?> {
        val status = card.status
        // A stop the ticks missed: the pump's last stop moved while it runs and the ledger holds none.
        unseenStopMs = null
        val stopNow = status.lastStop
        if (stopNow != null && lastStopSeen != null && stopNow != lastStopSeen && !card.notDelivering &&
            atc3HistorySync.openTbr()?.suspension != true
        ) {
            unseenStopMs = status.lastStopMoment()?.takeIf { it > lastRunningSnapshotMs }
            unseenStopMs?.let { trace.event(Atc3TraceCat.TBR, "stop_unseen", "at" to it) }
        }
        lastStopSeen = stopNow
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

    /** How a state check ended once the driver had looked for the cause. */
    enum class Resolution {

        /** The journal accounted for the pump's count and nothing was read. */
        BALANCED,

        /** There was nothing to compare against; the journals were read and this snapshot accepted. */
        ANCHORED,

        /** The journals were read, AAPS brought to them, and the two sides now agree. */
        EXPLAINED,

        /**
         * The journals hold nothing new and the two sides differ by no more than a pulse past the tolerance:
         * the count and the records each fall on pulses at moments of their own. Taken as agreement.
         */
        QUANTISED,

        /**
         * The pump counted more than the journal, by no more than a bolus of ours without a record yet: the
         * bolus is still being delivered, or was with nobody listening. Nobody is told, and the anchor stays.
         */
        BOLUS_RUNNING,

        /** Read and reconciled, and the two sides still do not agree. */
        UNEXPLAINED,

        /** There was something to look into and the pump would not answer. */
        READ_FAILED
    }

    /** What resolving a verdict came to, with what the bolus history said on the way. */
    data class Resolved(
        val resolution: Resolution,
        val reconciled: Atc3HistorySync.ReconcileResult? = null,
        val verdict: Atc3StateCheck.Verdict = Atc3StateCheck.Verdict.Unknown,
        /** False when the running temporary basal had to be read and did not answer. */
        val tbrRead: Boolean = true
    ) {

        /**
         * Whether the data the loop decided on stood: the two sides agreed, or were apart by the comparison's
         * own quantisation, or there was nothing to compare and the journals held nothing new, or the excess
         * is a bolus of ours still being delivered.
         */
        val stood: Boolean
            get() = resolution == Resolution.BALANCED || resolution == Resolution.ANCHORED ||
                resolution == Resolution.QUANTISED || resolution == Resolution.BOLUS_RUNNING
    }

    /** Compare the pump's count at the read with what the AAPS journal accounts for since the anchor, see [Atc3StateCheck]. */
    private suspend fun checkState(): Atc3StateCheck.Verdict {
        noBase = null
        val card = pumpState.statusCard ?: return Atc3StateCheck.Verdict.Unknown
        val status = card.status
        val readMs = card.readAtMs
        val counter = status.deliveredTodayUnits
        // Nothing is compared across a beginning. A start of AAPS is one by itself, the anchor not being on
        // disk; a refill and the pump back from a stop for want of an answer are the two the count does not show.
        val linkBack = linkKeeper.isHeldStopped
        if (linkBack || refilled) stateCheck.forget()
        val before = stateCheck.baseline()
        val base = stateCheck.baselineFor(readMs, counter)
            ?: return unknown(
                when {
                    linkBack                                     -> NoBase.LINK_BACK
                    refilled                                     -> NoBase.REFILL
                    before == null                               -> NoBase.FIRST
                    !Atc3DayClock.sameDay(before.readMs, readMs) -> NoBase.MIDNIGHT
                    readMs < before.readMs                       -> NoBase.TIME_BACK
                    else                                         -> NoBase.COUNT_RESET
                },
                counter, readMs
            )
        // A bolus begun before the anchor and learned after it is partly in both: the anchor is no good.
        if (atc3HistorySync.bolusStraddles(base.learnedAfterMs, base.readMs)) {
            stateCheck.forget()
            return unknown(NoBase.BOLUS_STRADDLES, counter, readMs)
        }
        val journal = aapsJournal.insulinBetween(base.readMs, readMs, atc3HistorySync.bolusesLearnedAfter(base.learnedAfterMs))
            ?: return unknown(NoBase.NO_PROFILE, counter, readMs).also {
                aapsLogger.error(LTag.PUMP, "ATC3: no profile is running, the journal cannot be summed")
            }
        val verdict = stateCheck.compare(base, readMs, counter, journal.totalUnits, checkTolerance())
        trace.event(
            Atc3TraceCat.HIST, "check",
            "state" to verdict.trace(),
            "s" to (readMs - base.readMs) / 1000,
            "pump" to counter - base.counterUnits,
            "aaps" to journal.totalUnits,
            "bolus" to journal.bolusUnits,
            "tbr" to journal.temporaryBasalUnits,
            "sched" to journal.scheduledUnits,
            "diff" to counter - base.counterUnits - journal.totalUnits,
            "tolerance" to checkTolerance(),
            "stuck" to stateCheck.stuckReads(),
            "snap" to status.snapshotTime,
            "counter" to counter
        )
        return verdict
    }

    /** The comparison has nothing to compare against, and says why. */
    private fun unknown(why: NoBase, counter: Double, readMs: Long): Atc3StateCheck.Verdict {
        noBase = why
        trace.event(Atc3TraceCat.HIST, "check", "state" to "unknown", "why" to why.trace, "counter" to counter, "at" to readMs)
        return Atc3StateCheck.Verdict.Unknown
    }

    /** After a beginning, the day's account on the screen is counted from this read too, or from the pump's midnight. */
    private fun noteBeginning(status: Atc3StatusV1) {
        val why = noBase?.takeIf { it.begins } ?: return
        refilled = false
        dayBase = if (why == NoBase.MIDNIGHT) null else DayBase(pumpState.statusReadAtMs, status.deliveredTodayUnits)
        trace.event(Atc3TraceCat.HIST, "beginning", "why" to why.trace, "at" to pumpState.statusReadAtMs, "counter" to status.deliveredTodayUnits)
    }

    /**
     * The day's sum of the AAPS journal beside the pump's count of the day, for the driver's screen;
     * counted from the last beginning, across which the two do not belong side by side. Decides nothing.
     */
    suspend fun noteDayAccount() {
        val card = pumpState.statusCard ?: return
        val readMs = card.readAtMs
        val counter = card.deliveredTodayUnits
        val base = dayBase?.takeIf { Atc3DayClock.sameDay(it.readMs, readMs) && it.readMs <= readMs && counter + COUNT_EPSILON >= it.counterUnits }
        val journal = aapsJournal.insulinOfDay(base?.readMs ?: Atc3DayClock.dayStartOf(readMs), readMs) ?: return
        val pumpUnits = counter - (base?.counterUnits ?: 0.0)
        pumpState.dayAccount = Atc3PumpState.DayAccount(journal.totalUnits, pumpUnits, readMs, base?.readMs)
        trace.event(
            Atc3TraceCat.HIST, "day",
            "aaps" to journal.totalUnits, "pump" to pumpUnits, "diff" to pumpUnits - journal.totalUnits,
            "bolus" to journal.bolusUnits, "tbr" to journal.temporaryBasalUnits, "sched" to journal.scheduledUnits,
            "since" to (base?.readMs ?: 0L)
        )
        rxBus.send(EventAtc3PumpDataChanged())
    }

    /**
     * How far the count and the journal may sit apart since the anchor: a minute's delivery at the
     * highest basal rate the pump allows, never under two pulses; the pump delivers a rate in steps.
     */
    private fun checkTolerance(): Double =
        maxOf(2 * Atc3Protocol.DOSE_SCALE, (pumpState.settings?.maxBasal ?: 0.0) / 60.0)

    /** This read is the anchor from now on, see [Atc3StateCheck.Baseline.learnedAfterMs]. */
    fun anchor(card: Atc3PumpState.StatusCard) {
        val now = dateUtil.now()
        stateCheck.accept(card.readAtMs, card.deliveredTodayUnits, now)
        atc3HistorySync.forgetBolusesLearnedUpTo(now)
    }

    /**
     * Bring AAPS to the pump's journals when its own journal does not account for the pump's count, or
     * when there is nothing to compare against: the bolus history, for a bolus given elsewhere, and the
     * temporary basal journal, for what ran between two looks. Then compare again: agreement is taken,
     * disagreement left standing for the next tick and said after [UNEXPLAINED_RUNS_TO_TELL]. A read that
     * fails leaves the disagreement standing.
     */
    private suspend fun resolveState(verdict: Atc3StateCheck.Verdict): Resolved {
        val card = pumpState.statusCard
        val status = card?.status
        if (verdict is Atc3StateCheck.Verdict.Matches && status != null) {
            lastBalancedAtMs = dateUtil.now()
            rxBus.send(EventDismissNotification(Notification.PUMP_SYNC_ERROR))
            return Resolved(Resolution.BALANCED)
        }

        val startedAt = trace.now()
        val history = readBolusHistory()
        if (history == null) {
            trace.event(Atc3TraceCat.HIST, "resolve", "read" to false, "ms" to trace.since(startedAt))
            return Resolved(Resolution.READ_FAILED)
        }
        val reconciled = atc3HistorySync.reconcileBoluses(history.records, history.recordCount)

        val journal = atc3Manager.readTbrHistory()
        if (journal == null) {
            trace.event(Atc3TraceCat.HIST, "resolve", "read" to false, "why" to "no_journal", "ms" to trace.since(startedAt))
            return Resolved(Resolution.READ_FAILED, reconciled)
        }
        val changed = atc3HistorySync.reconcileTbrHistory(journal)

        // The running temporary basal's own account, for the trace only.
        if (card?.tbrActive == true) {
            runningTbrThisTick()?.let {
                trace.event(Atc3TraceCat.TBR, "running", "since" to it.startTimestamp, "units" to it.deliveredUnits, "asked" to it.amountAsked)
            }
        }

        val resolution: Resolution
        var difference = (verdict as? Atc3StateCheck.Verdict.Differs)?.units ?: 0.0
        if (card == null || status == null) {
            resolution = Resolution.READ_FAILED
        } else if (verdict is Atc3StateCheck.Verdict.Unknown) {
            // A stop for want of an answer is closed first: until it is written there is no anchor to take.
            if (linkKeeper.closeStop(card)) {
                anchor(card)
                noteBeginning(status)
                lastBalancedAtMs = dateUtil.now()
                resolution = Resolution.ANCHORED
            } else {
                resolution = Resolution.READ_FAILED
            }
        } else {
            // The comparison again, with the rows as the journals have now made them.
            var again = checkState()
            difference = (again as? Atc3StateCheck.Verdict.Differs)?.units ?: 0.0
            // A shortfall with a stop the ticks missed: the stop is recorded for the length the shortfall
            // shows, its own row at the scheduled rate or a cut in the running temporary basal, and compared again.
            val unseen = unseenStopMs
            if (again is Atc3StateCheck.Verdict.Differs && again.units < 0 && unseen != null) {
                // Inside the open temporary basal when the stop's minute is no earlier than the pump's stamp of it.
                val open = atc3HistorySync.openTbr()?.takeIf { !it.suspension && it.rate > 0.0 && unseen >= it.anchorMs }
                val scheduled = if (open == null) aapsJournal.scheduledRateAt(unseen) else null
                val rate = open?.rate ?: scheduled
                if (rate != null && rate > 0.0) {
                    val lengthMs = (-again.units / rate * 3_600_000.0).toLong()
                        .coerceIn(60_000L, maxOf(60_000L, status.snapshotTime - unseen))
                    val recorded = if (open != null) {
                        atc3HistorySync.recordDerivedStopInTbr(unseen, lengthMs, journal)
                    } else {
                        atc3HistorySync.recordDerivedStop(unseen, lengthMs)
                        true
                    }
                    if (recorded) {
                        unseenStopMs = null
                        again = checkState()
                        difference = (again as? Atc3StateCheck.Verdict.Differs)?.units ?: 0.0
                    }
                }
            }
            if (again is Atc3StateCheck.Verdict.Matches) {
                anchor(card)
                lastBalancedAtMs = dateUtil.now()
                rxBus.send(EventDismissNotification(Notification.PUMP_SYNC_ERROR))
                resolution = Resolution.EXPLAINED
            } else if (again is Atc3StateCheck.Verdict.Differs && again.units > 0 &&
                again.units <= atc3HistorySync.pendingBolusUnits() + checkTolerance()
            ) {
                // A count above the journal while our bolus runs is that bolus, see [Resolution.BOLUS_RUNNING].
                trace.event(Atc3TraceCat.HIST, "bolus_running", "units" to again.units, "pending" to atc3HistorySync.pendingBolusUnits())
                resolution = Resolution.BOLUS_RUNNING
            } else if (again is Atc3StateCheck.Verdict.Differs && abs(again.units) <= checkTolerance() + QUANTISATION_SLACK_UNITS) {
                anchor(card)
                lastBalancedAtMs = dateUtil.now()
                rxBus.send(EventDismissNotification(Notification.PUMP_SYNC_ERROR))
                trace.event(Atc3TraceCat.HIST, "accepted_quantised", "units" to again.units)
                // Where the journals changed what AAPS held, the loop decided without it: refused as explained.
                resolution = if (reconciled.importedUnits > 0.0 || changed > 0) Resolution.EXPLAINED else Resolution.QUANTISED
            } else {
                // The exact basal mode is no exception here, see [Atc3BasalPeriodKeeper].
                val runs = stateCheck.unexplained()
                aapsLogger.warn(
                    LTag.PUMP,
                    "ATC3: the pump counted ${"%.3f".format(abs(difference))} U ${if (difference < 0) "less" else "more"} than the AAPS journal " +
                        "accounts for and the journals do not explain it, $runs time(s) in a row"
                )
                if (runs >= UNEXPLAINED_RUNS_TO_TELL) {
                    // Said as more or as less, never as a signed amount.
                    uiInteraction.addNotification(
                        Notification.PUMP_SYNC_ERROR,
                        if (difference < 0) rh.gs(R.string.atc3_journal_shortfall, -difference)
                        else rh.gs(R.string.atc3_journal_unexplained, difference),
                        Notification.URGENT
                    )
                    // Said and let go: the next comparison starts afresh rather than failing until midnight.
                    anchor(card)
                    trace.event(Atc3TraceCat.HIST, "accepted_unexplained", "units" to difference, "runs" to runs)
                }
                resolution = Resolution.UNEXPLAINED
            }
        }
        trace.event(
            Atc3TraceCat.HIST, "resolve",
            "state" to verdict.trace(),
            "units" to difference,
            "boluses" to reconciled.importedUnits,
            "tbr" to changed,
            "outcome" to resolution.name.lowercase(),
            "runs" to stateCheck.unexplainedRuns(),
            "ms" to trace.since(startedAt)
        )
        return Resolved(resolution, reconciled)
    }

    /** Say it when the pump owes insulin and its count has not moved: first told, the second tick in a row stops the loop. */
    fun reportDeliveryStopped() {
        if (!stateCheck.notDelivering()) {
            deliveryStoppedReports = 0
            return
        }
        deliveryStoppedReports++
        aapsLogger.error(
            LTag.PUMP,
            "ATC3: the pump owes insulin and the reservoir has not moved, reported " +
                "$deliveryStoppedReports time(s)"
        )
        trace.event(Atc3TraceCat.DRV, "no_delivery", "times" to deliveryStoppedReports)
        uiInteraction.addNotification(
            Notification.PUMP_ERROR,
            rh.gs(R.string.atc3_no_delivery),
            Notification.URGENT
        )
    }

    /**
     * Make sure the pump's boluses were read recently before delivery is changed: the queue connects
     * before it takes a command, so AAPS never changes delivery on knowledge older than
     * [Atc3Const.HISTORY_FRESH_MS], at one read per connection. The tick and the bolus read anyway.
     */
    suspend fun ensureHistoryFresh() {
        val now = dateUtil.now()
        // The count agreeing with the journal is the same proof, had for free at the last tick.
        if (lastBalancedAtMs != 0L && now - lastBalancedAtMs < Atc3Const.HISTORY_FRESH_MS) {
            trace.event(Atc3TraceCat.DRV, "barrier", "read" to false, "why" to "counter")
            return
        }
        if (atc3HistorySync.historyFresh(now)) {
            trace.event(Atc3TraceCat.DRV, "barrier", "read" to false, "why" to "fresh")
            return
        }
        val startedAt = trace.now()
        val history = readBolusHistory()
        history?.let { atc3HistorySync.reconcileBoluses(it.records, it.recordCount) }
        trace.event(
            Atc3TraceCat.DRV, "barrier",
            "read" to true,
            "ok" to (history != null),
            "ms" to trace.since(startedAt)
        )
    }

    /** Read the pump's boluses, the full history only when records have aged out of the periodic answer, see [app.aaps.pump.atc3.history.Atc3BolusReconciler.recordsMissing]. */
    fun readBolusHistory(): Atc3BolusHistory? {
        val latest = atc3Manager.readBolusHistory() ?: return null
        if (!atc3HistorySync.recordsMissing(latest)) return latest
        trace.event(Atc3TraceCat.DRV, "full_history", "held" to latest.recordCount, "sent" to latest.records.size)
        aapsLogger.debug(
            LTag.PUMP,
            "ATC3: the pump holds ${latest.recordCount} records but sent ${latest.records.size}, reading the full history"
        )
        // What the search returned is the recent part, where a bolus of ours would be.
        return atc3Manager.readFullBolusHistory() ?: latest
    }

    /** A refill was seen: the next comparison starts anew from the read that follows it. */
    fun noteRefill() {
        refilled = true
    }

    /**
     * Compare the status just read with the AAPS journal, write what the pump runs into AAPS, and
     * when the two disagree bring AAPS to the pump's journals.
     *
     * @param statusRead false when the status could not be read: the journals are read all the same
     */
    suspend fun reconcile(statusRead: Boolean = true): Resolved {
        activeTbrReadThisTick = false
        activeTbrThisTick = null
        // The verdict before the record: what the pump did on its own since the anchor is measured
        // against the journal the loop decided on, and only then written into it.
        val verdict = if (statusRead) checkState() else Atc3StateCheck.Verdict.Unknown
        val tbrRead = if (statusRead) syncTbrFromStatus() else true
        return resolveState(verdict).copy(verdict = verdict, tbrRead = tbrRead)
    }

    /** Write what the status just read says the pump runs into AAPS, outside a comparison. */
    suspend fun recordRunningState() {
        activeTbrReadThisTick = false
        activeTbrThisTick = null
        syncTbrFromStatus()
    }

    private companion object {

        /** Comparisons left unexplained by the journals, in a row, before the user is told. */
        const val UNEXPLAINED_RUNS_TO_TELL = 2

        /** How far past the tolerance the two sides may sit and still be the comparison's own quantisation, see [Resolution.QUANTISED]. */
        const val QUANTISATION_SLACK_UNITS = 2 * Atc3Protocol.DOSE_SCALE + 1e-9

        /** Ticks finding the pump not delivering before the loop is stopped as well as the user told. */
        const val NO_DELIVERY_REPORTS_BEFORE_STOP = 2

        /** Under any step of the pump: a count is below another only when it is so by more than this. */
        const val COUNT_EPSILON = 1e-6
    }
}
