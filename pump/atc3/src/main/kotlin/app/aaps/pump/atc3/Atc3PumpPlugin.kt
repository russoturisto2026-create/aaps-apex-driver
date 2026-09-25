package app.aaps.pump.atc3

import android.content.Context
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.model.BS
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.determineCorrectBasalSize
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventRunningModeChange
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.pump.atc3.comm.Atc3Alarm
import app.aaps.pump.atc3.comm.Atc3BolusHistory
import app.aaps.pump.atc3.comm.Atc3BtPassword
import app.aaps.pump.atc3.comm.Atc3LinkProtection
import app.aaps.pump.atc3.comm.Atc3StatusV1
import app.aaps.pump.atc3.comm.Atc3TbrRecord
import app.aaps.pump.atc3.comm.Atc3TbrStatus
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.pump.atc3.keys.Atc3IntNonKey
import app.aaps.pump.atc3.keys.Atc3IntentKey
import app.aaps.pump.atc3.keys.Atc3LongNonKey
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.keys.Atc3StringNonKey
import app.aaps.pump.atc3.history.Atc3AapsJournal
import app.aaps.pump.atc3.history.Atc3BolusSpacing
import app.aaps.pump.atc3.history.Atc3ClockWatch
import app.aaps.pump.atc3.history.Atc3StateCheck
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.history.Atc3TbrTracker
import app.aaps.pump.atc3.history.LoopTbr
import app.aaps.pump.atc3.manager.Atc3BolusOutcome
import app.aaps.pump.atc3.manager.Atc3Manager
import app.aaps.pump.atc3.manager.Atc3SetSuspended
import app.aaps.pump.atc3.manager.Atc3TbrResult
import app.aaps.pump.atc3.manager.Atc3SetBtPassword
import app.aaps.pump.atc3.manager.Atc3WriteSettings
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * AAPS pump driver for ATC3.
 *
 * The driver reads the pump's state, writes the AAPS basal schedule, delivers and stops boluses and
 * runs temporary basals. Everything the pump delivers is reconciled into AAPS through
 * [Atc3HistorySync], including boluses given on the pump itself or from another device.
 */
