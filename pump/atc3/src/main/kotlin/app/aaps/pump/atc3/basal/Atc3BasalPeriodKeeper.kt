package app.aaps.pump.atc3.basal

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.check.Atc3AapsJournal
import app.aaps.pump.atc3.clock.Atc3DayClock
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.pump.atc3.keys.Atc3StringNonKey
import app.aaps.pump.atc3.manager.Atc3Manager
import app.aaps.pump.atc3.protocol.Atc3BolusHistory
import app.aaps.pump.atc3.state.Atc3PumpState
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
 * The exact basal mode: the half hour that has passed, closed by the pump's count, see
 * [Atc3BasalPeriod]. One of two ways of laying the basal out in AAPS; what the pump delivered is the
 * same either way. Asked once per tick, after the comparison; the period is kept on disk, see
 * [Atc3StringNonKey.BasalPeriod].
 */
@Singleton
class Atc3BasalPeriodKeeper @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val commandQueue: CommandQueue,
    private val pumpState: Atc3PumpState,
    private val atc3Manager: Atc3Manager,
    private val atc3HistorySync: Atc3HistorySync,
    private val aapsJournal: Atc3AapsJournal,
    private val trace: Atc3Trace,
    private val basalFact: Atc3BasalFact
) {

    /** The read asked for at every half hour in the exact basal mode, see [watch]. */
    private var halfHourWatch: Job? = null

    /** Ask for a status a little past every half hour, the read a period is closed at; only while the mode is on. */
    fun watch(scope: CoroutineScope, enabled: () -> Boolean) {
        if (halfHourWatch?.isActive == true) return
        halfHourWatch = scope.launch {
            while (isActive) {
                delay(Atc3BasalPeriod.msToNextRead(dateUtil.now()))
                if (enabled() && preferences.get(Atc3BooleanKey.ExactBasal))
                    commandQueue.readStatus(rh.gs(R.string.atc3_half_hour_read), null)
            }
        }
    }

    fun stop() {
        halfHourWatch?.cancel()
        halfHourWatch = null
    }

    /**
     * Close the half hour that has passed by the pump's count, and look once more at the one closed
     * before it, see [Atc3BasalPeriod].
     *
     * @param journalRead true when this tick already took in the bolus journal
     * @param readHistory the driver's read of the bolus history
     * @param anchor takes this read as the comparison's anchor: once the period is closed, the journal is the pump's count up to it
     */
    suspend fun closeIfDue(journalRead: Boolean, readHistory: () -> Atc3BolusHistory?, anchor: (Atc3PumpState.StatusCard) -> Unit) {
        val stored: String = (preferences.get(Atc3StringNonKey.BasalPeriod) as String?).orEmpty()
        if (!preferences.get(Atc3BooleanKey.ExactBasal)) {
            if (stored.isNotEmpty()) preferences.put(Atc3StringNonKey.BasalPeriod, "")
            return
        }
        val card = pumpState.statusCard ?: return
        val status = card.status
        val readMs = card.readAtMs
        val state = Atc3BasalPeriod.State.decode(stored)
        // The mode has just been switched on: the first period begins at this read.
        val start = state.start ?: return begin(card, state.closed, "mode_on")
        val closed = state.closed
        // The count began anew within the day: there is no count to close from, the rows stay.
        if (Atc3DayClock.sameDay(start.readMs, readMs) && status.deliveredTodayUnits + COUNT_EPSILON < start.counterUnits)
            return begin(card, closed, "count_reset")
        // A read can be the cycle after a close, or the end of the period under way; most are neither.
        val lookAgain = closed != null && !closed.checked && readMs > closed.endReadMs
        val due = Atc3BasalPeriod.isDue(start.readMs, readMs)
        if (!lookAgain && !due) return

        // Either way, boluses first.
        if (!journalRead) {
            val history = readHistory()
            if (history == null) {
                trace.event(Atc3TraceCat.HIST, "period", "state" to "postponed", "why" to "no_journal")
                return
            }
            atc3HistorySync.reconcileBoluses(history.records, history.recordCount)
        }
        if (lookAgain) {
            // A bolus that ran across its end: the period is closed again, up to this read.
            if (atc3HistorySync.bolusStraddles(closed.closedAtMs, closed.endReadMs)) return closePeriod(card, closed.start, closed, anchor)
            preferences.put(Atc3StringNonKey.BasalPeriod, state.copy(closed = closed.copy(checked = true)).encode())
        }
        if (due) closePeriod(card, start, closed, anchor)
    }

    /**
     * Close the period from [from] to this read: its boluses out of the count, the rest written as basal.
     *
     * @param before the period closed last; when it began at [from] too, it is being closed again
     */
    private suspend fun closePeriod(card: Atc3PumpState.StatusCard, from: Atc3BasalPeriod.Mark, before: Atc3BasalPeriod.Closed?, anchor: (Atc3PumpState.StatusCard) -> Unit) {
        val readMs = card.readAtMs
        val counter = card.deliveredTodayUnits
        val again = before?.takeIf { it.start == from }
        val bolus = atc3HistorySync.bolusesLearnedAfter(from.learnedAfterMs)
        val step = Atc3BasalPeriod.step(
            from, readMs, counter, bolus,
            settled = !atc3HistorySync.hasPendingBolus(),
            dayTotalUnits = if (Atc3BasalPeriod.dayChanged(from.readMs, readMs)) dayTotalOf(from.readMs) else null,
            force = again != null
        )
        when (step) {
            is Atc3BasalPeriod.Step.Close    -> {
                // The length the running temporary basal was started for: its row goes on to the pump's end.
                val pumpTbrDurationMs =
                    if (card.tbrActive && !card.notDelivering) card.tbrDurationMinutes.takeIf { it > 0 }?.let { it * 60_000L } else null
                val rows = aapsJournal.rowsBetween(from.readMs, readMs)
                val writes = again?.writes ?: 0
                if (!basalFact.writeBasalFact(from.readMs, readMs, step.units, rows, pumpTbrDurationMs, write = writes)) return
                val now = dateUtil.now()
                preferences.put(
                    Atc3StringNonKey.BasalPeriod,
                    Atc3BasalPeriod.State(
                        Atc3BasalPeriod.Mark(readMs, counter, now),
                        Atc3BasalPeriod.Closed(from, readMs, now, writes + 1, checked = false)
                    ).encode()
                )
                trace.event(
                    Atc3TraceCat.HIST, "period", "state" to "closed", "again" to (again != null),
                    "from" to from.readMs, "to" to readMs, "bolus" to bolus, "basal" to step.units
                )
                // The journal is the pump's count up to here; the comparison goes on from this read.
                anchor(card)
            }

            is Atc3BasalPeriod.Step.Postpone -> {
                aapsLogger.debug(LTag.PUMP, "ATC3: the half hour is not closed at this read, ${step.why}; the next read asks again")
                trace.event(Atc3TraceCat.HIST, "period", "state" to "postponed", "why" to step.why, "from" to from.readMs, "bolus" to bolus)
            }

            is Atc3BasalPeriod.Step.Restart  -> begin(card, before?.copy(checked = true), step.why)

            Atc3BasalPeriod.Step.Wait        -> Unit
        }
    }

    /** What the pump's journal of daily totals holds for the day of [dayMs], or null when it could not be had. */
    fun dayTotalOf(dayMs: Long): Double? =
        atc3Manager.readDailyStats()?.firstOrNull { !it.isEmpty && it.isDatePlausible && it.isSameDayAs(dayMs) }?.totalUnits

    /** A period begins at the read just made without the one before it being closed: its rows stay as they are. */
    fun begin(card: Atc3PumpState.StatusCard, closed: Atc3BasalPeriod.Closed?, why: String) {
        val readMs = card.readAtMs
        preferences.put(
            Atc3StringNonKey.BasalPeriod,
            Atc3BasalPeriod.State(Atc3BasalPeriod.Mark(readMs, card.deliveredTodayUnits, dateUtil.now()), closed).encode()
        )
        trace.event(Atc3TraceCat.HIST, "period", "state" to "begun", "why" to why, "at" to readMs, "counter" to card.deliveredTodayUnits)
    }

    /**
     * The pump is back after being held stopped for want of an answer: in the exact basal mode the
     * half hour under way begins at this read, what lies before it having been written.
     */
    fun beginAfterLinkBack(card: Atc3PumpState.StatusCard) {
        if (!preferences.get(Atc3BooleanKey.ExactBasal)) return
        val state = Atc3BasalPeriod.State.decode((preferences.get(Atc3StringNonKey.BasalPeriod) as String?).orEmpty())
        begin(card, state.closed?.copy(checked = true), "link_back")
    }

    private companion object {

        /** Under any step of the pump: a count is below another only when it is so by more than this. */
        const val COUNT_EPSILON = 1e-6
    }
}
