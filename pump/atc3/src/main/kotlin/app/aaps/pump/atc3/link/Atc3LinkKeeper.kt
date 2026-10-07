package app.aaps.pump.atc3.link

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.basal.Atc3BasalFact
import app.aaps.pump.atc3.basal.Atc3BasalPeriodKeeper
import app.aaps.pump.atc3.check.Atc3AapsJournal
import app.aaps.pump.atc3.clock.Atc3DayClock
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.keys.Atc3StringNonKey
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A pump that stopped answering, by the rules of [Atc3LinkWatch]: looks once a minute, tells the
 * user, holds the pump stopped, and when it answers again writes the silence as the pump accounts
 * for it. Nothing is asked of the pump from here: a status is asked of the queue.
 */
@Singleton
class Atc3LinkKeeper @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val commandQueue: CommandQueue,
    private val rxBus: RxBus,
    private val uiInteraction: UiInteraction,
    private val pumpState: Atc3PumpState,
    private val atc3Connection: Atc3Connection,
    private val atc3HistorySync: Atc3HistorySync,
    private val aapsJournal: Atc3AapsJournal,
    private val basalPeriods: Atc3BasalPeriodKeeper,
    private val trace: Atc3Trace,
    private val basalFact: Atc3BasalFact
) {

    /** When this process began watching: silence is counted from here until the first answer. */
    private val linkWatchedSinceMs = dateUtil.now()

    /** The look, once a minute, for a pump that has stopped answering, see [watch]. */
    private var linkWatch: Job? = null

    /** How many times the user has been told of the silence under way. */
    private var linkAlarmsSaid = 0

    /** Keeps the minutely look and the read that ends a silence from writing the stop's row at once. */
    private val linkLock = Mutex()

    /** The stop the pump is held in, as on disk, see [linkStop]. */
    @Volatile private var linkStopHeld: Atc3LinkWatch.Stop? = null
    @Volatile private var linkStopLoaded = false

    /** Whether the driver is the active pump; set by [watch]. */
    private var enabled: () -> Boolean = { true }

    /** True while the pump is held stopped for want of an answer: the loop does not run. */
    val isHeldStopped: Boolean get() = linkStop() != null

    /** The pump answered: the moment and count a stop would be counted from, kept on disk. */
    fun noteAnswer() {
        val card = pumpState.statusCard ?: return
        preferences.put(Atc3StringNonKey.LastAnswer, Atc3LinkWatch.Stop(card.readAtMs, card.deliveredTodayUnits).encode())
    }

    /** Look once a minute for a pump that stopped answering, see [Atc3LinkWatch]. */
    fun watch(scope: CoroutineScope, enabled: () -> Boolean) {
        this.enabled = enabled
        if (linkWatch?.isActive == true) return
        linkWatch = scope.launch {
            while (isActive) {
                delay(LINK_WATCH_MS)
                try {
                    checkLink()
                } catch (e: Exception) {
                    if (!isActive) throw e
                    aapsLogger.error(LTag.PUMP, "ATC3: the look for the pump's answer failed", e)
                }
            }
        }
    }

    fun stop() {
        linkWatch?.cancel()
        linkWatch = null
    }

    /** One look of [watch]; internal for tests. */
    internal suspend fun checkLink() {
        if (!enabled() || preferences.get(Atc3StringKey.Atc3SerialNumber).isBlank()) return
        var silent = false
        linkLock.withLock {
            val now = dateUtil.now()
            val silence = now - (pumpState.lastConnection.takeIf { it > 0L } ?: linkWatchedSinceMs)
            val due = Atc3LinkWatch.alarmsDue(silence)
            if (due == 0) {
                // The pump answers. A stop still open is closed by the read that found it, not here.
                if (linkAlarmsSaid > 0) {
                    linkAlarmsSaid = 0
                    rxBus.send(EventDismissNotification(Notification.PUMP_UNREACHABLE))
                }
                return@withLock
            }
            silent = true
            var stop = linkStop()
            if (stop == null && Atc3LinkWatch.stopDue(silence)) stop = holdLinkStop(now)
            if (due <= linkAlarmsSaid) return@withLock
            linkAlarmsSaid = due
            val minutes = (silence / 60_000L).toInt()
            aapsLogger.error(LTag.PUMP, "ATC3: no answer from the pump for $minutes min" + if (stop != null) ", it is held to be stopped" else "")
            trace.event(Atc3TraceCat.DRV, "link_silent", "min" to minutes, "times" to due, "stopped" to (stop != null))
            uiInteraction.addNotificationWithSound(
                Notification.PUMP_UNREACHABLE,
                // A silence of the driver's own making is called what it is.
                if (!atc3Connection.isPasswordEntered) rh.gs(R.string.atc3_password_missing)
                else rh.gs(if (stop != null) R.string.atc3_link_stopped else R.string.atc3_link_silent, minutes),
                Notification.URGENT,
                app.aaps.core.ui.R.raw.alarm
            )
            // No basal from the last answer on, drawn as far as the next telling.
            stop?.let { atc3HistorySync.recordLinkStop(it.fromReadMs, now - it.fromReadMs + Atc3LinkWatch.ALARM_AFTER_MS) }
        }
        if (silent) commandQueue.readStatus(rh.gs(R.string.atc3_link_wait), null)
    }

    /** The stop the pump is held in for want of an answer, or null when there is none. */
    private fun linkStop(): Atc3LinkWatch.Stop? {
        if (!linkStopLoaded) {
            linkStopHeld = Atc3LinkWatch.Stop.decode((preferences.get(Atc3StringNonKey.LinkStop) as String?).orEmpty())
            linkStopLoaded = true
        }
        return linkStopHeld
    }

    /**
     * Half an hour of silence: the pump is held stopped from its last answer on disk. A pump never
     * heard from, or last heard from over a day ago, or heard from just now, is held to nothing.
     */
    private fun holdLinkStop(now: Long): Atc3LinkWatch.Stop? {
        val stop = Atc3LinkWatch.Stop.decode((preferences.get(Atc3StringNonKey.LastAnswer) as String?).orEmpty()) ?: return null
        if (now - stop.fromReadMs > DAY_MS || !Atc3LinkWatch.stopDue(now - stop.fromReadMs)) return null
        preferences.put(Atc3StringNonKey.LinkStop, stop.encode())
        linkStopHeld = stop
        linkStopLoaded = true
        trace.event(Atc3TraceCat.DRV, "link_stop", "from" to stop.fromReadMs, "counter" to stop.fromCounterUnits)
        return stop
    }

    /**
     * The pump answered after being held stopped: the silence is written as the pump accounts for it,
     * see [Atc3LinkWatch.account], with the bolus journal already read, and the stop is over.
     *
     * @return false when no row could be written at this read: the stop stays and the next read asks again
     */
    suspend fun closeStop(card: Atc3PumpState.StatusCard): Boolean = linkLock.withLock {
        val stop = linkStop() ?: return@withLock true
        val readMs = card.readAtMs
        if (readMs > stop.fromReadMs) {
            val midnight = Atc3DayClock.dayStartOf(readMs)
            val dayBefore = stop.fromReadMs < midnight && Atc3DayClock.sameDay(stop.fromReadMs + DAY_MS, readMs)
            val account = Atc3LinkWatch.account(
                stop, readMs, card.deliveredTodayUnits,
                bolusUnits = atc3HistorySync.bolusesLearnedAfter(stop.fromReadMs),
                midnightMs = midnight,
                dayTotalUnits = if (dayBefore) basalPeriods.dayTotalOf(stop.fromReadMs) else null,
                bolusSinceMidnightUnits = if (stop.fromReadMs < midnight) aapsJournal.bolusesBetween(midnight, readMs) else 0.0
            )
            // The stop's own row ends at this read, and the pump's account takes its place.
            atc3HistorySync.recordLinkStop(stop.fromReadMs, readMs - stop.fromReadMs)
            val pumpTbrDurationMs =
                if (card.tbrActive && !card.notDelivering) card.tbrDurationMinutes.takeIf { it > 0 }?.let { it * 60_000L } else null
            for (stretch in account.stretches) {
                val rows = aapsJournal.rowsBetween(stretch.fromMs, stretch.toMs)
                if (!basalFact.writeBasalFact(stretch.fromMs, stretch.toMs, stretch.units, rows, pumpTbrDurationMs)) {
                    trace.event(Atc3TraceCat.HIST, "link_back", "ok" to false, "from" to stretch.fromMs, "to" to stretch.toMs)
                    return@withLock false
                }
            }
            val minutes = ((readMs - stop.fromReadMs) / 60_000L).toInt()
            val units = account.stretches.sumOf { it.units }
            aapsLogger.warn(LTag.PUMP, "ATC3: the pump is back after $minutes min, ${account.outcome}, $units U of basal over that time")
            trace.event(
                Atc3TraceCat.HIST, "link_back",
                "ok" to true, "from" to stop.fromReadMs, "to" to readMs, "outcome" to account.outcome.name.lowercase(), "basal" to units
            )
            when (account.outcome) {
                Atc3LinkWatch.Outcome.COUNTED                 -> Unit

                Atc3LinkWatch.Outcome.COUNT_RESET             ->
                    uiInteraction.addNotification(Notification.WRONG_PUMP_DATA, rh.gs(R.string.atc3_link_back_reset, minutes), Notification.NORMAL)

                Atc3LinkWatch.Outcome.BEFORE_MIDNIGHT_UNKNOWN ->
                    uiInteraction.addNotification(Notification.WRONG_PUMP_DATA, rh.gs(R.string.atc3_link_back_midnight, minutes), Notification.URGENT)
            }
        }
        preferences.put(Atc3StringNonKey.LinkStop, "")
        linkStopHeld = null
        linkAlarmsSaid = 0
        rxBus.send(EventDismissNotification(Notification.PUMP_UNREACHABLE))
        // In the exact basal mode the half hour under way begins at this read.
        basalPeriods.beginAfterLinkBack(card)
        true
    }

    private companion object {

        const val LINK_WATCH_MS = 60_000L
        const val DAY_MS = 24 * 60 * 60_000L
    }
}