@Singleton
class Atc3PumpPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    commandQueue: CommandQueue,
    private val atc3Pump: Atc3Pump,
    private val atc3Manager: Atc3Manager,
    private val rxBus: RxBus,
    private val uiInteraction: UiInteraction,
    private val atc3HistorySync: Atc3HistorySync,
    private val aapsJournal: Atc3AapsJournal,
    private val clockWatch: Atc3ClockWatch,
    private val dateUtil: DateUtil,
    private val aapsSchedulers: AapsSchedulers,
    private val fabricPrivacy: FabricPrivacy,
    private val pumpEnactResultProvider: Provider<PumpEnactResult>,
    private val trace: Atc3Trace
) : PumpPluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.PUMP)
        .fragmentClass(Atc3Fragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.atc3_name)
        .shortName(R.string.atc3_name_short)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .description(R.string.atc3_pump_description),
    ownPreferences = listOf(
        Atc3StringKey::class.java, Atc3IntentKey::class.java, Atc3StringNonKey::class.java,
        Atc3IntNonKey::class.java, Atc3BooleanKey::class.java, Atc3LongNonKey::class.java
    ),
    aapsLogger, rh, preferences, commandQueue
), Pump, PluginConstraints {

    private val disposable = CompositeDisposable()

    /** Where the driver waits on something of its own, off the thread that asked. */
    private val pluginScope = CoroutineScope(Dispatchers.Default + Job())

    /** Phone clock at the last Status V2 read, so the battery is not re-read in every connection. */
    private var batteryReadAtMs = 0L

    /** Phone clock at the last alarm history read, so it is not re-read in every connection. */
    private var alarmsReadAtMs = 0L

    /** Phone clock at the last read of the refill history, zero until once. */
    private var refillsReadAtMs = 0L

    /** The reservoir at the previous tick, for noticing that it went up. */
    private var reservoirSeenUnits = -1.0

    /**
     * True while the alarm notification raised here is the one on screen.
     *
     * [Notification.PUMP_ERROR] is not this driver's alone — a refused Bluetooth password uses it
     * too — so the alarm may only dismiss a notification it raised itself. Without this, the first
     * successful status read after a password was refused would wipe that message off the screen
     * while the user still needed it.
     */
    private var alarmNotificationShown = false

    /**
     * The comparison of the pump's own count of delivered insulin with the AAPS journal.
     *
     * One instance serves the tick and the bolus path alike, because they are asking the same
     * question and a snapshot either of them accepts is where the other's next comparison starts.
     */
    private val stateCheck = Atc3StateCheck()

    /**
     * The snapshot time of the last status that showed the pump delivering, so that a stop the
     * pump keeps from earlier in the day is not taken for the one it is in now.
     */
    private var lastRunningSnapshotMs = 0L

    /**
     * Phone clock at the last comparison that found the pump's count and the AAPS journal to agree,
     * or were brought to agree. A command within [Atc3Const.HISTORY_FRESH_MS] of it needs no bolus
     * history read of its own: the count already proved nobody delivered insulin AAPS does not hold.
     */
    private var lastBalancedAtMs = 0L

    /** True while object 0x0A was read in the tick under way, so it is not read a second time. */
    private var activeTbrReadThisTick = false

    /** The status read pending while the pump is stopped, see [watchForResume]. */
    private var resumeWatch: Job? = null

    /** The last stop the pump reported, as of the previous tick, for noticing one the ticks missed. */
    private var lastStopSeen: Atc3StatusV1.LastStop? = null

    /**
     * The moment of a stop that began and ended between two ticks, noticed on this tick by the
     * pump's last stop having moved while the pump runs and the ledger holds no stop; null when
     * there is none. See [pumpMoments] and [resolveState].
     */
    private var unseenStopMs: Long? = null

    /**
     * While the pump is stopped, ask for a status every [RESUME_WATCH_MS], so that the snapshot
     * the resume rebuilds -- the only place the moment of resuming is kept, and only until the
     * next rebuild a minute later -- is seen while it is there. Without it the stop's row would be
     * closed only at the next tick, minutes late.
     *
     * A stop is rare and a status is one exchange, and while the pump is stopped nothing else asks
     * the pump anything.
     */
    private fun watchForResume() {
        if (!stopIsOpen()) return
        if (resumeWatch?.isActive == true) return
        resumeWatch = pluginScope.launch {
            delay(RESUME_WATCH_MS)
            // Cleared before the read: the read is a tick, and the tick arms the next watch at its
            // end -- which it cannot while this one still counts as running.
            resumeWatch = null
            if (stopIsOpen()) commandQueue.readStatus(rh.gs(R.string.atc3_resume_watch), null)
        }
    }

    /**
     * True while AAPS holds the pump as stopped: the pump says so, or the ledger still has the
     * stop open. The second is the one that matters: a status read on the way past -- the
     * manager confirming a resume it sent -- tells the driver the pump runs without closing the
     * stop's row, and the row is closed only by a tick.
     */
    private fun stopIsOpen(): Boolean = atc3Pump.notDelivering || atc3HistorySync.openTbr()?.suspension == true

    /** What the pump's temporary basal journal answers, handed to the history sync at every close. */
    private val journal: () -> List<Atc3TbrRecord>? = { atc3Manager.readTbrHistory() }

    /**
     * Keeps two of the driver's own boluses far enough apart for their records to be told apart.
     *
     * See the class: it is the confirmation window that needs the distance, not the person.
     */
    private val bolusSpacing = Atc3BolusSpacing()

    /**
     * Ask for the pump's boluses when nothing else is going to.
     *
     * The command queue only connects when it has something to send, and AAPS's own keepalive
     * waits until the last connection is a quarter of an hour old. So on a cycle where the loop
     * changes nothing, a bolus given on the pump would sit unseen for that whole quarter of an
     * hour, and the loop would keep dosing against an insulin on board that is missing it.
     *
     * **Asked for when the loop has finished deciding, not when the glucose arrived.** Both are
     * once a cycle, but the moment matters: asked at the glucose, the read opens a connection of
     * its own, finishes in two seconds and the link is closed again before the loop's command
     * turns up some seconds later. Asked after the decision, the read and the command are queued
     * together and travel in one connection.
     *
     * The glucose is still listened to, as the fallback for a cycle in which the loop produces no
     * decision. It asks on a longer threshold so that it does not pre-empt the read that would
     * have shared a connection.
     *
     * The question asked is [Atc3HistorySync.statusFresh], not `historyFresh`: every command reads
     * the bolus history on its way past, which makes the history fresh without anything having
     * looked at the temporary basal or the suspension.
     *
     * Repeating the request is harmless: the queue drops a read status that is already scheduled.
     */
    override fun onStart() {
        super.onStart()
        disposable += rxBus
            .toObservable(EventAPSCalculationFinished::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ readStateIfStale("decided", Atc3Const.STATUS_FRESH_MS) }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventRunningModeChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ clockSyncWanted = true }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventNewBG::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ readStateIfStale("glucose", Atc3Const.STATUS_STALE_MS) }, fabricPrivacy::logException)
    }

    override fun onStop() {
        disposable.clear()
        super.onStop()
    }

    private fun readStateIfStale(trigger: String, threshold: Long) {
        if (!isEnabled()) return
        // Only the state is asked about: getPumpStatus refreshes both, so a fresh state always
        // means a fresh history too. The other way round does not hold, which is the whole point.
        if (atc3HistorySync.stateAgeMs(dateUtil.now()) < threshold) {
            trace.event(Atc3TraceCat.DRV, "bg_read", "by" to trigger, "asked" to false, "why" to "fresh")
            return
        }
        val queued = commandQueue.readStatus(rh.gs(R.string.atc3_history_stale), null)
        // False means the queue already holds a read status. That is the cadence working, not a
        // failure.
        trace.event(Atc3TraceCat.DRV, "bg_read", "by" to trigger, "asked" to true, "queued" to queued)
    }

    // Limits the pump itself is set to

    /**
     * Never ask the pump for a basal rate it is configured to refuse.
     *
     * The ceilings in [PumpType] are outer bounds for the model, not this pump's limits: the real
     * ones are in its own settings block, which the driver reads with every status. Asking past
     * them would not overdeliver — the pump answers with a refusal — but it would turn a loop
     * decision into a failed command for no reason, and the reason would only be visible in a log.
     *
     * Nothing is limited until the settings have been read once. A limit invented before the pump
     * has been heard from would be a guess, and the loop is better served by the model's bound.
     */
    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        val maxBasal = atc3Pump.settings?.maxBasal ?: return absoluteRate
        absoluteRate.setIfSmaller(
            maxBasal,
            rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(app.aaps.core.ui.R.string.pumplimit)),
            this
        )
        return absoluteRate
    }

    /** The same for a bolus, from the same settings block. */
    override fun applyBolusConstraints(insulin: Constraint<Double>): Constraint<Double> {
        val maxBolus = atc3Pump.settings?.maxBolus ?: return insulin
        insulin.setIfSmaller(
            maxBolus,
            rh.gs(app.aaps.core.ui.R.string.limitingbolus, maxBolus, rh.gs(app.aaps.core.ui.R.string.pumplimit)),
            this
        )
        return insulin
    }

    // Connection state

    override fun isInitialized(): Boolean = atc3Pump.isInitialized
    /**
     * True while the pump is stopped and delivering nothing.
     *
     * The pump says so by reporting `0xFFFF` where the scheduled basal rate goes, see
     * [app.aaps.pump.atc3.comm.Atc3StatusV1]. It does not always say so: under a reservoir-empty
     * alarm it reports itself running and delivers nothing, so the answer comes from
     * [Atc3Pump.notDelivering], which knows about that too. Getting this wrong is the worst kind of
     * wrong: the loop would keep crediting basal the pump is not delivering.
     */
    override fun isSuspended(): Boolean = atc3Pump.notDelivering
    /**
     * True while the pump will not take another command, which for this pump means an exchange is
     * in flight or a bolus is running.
     *
     * The command queue reads this before it picks up the next command, and the keepalive before it
     * asks for a status. Neither should walk into the middle of a bolus.
     */
    override fun isBusy(): Boolean = atc3Manager.isBusy
    override fun isConnected(): Boolean = atc3Manager.isConnected
    override fun isConnecting(): Boolean = atc3Manager.isConnecting
    override fun isHandshakeInProgress(): Boolean = false

    override fun connect(reason: String) {
        atc3Manager.connect(reason)
    }

    override fun disconnect(reason: String) {
        atc3Manager.disconnect(reason)
    }

    override fun stopConnecting() {
        atc3Manager.disconnect("stopConnecting")
    }

    /**
     * One tick.
     *
     * The order is not incidental and it follows one rule: **if the tick is cut short at any point,
     * everything essential is already done.** So the state comes first, then anything that decides
     * whether the pump is safe to leave alone, then what AAPS has to be told, and only at the end
     * the things that can equally well wait for the next tick.
     *
     * 1. the status, without which everything after it would be a guess about the pump;
     * 2. alarms, which take priority over everything and each of which re-reads its own subject;
     * 3. whether the pump is delivering at all, which AAPS has to know before it counts any insulin;
     * 4. the temporary basal and the suspension into AAPS, from the pump's own moments, so that the
     *    journal AAPS holds is up to this snapshot before it is compared with the pump's count;
     * 5. the pump held to the loop's last word if somebody changed it by hand, and what ran by that
     *    hand closed from the journal;
     * 6. whether the pump's count of delivered insulin is what the AAPS journal accounts for, which
     *    costs nothing — the count is in the frame just read; if it is not, the pump's journals,
     *    and AAPS brought to what they say;
     * 7. the pump's clock, last of the things that touch the pump, because writing it moves where
     *    later records land and because correcting it mid-tick would leave one tick holding two
     *    systems of time;
     * 8. housekeeping — battery, firmware, profiles — none of which any decision waits on.
     */
    override fun getPumpStatus(reason: String) = runBlocking { tick(reason) }

    private suspend fun tick(reason: String) {
        aapsLogger.debug(LTag.PUMP, "ATC3: getPumpStatus, reason $reason")
        val startedAt = trace.now()
        activeTbrReadThisTick = false
        trace.event(Atc3TraceCat.DRV, "status.begin", "reason" to reason)
        if (!atc3Manager.isConnected) {
            atc3Manager.connect(reason)
            trace.event(Atc3TraceCat.DRV, "status.end", "ok" to false, "why" to "connecting", "ms" to trace.since(startedAt))
            return
        }
        // 0. Which pump this is. Read once per link, before anything is asked of it: a pump on
        // firmware without a Bluetooth password is not one this driver runs.
        if (atc3Pump.version == null) atc3Manager.readVersion()
        if (refuseOldFirmware()) {
            trace.event(Atc3TraceCat.DRV, "status.end", "ok" to false, "why" to "firmware", "ms" to trace.since(startedAt))
            return
        }
        // The heartbeat the held link is watched by, on the period the watch expects. Once per link.
        atc3Manager.ensureHeartbeatPeriod()
        // 1. The one read the tick cannot go on without.
        if (!atc3Manager.readStatus()) {
            trace.event(Atc3TraceCat.DRV, "status.end", "ok" to false, "why" to "no_status", "ms" to trace.since(startedAt))
            return
        }
        traceState()

        // 2. Alarms first. An alarm is the pump saying something is wrong, and everything that
        // follows is worth less than knowing it.
        announceAlarms()
        stopOnDeliveryAlarm()
        readAlarmSubjects()
        readAlarmsIfDue()
        readRefillsIfDue()

        // 3. Is it delivering at all. Nothing else in AAPS says "no insulin is going in".
        announceSuspension(atc3Pump.notDelivering)

        // 4. What the pump is delivering goes into AAPS: a temporary basal whoever started it, and
        // a stop, which reaches AAPS as a temporary basal of zero from the moment the pump stopped.
        val tbrRead = syncTbrFromStatus()

        // 5. The pump is held to the loop's last word if somebody changed it by hand, and what ran
        // by that hand is closed from the journal -- before the comparison, so that the rows it
        // compares are already the pump's account. Compared first, a stranger's temporary basal
        // still running would read as an excess the journals cannot yet explain.
        holdPumpToLoop(synced = true, where = "tick")

        // 6. Does the pump's count of delivered insulin agree with the AAPS journal. Free: the count
        // is in the frame above, and the journal is AAPS's own. If it does not, the pump's journals
        // say what AAPS is missing.
        val verdict = checkState()
        val resolution = resolveState(verdict).resolution
        reportDeliveryStopped()

        // 7. The clock, last of what touches the pump.
        correctPumpClockIfAdrift()

        // 8. Housekeeping.
        readBatteryIfDue()
        readVersionIfUnknown()
        warnAboutUnprotectedLink()
        readProfilesIfNeeded()
        watchForResume()

        trace.event(
            Atc3TraceCat.DRV, "status.end",
            "ok" to (tbrRead && resolution != Resolution.READ_FAILED),
            "state" to verdict.trace(),
            "resolution" to resolution.name.lowercase(),
            "tbr" to tbrRead,
            "ms" to trace.since(startedAt)
        )
    }

    /**
     * What the pump runs goes into AAPS, from the Status V1 just read.
     *
     * Status V1 says whether a temporary basal runs, at what rate and for how long it was started,
     * and that is compared with the one AAPS has. Only a disagreement costs a read: object 0x0A when
     * one runs that AAPS does not have as it is, for the pump's own start of it; object 0x0B when the
     * one AAPS has went before its time and not by our command, for the moment it really ended. See
     * Atc3TbrTracker.
     *
     * @return false when object 0x0A was wanted and did not answer
     */
    private suspend fun syncTbrFromStatus(): Boolean {
        val openTbr = atc3HistorySync.openTbr()
        val tickAt = dateUtil.now()
        val tbrDurationMs = atc3Pump.tbrDurationMinutes.takeIf { it > 0 }?.let { it * 60_000L }
        val (pausedAtMs, resumedAtMs) = pumpMoments()
        val startRead = Atc3TbrTracker.needsStartRead(
            openTbr, atc3Pump.notDelivering, atc3Pump.tbrActive, atc3Pump.tbrRate, tbrDurationMs, tickAt,
            elapsedMinutes = atc3Pump.tbrElapsedMinutes.takeIf { atc3Pump.tbrActive }
        )
        val tbrRead = !startRead || atc3Manager.readActiveTbr()
        if (startRead && tbrRead) activeTbrReadThisTick = true
        val pumpTbrStart = if (startRead && tbrRead) atc3Manager.activeTbr else null
        val endedAtMs =
            if (Atc3TbrTracker.needsEndRead(openTbr, atc3Pump.notDelivering, atc3Pump.tbrActive, tickAt) &&
                atc3Manager.readFinishedTbr()
            ) atc3HistorySync.realEndOf(atc3Manager.finishedTbr)
            else null
        atc3HistorySync.onStatus(
            atc3Pump.notDelivering, atc3Pump.tbrActive, atc3Pump.tbrRate, tbrDurationMs, pumpTbrStart, endedAtMs,
            pausedAtMs = pausedAtMs, resumedAtMs = resumedAtMs, journal = journal
        )
        return tbrRead
    }

    /**
     * The moments of a stop and a resume as the pump keeps them, from the status just read: the
     * first when the pump is not delivering, the second when it is.
     *
     * A stop the pump made itself is kept to the minute in Status V1, and to the second in the
     * snapshot clock when the stop rebuilt the snapshot. A stop the pump does not admit to -- an
     * empty reservoir -- has no moment of its own, and the snapshot is the best there is. A resume
     * has its moment only in the snapshot the resume rebuilt, which the next rebuild a minute later
     * replaces; after that the snapshot is at most a minute late, and the trace says which it was.
     * A stop from earlier in the day, kept by the pump while it has been running since, is not the
     * stop it is in now.
     */
    private fun pumpMoments(): Pair<Long?, Long?> {
        val status = atc3Pump.lastStatus ?: return null to null
        // A stop the ticks missed: the pump's last stop moved, the pump runs, and the ledger holds
        // no stop to close. Its moment is the minute the pump keeps; its end nobody saw.
        unseenStopMs = null
        val stopNow = status.lastStop
        if (stopNow != null && lastStopSeen != null && stopNow != lastStopSeen && !atc3Pump.notDelivering &&
            atc3HistorySync.openTbr()?.suspension != true
        ) {
            unseenStopMs = status.lastStopMoment()?.takeIf { it > lastRunningSnapshotMs }
            unseenStopMs?.let { trace.event(Atc3TraceCat.TBR, "stop_unseen", "at" to it) }
        }
        lastStopSeen = stopNow
        if (!atc3Pump.notDelivering) {
            lastRunningSnapshotMs = status.snapshotTime
            return null to status.snapshotTime
        }
        // The minute the pump keeps, or the second when any read since the stop caught the snapshot
        // the stop itself rebuilt: the status read that confirms a stop command sees that
        // snapshot, while the tick that records the stop can come minutes later.
        val explicit = status.lastStopMoment()
            ?.let { minute -> atc3Pump.stopSnapshotMs?.takeIf { it / 60_000L == minute / 60_000L } ?: minute }
            ?.takeIf { status.suspended && it > lastRunningSnapshotMs }
        if (explicit != null) trace.event(
            Atc3TraceCat.TBR, "stop_moment", "at" to explicit, "exact" to (explicit % 60_000L != 0L || status.snapshotIsTheStop())
        )
        return (explicit ?: status.snapshotTime) to null
    }

    /**
     * When the pump was last found running a temporary basal other than the loop's and put back,
     * phone epoch milliseconds, or 0. An SMB the loop decided on before that moment is refused.
     */
    @Volatile private var tbrTouchedAtMs: Long = 0L

    /**
     * Hold the pump to the loop's last word on the temporary basal.
     *
     * Every temporary basal is set by the loop through this driver, and the driver writes the
     * history of it. A pump running anything else -- a temporary basal set or cancelled on the
     * keypad or by another client -- is put back at once: the loop's rate for the loop's duration,
     * or no temporary basal when the loop's last word was a cancel or its temporary basal has run
     * out. What ran in between reaches AAPS from the journal, object 0x27, read right after, so that
     * AAPS counts the insulin that really went in.
     *
     * Not while the pump is stopped: a stopped pump delivers nothing, keeps its temporary basal
     * fields filled, and is not the loop's to restart. And not while the driver has no word from
     * the loop yet, since there is nothing to put the pump back to.
     *
     * @param synced whether [syncTbrFromStatus] already ran on this Status V1
     * @param where which path looked, for the log and the trace
     * @return true when the pump was put back
     */
    private suspend fun holdPumpToLoop(synced: Boolean, where: String): Boolean {
        val expected = noticeHand(synced, where) ?: return false
        val now = dateUtil.now()
        val ends = expected.endsAtMs
        val wantsRate = expected.rawRate != null && ends != null && now < ends
        aapsLogger.warn(LTag.PUMP, "ATC3: putting the loop's temporary basal back")
        if (wantsRate) {
            val result = atc3Manager.setTempBasal(expected.rawRate!! * Atc3Const.DOSE_SCALE, expected.durationMinutes)
            if (result.failure == null) recordOwnTbr(result)
            else aapsLogger.error(LTag.PUMP, "ATC3: could not put the loop's temporary basal back, ${result.failure}")
        } else {
            val failure = atc3Manager.cancelTempBasal()
            if (failure == null) {
                val cancelledAt = atc3Manager.lastTbrCancelAckMs.takeIf { it > 0L } ?: dateUtil.now()
                atc3HistorySync.tbrStopped(cancelledAt, journal = journal)
                atc3HistorySync.loopCancelledTbr(cancelledAt)
            } else aapsLogger.error(LTag.PUMP, "ATC3: could not cancel the temporary basal set by hand, $failure")
        }
        // What ran by somebody else's hand is finished now, and the journal holds it.
        readJournalAfterHand()
        return true
    }

    /**
     * Notice a temporary basal set or cancelled by hand, from the Status V1 just read: what the pump
     * runs goes into AAPS, and the moment is kept so that the SMB of this cycle waits.
     *
     * @param synced whether [syncTbrFromStatus] already ran on this Status V1
     * @param where which path noticed it, for the log and the trace: the tick, or the loop's own
     *   command about to replace it
     * @return the loop's last word when the pump runs something else, or null when it does not
     */
    private suspend fun noticeHand(synced: Boolean, where: String): LoopTbr? {
        val expected = atc3HistorySync.loopTbr() ?: return null
        val now = dateUtil.now()
        if (!touchedByHand(expected, now)) return null
        val ends = expected.endsAtMs
        val wantsRate = expected.rawRate != null && ends != null && now < ends
        aapsLogger.warn(
            LTag.PUMP,
            "ATC3: noticed at $where, the pump runs " +
                (if (atc3Pump.tbrActive) "${atc3Pump.tbrRate} U/h for ${atc3Pump.tbrDurationMinutes} min" else "no temporary basal") +
                ", the loop's last word was " +
                (if (wantsRate) "${expected.rawRate!! * Atc3Const.DOSE_SCALE} U/h for ${expected.durationMinutes} min" else "none")
        )
        trace.event(
            Atc3TraceCat.TBR, "touched",
            "where" to where,
            "found" to (if (atc3Pump.tbrActive) atc3Pump.tbrRate else -1.0),
            "foundMin" to atc3Pump.tbrDurationMinutes,
            "expected" to (if (wantsRate) expected.rawRate!! * Atc3Const.DOSE_SCALE else -1.0),
            "expectedMin" to expected.durationMinutes
        )
        if (!synced) syncTbrFromStatus()
        tbrTouchedAtMs = now
        return expected
    }

    /** What ran by somebody else's hand, now finished, goes into AAPS from the journal. */
    private suspend fun readJournalAfterHand() {
        atc3Manager.readTbrHistory()?.let { atc3HistorySync.reconcileTbrHistory(it) }
            ?: aapsLogger.error(LTag.PUMP, "ATC3: the temporary basal journal did not answer after a change by hand")
    }

    /** Whether the pump runs something other than the loop's last word on the temporary basal. */
    private fun touchedByHand(expected: LoopTbr, now: Long): Boolean {
        if (atc3Pump.notDelivering) return false
        val ends = expected.endsAtMs
        // Around the loop's own end the pump and the phone can disagree for some seconds about
        // whether it still runs; neither answer there is somebody's hand.
        if (ends != null && abs(now - ends) < LOOP_END_SLACK_MS) return false
        val wantsRate = expected.rawRate != null && ends != null && now < ends
        if (!wantsRate) return atc3Pump.tbrActive
        return !atc3Pump.tbrActive ||
            Math.round(atc3Pump.tbrRate / Atc3Const.DOSE_SCALE).toInt() != expected.rawRate ||
            atc3Pump.tbrDurationMinutes != expected.durationMinutes
    }

    /**
     * A therapy command goes to the pump only when the data the loop decided on was the pump's:
     * the status just read is compared with the AAPS journal, and when the journals had to change
     * what AAPS holds -- insulin the loop did not know of, a stop it did not see -- or could not
     * explain the count, the command of this cycle is refused, and the loop decides again on the
     * corrected data. A decision computed from wrong data is not applied, whatever it was.
     *
     * The comparison itself is the tick's: [checkState] and [resolveState], on the status the
     * caller has just read.
     *
     * @param loops true for a decision of the loop's, which is refused when the data changed;
     *   false for a command the user gave -- disconnecting the pump, suspending, resuming -- which
     *   goes through whatever the journals said: it was not computed from anything.
     * @return the refusal, or null when the data stood
     */
    private suspend fun dataChangedUnderTheLoop(loops: Boolean = true): PumpEnactResult? {
        // No status decoded yet: nothing to compare the journal with, and the bolus history read
        // is the barrier it always was.
        if (atc3Pump.lastStatus == null) {
            ensureHistoryFresh()
            return null
        }
        activeTbrReadThisTick = false
        syncTbrFromStatus()
        val resolved = resolveState(checkState())
        if (resolved.resolution == Resolution.BALANCED || resolved.resolution == Resolution.ANCHORED || resolved.resolution == Resolution.QUANTISED) return null
        if (!loops) {
            trace.event(Atc3TraceCat.DRV, "command_kept", "why" to resolved.resolution.name.lowercase(), "who" to "user")
            return null
        }
        aapsLogger.warn(LTag.PUMP, "ATC3: refusing the command, the journal changed under the loop (${resolved.resolution.name.lowercase()})")
        trace.event(Atc3TraceCat.DRV, "command_refused", "why" to resolved.resolution.name.lowercase())
        return pumpEnactResultProvider.get().success(false).enacted(false)
            .comment(rh.gs(R.string.atc3_command_data_changed))
    }

    /**
     * Refuse an SMB the loop decided on before the pump was found running a temporary basal other
     * than the loop's. The loop's insulin on board was wrong when it decided; it runs again with the
     * history put right, and the SMB of that next cycle goes through. Only SMBs: a bolus the user
     * asked for is theirs to decide on.
     */
    private fun smbOnTouchedTbr(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult? {
        if (detailedBolusInfo.bolusType != BS.Type.SMB) return null
        val decidedAt = detailedBolusInfo.deliverAtTheLatest
        if (decidedAt == 0L || tbrTouchedAtMs == 0L || tbrTouchedAtMs < decidedAt) return null
        aapsLogger.warn(
            LTag.PUMP,
            "ATC3: refusing the SMB, the temporary basal was put back at $tbrTouchedAtMs after the loop decided at $decidedAt"
        )
        trace.event(
            Atc3TraceCat.DRV, "smb_refused",
            "tbrTouchedAt" to tbrTouchedAtMs,
            "decidedAt" to decidedAt,
            "units" to detailedBolusInfo.insulin
        )
        return pumpEnactResultProvider.get().success(false).enacted(false)
            .comment(rh.gs(R.string.atc3_smb_tbr_touched))
    }

    /** How a state check ended once the driver had looked for the cause. */
    private enum class Resolution {

        /** The journal accounted for the pump's count and nothing was read. */
        BALANCED,

        /** There was nothing to compare against; the journals were read and this snapshot accepted. */
        ANCHORED,

        /** The journals were read, AAPS brought to them, and the two sides now agree. */
        EXPLAINED,

        /**
         * The journals were read, they hold nothing AAPS did not, and the two sides differ by no
         * more than a second pulse: the pump's count and its records are each quantised to a
         * pulse at moments of their own, and one pulse of slack between them is the comparison
         * measuring its own quantisation, not the pump. Accepted as a comparison that agrees,
         * so that it costs the loop no refused command and the user no notification.
         */
        QUANTISED,

        /** Read and reconciled, and the two sides still do not agree. */
        UNEXPLAINED,

        /** There was something to look into and the pump would not answer. */
        READ_FAILED
    }

    /** What resolving a verdict came to, with what the bolus history said on the way. */
    private data class Resolved(val resolution: Resolution, val reconciled: Atc3HistorySync.ReconcileResult? = null)

    /**
     * Compare the pump's count of delivered insulin, as it stood when the status was read, with
     * what the AAPS journal accounts for since the last accepted read. See [Atc3StateCheck].
     */
    private suspend fun checkState(): Atc3StateCheck.Verdict {
        val status = atc3Pump.lastStatus ?: return Atc3StateCheck.Verdict.Unknown
        val readMs = atc3Pump.statusReadAtMs
        val counter = status.deliveredTodayUnits
        val base = stateCheck.baselineFor(readMs, counter)
            ?: return Atc3StateCheck.Verdict.Unknown.also {
                trace.event(Atc3TraceCat.HIST, "check", "state" to "unknown", "counter" to counter, "at" to readMs)
            }
        val journal = aapsJournal.insulinBetween(base.readMs, readMs, base.bolusUntilMs, bolusUntilOf(status))
            ?: return Atc3StateCheck.Verdict.Unknown.also {
                aapsLogger.error(LTag.PUMP, "ATC3: no profile is running, the journal cannot be summed")
                trace.event(Atc3TraceCat.HIST, "check", "state" to "unknown", "why" to "no_profile")
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

    /**
     * How far the pump's count and the journal may sit apart since the anchor: what the pump
     * delivers in a minute at the highest basal rate it is set to allow, and never under two
     * pulses. The journal counts a rate over its time and the pump delivers it in steps, a minute's
     * worth at once at the highest rates; more than that between the two is a mistake or
     * somebody else's hand, and the journals are read.
     */
    private fun checkTolerance(): Double =
        maxOf(2 * Atc3Const.DOSE_SCALE, (atc3Pump.settings?.maxBasal ?: 0.0) / 60.0)

    /**
     * Where the boluses of the interval ending at this snapshot are counted up to.
     *
     * A stranger's bolus is written at second 59 of the minute it started in, and a snapshot the
     * bolus itself rebuilt -- one carrying seconds -- already holds it in the count; so the boluses
     * of that whole minute belong to this interval. A periodic snapshot, on the whole minute, was
     * built before anything of its minute happened. See Atc3StateCheck.Baseline.bolusUntilMs.
     */
    private fun bolusUntilOf(status: Atc3StatusV1): Long =
        if (status.snapshotCarriesSeconds && !clockWriteRebuiltSnapshot(status)) Math.floorDiv(status.snapshotTime, 60_000L) * 60_000L + 60_000L
        else status.snapshotTime

    /**
     * Whether this snapshot was rebuilt by the driver's own clock write rather than by a bolus:
     * the clock write rebuilds the snapshot with seconds too, and a snapshot rebuilt that way holds
     * no bolus of its minute. Taking its whole minute as counted would leave a bolus given later in
     * that minute outside every comparison.
     */
    private fun clockWriteRebuiltSnapshot(status: Atc3StatusV1): Boolean =
        clockSyncedAtMs != 0L && abs(status.snapshotTime - clockSyncedAtMs) <= CLOCK_WRITE_SNAPSHOT_SLACK_MS

    /**
     * What the pump is delivering right now, per hour.
     *
     * **Zero while it is not delivering**, whatever its temporary basal fields still say. A stopped
     * pump credited with its basal builds a phantom of insulin that never left the reservoir, and a
     * stranger's bolus can then hide behind that phantom without the books ever noticing.
     */
    private fun deliveringRate(): Double = when {
        atc3Pump.notDelivering -> 0.0
        atc3Pump.tbrActive     -> atc3Pump.tbrRate
        else                   -> atc3Pump.scheduledBasalRate
    }

    /**
     * Bring AAPS to what the pump's journals say, when its own journal does not account for what
     * the pump counted, or when there is nothing to compare against yet.
     *
     * All three are read: the bolus history, which closes the most likely and the most dangerous
     * case — insulin delivered from the pump's keypad or by another phone; the temporary basal
     * journal, whose records shape every row of ours to what it delivered and hold whatever ran
     * between two polls; and the running temporary basal's own record, for the trace. Then the
     * comparison is made again with the rows as they now are: agreement is accepted, disagreement
     * is left standing so the next tick asks again, and said out loud after two ticks in a row.
     *
     * A read that fails leaves the disagreement standing. That is the honest outcome: the snapshot
     * was not accepted, so the next tick asks again rather than starting from a count it never
     * explained.
     */
    private suspend fun resolveState(verdict: Atc3StateCheck.Verdict): Resolved {
        val status = atc3Pump.lastStatus
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

        // The running temporary basal's own account, for the record only: what the pump says it
        // has delivered so far is read at this moment and the count at the snapshot's, and which
        // moment object 0x0A speaks for has not been established, so the sum uses the row. Read
        // once a tick: what the tracker read moments ago is the same answer.
        if (atc3Pump.tbrActive && (activeTbrReadThisTick || atc3Manager.readActiveTbr())) {
            activeTbrReadThisTick = true
            atc3Manager.activeTbr?.let {
                trace.event(Atc3TraceCat.TBR, "running", "since" to it.startTimestamp, "units" to it.deliveredUnits, "asked" to it.amountAsked)
            }
        }

        val resolution: Resolution
        var difference = (verdict as? Atc3StateCheck.Verdict.Differs)?.units ?: 0.0
        if (status == null) {
            resolution = Resolution.READ_FAILED
        } else if (verdict is Atc3StateCheck.Verdict.Unknown) {
            stateCheck.accept(atc3Pump.statusReadAtMs, status.deliveredTodayUnits, bolusUntilOf(status))
            lastBalancedAtMs = dateUtil.now()
            resolution = Resolution.ANCHORED
        } else {
            // The comparison again, with the rows as the journals have now made them.
            var again = checkState()
            difference = (again as? Atc3StateCheck.Verdict.Differs)?.units ?: 0.0
            // A shortfall with a stop the ticks missed: the stop is recorded for the length the
            // shortfall evidences, and compared once more. At the scheduled rate it is a row of
            // its own; inside a running temporary basal it cuts that one in two, by the record
            // the pump wrote of the part before the stop.
            val unseen = unseenStopMs
            if (again is Atc3StateCheck.Verdict.Differs && again.units < 0 && unseen != null) {
                // Inside the open temporary basal when the stop's minute is no earlier than the
                // pump's own stamp of it: our row begins at the acknowledgement, up to a minute
                // after that stamp, and the stop's minute can fall between the two.
                val open = atc3HistorySync.openTbr()?.takeIf { !it.suspension && it.rate > 0.0 && unseen >= it.anchorMs }
                val scheduled = if (open == null) aapsJournal.scheduledRateAt(unseen) else null
                val rate = open?.rate ?: scheduled
                if (rate != null && rate > 0.0) {
                    val lengthMs = (-again.units / rate * 3_600_000.0).toLong()
                        .coerceIn(60_000L, maxOf(60_000L, status.snapshotTime - unseen))
                    val recorded =
                        if (open != null) atc3HistorySync.recordDerivedStopInTbr(unseen, lengthMs, journal)
                        else { atc3HistorySync.recordDerivedStop(unseen, lengthMs); true }
                    if (recorded) {
                        unseenStopMs = null
                        again = checkState()
                        difference = (again as? Atc3StateCheck.Verdict.Differs)?.units ?: 0.0
                    }
                }
            }
            if (again is Atc3StateCheck.Verdict.Matches) {
                stateCheck.accept(atc3Pump.statusReadAtMs, status.deliveredTodayUnits, bolusUntilOf(status))
                lastBalancedAtMs = dateUtil.now()
                rxBus.send(EventDismissNotification(Notification.PUMP_SYNC_ERROR))
                resolution = Resolution.EXPLAINED
            } else if (again is Atc3StateCheck.Verdict.Differs && abs(again.units) <= checkTolerance() + QUANTISATION_SLACK_UNITS) {
                stateCheck.accept(atc3Pump.statusReadAtMs, status.deliveredTodayUnits, bolusUntilOf(status))
                lastBalancedAtMs = dateUtil.now()
                rxBus.send(EventDismissNotification(Notification.PUMP_SYNC_ERROR))
                trace.event(Atc3TraceCat.HIST, "accepted_quantised", "units" to again.units)
                // Where the journals changed what AAPS held, the change is what the loop decided
                // without, and the command of this cycle is refused as for any explained one.
                resolution = if (reconciled.importedUnits > 0.0 || changed > 0) Resolution.EXPLAINED else Resolution.QUANTISED
            } else {
                val runs = stateCheck.unexplained()
                aapsLogger.warn(
                    LTag.PUMP,
                    "ATC3: the pump counted ${"%.3f".format(difference)} U more than the AAPS journal accounts for " +
                        "and the journals do not explain it, $runs time(s) in a row"
                )
                if (runs >= UNEXPLAINED_RUNS_TO_TELL) {
                    uiInteraction.addNotification(
                        Notification.PUMP_SYNC_ERROR,
                        rh.gs(R.string.atc3_journal_unexplained, difference),
                        Notification.URGENT
                    )
                    // Said, and let go: the read is accepted, so that the next comparison starts
                    // afresh rather than failing against the same baseline until midnight.
                    stateCheck.accept(atc3Pump.statusReadAtMs, status.deliveredTodayUnits, bolusUntilOf(status))
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

    /**
     * Say it out loud when the pump owes insulin and the level has not moved.
     *
     * First time a notification, second time with the same cause the loop is stopped. Two ticks is
     * what makes it a fact rather than a sample falling between two pulses, and the count forgets
     * itself the moment the level moves.
     */
    private fun reportDeliveryStopped() {
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

    /** How many ticks running the driver has reported that nothing is being delivered. */
    private var deliveryStoppedReports: Int = 0

    /**
     * True from the moment the phone reports a new timezone or a daylight saving change until the
     * pump's clock has been written to follow it. See [timezoneOrDSTChanged].
     */
    private var phoneTimeChanged = false

    /**
     * When the pump's clock was last put on the phone's, phone epoch milliseconds; zero since
     * AAPS started, so the first tick writes it.
     */
    private var clockSyncedAtMs = 0L

    /** True from a change of the loop's running mode until the clock has been written. */
    @Volatile internal var clockSyncWanted = false

    private companion object {

        /** How far a snapshot rebuilt by the clock write may sit from the write's own moment, milliseconds. */
        const val CLOCK_WRITE_SNAPSHOT_SLACK_MS = 15_000L

        /** Comparisons left unexplained by the journals, in a row, before the user is told. */
        const val UNEXPLAINED_RUNS_TO_TELL = 2

        /**
         * How far past the tolerance the two sides may sit, with the journals holding nothing new,
         * and still be the comparison's own quantisation, see [Resolution.QUANTISED].
         */
        const val QUANTISATION_SLACK_UNITS = 2 * Atc3Const.DOSE_SCALE + 1e-9

        /**
         * How often a stopped pump is asked for its status, milliseconds: under the minute the
         * pump keeps the resume's own snapshot for.
         */
        const val RESUME_WATCH_MS = 50_000L

        /**
         * How far the pump's start of a temporary basal may sit from our acknowledgement of it.
         *
         * The start object 0x0A gives is stamped on a whole minute and can stand up to a minute
         * before the acknowledgement; with a few seconds of the pump's clock running behind, a
         * minute is not enough. Rate and duration are compared as well, so the width takes nothing
         * else in.
         */
        const val OWN_TBR_START_MS = 90_000L

        /**
         * How near the loop's own end of its temporary basal the pump is not judged: the two clocks
         * and the pump's own timer can disagree for some seconds about whether it still runs.
         */
        const val LOOP_END_SLACK_MS = 90_000L

        /**
         * Reports of nothing being delivered before the loop is stopped as well as the user told.
         *
         * The first is a notification, because the reservoir standing still for two samples is
         * suggestive and not yet proof of anything a person cannot explain — a set change, a pump
         * held out of the way. The second is the same finding surviving another whole round, and
         * at that point letting the loop go on deciding from insulin it thinks went in is worse
         * than stopping and asking.
         */
        const val NO_DELIVERY_REPORTS_BEFORE_STOP = 2

        /**
         * How often a held bolus looks to see whether the user has given up on it, milliseconds.
         *
         * Fine enough that the stop button answers at once, coarse enough that a minute of waiting
         * is a couple of hundred cheap checks and nothing else.
         */
        const val BOLUS_HOLD_POLL_MS = 250L
    }

    /**
     * An alarm re-reads the thing it is about.
     *
     * Cheap and obvious: the cycle is about to be interrupted to deal with the alarm anyway, so an
     * extra exchange costs nothing that matters. The battery is the one that would otherwise be
     * badly stale — it is read hourly, so at the moment a low battery alarm arrives the voltage on
     * the screen can be an hour old.
     */
    private fun readAlarmSubjects() {
        if (Atc3Alarm.LOW_BATTERY in atc3Pump.activeAlarms) atc3Manager.readStatusV2()
    }

    /**
     * Read the pump's firmware version once, and then not again.
     *
     * It cannot change while the pump is running, and a firmware update is a trip to the dealer, so
     * one exchange in the life of the app covers it. The version decides which of the two link
     * warnings the user gets, so it is read before either is raised.
     */
    private fun readVersionIfUnknown() {
        if (atc3Pump.version != null) return
        atc3Manager.readVersion()
    }

    /**
     * Refuse a pump whose firmware is older than [Atc3Const.MINIMUM_FIRMWARE], and say why.
     *
     * Such firmware has no Bluetooth password: the pump takes commands from anything within radio
     * range, and the driver cannot make it safer. The user is told to have the firmware updated by
     * the distributor; until then no command goes to the pump and the loop does not run.
     *
     * @return true when the pump is refused
     */
    private fun refuseOldFirmware(): Boolean {
        if (!atc3Pump.firmwareTooOld) {
            if (oldFirmwareTold) {
                rxBus.send(EventDismissNotification(Notification.PUMP_ERROR))
                oldFirmwareTold = false
            }
            return false
        }
        val firmware = atc3Pump.version?.firmwareText ?: "?"
        aapsLogger.error(LTag.PUMP, "ATC3: firmware $firmware is older than ${Atc3Const.MINIMUM_FIRMWARE.joinToString(".")}, refusing to run this pump")
        trace.event(Atc3TraceCat.DRV, "firmware_refused", "firmware" to firmware)
        uiInteraction.addNotification(
            Notification.PUMP_ERROR,
            rh.gs(R.string.atc3_firmware_too_old, firmware, Atc3Const.MINIMUM_FIRMWARE.joinToString(".")),
            Notification.URGENT
        )
        oldFirmwareTold = true
        return true
    }

    /** True while the user has been told the firmware is too old, so the notice can be taken down. */
    private var oldFirmwareTold = false

    /**
     * Tell the user when anything within radio range can drive their pump.
     *
     * The Bluetooth password is the only thing between the pump and a stranger: there is no pairing,
     * no bonding and no link encryption, and acceptance lasts for the link rather than for the
     * client that earned it, so an unprotected pump takes commands from whoever asks first. Which of
     * the two messages applies is not a matter of taste — a pump whose firmware has no password at
     * all cannot be fixed from here, and telling its owner to set one would send them looking for a
     * menu that does not exist.
     *
     * The notification is raised on every status read while the state lasts, the way the clock skew
     * warning is: AAPS replaces a notification of the same id rather than stacking them, and one the
     * user dismissed should come back while the pump is still open to the world.
     */
    private fun warnAboutUnprotectedLink() {
        when (atc3Manager.linkProtection) {
            Atc3LinkProtection.UNPROTECTED ->
                // NORMAL rather than the id's default IMPORTANT: the pump works, and the
                // user is being told to improve it, not that something has gone wrong.
                uiInteraction.addNotification(
                    Notification.WRONG_PUMP_PASSWORD,
                    rh.gs(R.string.atc3_password_not_set),
                    Notification.NORMAL
                )

            Atc3LinkProtection.UNSUPPORTED -> {
                // Nothing to notify about: there is no action for the user to take today, and a
                // notification that cannot be acted on is one they learn to swipe away. The status
                // screen says it instead, for as long as it is true.
                aapsLogger.debug(
                    LTag.PUMP,
                    "ATC3: this pump has no Bluetooth password at all, firmware " +
                        (atc3Pump.version?.firmwareText ?: "unknown")
                )
            }

            Atc3LinkProtection.PROTECTED,
            Atc3LinkProtection.UNKNOWN     -> rxBus.send(EventDismissNotification(Notification.WRONG_PUMP_PASSWORD))
        }
    }

    /**
     * Read the battery voltage at most once per [Atc3Const.BATTERY_READ_INTERVAL_MS] rather than in
     * every connection: Status V2 carries nothing else the driver uses, and a battery moves over
     * days.
     */
    private fun readBatteryIfDue() {
        val now = dateUtil.now()
        if (batteryReadAtMs != 0L && now - batteryReadAtMs < Atc3Const.BATTERY_READ_INTERVAL_MS) return
        if (atc3Manager.readStatusV2()) batteryReadAtMs = now
    }

    /**
     * Re-read the pump's stored profiles only when what is cached can no longer be trusted.
     *
     * They exist for one question, [isThisProfileSet]: is the pump still holding the AAPS
     * schedule. They change only when AAPS writes them or somebody edits them on the pump. A write
     * of our own re-reads them itself, as its proof that the write took. Otherwise the read is
     * triggered by any of three things, each answered from Status V1 at no cost:
     *
     * - nothing has ever been read, a fresh start where [isThisProfileSet] would otherwise answer
     *   "set" having compared against nothing;
     * - the pump has moved to a different profile than the one the rates were read for;
     * - the rate the pump is delivering right now disagrees with the cached profile's rate for the
     *   half hour we are in, which is how rates edited inside the profile already in use show up.
     *
     * That comparison is only meaningful while the scheduled rate is what is being delivered, so it
     * is skipped when a temporary basal is running or the pump is stopped. Those are the minority
     * of cycles, and a stale cache waits for the next ordinary one.
     */
    private fun readProfilesIfNeeded() {
        val active = atc3Pump.activeProfileIndex
        val stale = atc3Pump.pumpProfiles == null ||
            atc3Pump.profilesReadForIndex != active ||
            scheduleDisagrees()
        if (!stale) return
        if (atc3Manager.readBasalProfiles()) atc3Pump.profilesReadForIndex = active
    }

    /**
     * True when the rate the pump is delivering is not the one the cached profile says it should be.
     *
     * A tolerance of one basal step, for the same reason [isThisProfileSet] uses one: an exact
     * comparison would fire on every rounding artefact and re-read the profiles for ever.
     */
    private fun scheduleDisagrees(): Boolean {
        if (atc3Pump.tbrActive || atc3Pump.notDelivering) return false
        val expected = atc3Pump.scheduledRateFromProfile(dateUtil.now()) ?: return false
        val disagrees = abs(expected - atc3Pump.scheduledBasalRate) > pumpDescription.basalStep
        if (disagrees) {
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: the pump is delivering ${atc3Pump.scheduledBasalRate} where the stored profile " +
                    "says $expected, reading the profiles again"
            )
            trace.event(
                Atc3TraceCat.DRV, "profile_drift",
                "pump" to atc3Pump.scheduledBasalRate, "cached" to expected
            )
        }
        return disagrees
    }

    /** Everything the pump just said about itself, in one trace line. */
    private fun traceState() {
        trace.event(
            Atc3TraceCat.STATE, "pump",
            "battPct" to atc3Pump.batteryPercent,
            "battV" to atc3Pump.batteryVolts,
            "res" to atc3Pump.reservoirUnits,
            // The pump's own count of what it delivered today, at the snapshot.
            "today" to atc3Pump.deliveredTodayUnits,
            "snap" to atc3Pump.snapshotAtMs,
            "susp" to atc3Pump.suspended,
            "tbr" to atc3Pump.tbrActive,
            "tbrRate" to atc3Pump.tbrRate,
            "tbrMin" to atc3Pump.tbrElapsedMinutes,
            "basal" to atc3Pump.scheduledBasalRate,
            "iob" to atc3Pump.pumpActiveInsulin,
            "profile" to atc3Pump.activeProfileIndex,
            "alarms" to atc3Pump.activeAlarmCodes.joinToString("+").ifEmpty { "-" }
        )
    }

    // Pump state

    override val lastDataTime: Long get() = atc3Pump.lastConnection
    override val lastBolusTime: Long? get() = atc3Pump.lastBolusTime
    override val lastBolusAmount: Double? get() = atc3Pump.lastBolusAmount
    /**
     * The rate the pump's schedule calls for, whatever the pump is doing about it right now.
     *
     * `Pump` requires this to be free of current pump state, temporary basals and suspension alike,
     * and AAPS's own "disconnect pump" shows why: it stops delivery without touching this at all.
     * It writes a temporary basal of zero and a running mode, and those two carry the whole fact
     * (`LoopPlugin.goToZeroTemp`). A pump that stopped by itself is the same situation reached from
     * the other side, so it is reported the same way — the zero temporary basal is in
     * [app.aaps.pump.atc3.history.Atc3TbrTracker] and the running mode is AAPS's own answer to
     * [isSuspended].
     *
     * Status V1 puts its stopped marker where the scheduled rate goes, so while the pump is stopped
     * the rate comes from the schedule the driver holds. Before any profile has been read there is
     * nothing to fall back on, and zero is the honest answer: it also stops the loop, which is what
     * should happen when the driver knows nothing about the pump.
     */
    override val baseBasalRate: Double
        get() = if (atc3Pump.notDelivering) atc3Pump.scheduledRateFromProfile(dateUtil.now()) ?: 0.0
        else atc3Pump.scheduledBasalRate
    override val reservoirLevel: Double get() = atc3Pump.reservoirUnits

    /**
     * Battery charge, percent, worked out from the cell voltage.
     *
     * The pump reports the voltage of its cell and nothing else; the percentage is a straight line
     * over the voltage, see [app.aaps.pump.atc3.comm.Atc3StatusV2]. The voltage itself is available
     * as [batteryVolts].
     */
    override val batteryLevel: Int? get() = atc3Pump.batteryPercent

    /** Battery voltage the pump reports, volts. Zero until Status V2 has been read. */
    val batteryVolts: Double get() = atc3Pump.batteryVolts

    // Profile

    /**
     * Put the AAPS basal schedule into the pump.
     *
     * AAPS owns the schedule; the pump holds it in one slot, see [Atc3Const.DRIVER_PROFILE_INDEX].
     * Success is reported only after the pump has returned the same 48 rates.
     */
    override fun setNewBasalProfile(profile: Profile): PumpEnactResult = runBlocking {
        tracked("profile") { setNewBasalProfileInner(profile) }
    }

    private suspend fun setNewBasalProfileInner(profile: Profile): PumpEnactResult {
        if (!atc3Manager.isConnected) {
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(rh.gs(R.string.atc3_not_connected))
        }
        refusedWhileLocked()?.let { return it }
        ensureHistoryFresh()
        val rates = Atc3Pump.buildBasalSlots(profile) { PumpType.ATC3.determineCorrectBasalSize(it) }
        val failure = atc3Manager.writeBasalProfile(rates, pumpDescription.basalStep)
        return if (failure == null) {
            rxBus.send(EventDismissNotification(Notification.FAILED_UPDATE_PROFILE))
            uiInteraction.addNotificationValidFor(
                Notification.PROFILE_SET_OK, rh.gs(app.aaps.core.ui.R.string.profile_set_ok), Notification.INFO, 60
            )
            pumpEnactResultProvider.get().success(true).enacted(true)
        } else {
            aapsLogger.error(LTag.PUMP, "ATC3: setNewBasalProfile failed, $failure")
            uiInteraction.addNotification(
                Notification.FAILED_UPDATE_PROFILE, rh.gs(app.aaps.core.ui.R.string.failed_update_basal_profile), Notification.URGENT
            )
            pumpEnactResultProvider.get().success(false).enacted(false).comment(failure)
        }
    }

    /**
     * Compare the wanted schedule with what the pump actually holds.
     *
     * Both the rates and the active slot have to match, because a write lands in whichever profile
     * is active. A tolerance of one basal step is used: an exact comparison would report a
     * difference for every rounding artefact and make AAPS rewrite the profile in a loop.
     */
    override fun isThisProfileSet(profile: Profile): Boolean {
        // Before the pump has been read there is nothing to compare against. Reporting a mismatch
        // here would make AAPS queue a profile write it cannot yet carry out.
        val stored = atc3Pump.pumpProfiles?.getOrNull(Atc3Const.DRIVER_PROFILE_INDEX) ?: return true
        if (atc3Pump.activeProfileIndex != Atc3Const.DRIVER_PROFILE_INDEX) return false
        val wanted = Atc3Pump.buildBasalSlots(profile) { PumpType.ATC3.determineCorrectBasalSize(it) }
        return Atc3Pump.rateArraysMatch(wanted, stored, pumpDescription.basalStep)
    }

    // Delivery

    /**
     * Deliver a bolus and make sure AAPS knows about the insulin whatever happens next.
     *
     * The bolus is written into AAPS the moment the pump accepts the command, under a temporary id.
     * Only then is delivery followed. A bolus
     * the pump closes with its completion frame is closed at that frame's amount; one cut short is
     * closed at what the pump's own record says. Everything after the acknowledgement can fail
     * without the insulin going missing: the row already exists, the ledger remembers it, and the
     * next connection finishes the job.
     */
    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult =
        runBlocking { deliver(detailedBolusInfo) }

    private suspend fun deliver(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult = tracked(
        "bolus",
        "units" to detailedBolusInfo.insulin,
        "type" to detailedBolusInfo.bolusType.name,
        "lastBolus" to detailedBolusInfo.lastKnownBolusTime
    ) {
        deliverTreatmentInner(detailedBolusInfo)
    }

    private suspend fun deliverTreatmentInner(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        // Carbs travel their own way into AAPS and never through a pump. One arriving here would
        // mean the caller expected this method to store it, and it would be silently lost.
        require(detailedBolusInfo.carbs == 0.0) { detailedBolusInfo.toString() }
        val requested = detailedBolusInfo.insulin
        if (requested <= 0.0) {
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(rh.gs(R.string.atc3_bolus_zero))
        }
        if (!atc3Manager.isConnected) {
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(rh.gs(R.string.atc3_not_connected))
        }
        refusedWhileLocked()?.let { return it }

        // Nothing has been sent and nothing has been written yet, which is what makes this the only
        // place the hold can go: a cancel here costs nothing at all. See [holdApart].
        holdApart()?.let { return it }

        // Reconcile first: a bolus somebody gave from the pump or another device minutes ago
        // is then already accounted for and cannot be mistaken for the one about to be delivered.
        // The question in front of a bolus is whether anybody delivered insulin the loop does not
        // know about. The pump's count of delivered insulin answers it, and it is in Status V1,
        // which is cheaper than the history and fresh, unlike the last status the driver holds,
        // which can be minutes old.
        //
        // So the status is read first and the journals only when the count and the AAPS journal
        // disagree. Skipping them silently disables the stacking check below, which is why the
        // tolerance is one step and why anything unusual - no status, nothing to compare against -
        // reads the journals rather than skips them.
        activeTbrReadThisTick = false
        val statusRead = atc3Manager.readStatus()
        if (statusRead) {
            syncTbrFromStatus()
            holdPumpToLoop(synced = true, where = "bolus")
        }
        val verdict = if (!statusRead) Atc3StateCheck.Verdict.Unknown else checkState()
        val resolved = resolveState(verdict)
        trace.event(
            Atc3TraceCat.HIST, "bolus_baseline",
            "why" to verdict.trace(),
            "outcome" to resolved.resolution.name.lowercase()
        )
        val reconciled = resolved.reconciled

        stackedOnUnknownInsulin(detailedBolusInfo, reconciled)?.let { return it }
        smbOnTouchedTbr(detailedBolusInfo)?.let { return it }
        // A stopped pump turns every bolus down. The loop can decide an SMB on a status from before
        // a pause while the fresh status here says stopped; saying so here sends the pump nothing.
        if (statusRead && atc3Pump.notDelivering) {
            aapsLogger.warn(LTag.PUMP, "ATC3: the pump is stopped, not sending the bolus")
            trace.event(Atc3TraceCat.DRV, "bolus_held_stopped", "units" to detailedBolusInfo.insulin)
            watchForResume()
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(rh.gs(R.string.atc3_pump_suspended))
        }

        var temporaryId = 0L
        val outcome = atc3Manager.bolus(
            units = requested,
            onAccepted = { acceptedAt ->
                // The next bolus is held until this start is a minute old, so that the two records
                // the pump writes, each stamped with its bolus's start minute, fall in different
                // minutes. See [holdApart].
                bolusSpacing.started(acceptedAt)
                temporaryId = atc3HistorySync.registerPending(
                    ackAtMs = acceptedAt,
                    requestedUnits = requested,
                    type = detailedBolusInfo.bolusType
                )
            },
            onProgress = { delivered ->
                BolusProgressData.delivered = delivered
                atc3HistorySync.onProgress(temporaryId, delivered)
                rxBus.send(EventOverviewBolusProgress(rh, delivered, detailedBolusInfo.id))
            }
        )
        if (outcome !is Atc3BolusOutcome.Delivered) {
            val comment =
                if (outcome is Atc3BolusOutcome.Refused) rh.gs(R.string.atc3_refused_by_pump)
                else rh.gs(R.string.atc3_bolus_failed)
            aapsLogger.error(LTag.PUMP, "ATC3: bolus not started, $comment")
            return pumpEnactResultProvider.get().success(false).enacted(false).comment(comment)
        }
        // Progress came and the pump closed the bolus with its completion frame: the bolus was
        // delivered, and the frame carries what went in. Nothing is asked of the history; the
        // record, stamped with this bolus's start minute, is recognised whenever the history is
        // next read.
        if (outcome.completed && outcome.sawProgress &&
            atc3HistorySync.settleCompleted(temporaryId, outcome.reportedUnits)
        ) {
            return judgeBolus(requested, outcome.reportedUnits, outcome.cancelled)
        }

        // No completion frame: the bolus was cut short, by a cancel, an alarm or the link, and
        // what went in is for the pump's history to say.
        val history = atc3Manager.readBolusHistoryUntil(Atc3Const.BOLUS_RECORD_POLL_ATTEMPTS) {
            atc3HistorySync.wouldResolve(it.records, temporaryId)
        }
        val confirmed = history?.let { atc3HistorySync.reconcileBoluses(it.records, it.recordCount) }?.confirmedUnits

        if (confirmed == null) {
            // Two different things reach here, and only one of them is evidence.
            //
            // The history was read and this bolus is not in it: the pump was asked and answered,
            // and the record's absence says something about the bolus.
            //
            // The history could not be read at all -- which is what happens when the link is what
            // cut the bolus short, so it is the likelier of the two on this path -- and then
            // nothing has been observed about the bolus whatever. The pump goes on delivering a
            // bolus it has accepted after the phone has gone; the last progress frame the driver
            // saw is where the phone stopped watching, not where the pump stopped.
            val asked = history != null
            // The insulin is delivered and recorded at the amount asked for; only the pump's own
            // account of it is missing. Ask for another connection so that it is not missing long.
            aapsLogger.error(
                LTag.PUMP,
                if (asked) "ATC3: the pump has not recorded this bolus yet"
                else "ATC3: the pump could not be asked about this bolus"
            )
            commandQueue.readStatus(rh.gs(R.string.atc3_bolus_unconfirmed), null)
            // Judged on what the pump reported while delivering, because that is all there is. A
            // bolus that stalled at nothing must not pass as done merely because its record is
            // missing; one that ran to the end is fine, and its amount is corrected on the next read.
            val judged = judgeBolus(requested, outcome.reportedUnits, outcome.cancelled, fromRecord = false)
            // Unsuccessful either way -- the loop must not take as delivered what nothing has
            // confirmed -- but a pump that was never asked is not a pump that delivered less, and
            // saying so names a shortfall nobody measured.
            return if (judged.success || !asked) judged.comment(rh.gs(R.string.atc3_bolus_unconfirmed)) else judged
        }
        return judgeBolus(requested, confirmed, outcome.cancelled)
    }

    /**
     * Hold a bolus back until the previous one's record can no longer be taken for its own.
     *
     * The pump stamps a bolus record with the minute the bolus **started**, and that minute with
     * the dose is what matches the record to the bolus. Two boluses started in one minute with
     * equal doses leave nothing to tell their records apart. So they are kept apart instead, by
     * [Atc3Const.BOLUS_SPACING_MS] measured start to start -- see [Atc3BolusSpacing].
     *
     * **Held, not refused.** The delivery window simply blocks, exactly as it already does while
     * the driver waits for a connection, and the command goes out when it may. Refusing would put
     * the user in front of a bolus that did not happen for a reason that is not about their
     * therapy.
     *
     * **The wait costs no command budget.** [Atc3Const.COMMAND_BUDGET_MS] is counted from inside
     * [app.aaps.pump.atc3.manager.Atc3Manager], per exchange, against a deadline taken when that
     * exchange begins. Nothing is measuring here, and this runs before the first byte reaches the
     * pump, so a minute of waiting takes nothing away from the time delivery is allowed. That is
     * the point: a hold that ate the budget would be a refusal by another road.
     *
     * **A cancel here costs nothing.** Nothing has been sent and no row exists, so there is nothing
     * to undo; the answer is the one the driver already gives when a bolus never left --
     * unsuccessful, not enacted -- and the wizard shows no error for it because the stop was
     * pressed (`WizardBolusExecutorImpl`). The cancel is seen through
     * [BolusProgressData.stopPressed], which is what `cancelAllBoluses` sets on the running
     * bolus command and what the other drivers watch; `stopBolusDelivering` cannot serve here
     * because there is no bolus running for the pump to stop.
     *
     * @return null when the bolus may go ahead, or the result to answer with when it was cancelled
     */
    private suspend fun holdApart(): PumpEnactResult? {
        val holdMs = bolusSpacing.waitMs(dateUtil.now())
        if (holdMs <= 0L) return null
        aapsLogger.debug(
            LTag.PUMP,
            "ATC3: holding the bolus for ${holdMs}ms, the previous one started too recently for the records to be told apart"
        )
        var remaining = holdMs
        while (remaining > 0L) {
            if (BolusProgressData.stopPressed) {
                aapsLogger.debug(LTag.PUMP, "ATC3: the bolus was cancelled while held, after ${holdMs - remaining}ms")
                trace.event(
                    Atc3TraceCat.DRV, "bolus_hold_cancelled",
                    "askedMs" to holdMs,
                    "heldMs" to (holdMs - remaining)
                )
                // Not a failure: the user pressed stop, and the bolus was still waiting, so nothing
                // was ever sent. Said as its own thing, because "Bolus could not be delivered" in
                // answer to one's own stop reads as the pump having gone wrong. Still neither
                // enacted nor a success, so the loop cannot take as delivered insulin that never
                // left the reservoir.
                return pumpEnactResultProvider.get().success(false).enacted(false)
                    .comment(rh.gs(R.string.atc3_bolus_cancelled))
            }
            val step = minOf(remaining, BOLUS_HOLD_POLL_MS)
            delay(step)
            remaining -= step
        }
        trace.event(Atc3TraceCat.DRV, "bolus_held", "ms" to holdMs)
        return null
    }

    /**
     * Judge a finished bolus by what the pump wrote down, not by the fact that it was accepted.
     *
     * A bolus that stopped part way through, by one bolus step or more, is a failure however
     * cleanly the command went out, and saying otherwise leaves the loop believing the insulin it
     * asked for is on board.
     *
     * A bolus the user stopped is the exception: delivering less is exactly what was asked for, so
     * that counts as done.
     *
     * Failure here costs nothing that has to be put back: the amount really delivered is already
     * recorded from the pump's own record, and on a failed microbolus the loop simply recalculates a
     * second later with microboluses switched off.
     *
     * @param fromRecord true when [delivered] is what the pump wrote down, false when it is the
     *   last the pump reported while delivering. The judgement is the same either way -- a bolus
     *   nothing confirms must not pass as done -- but only one of the two is the pump's own account,
     *   and the log should not call the other one that.
     */
    private fun judgeBolus(
        requested: Double,
        delivered: Double,
        cancelled: Boolean,
        fromRecord: Boolean = true
    ): PumpEnactResult {
        val short = requested - delivered >= pumpDescription.bolusStep
        if (!short || cancelled) {
            return pumpEnactResultProvider.get().success(true).enacted(true).bolusDelivered(delivered)
        }
        aapsLogger.error(
            LTag.PUMP,
            if (fromRecord) "ATC3: the pump recorded $delivered U of the $requested U asked for, reporting a failure"
            else "ATC3: the pump was last seen at $delivered U of the $requested U asked for and has not " +
                "accounted for the bolus, reporting a failure"
        )
        return pumpEnactResultProvider.get().success(false).enacted(true).bolusDelivered(delivered)
            .comment(rh.gs(R.string.atc3_bolus_short, delivered, requested))
    }

    /**
     * Refuse an SMB the loop decided on without knowing about insulin somebody else had given.
     *
     * The read above is the last and freshest point in the whole chain: AAPS's own two defences
     * both consult the database before this driver ever gets to look at the pump, so a bolus given
     * on the keypad reaches them too late. If that read has just imported a bolus newer than the
     * moment the loop stamped its decision, the loop's insulin on board was wrong when it asked for
     * this SMB, and delivering it would stack insulin on insulin.
     *
     * Only SMBs are refused. A bolus the user asked for themselves is theirs to decide on, and they
     * are standing at the phone; taking it away from them would be the wrong kind of safe.
     *
     * Nothing has to be compensated for afterwards: on a failed SMB the loop re-runs itself a
     * second later with micro boluses switched off, so it goes on managing basal with the corrected
     * insulin on board.
     *
     * @return the result to return from [deliverTreatment], or null to carry on and deliver
     */
    private fun stackedOnUnknownInsulin(
        detailedBolusInfo: DetailedBolusInfo,
        reconciled: Atc3HistorySync.ReconcileResult?
    ): PumpEnactResult? {
        if (detailedBolusInfo.bolusType != BS.Type.SMB) return null
        // Zero means nobody stamped the decision, so there is nothing to compare against.
        if (detailedBolusInfo.lastKnownBolusTime == 0L) return null
        val imported = reconciled?.newestImportedAtMs ?: return null
        if (imported <= detailedBolusInfo.lastKnownBolusTime) return null

        aapsLogger.warn(
            LTag.PUMP,
            "ATC3: refusing the SMB, a bolus at $imported reached us after the loop decided at " +
                "${detailedBolusInfo.lastKnownBolusTime}"
        )
        trace.event(
            Atc3TraceCat.DRV, "smb_refused",
            "importedAt" to imported,
            "lastBolus" to detailedBolusInfo.lastKnownBolusTime,
            "units" to detailedBolusInfo.insulin
        )
        return pumpEnactResultProvider.get().success(false).enacted(false)
            .comment(rh.gs(R.string.atc3_smb_stale_iob))
    }

    /**
     * Put the phone's time into the pump when the pump's own bolus history says its clock is off.
     *
     * The pump has no timezone and no daylight saving, only a wall clock, so nothing moves it but
     * this. The evidence about that clock is the bolus history: each record carries the minute its
     * bolus started, and [Atc3ClockWatch] counts the history reads that found the records of our
     * boluses a minute off the minute those boluses started. After
     * [Atc3Const.CLOCK_JOURNAL_MISSES_TO_SET] such reads in a row the clock is written. The write
     * sets the seconds too, so right after it the two clocks agree. Whether the write took is what
     * the pump answers to it.
     *
     * Two things are not decided from the history:
     *
     * - a status snapshot more than [Atc3Const.CLOCK_MAX_CORRECTION_MS] away from the phone at the
     *   moment it was read. The snapshot is not a clock and nothing takes its time; it is only
     *   compared with the phone's, and a difference that large is a disagreement about what time
     *   it is. Nothing is written then, an alarm is raised and the loop stops
     *   ([isLoopInvocationAllowed]);
     * - the phone reporting a new timezone or a daylight saving change ([timezoneOrDSTChanged]).
     *   Within the limit above the clock is written at once and the user is told; the history could
     *   not see a move of more than a minute.
     */
    private suspend fun correctPumpClockIfAdrift() {
        val apart = abs(atc3Pump.snapshotAtMs - atc3Pump.statusReadAtMs)
        if (apart >= Atc3Const.CLOCK_MAX_CORRECTION_MS) {
            aapsLogger.error(
                LTag.PUMP,
                "ATC3: the pump's status is $apart ms away from the phone, not setting the clock, stopping the loop"
            )
            trace.event(Atc3TraceCat.DRV, "clock_too_far", "apartMs" to apart)
            // The id is named for a limit of a day; here it means a time change larger than the
            // driver will make on its own. URGENT rather than the id's default LOW: this one stops
            // the loop.
            uiInteraction.addNotification(
                Notification.OVER_24H_TIME_CHANGE_REQUESTED,
                rh.gs(R.string.atc3_clock_skew_too_large),
                Notification.URGENT
            )
            return
        }
        rxBus.send(EventDismissNotification(Notification.OVER_24H_TIME_CHANGE_REQUESTED))

        // The middle band: more than five minutes apart, or the phone's own time moved. The
        // clock is put right at once and the user is told. The bolus history cannot be the
        // trigger here: it pairs our boluses within a minute, and a clock this far off pairs none.
        if (phoneTimeChanged || apart > Atc3Const.CLOCK_QUIET_CORRECTION_MS) {
            val why = if (phoneTimeChanged) "the phone's time changed" else "the pump's snapshot is $apart ms from the phone"
            if (writeClockAfterPairing(why)) {
                val text = if (phoneTimeChanged) rh.gs(R.string.atc3_clock_follows_phone)
                else rh.gs(R.string.atc3_clock_set_after_drift, apart / 60_000L)
                phoneTimeChanged = false
                uiInteraction.addNotificationValidFor(Notification.INSIGHT_DATE_TIME_UPDATED, text, Notification.INFO, 60)
            }
            return
        }

        val now = dateUtil.now()
        // A change of the loop's running mode asks for the clock, but not for every one of them:
        // AAPS rewrites its mode more than once around a stopped pump, and each rewrite would be
        // a clock write of its own. Once the clock has been set, a mode change within
        // [Atc3Const.CLOCK_SYNC_MIN_GAP_MS] of it adds nothing.
        if (clockSyncWanted && clockSyncedAtMs != 0L && now - clockSyncedAtMs < Atc3Const.CLOCK_SYNC_MIN_GAP_MS) clockSyncWanted = false
        if (clockSyncWanted || now - clockSyncedAtMs >= Atc3Const.CLOCK_SYNC_EVERY_MS) {
            val why = when {
                clockSyncWanted       -> "the loop's running mode changed"
                clockSyncedAtMs == 0L -> "AAPS has started"
                else                  -> "the clock was last set ${(now - clockSyncedAtMs) / 60_000L} min ago"
            }
            if (writeClockAfterPairing(why)) clockSyncWanted = false
            return
        }

        if (!clockWatch.needsSetting(now)) {
            rxBus.send(EventDismissNotification(Notification.PUMP_WARNING))
            return
        }

        writeClockAfterPairing("the pump's records put our boluses a minute off, or stamped our temporary basals too far from their acknowledgement, twice in a row")
    }

    /**
     * Write the phone's time into the pump, after the bolus history is read and taken in.
     *
     * Read first, always: our boluses are paired with their records and our rows moved onto the
     * pump's minutes, and strangers' boluses are imported, all on the clock they were stamped with.
     * After the write the pump stamps new records on the new clock; one read holding records from
     * both sides of the write could not be paired under one shift, and a clock put back would leave
     * the new records behind the place imports start from. That place is moved to the moment of the
     * write ([Atc3HistorySync.onPumpClockWritten]). No history, no write: the clock is still there to
     * correct on the next connection.
     *
     * @return true when the pump acknowledged the new time
     */
    private suspend fun writeClockAfterPairing(why: String): Boolean {
        // The clock write is a control command like any other, so a locked pump refuses it. Spending
        // the exchange to be told no changes nothing: the clock is still there to correct the moment
        // the user unlocks the pump. The warning stays up, because the clock really is out.
        if (atc3Pump.locked) {
            aapsLogger.debug(LTag.PUMP, "ATC3: $why, but the pump is locked, not setting its clock")
            uiInteraction.addNotification(Notification.PUMP_WARNING, rh.gs(R.string.atc3_clock_not_set), Notification.NORMAL)
            return false
        }
        val history = readBolusHistory()
        if (history == null) {
            aapsLogger.debug(LTag.PUMP, "ATC3: could not read the bolus history, not setting the pump clock yet")
            uiInteraction.addNotification(Notification.PUMP_WARNING, rh.gs(R.string.atc3_clock_not_set), Notification.NORMAL)
            return false
        }
        atc3HistorySync.reconcileBoluses(history.records, history.recordCount)

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
        // The snapshot times the comparison rests on have jumped with the clock: nothing to
        // compare the next snapshot against.
        stateCheck.forget()
        rxBus.send(EventDismissNotification(Notification.PUMP_WARNING))
        return true
    }

    /**
     * Stop the loop while the pump and the phone disagree about what time it is.
     *
     * The last status snapshot is compared with the phone clock at the moment it was read; more
     * than [Atc3Const.CLOCK_MAX_CORRECTION_MS] apart and [correctPumpClockIfAdrift] writes nothing
     * and raises an alarm. A pump that far off stamps its records hours away from where AAPS thinks
     * it is, so the loop does not run until someone has put the two clocks back together.
     *
     * Read straight off the last status rather than kept in a flag: this is called whether or not
     * the pump is connected, and the last reading is the most recent thing there is to go on. Before
     * any status has been read both numbers are zero, which blocks nothing. AAPS lifts the block by
     * itself once a later status is back within the limit.
     */
    override fun isLoopInvocationAllowed(value: Constraint<Boolean>): Constraint<Boolean> {
        if (atc3Pump.firmwareTooOld) value.set(false, rh.gs(R.string.atc3_firmware_loop_blocked), this)
        if (abs(atc3Pump.snapshotAtMs - atc3Pump.statusReadAtMs) >= Atc3Const.CLOCK_MAX_CORRECTION_MS)
            value.set(false, rh.gs(R.string.atc3_clock_loop_blocked), this)
        // Told once and it happened again: the pump says it is delivering and the reservoir has
        // not moved through another round of the whole thing. Whatever the loop decides next would
        // be decided on insulin it believes went in, and the evidence is that it did not. Stop
        // until somebody has looked at the pump.
        if (deliveryStoppedReports >= NO_DELIVERY_REPORTS_BEFORE_STOP)
            value.set(false, rh.gs(R.string.atc3_no_delivery_loop_blocked), this)
        return value
    }

    /**
     * The phone changed timezone or went on or off daylight saving.
     *
     * The pump keeps a wall clock, so its time is now wrong by whatever the phone moved, and the
     * bolus history cannot see a move of more than a minute. So the change is remembered and the
     * clock written on the next connection, in [correctPumpClockIfAdrift]. Nothing is sent from
     * here: this is not the command queue's thread and the pump may not even be connected.
     */
    override fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {
        phoneTimeChanged = true
        aapsLogger.debug(LTag.PUMP, "ATC3: phone time changed, $timeChangeType, asking to read the pump")
        commandQueue.readStatus(rh.gs(R.string.atc3_clock_phone_time_changed), null)
    }

    /**
     * Tell the user when the pump itself is stopped.
     *
     * AAPS turns [isSuspended] into its own suspended running mode, which stops the loop from
     * acting, but that reads as "the loop is suspended" and not as "the pump is delivering nothing".
     * They are different facts and the second is the one the user can do something about, so it is
     * said plainly.
     *
     * Repeating this on every poll costs nothing: notifications are keyed by id, and adding one
     * that is already showing only refreshes it.
     */
    private fun announceSuspension(suspended: Boolean) {
        if (suspended) {
            uiInteraction.addNotification(Notification.PUMP_SUSPENDED, rh.gs(R.string.atc3_pump_suspended), Notification.NORMAL)
        } else {
            rxBus.send(EventDismissNotification(Notification.PUMP_SUSPENDED))
        }
    }

    /**
     * Tell the user what the pump is raising right now.
     *
     * Status V1 carries this in every status read at no extra cost, so the user hears about an
     * alarm on the next poll rather than the next time they look at the pump. The pump announces
     * nothing by itself, even on a link that is up and subscribed, so polling is the only way
     * anyone finds out.
     *
     * **This stops at telling.** Nothing here touches [isSuspended] or the basal rate: on this pump
     * an alarm is raised by the pressure a bolus builds, and reading it as "the pump has stopped"
     * would make AAPS stop counting insulin the pump may still be delivering. The one alarm that is
     * a stop is handled next door, in [stopOnDeliveryAlarm]. See [app.aaps.pump.atc3.comm.Atc3Alarm].
     */
    private fun announceAlarms() {
        val codes = atc3Pump.activeAlarmCodes
        if (codes.isEmpty()) {
            if (alarmNotificationShown) {
                rxBus.send(EventDismissNotification(Notification.PUMP_ERROR))
                alarmNotificationShown = false
            }
            return
        }
        val names = codes.joinToString(", ") { code ->
            Atc3Alarm.ofCode(code)?.let { rh.gs(alarmLabel(it)) } ?: rh.gs(R.string.atc3_alarm_unknown, code)
        }
        uiInteraction.addNotification(Notification.PUMP_ERROR, rh.gs(R.string.atc3_alarm_active, names), Notification.URGENT)
        alarmNotificationShown = true
    }

    /**
     * Put the pump on hold while it is raising an alarm under which it delivers nothing: an empty
     * reservoir, or the daily dose limit.
     *
     * Telling AAPS that nothing is being delivered, which [Atc3Pump.notDelivering] does, is not the
     * whole job. The pump comes out of such an alarm still holding whatever temporary basal was
     * running and picks it up again by itself within seconds of the alarm clearing, however long
     * ago that temporary basal was set; under the daily limit it goes on taking commands and
     * delivering a fraction of each. That leaves the two sides disagreeing about what is
     * happening to somebody who has walked away from the phone.
     *
     * So the pump is stopped as well. Both sides then say the same thing, and delivery starts again
     * when somebody looks at the pump and starts it, not when an alarm happens to clear.
     *
     * Only these alarms, [Atc3Alarm.stopsDelivery]. The others say nothing about basal, and
     * stopping the pump for them would be this driver halting therapy on its own guess.
     */
    private fun stopOnDeliveryAlarm() {
        val alarm = atc3Pump.activeAlarms.firstOrNull { it.stopsDelivery } ?: return
        // The pump's own field, not notDelivering: what is being asked here is whether the stop
        // still has to be sent, and under this alarm notDelivering is true before it ever is.
        if (atc3Pump.suspended) return
        // A locked pump refuses every control command, this one included. Sending it anyway would
        // leave the two sides in different states every cycle, which is the one thing this method
        // exists to prevent, and the driver would go on believing it had done its part. Say so
        // instead: the pump has to be unlocked by hand.
        if (atc3Pump.locked) {
            trace.event(Atc3TraceCat.DRV, "alarm_stop", "alarm" to alarm.name, "ok" to false, "why" to "locked")
            aapsLogger.error(LTag.PUMP, "ATC3: the pump raises ${alarm.name} and is locked, cannot stop it")
            uiInteraction.addNotification(
                Notification.PUMP_ERROR,
                rh.gs(R.string.atc3_alarm_stop_locked),
                Notification.URGENT
            )
            return
        }
        val failure = atc3Manager.setSuspended(true)
        trace.event(Atc3TraceCat.DRV, "alarm_stop", "alarm" to alarm.name, "ok" to (failure == null), "why" to (failure ?: "-"))
        if (failure != null) aapsLogger.error(LTag.PUMP, "ATC3: could not stop the pump on ${alarm.name}: $failure")
    }

    private fun alarmLabel(alarm: Atc3Alarm): Int = when (alarm) {
        Atc3Alarm.LOW_BATTERY            -> R.string.atc3_alarm_low_battery
        Atc3Alarm.BLOOD_GLUCOSE_REMINDER -> R.string.atc3_alarm_bg_reminder
        Atc3Alarm.BUTTON_ERROR           -> R.string.atc3_alarm_button_error
        Atc3Alarm.NO_DELIVERY            -> R.string.atc3_alarm_no_delivery
        Atc3Alarm.RESERVOIR_EMPTY        -> R.string.atc3_alarm_reservoir_empty
        Atc3Alarm.DAILY_LIMIT            -> R.string.atc3_alarm_daily_limit
    }

    /**
     * Put the pump's alarms into the AAPS history, without spending an exchange on it every cycle.
     *
     * Read when the status says something is being raised, and otherwise every
     * [Atc3Const.ALARM_READ_INTERVAL_MS]. The interval is what catches an alarm that came and went
     * between two polls: the slots only show what is up at the moment they were sampled, and
     * Status V1 is a snapshot up to a minute old at that.
     */
    /**
     * Put the pump's refills into the AAPS history: read when the reservoir went up since the last
     * tick, which is what a refill looks like from the status, and otherwise every
     * [Atc3Const.ALARM_READ_INTERVAL_MS] for one the ticks did not see.
     */
    private suspend fun readRefillsIfDue() {
        val now = dateUtil.now()
        val level = atc3Pump.reservoirUnits
        val wentUp = reservoirSeenUnits >= 0.0 && level > reservoirSeenUnits + Atc3Const.DOSE_SCALE
        reservoirSeenUnits = level
        val due = wentUp || refillsReadAtMs == 0L || now - refillsReadAtMs >= Atc3Const.ALARM_READ_INTERVAL_MS
        if (!due) return
        val records = atc3Manager.readRefillHistory() ?: return
        refillsReadAtMs = now
        atc3HistorySync.recordRefills(records)
    }

    private suspend fun readAlarmsIfDue() {
        val now = dateUtil.now()
        val raising = atc3Pump.activeAlarmCodes.isNotEmpty()
        val due = raising || alarmsReadAtMs == 0L || now - alarmsReadAtMs >= Atc3Const.ALARM_READ_INTERVAL_MS
        if (!due) return
        val records = atc3Manager.readAlarmHistory() ?: return
        alarmsReadAtMs = now
        atc3HistorySync.recordAlarms(records)
    }

    /**
     * Make sure the pump's boluses have been read recently before changing delivery.
     *
     * The command queue finishes connecting before it takes the first command
     * (`QueueWorker`: the connect checks come before `queue.pickup()`), so calling this at the top
     * of a command means **AAPS never changes delivery on knowledge older than
     * [Atc3Const.HISTORY_FRESH_MS]**. That is the whole barrier, and it costs one exchange per
     * connection rather than one per command.
     *
     * It also makes AAPS's own defence work: once a bolus given on the pump is in the database, the
     * queue refuses an SMB that follows it within `ApsMaxSmbFrequency`. Since the loop applies its
     * temporary basal before its SMB, the temporary basal command is what puts the record there in
     * time.
     *
     * [getPumpStatus] and [deliverTreatment] do not call this: both already read the history
     * unconditionally, which is stronger.
     */
    private suspend fun ensureHistoryFresh() {
        val now = dateUtil.now()
        // The pump's count agreeing with the AAPS journal is the same proof the history read
        // gives, and it was had for free at the last tick: nobody delivered insulin AAPS does not
        // hold. It saves a bolus history read in front of every loop command.
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

    /**
     * Read the pump's boluses, reaching for the full history only when records have aged out.
     *
     * The periodic search is one cheap exchange but stops at ten records, so a stretch of history
     * can fall off its edge while the phone is out of range. Its count byte is what gives that
     * away, and only then is the whole history worth the extra frames; see
     * [app.aaps.pump.atc3.history.Atc3BolusReconciler.recordsMissing].
     */
    private fun readBolusHistory(): Atc3BolusHistory? {
        val latest = atc3Manager.readBolusHistory() ?: return null
        if (!atc3HistorySync.recordsMissing(latest)) return latest
        trace.event(Atc3TraceCat.DRV, "full_history", "held" to latest.recordCount, "sent" to latest.records.size)
        aapsLogger.debug(
            LTag.PUMP,
            "ATC3: the pump holds ${latest.recordCount} records but sent ${latest.records.size}, reading the full history"
        )
        // Falling back to what the search did return is still better than nothing: those records
        // are the recent ones, which is where a bolus of ours would be.
        return atc3Manager.readFullBolusHistory() ?: latest
    }

    override fun stopBolusDelivering() {
        aapsLogger.debug(LTag.PUMP, "ATC3: stopping bolus")
        if (!atc3Manager.stopBolus()) aapsLogger.error(LTag.PUMP, "ATC3: could not stop the bolus")
    }

    // The profile is not needed: it is only there to turn a rate into a percentage, and this
    // pump is told absolute rates.
    override fun setTempBasalAbsolute(
        absoluteRate: Double,
        durationInMinutes: Int,
        profile: Profile,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult = runBlocking {
        tracked(
            "tbr",
            "rate" to absoluteRate,
            "min" to durationInMinutes,
            "enforceNew" to enforceNew,
            "type" to tbrType.name
        ) {
            setTempBasalAbsoluteInner(absoluteRate, durationInMinutes, enforceNew, tbrType)
        }
    }

    private suspend fun setTempBasalAbsoluteInner(
        absoluteRate: Double,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType = PumpSync.TemporaryBasalType.NORMAL
    ): PumpEnactResult {
        if (!atc3Manager.isConnected) {
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(rh.gs(R.string.atc3_not_connected))
        }
        refusedWhileLocked()?.let { return it }
        // Whatever somebody set or cancelled by hand since the last look is recorded before this
        // command replaces it, and read back from the journal after; otherwise the loop's own
        // command would overwrite it and AAPS would never see it.
        val handled = atc3Manager.readStatus() && noticeHand(synced = false, where = "set") != null
        // Only the loop's own temporary basal is a decision computed from data; the user's
        // disconnect and suspend carry their own type and go through.
        dataChangedUnderTheLoop(loops = tbrType == PumpSync.TemporaryBasalType.NORMAL)?.let { return it }
        val rate = PumpType.ATC3.determineCorrectBasalSize(absoluteRate)
        if (enforceNew && atc3Pump.tbrActive) {
            aapsLogger.debug(LTag.PUMP, "ATC3: cancelling the running temporary basal first")
            atc3Manager.cancelTempBasal()?.let {
                aapsLogger.error(LTag.PUMP, "ATC3: could not clear the running temporary basal, $it")
                return pumpEnactResultProvider.get().success(false).enacted(false).comment(it)
            }
            val cancelledAt = atc3Manager.lastTbrCancelAckMs.takeIf { it > 0L } ?: dateUtil.now()
            atc3HistorySync.tbrStopped(cancelledAt, journal = journal)
        }
        val result = atc3Manager.setTempBasal(rate, durationInMinutes)
        result.failure?.let {
            aapsLogger.error(LTag.PUMP, "ATC3: setTempBasalAbsolute failed, $it")
            return pumpEnactResultProvider.get().success(false).enacted(false).comment(it)
        }
        recordOwnTbr(result, tbrType)
        if (handled) readJournalAfterHand()
        return pumpEnactResultProvider.get().success(true).enacted(true)
            .absolute(result.rate).duration(result.durationMinutes)
    }

    /** A temporary basal the pump has just accepted from this driver goes into the books and AAPS. */
    private suspend fun recordOwnTbr(result: Atc3TbrResult, type: PumpSync.TemporaryBasalType = PumpSync.TemporaryBasalType.NORMAL) {
        // The pump's own record of the command, object 0x0A, so that AAPS's record carries the
        // start the pump keeps. Taken only when it is this command; otherwise the record carries the
        // moment of acknowledgement and the next tick asks again.
        val record = if (atc3Manager.readActiveTbr()) atc3Manager.activeTbr else null
        // The same command by rate and duration, wherever its stamp sits: how far the stamp sits
        // from the acknowledgement is what the clock watch wants to know, see Atc3ClockWatch.
        if (record != null && isSameCommand(record, result)) {
            val lagMs = result.acceptedAtMs - record.startTimestamp
            clockWatch.ownTbrStamped(lagMs, dateUtil.now())
            trace.event(Atc3TraceCat.TBR, "own_stamp", "lagMs" to lagMs, "clockSet" to clockWatch.needsSetting(dateUtil.now()))
        }
        val pumpStart = record?.takeIf { isCommandOf(it, result) }
        if (pumpStart == null) aapsLogger.debug(LTag.PUMP, "ATC3: the pump's start of the temporary basal just set is not known yet")
        atc3HistorySync.tbrStartedByAaps(result.acceptedAtMs, result.rate, result.durationMinutes, pumpStart, type = type, journal = journal)
    }

    /**
     * Whether object `0x0A` holds the temporary basal command just accepted: an absolute rate, the
     * same rate and duration, and a start close to the acknowledgement.
     */
    private fun isCommandOf(record: Atc3TbrStatus, result: Atc3TbrResult): Boolean =
        isSameCommand(record, result) && abs(record.startTimestamp - result.acceptedAtMs) <= OWN_TBR_START_MS

    /** Whether object `0x0A` holds a command of the same rate and duration, with a start it filled in. */
    private fun isSameCommand(record: Atc3TbrStatus, result: Atc3TbrResult): Boolean {
        val rate = record.rate ?: return false
        return Math.round(rate / Atc3Const.DOSE_SCALE) == Math.round(result.rate / Atc3Const.DOSE_SCALE) &&
            record.durationMinutes == result.durationMinutes &&
            record.isStartPlausible(pumpNow = dateUtil.now())
    }

    /** The pump works in absolute rates, so percentage temp basals are not offered. */
    override fun setTempBasalPercent(
        percent: Int,
        durationInMinutes: Int,
        profile: Profile,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult = unsupported(R.string.atc3_not_supported_percent_tbr)

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult =
        runBlocking { tracked("tbr_cancel", "enforceNew" to enforceNew) { cancelTempBasalInner(enforceNew) } }

    /**
     * @param enforceNew true for the user's own cancel -- reconnecting the pump, resuming the
     *   loop, the actions screen -- which goes through whatever the journals said; the loop's
     *   own cancel comes without it
     */
    private suspend fun cancelTempBasalInner(enforceNew: Boolean = false): PumpEnactResult {
        if (!atc3Manager.isConnected) {
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(rh.gs(R.string.atc3_not_connected))
        }
        // Ask the pump rather than trusting a cached flag: reporting a successful cancel without
        // having sent anything is the one answer that must never be given here.
        if (!atc3Manager.readStatus()) {
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(rh.gs(R.string.atc3_not_connected))
        }
        val handled = noticeHand(synced = false, where = "cancel") != null
        // A stopped pump delivers nothing, its temporary basal included: the status still reports
        // that temporary basal as running, but that is its timer, not insulin. So there is nothing to
        // cancel and nothing is sent. The open row in AAPS is the stop, and a cancel must not end it.
        // The same under an alarm that stops delivery: the pump takes the cancel and drops its
        // temporary basal while delivering nothing, and the stop's row would be closed for it.
        // AAPS asks for exactly this cancel whenever it sees the pump stopped (suspendLoop).
        // The stop itself is still written into AAPS from the status just read, as any command
        // does: a short pause may be over before the next tick, and then this is the only look at it.
        if (atc3Pump.notDelivering) {
            activeTbrReadThisTick = false
            syncTbrFromStatus()
            aapsLogger.debug(LTag.PUMP, "ATC3: the pump is stopped, nothing to cancel")
            trace.event(Atc3TraceCat.DRV, "tbr_cancel_skipped", "why" to "suspended")
            if (handled) readJournalAfterHand()
            // This is often the only look at a stop, AAPS asking for the cancel the moment it
            // sees the pump stopped, so the resume is watched for from here as from a tick.
            watchForResume()
            return pumpEnactResultProvider.get().success(true).enacted(false).isTempCancel(true)
        }
        dataChangedUnderTheLoop(loops = !enforceNew)?.let { return it }
        if (!atc3Pump.tbrActive) {
            // Nothing is running, so the pump is already in the wanted state. AAPS's own copy may
            // still be open, from a temporary basal cancelled on the pump itself, so close it where
            // it ended: at the pump's end when it went early, at its own end when its time is over.
            val open = atc3HistorySync.openTbr()
            val now = dateUtil.now()
            val endedAt =
                if (Atc3TbrTracker.needsEndRead(open, atc3Pump.notDelivering, false, now) && atc3Manager.readFinishedTbr())
                    atc3HistorySync.realEndOf(atc3Manager.finishedTbr)
                else null
            val ranOut = open?.takeIf { !it.suspension }?.let { it.startedAtMs + it.ownDurationMs }?.takeIf { it <= now }
            atc3HistorySync.tbrStopped(endedAt ?: ranOut ?: now, byStamp = endedAt != null, journal = journal)
            atc3HistorySync.loopCancelledTbr(now)
            if (handled) readJournalAfterHand()
            return pumpEnactResultProvider.get().success(true).enacted(false).isTempCancel(true)
        }
        // Checked here rather than at the top: with nothing running there is nothing to send, and
        // that path is already in the state AAPS asked for whether the pump is locked or not.
        refusedWhileLocked()?.let { return it }
        val failure = atc3Manager.cancelTempBasal()
        if (failure != null) {
            aapsLogger.error(LTag.PUMP, "ATC3: cancelTempBasal failed, $failure")
            return pumpEnactResultProvider.get().success(false).enacted(false).comment(failure)
        }
        // Back to the scheduled rate from the moment the pump acknowledged the cancel, which is what
        // the books have to credit from rather than the temporary basal that has just gone.
        val cancelledAt = atc3Manager.lastTbrCancelAckMs.takeIf { it > 0L } ?: dateUtil.now()
        atc3HistorySync.tbrStopped(cancelledAt, journal = journal)
        atc3HistorySync.loopCancelledTbr(cancelledAt)
        if (handled) readJournalAfterHand()
        return pumpEnactResultProvider.get().success(true).enacted(true).isTempCancel(true)
    }

    /**
     * Not offered.
     *
     * The pump does have an extended bolus, [Atc3Const.ControlOpcode.EXTENDED_BOLUS], but AAPS is
     * not given one: the loop does not need it, and the pump's history record carries no duration,
     * so an extended bolus read back could not be reconstructed. One made on the pump or elsewhere
     * is imported as an ordinary bolus of what it delivered, see [Atc3HistorySync].
     */
    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult =
        unsupported(R.string.atc3_not_supported_extended_bolus)

    /** Not offered, see [setExtendedBolus]. */
    override fun cancelExtendedBolus(): PumpEnactResult =
        unsupported(R.string.atc3_not_supported_extended_bolus)

    // Settings

    /**
     * Carry out a settings write asked for from the ATC3 screens.
     *
     * Nothing else is offered here: the settings block is the only thing those screens change, and
     * an unknown command must not be silently swallowed, so anything else falls through to null and
     * the queue reports it as unsupported.
     */
    // Two of these branches reach suspend code of the driver's own, and this method is not
    // suspend and cannot be made one. They are bridged with runBlocking rather than pushed
    // off onto a scope, because the answer is the return value: a custom command that returned
    // before it had one would report success it has not seen. Blocking here is safe: nothing
    // below re-enters the command queue, so the thread running this
    // command waits only on this driver's own exchange with the pump, which is serial anyway.
    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult? =
        when (customCommand) {
            is Atc3WriteSettings -> writeSettings(customCommand)
            is Atc3SetSuspended  -> runBlocking { setSuspended(customCommand.suspended) }
            is Atc3SetBtPassword -> setBtPassword(customCommand.password)
            else                 -> {
                aapsLogger.error(LTag.PUMP, "ATC3: unsupported custom command ${customCommand.statusDescription}")
                null
            }
        }

    /**
     * Stop or resume the pump on the user's say-so.
     *
     * Nothing has to be recorded here. A stopped pump comes back in the next status as a scheduled
     * rate of `0xFFFF`, and that is what reaches AAPS as a temporary basal of zero — the same path
     * a stop made on the pump's own keypad takes, see [Atc3HistorySync.onStatus]. One mechanism for
     * both, whoever pressed the button.
     */
    private suspend fun setSuspended(suspended: Boolean): PumpEnactResult =
        tracked("suspend", "wanted" to suspended) { setSuspendedInner(suspended) }

    private suspend fun setSuspendedInner(suspended: Boolean): PumpEnactResult {
        if (!atc3Manager.isConnected) {
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(rh.gs(R.string.atc3_not_connected))
        }
        // The status screen shows the lock on this very button, so the tap that follows it has to
        // say the same thing the screen does rather than the pump's blank refusal.
        refusedWhileLocked()?.let { return it }
        val failure = atc3Manager.setSuspended(suspended)
        if (failure != null) {
            aapsLogger.error(LTag.PUMP, "ATC3: could not ${if (suspended) "stop" else "resume"} the pump, $failure")
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(if (atc3Manager.wasRefused()) rh.gs(R.string.atc3_refused_by_pump) else failure)
        }
        // Tell AAPS what the pump is doing now without waiting for the next poll: the suspension has
        // to reach the insulin on board, and the loop has to stop acting.
        getPumpStatus("suspend state changed")
        return pumpEnactResultProvider.get().success(true).enacted(true)
    }

    /**
     * Give the pump a new Bluetooth password and remember it.
     *
     * The stored value is updated only once the pump has acknowledged, and before the link drops:
     * the pump restarts its Bluetooth stack after taking a new password, so the next connection is
     * already the one that has to present it. Storing it any later, or not at all after a
     * successful change, would lock the driver out of the pump it just reconfigured.
     *
     * An acknowledgement says the pump took the command, not which password it ended up with: the
     * pump can take the payload read literally rather than the value asked for. So both candidates
     * are written down, the likelier one as the password to present and the other as the fallback
     * the next connection reaches for if that one is refused.
     */
    private fun setBtPassword(password: Int): PumpEnactResult =
        tracked("btPassword") {
            if (!atc3Manager.isConnected) {
                return@tracked pumpEnactResultProvider.get().success(false).enacted(false)
                    .comment(rh.gs(R.string.atc3_not_connected))
            }
            refusedWhileLocked()?.let { return@tracked it }
            val failure = atc3Manager.setBtPassword(password)
            if (failure != null) {
                aapsLogger.error(LTag.PUMP, "ATC3: Bluetooth password change failed, $failure")
                return@tracked pumpEnactResultProvider.get().success(false).enacted(false)
                    .comment(if (atc3Manager.wasRefused()) rh.gs(R.string.atc3_refused_by_pump) else failure)
            }
            val candidates = Atc3BtPassword.candidatesFor(password)
            preferences.put(Atc3StringKey.Atc3BtPassword, candidates[0])
            preferences.put(Atc3StringKey.Atc3BtPasswordAlternate, candidates[1])
            pumpEnactResultProvider.get().success(true).enacted(true)
        }

    private fun writeSettings(command: Atc3WriteSettings): PumpEnactResult =
        tracked("settings") { writeSettingsInner(command) }

    private fun writeSettingsInner(command: Atc3WriteSettings): PumpEnactResult {
        if (!atc3Manager.isConnected) {
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(rh.gs(R.string.atc3_not_connected))
        }
        refusedWhileLocked()?.let { return it }
        val failure = atc3Manager.writeSettings(command.settings)
        if (failure != null) {
            aapsLogger.error(LTag.PUMP, "ATC3: settings write failed, $failure")
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(if (atc3Manager.wasRefused()) rh.gs(R.string.atc3_refused_by_pump) else failure)
        }
        return pumpEnactResultProvider.get().success(true).enacted(true)
    }

    // Identity

    override fun manufacturer(): ManufacturerType = ManufacturerType.Atc3
    override fun model(): PumpType = PumpType.ATC3
    override fun serialNumber(): String = atc3Pump.serialNumber
    /**
     * Built once. It never changes, and it is read on paths that run often, down to every basal
     * comparison the keepalive makes.
     */
    override val pumpDescription: PumpDescription = PumpDescription().fillFor(PumpType.ATC3)
    override val isFakingTempsByExtendedBoluses: Boolean = false
    /**
     * Read the pump's own daily totals and hand them to AAPS.
     *
     * The three date bytes are read as year, month and day, and that reading is checked before
     * anything is stamped with it: one of the records has to land on the day the pump's own clock
     * says it is, since today's partial total is always among them. If none does, the reading is
     * wrong and nothing is written at all: missing totals are a nuisance, totals filed under the
     * wrong day are a lie.
     */
    override fun loadTDDs(): PumpEnactResult = runBlocking { tracked("tdd") { loadTDDsInner() } }

    private suspend fun loadTDDsInner(): PumpEnactResult {
        if (!atc3Manager.isConnected) {
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(rh.gs(R.string.atc3_not_connected))
        }
        // The pump's clock is what the dates are checked against, so it has to be current.
        if (!atc3Manager.readStatus()) {
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(rh.gs(R.string.atc3_not_connected))
        }
        val stats = atc3Manager.readDailyStats()
        if (stats.isNullOrEmpty()) {
            aapsLogger.error(LTag.PUMP, "ATC3: the pump returned no daily totals")
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(rh.gs(R.string.atc3_tdd_unavailable))
        }

        val usable = stats.filter { !it.isEmpty && it.isDatePlausible }
        if (usable.none { it.isSameDayAs(dateUtil.now()) }) {
            aapsLogger.error(
                LTag.PUMP,
                "ATC3: no daily total falls on the pump's own date, so the date bytes are not what " +
                    "they were taken for; writing none of them"
            )
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(rh.gs(R.string.atc3_tdd_unavailable))
        }

        val written = atc3HistorySync.recordDailyTotals(usable)
        aapsLogger.debug(LTag.PUMP, "ATC3: $written of ${usable.size} daily totals were new to AAPS")
        trace.event(Atc3TraceCat.HIST, "tdd", "days" to usable.size, "new" to written)
        return pumpEnactResultProvider.get().success(true).enacted(true)
    }
    override fun canHandleDST(): Boolean = false

    /**
     * The two switches that are the driver's own, and nothing else.
     *
     * Everything else is where it is used. Pairing - which pump, its serial, its password - is
     * asked for on the screen where a pump is chosen, because those three are one job. Everything
     * the pump itself holds is on the pump's own screen: the driver reads that block out of the
     * pump and writes it back whole with [Atc3Const.ControlOpcode.WRITE_SETTINGS], so a plain
     * preference bound to local storage would show a value the pump has never heard of.
     *
     * These two remain here as well as on the pump screen, editing the same keys, so that AAPS's
     * own settings search can still find them.
     */
    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "atc3_settings"
            title = rh.gs(R.string.atc3_name)
            initialExpandedChildrenCount = 0
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context, booleanKey = Atc3BooleanKey.HoldLink,
                    title = R.string.atc3_hold_link_title, summary = R.string.atc3_hold_link_summary
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context, booleanKey = Atc3BooleanKey.Trace,
                    title = R.string.atc3_trace_title, summary = R.string.atc3_trace_summary
                )
            )
        }
    }

    /**
     * Wrap one thing AAPS asked the driver to do, and write down what came of it.
     *
     * A begin and an end rather than a single line at the finish, because the interesting failures
     * are the ones with no end: a command that never returned is invisible to a line written after
     * the fact, and it is exactly the case worth catching. The end is emitted from a `finally`, so
     * a thrown exception closes the span as well.
     */
    private inline fun tracked(op: String, vararg fields: Pair<String, Any?>, block: () -> PumpEnactResult): PumpEnactResult {
        val startedAt = trace.now()
        trace.event(Atc3TraceCat.DRV, "$op.begin", *fields)
        var result: PumpEnactResult? = null
        try {
            result = block()
            return result
        } finally {
            trace.event(
                Atc3TraceCat.DRV, "$op.end",
                "ok" to result?.success,
                "enacted" to result?.enacted,
                "ms" to trace.since(startedAt),
                "why" to result?.comment?.ifEmpty { null }
            )
        }
    }

    private fun unsupported(comment: Int): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false).comment(rh.gs(comment))

    /**
     * Refuse a control command the pump is going to refuse anyway, or null when it will not.
     *
     * A locked pump answers reads and turns down every control command, with a refusal frame that
     * says nothing about why. Sending one costs an exchange to learn what the last status already
     * said, and the user gets "the pump refused the command, check its limits" — which sends them
     * looking in the wrong place. Status V1 carries the lock, so this says the true thing instead,
     * and says the one thing they can act on: the pump unlocks on the pump.
     *
     * Only for control commands. Reads are unaffected, and the status poll has to keep running
     * while the pump is locked or the driver would go blind exactly when it is being told no.
     */
    private fun refusedWhileLocked(): PumpEnactResult? {
        if (atc3Pump.firmwareTooOld) {
            refuseOldFirmware()
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(rh.gs(R.string.atc3_firmware_too_old, atc3Pump.version?.firmwareText ?: "?", Atc3Const.MINIMUM_FIRMWARE.joinToString(".")))
        }
        if (!atc3Pump.locked) return null
        aapsLogger.debug(LTag.PUMP, "ATC3: the pump is locked, not sending the command")
        trace.event(Atc3TraceCat.DRV, "locked")
        return pumpEnactResultProvider.get().success(false).enacted(false)
            .comment(rh.gs(R.string.atc3_pump_locked))
    }

    private fun notImplemented(operation: String): PumpEnactResult {
        aapsLogger.debug(LTag.PUMP, "ATC3: $operation is not implemented yet")
        return pumpEnactResultProvider.get().success(false).enacted(false).comment(rh.gs(R.string.atc3_not_implemented))
    }
}
