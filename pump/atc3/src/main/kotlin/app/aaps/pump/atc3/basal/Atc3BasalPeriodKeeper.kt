package app.aaps.pump.atc3.basal

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.check.Atc3AapsJournal
import app.aaps.pump.atc3.check.Atc3Reconciliation
import app.aaps.pump.atc3.clock.Atc3DayClock
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.manager.Atc3Manager
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.store.Atc3Store
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the window of the basal account, see [Atc3BasalPeriod]: begins it where there is nothing to
 * close from, closes it at the half hour by the pump's count, and hands what it found to
 * [Atc3BasalSpread]. The window is kept on disk, see [Atc3Store]. Asked twice a tick: before the
 * comparison, for a beginning, and after it, for a close.
 */
@Singleton
class Atc3BasalPeriodKeeper @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val store: Atc3Store,
    private val dateUtil: DateUtil,
    private val commandQueue: CommandQueue,
    private val pumpState: Atc3PumpState,
    private val atc3Manager: Atc3Manager,
    private val atc3HistorySync: Atc3HistorySync,
    private val aapsJournal: Atc3AapsJournal,
    private val reconciliation: Atc3Reconciliation,
    private val trace: Atc3Trace,
    private val spread: Atc3BasalSpread
) {

    /** The read asked for at every half hour, see [watch]. */
    private var halfHourWatch: Job? = null

    /** True from a close until a tick has read the bolus journal after it: a bolus that ran across the end has its record only then. */
    private var journalWantedAfterClose = false

    /** True from a refill until the window has begun anew: the count may hold the priming. */
    private var refilled = false

    /** Ask for a status a little past every half hour: the read a window is closed at. */
    fun watch(scope: CoroutineScope, enabled: () -> Boolean) {
        if (halfHourWatch?.isActive == true) return
        halfHourWatch = scope.launch {
            while (isActive) {
                delay(Atc3BasalPeriod.msToNextRead(dateUtil.now()))
                if (enabled()) commandQueue.readStatus(rh.gs(R.string.atc3_half_hour_read), null)
            }
        }
    }

    fun stop() {
        halfHourWatch?.cancel()
        halfHourWatch = null
    }

    /** A refill was seen: the window begins anew at the next read. */
    fun noteRefill() {
        refilled = true
    }

    /**
     * Begin the window at this read when there is nothing to close from: the first read, a refill, a
     * count begun anew within the day. The rows stay as they are. Asked before the comparison, which
     * counts from the window's start.
     *
     * @return true when the window began at this read: the bolus journal is then read, as the count can say nothing yet
     */
    fun beginIfNeeded(card: Atc3PumpState.StatusCard): Boolean {
        val state = store.state.basalPeriod
        val start = state.start
        val why = when {
            start == null -> "first"
            refilled      -> "refill"

            Atc3DayClock.sameDay(start.readMs, card.readAtMs) && card.deliveredTodayUnits + Atc3Protocol.COUNT_EPSILON < start.counterUnits ->
                "count_reset"

            else          -> return false
        }
        refilled = false
        begin(card, state.closed, why)
        return true
    }

    /**
     * Close the window that has passed by the pump's count, and the one closed before it once more when
     * a bolus ran across its end, see [Atc3BasalPeriod].
     *
     * @param journalRead true when this tick already took in the bolus journal
     */
    suspend fun closeIfDue(journalRead: Boolean) {
        val card = pumpState.statusCard ?: return
        val readMs = card.readAtMs
        val state = store.state.basalPeriod
        val start = state.start ?: return
        val due = Atc3BasalPeriod.isDue(start.readMs, readMs)
        // Boluses first: at the boundary, and once after a close.
        var takenIn = journalRead
        if ((due || journalWantedAfterClose) && !takenIn) {
            if (reconciliation.readBoluses() == null) {
                trace.event(Atc3TraceCat.HIST, "period", "state" to "postponed", "why" to "no_journal")
                return
            }
            takenIn = true
        }
        if (takenIn) {
            journalWantedAfterClose = false
            val closed = state.closed
            // A bolus learned since the close that began by its end: the window is closed again, up to this read.
            if (closed != null && atc3HistorySync.bolusStraddles(closed.closedAtMs, closed.endReadMs)) {
                return closePeriod(card, closed.start, again = true)
            }
            // A bolus learned since the window began that had begun by its first read is partly in the
            // count there: the window begins anew at this read, its figure being no good.
            if (atc3HistorySync.bolusStraddles(start.learnedAfterMs, start.readMs)) return begin(card, closed, "bolus_straddles")
        }
        if (due) closePeriod(card, start, again = false)
    }

    /**
     * Close the window from [from] to this read: its boluses out of the count, the rest is its basal.
     * The figures go to the trace and to [Atc3BasalSpread]; the rows are not touched here.
     *
     * @param again true when the window was closed before and is closed again: closed at this read whatever it is
     */
    private suspend fun closePeriod(card: Atc3PumpState.StatusCard, from: Atc3BasalPeriod.Mark, again: Boolean) {
        val readMs = card.readAtMs
        val counter = card.deliveredTodayUnits
        val bolus = atc3HistorySync.bolusesLearnedAfter(from.learnedAfterMs)
        val step = Atc3BasalPeriod.step(
            from, readMs, counter, bolus,
            settled = !atc3HistorySync.hasExpectedBolus(),
            dayTotalUnits = if (Atc3BasalPeriod.dayChanged(from.readMs, readMs)) dayTotalOf(from.readMs) else null,
            force = again
        )
        when (step) {
            is Atc3BasalPeriod.Step.Close    -> {
                // What the rows order over the window, basal only; null while no profile runs.
                val ordered = aapsJournal.insulinBetween(from.readMs, readMs, bolus)?.let { it.temporaryBasalUnits + it.scheduledUnits }
                val window = Atc3BasalPeriod.Window(from.readMs, readMs, step.countedUnits, bolus, step.units, ordered, again)
                val now = dateUtil.now()
                store.update {
                    it.copy(basalPeriod = Atc3BasalPeriod.State(Atc3BasalPeriod.Mark(readMs, counter, now), Atc3BasalPeriod.Closed(from, readMs, now)))
                }
                atc3HistorySync.pruneLearned()
                journalWantedAfterClose = true
                aapsLogger.debug(
                    LTag.PUMP,
                    "ATC3: window from ${from.readMs} to $readMs: ${step.units} U of basal by the pump's count, ${ordered ?: "?"} U ordered"
                )
                trace.event(
                    Atc3TraceCat.HIST, "window",
                    "from" to from.readMs, "to" to readMs, "s" to (readMs - from.readMs) / 1000, "again" to again,
                    "pump" to step.countedUnits, "bolus" to bolus, "basal" to step.units,
                    "ordered" to ordered, "diff" to window.differenceUnits
                )
                spread.settle(window)
            }

            is Atc3BasalPeriod.Step.Postpone -> {
                aapsLogger.debug(LTag.PUMP, "ATC3: the window is not closed at this read, ${step.why}; the next read asks again")
                trace.event(Atc3TraceCat.HIST, "period", "state" to "postponed", "why" to step.why, "from" to from.readMs, "bolus" to bolus)
            }

            is Atc3BasalPeriod.Step.Restart  -> begin(card, null, step.why)

            Atc3BasalPeriod.Step.Wait        -> Unit
        }
    }

    /** What the pump's journal of daily totals holds for the day of [dayMs], or null when it could not be had. */
    fun dayTotalOf(dayMs: Long): Double? =
        atc3Manager.readDailyStats()?.firstOrNull { !it.isEmpty && it.isDatePlausible && it.isSameDayAs(dayMs) }?.totalUnits

    /** A window begins at the read just made without the one before it being closed: its rows stay as they are. */
    private fun begin(card: Atc3PumpState.StatusCard, closed: Atc3BasalPeriod.Closed?, why: String) {
        val readMs = card.readAtMs
        val period = Atc3BasalPeriod.State(Atc3BasalPeriod.Mark(readMs, card.deliveredTodayUnits, dateUtil.now()), closed)
        store.update { it.copy(basalPeriod = period) }
        trace.event(Atc3TraceCat.HIST, "period", "state" to "begun", "why" to why, "at" to readMs, "counter" to card.deliveredTodayUnits)
    }
}
