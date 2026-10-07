package app.aaps.pump.atc3.clock

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
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.manager.Atc3Manager
import app.aaps.pump.atc3.protocol.Atc3BolusHistory
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * The pump's clock, kept on the phone's: the pump has a wall clock and nothing moves it but this.
 * Against the phone's, three bands, see [Atc3Const.CLOCK_QUIET_CORRECTION_MS] and
 * [Atc3Const.CLOCK_MAX_CORRECTION_MS]: put right quietly on the evidence of [Atc3ClockWatch], put
 * right and said so, or left alone with the loop stopped. Asked last in a tick: a clock write moves
 * where later records land.
 */
@Singleton
class Atc3ClockKeeper @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val rxBus: RxBus,
    private val uiInteraction: UiInteraction,
    private val dateUtil: DateUtil,
    private val pumpState: Atc3PumpState,
    private val atc3Manager: Atc3Manager,
    private val atc3HistorySync: Atc3HistorySync,
    private val clockWatch: Atc3ClockWatch,
    private val trace: Atc3Trace
) {

    /** True from a phone timezone or daylight saving change until the pump's clock follows it. */
    private var phoneMoved = false

    /** When the clock was last written, 0 since AAPS started, so the first tick writes it. */
    private var clockSyncedAtMs = 0L

    /** True from a change of the loop's running mode until the clock has been written. */
    @Volatile internal var clockSyncWanted = false

    /** The phone changed timezone or went on or off daylight saving: the clock follows it at the next tick. */
    fun phoneTimeChanged() {
        phoneMoved = true
    }

    /** The loop's running mode changed: the clock is written at the next tick, unless it was just written. */
    fun modeChanged() {
        clockSyncWanted = true
    }

    /**
     * True while the last report sits [Atc3Const.CLOCK_MAX_CORRECTION_MS] or more from the phone's clock
     * at its read: nothing is written, and the loop is stopped. Read off the last report, connected or not.
     */
    val tooFarApart: Boolean
        get() = pumpState.statusCard?.let { abs(it.snapshotAtMs - it.readAtMs) >= Atc3Const.CLOCK_MAX_CORRECTION_MS } ?: false

    /**
     * Write the phone's time into the pump when it is off: after [Atc3Const.CLOCK_JOURNAL_MISSES_TO_SET]
     * history reads that put our boluses a minute off, at once when the report is more than
     * [Atc3Const.CLOCK_QUIET_CORRECTION_MS] away or the phone's own time moved, and never when it is
     * [Atc3Const.CLOCK_MAX_CORRECTION_MS] or more away ([tooFarApart]).
     */
    suspend fun correctIfAdrift(readHistory: () -> Atc3BolusHistory?) {
        val card = pumpState.statusCard ?: return
        val apart = abs(card.snapshotAtMs - card.readAtMs)
        if (apart >= Atc3Const.CLOCK_MAX_CORRECTION_MS) {
            aapsLogger.error(
                LTag.PUMP,
                "ATC3: the pump's status is $apart ms away from the phone, not setting the clock, stopping the loop"
            )
            trace.event(Atc3TraceCat.DRV, "clock_too_far", "apartMs" to apart)
            // URGENT rather than the id's default: this one stops the loop.
            uiInteraction.addNotification(
                Notification.OVER_24H_TIME_CHANGE_REQUESTED,
                rh.gs(R.string.atc3_clock_skew_too_large),
                Notification.URGENT
            )
            return
        }
        rxBus.send(EventDismissNotification(Notification.OVER_24H_TIME_CHANGE_REQUESTED))

        // The middle band: set at once and said so; no bolus record pairs this far off.
        if (phoneMoved || apart > Atc3Const.CLOCK_QUIET_CORRECTION_MS) {
            val why = if (phoneMoved) "the phone's time changed" else "the pump's snapshot is $apart ms from the phone"
            if (writeClockAfterPairing(why, readHistory)) {
                val text = if (phoneMoved) rh.gs(R.string.atc3_clock_follows_phone)
                else rh.gs(R.string.atc3_clock_set_after_drift, apart / 60_000L)
                phoneMoved = false
                uiInteraction.addNotificationValidFor(Notification.INSIGHT_DATE_TIME_UPDATED, text, Notification.INFO, 60)
            }
            return
        }

        val now = dateUtil.now()
        // The loop rewrites its mode more than once around a stopped pump: one write for them all.
        if (clockSyncWanted && clockSyncedAtMs != 0L && now - clockSyncedAtMs < Atc3Const.CLOCK_SYNC_MIN_GAP_MS) clockSyncWanted = false
        if (clockSyncWanted || now - clockSyncedAtMs >= Atc3Const.CLOCK_SYNC_EVERY_MS) {
            val why = when {
                clockSyncWanted       -> "the loop's running mode changed"
                clockSyncedAtMs == 0L -> "AAPS has started"
                else                  -> "the clock was last set ${(now - clockSyncedAtMs) / 60_000L} min ago"
            }
            if (writeClockAfterPairing(why, readHistory)) clockSyncWanted = false
            return
        }

        if (!clockWatch.needsSetting(now)) {
            rxBus.send(EventDismissNotification(Notification.PUMP_WARNING))
            return
        }

        writeClockAfterPairing("the pump's records put our boluses a minute off, or stamped our temporary basals too far from their acknowledgement, twice in a row", readHistory)
    }

    /**
     * Write the phone's time into the pump after the bolus history is read and taken in, on the clock
     * its records were stamped with; where imports start from moves to the moment of the write
     * ([Atc3HistorySync.onPumpClockWritten]). No history, no write.
     *
     * @param readHistory the driver's read of the bolus history
     * @return true when the pump acknowledged the new time
     */
    private suspend fun writeClockAfterPairing(why: String, readHistory: () -> Atc3BolusHistory?): Boolean {
        // A locked pump refuses the write: nothing is sent, the warning stays.
        if (pumpState.locked) {
            aapsLogger.debug(LTag.PUMP, "ATC3: $why, but the pump is locked, not setting its clock")
            uiInteraction.addNotification(Notification.PUMP_WARNING, rh.gs(R.string.atc3_clock_not_set), Notification.NORMAL)
            return false
        }
        val history = readHistory()
        if (history == null) {
            aapsLogger.debug(LTag.PUMP, "ATC3: could not read the bolus history, not setting the pump clock yet")
            uiInteraction.addNotification(Notification.PUMP_WARNING, rh.gs(R.string.atc3_clock_not_set), Notification.NORMAL)
            return false
        }
        atc3HistorySync.reconcileBoluses(history.records, history.recordCount)

        // No control command while a bolus of ours may still run: it would take the link down. The next tick asks again.
        if (atc3HistorySync.hasPendingBolus()) {
            aapsLogger.debug(LTag.PUMP, "ATC3: $why, but a bolus of ours has no record yet and may still be running; the clock waits")
            trace.event(Atc3TraceCat.DRV, "clock_waits", "why" to "bolus")
            return false
        }

        aapsLogger.debug(LTag.PUMP, "ATC3: $why, setting the pump clock")
        trace.event(Atc3TraceCat.DRV, "clock_set", "why" to why)
        val failure = atc3Manager.writeClock(dateUtil.now())
        if (failure != null) {
            aapsLogger.error(LTag.PUMP, "ATC3: could not set the pump clock, $failure")
            uiInteraction.addNotification(Notification.PUMP_WARNING, rh.gs(R.string.atc3_clock_not_set), Notification.NORMAL)
            return false
        }
        atc3HistorySync.onPumpClockWritten(dateUtil.now())
        clockSyncedAtMs = dateUtil.now()
        // The anchor stays: a clock write changes neither the count nor the phone's time.
        rxBus.send(EventDismissNotification(Notification.PUMP_WARNING))
        return true
    }
}
