package app.aaps.pump.atc3

import android.content.Context
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.model.BS
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.PluginConstraints
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
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusSize
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.interfaces.rx.events.EventRunningModeChange
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.pump.atc3.basal.Atc3BasalPeriodKeeper
import app.aaps.pump.atc3.basal.Atc3TbrTracker
import app.aaps.pump.atc3.check.Atc3Reconciliation
import app.aaps.pump.atc3.clock.Atc3ClockKeeper
import app.aaps.pump.atc3.clock.Atc3ClockWatch
import app.aaps.pump.atc3.command.Atc3BolusDelivery
import app.aaps.pump.atc3.command.Atc3BolusOutcome
import app.aaps.pump.atc3.command.Atc3Failure
import app.aaps.pump.atc3.command.Atc3Pair
import app.aaps.pump.atc3.command.Atc3ProbeLink
import app.aaps.pump.atc3.command.Atc3SetBtPassword
import app.aaps.pump.atc3.command.Atc3SetSuspended
import app.aaps.pump.atc3.command.Atc3TbrResult
import app.aaps.pump.atc3.command.Atc3WriteSettings
import app.aaps.pump.atc3.history.Atc3BolusSpacing
import app.aaps.pump.atc3.history.Atc3HistoryEvents
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.pump.atc3.keys.Atc3IntNonKey
import app.aaps.pump.atc3.keys.Atc3IntentKey
import app.aaps.pump.atc3.keys.Atc3LongNonKey
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.keys.Atc3StringNonKey
import app.aaps.pump.atc3.link.Atc3BtPassword
import app.aaps.pump.atc3.link.Atc3Connection
import app.aaps.pump.atc3.link.Atc3LinkKeeper
import app.aaps.pump.atc3.link.Atc3LinkProtection
import app.aaps.pump.atc3.manager.Atc3Manager
import app.aaps.pump.atc3.protocol.Atc3Alarm
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3StatusV2
import app.aaps.pump.atc3.protocol.Atc3TbrStatus
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import app.aaps.pump.atc3.ui.Atc3Fragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.abs

/**
 * AAPS pump driver for ATC3: reads the pump's state, writes the AAPS basal schedule, delivers and
 * stops boluses and runs temporary basals. Whatever the pump delivers, boluses given on it or from
 * another device included, reaches AAPS through [Atc3HistorySync].
 */
@Singleton
class Atc3PumpPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    commandQueue: CommandQueue,
    private val pumpState: Atc3PumpState,
    private val atc3Manager: Atc3Manager,
    private val bolusDelivery: Atc3BolusDelivery,
    private val rxBus: RxBus,
    private val uiInteraction: UiInteraction,
    private val atc3HistorySync: Atc3HistorySync,
    private val historyEvents: Atc3HistoryEvents,
    private val reconciliation: Atc3Reconciliation,
    private val clockWatch: Atc3ClockWatch,
    private val dateUtil: DateUtil,
    private val aapsSchedulers: AapsSchedulers,
    private val fabricPrivacy: FabricPrivacy,
    private val pumpEnactResultProvider: Provider<PumpEnactResult>,
    private val trace: Atc3Trace,
    private val clockKeeper: Atc3ClockKeeper,
    private val basalPeriods: Atc3BasalPeriodKeeper,
    private val linkKeeper: Atc3LinkKeeper,
    private val atc3Connection: Atc3Connection
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

    /** When the battery was last read, so it is not read in every connection. */
    private var batteryReadAtMs = 0L

    /** When the alarm history was last read. */
    private var alarmsReadAtMs = 0L

    /** When the refill history was last read, 0 until once. */
    private var refillsReadAtMs = 0L

    /** The reservoir at the previous tick, for noticing that it went up. */
    private var reservoirSeenUnits = -1.0

    /** True while the alarm notification on screen is this driver's: the id is shared, and only its own is dismissed. */
    private var alarmNotificationShown = false

    /** The status read pending while the pump is stopped, see [watchForResume]. */
    private var resumeWatch: Job? = null

    /**
     * While the pump is stopped, ask for a status every [RESUME_WATCH_MS], so that the report the
     * resume rebuilds, the only place its moment is kept, is seen before the next rebuild replaces it.
     */
    private fun watchForResume() {
        if (!stopIsOpen()) return
        if (resumeWatch?.isActive == true) return
        resumeWatch = pluginScope.launch {
            delay(RESUME_WATCH_MS)
            // Cleared before the read: the tick arms the next watch at its end.
            resumeWatch = null
            if (stopIsOpen()) commandQueue.readStatus(rh.gs(R.string.atc3_resume_watch), null)
        }
    }

    /**
     * True while AAPS holds the pump as stopped: the pump says so, or the ledger has the stop open; a
     * status read on the way past does not close the stop's row, only a tick does.
     */
    private fun stopIsOpen(): Boolean = pumpState.notDelivering || atc3HistorySync.openTbr()?.suspension == true

    /** Keeps two of the driver's boluses far enough apart for their records to be told apart, see [Atc3BolusSpacing]. */
    private val bolusSpacing = Atc3BolusSpacing()

    /**
     * Ask for the pump's state when nothing else would: the queue connects only with something to send,
     * and a bolus given on the pump would go unseen until AAPS's keepalive. Asked when the loop has
     * decided, so the read shares a connection with the loop's command; the glucose is the fallback for
     * a cycle without a decision, on a longer threshold.
     */
    override fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.PUMP, "ATC3: driver version ${Atc3Const.DRIVER_VERSION}")
        // A quiet held link is questioned through the queue, see [Atc3ProbeLink].
        atc3Connection.askForProbe = { commandQueue.customCommand(Atc3ProbeLink(), null) }
        disposable += rxBus
            .toObservable(EventAPSCalculationFinished::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ readStateIfStale("decided", Atc3Const.STATUS_FRESH_MS) }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventRunningModeChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ clockKeeper.modeChanged() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventNewBG::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ readStateIfStale("glucose", Atc3Const.STATUS_STALE_MS) }, fabricPrivacy::logException)
        basalPeriods.watch(pluginScope) { isEnabled() }
        linkKeeper.watch(pluginScope) { isEnabled() }
    }

    override fun onStop() {
        atc3Connection.askForProbe = null
        basalPeriods.stop()
        linkKeeper.stop()
        disposable.clear()
        super.onStop()
    }

    private fun readStateIfStale(trigger: String, threshold: Long) {
        if (!isEnabled()) return
        // The state, not the history: every command reads the history, which says nothing of temporary basals.
        if (atc3HistorySync.stateAgeMs(dateUtil.now()) < threshold) {
            trace.event(Atc3TraceCat.DRV, "bg_read", "by" to trigger, "asked" to false, "why" to "fresh")
            return
        }
        val queued = commandQueue.readStatus(rh.gs(R.string.atc3_history_stale), null)
        // False: the queue already holds a read status.
        trace.event(Atc3TraceCat.DRV, "bg_read", "by" to trigger, "asked" to true, "queued" to queued)
    }

    // Limits the pump itself is set to

    /**
     * Never ask the pump for a basal rate its own settings refuse: a refusal would turn the loop's
     * decision into a failed command. Nothing is limited until the settings have been read.
     */
    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        val maxBasal = pumpState.settings?.maxBasal ?: return absoluteRate
        absoluteRate.setIfSmaller(
            maxBasal,
            rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(app.aaps.core.ui.R.string.pumplimit)),
            this
        )
        return absoluteRate
    }

    /** The same for a bolus, from the same settings block. */
    override fun applyBolusConstraints(insulin: Constraint<Double>): Constraint<Double> {
        val maxBolus = pumpState.settings?.maxBolus ?: return insulin
        insulin.setIfSmaller(
            maxBolus,
            rh.gs(app.aaps.core.ui.R.string.limitingbolus, maxBolus, rh.gs(app.aaps.core.ui.R.string.pumplimit)),
            this
        )
        return insulin
    }

    // Connection state

    override fun isInitialized(): Boolean = pumpState.isInitialized

    /** True while the pump delivers nothing: stopped, or under an alarm that stops delivery, see [Atc3PumpState.notDelivering]. */
    override fun isSuspended(): Boolean = pumpState.notDelivering

    /** True while the pump takes no other command: an exchange or a bolus runs. */
    override fun isBusy(): Boolean = atc3Manager.isBusy

    override fun isConnected(): Boolean = atc3Connection.isConnected

    override fun isConnecting(): Boolean = atc3Connection.isConnecting

    override fun isHandshakeInProgress(): Boolean = false

    override fun connect(reason: String) {
        atc3Connection.connect(reason)
    }

    override fun disconnect(reason: String) {
        atc3Connection.disconnect(reason)
    }

    override fun stopConnecting() {
        atc3Connection.disconnect("stopConnecting")
    }

    /**
     * One tick, in an order that leaves everything essential done wherever it is cut short:
     *
     * 1. the status, without which everything after is a guess;
     * 2. alarms, each re-reading its subject;
     * 3. whether the pump delivers at all;
     * 4. the comparison of the pump's count with the AAPS journal, before anything of this read is
     *    written, then what the pump runs into AAPS, and the pump's journals on a disagreement, see
     *    [Atc3Reconciliation.reconcile];
     * 5. the exact basal mode's half hour, and the day's account for the screen;
     * 6. the clock, last of what touches the pump: writing it moves where later records land;
     * 7. housekeeping: battery, firmware, profiles.
     *
     * A command of the loop's goes only when the comparison stood, see [dataChangedUnderTheLoop].
     */
    override fun getPumpStatus(reason: String) = runBlocking { tick(reason) }

    private suspend fun tick(reason: String) {
        aapsLogger.debug(LTag.PUMP, "ATC3: getPumpStatus, reason $reason")
        val startedAt = trace.now()
        trace.event(Atc3TraceCat.DRV, "status.begin", "reason" to reason)
        if (!atc3Connection.isConnected) {
            atc3Connection.connect(reason)
            trace.event(Atc3TraceCat.DRV, "status.end", "ok" to false, "why" to "connecting", "ms" to trace.since(startedAt))
            return
        }
        // Which pump this is, once per link: a pump without a password is run under a warning.
        if (pumpState.version == null) atc3Manager.readVersion()
        // The heartbeat the held link is watched by, once per link.
        atc3Manager.ensureHeartbeatPeriod()
        // 1. The one read the tick cannot go on without.
        if (atc3Manager.readStatus() == null) {
            trace.event(Atc3TraceCat.DRV, "status.end", "ok" to false, "why" to "no_status", "ms" to trace.since(startedAt))
            return
        }
        traceState()
        // The answer a stop for want of one would be counted from, see [Atc3LinkKeeper].
        linkKeeper.noteAnswer()

        // 2. Alarms first.
        announceAlarms()
        stopOnDeliveryAlarm()
        readAlarmSubjects()
        readAlarmsIfDue()
        readRefillsIfDue()

        // 3. Is it delivering at all.
        announceSuspension(pumpState.notDelivering)

        // 4. The pump's count against the AAPS journal, and what the pump runs into AAPS.
        val resolved = reconciliation.reconcile()
        reconciliation.reportDeliveryStopped()

        // 5. The exact basal mode's half hour, by the same count.
        basalPeriods.closeIfDue(
            journalRead = resolved.reconciled != null, readHistory = reconciliation::readBolusHistory, anchor = reconciliation::anchor
        )

        // The day so far by both accounts.
        reconciliation.noteDayAccount()

        // 6. The clock.
        clockKeeper.correctIfAdrift(reconciliation::readBolusHistory)

        // 7. Housekeeping.
        readBatteryIfDue()
        readVersionIfUnknown()
        warnAboutUnprotectedLink()
        readProfilesIfNeeded()
        watchForResume()

        trace.event(
            Atc3TraceCat.DRV, "status.end",
            "ok" to (resolved.tbrRead && resolved.resolution != Atc3Reconciliation.Resolution.READ_FAILED),
            "state" to resolved.verdict.trace(),
            "resolution" to resolved.resolution.name.lowercase(),
            "tbr" to resolved.tbrRead,
            "ms" to trace.since(startedAt)
        )
    }

    /**
     * A therapy command goes to the pump only when the data the loop decided on was the pump's: the
     * status just read is compared with the AAPS journal, and when the journals had to change what AAPS
     * holds or could not explain the count, the command is refused and the loop decides again.
     *
     * @param loops true for the loop's decision; false for the user's command, which goes through whatever the journals said
     * @return the refusal, or null when the data stood
     */
    private suspend fun dataChangedUnderTheLoop(loops: Boolean = true): PumpEnactResult? {
        // No status decoded yet: nothing to compare, and the bolus history read is the barrier.
        if (pumpState.lastStatus == null) {
            reconciliation.ensureHistoryFresh()
            return null
        }
        val resolved = reconciliation.reconcile()
        if (resolved.stood) return null
        if (!loops) {
            trace.event(Atc3TraceCat.DRV, "command_kept", "why" to resolved.resolution.name.lowercase(), "who" to "user")
            return null
        }
        aapsLogger.warn(LTag.PUMP, "ATC3: refusing the command, the journal changed under the loop (${resolved.resolution.name.lowercase()})")
        trace.event(Atc3TraceCat.DRV, "command_refused", "why" to resolved.resolution.name.lowercase())
        return failed(rh.gs(R.string.atc3_command_data_changed))
    }

    /** An alarm re-reads what it is about: a low battery reads the battery, which is otherwise read hourly. */
    private fun readAlarmSubjects() {
        if (Atc3Alarm.LOW_BATTERY in pumpState.activeAlarms) atc3Manager.readStatusV2()
    }

    /** Read the firmware version once: it decides which link warning applies. */
    private fun readVersionIfUnknown() {
        if (pumpState.version != null) return
        atc3Manager.readVersion()
    }

    /**
     * Tell the user when anybody in radio range can drive the pump: no password set, or firmware
     * without one, each said as it is. Raised on every status read while it lasts, so a dismissed one comes back.
     */
    private fun warnAboutUnprotectedLink() {
        // Firmware older than the password is run, under this warning.
        val noPasswordFirmware = pumpState.version?.isAtLeast(Atc3Const.PASSWORD_FIRMWARE) == false
        when (atc3Connection.linkProtection) {
            Atc3LinkProtection.UNPROTECTED,
            Atc3LinkProtection.UNSUPPORTED ->
                // NORMAL rather than the id's default: the pump works, and is to be improved.
                uiInteraction.addNotification(
                    Notification.WRONG_PUMP_PASSWORD,
                    if (noPasswordFirmware || atc3Connection.linkProtection == Atc3LinkProtection.UNSUPPORTED)
                        rh.gs(
                            R.string.atc3_firmware_no_password,
                            pumpState.version?.firmwareText ?: "?", Atc3Const.PASSWORD_FIRMWARE.joinToString(".")
                        )
                    else rh.gs(R.string.atc3_password_not_set),
                    Notification.NORMAL
                )

            Atc3LinkProtection.PROTECTED,
            Atc3LinkProtection.UNKNOWN     -> rxBus.send(EventDismissNotification(Notification.WRONG_PUMP_PASSWORD))
        }
    }

    /** Read the battery at most once per [Atc3Const.BATTERY_READ_INTERVAL_MS]. */
    private fun readBatteryIfDue() {
        val now = dateUtil.now()
        if (batteryReadAtMs != 0L && now - batteryReadAtMs < Atc3Const.BATTERY_READ_INTERVAL_MS) return
        if (atc3Manager.readStatusV2() != null) batteryReadAtMs = now
    }

    /**
     * Read the stored profiles again only when the cache can no longer be trusted, for [isThisProfileSet]:
     * never read, another profile active, or the rate delivered now not the cached one's while the
     * scheduled rate runs. A write of our own reads them back itself.
     */
    private fun readProfilesIfNeeded() {
        val active = pumpState.activeProfileIndex
        val profiles = pumpState.profiles
        val stale = profiles == null ||
            profiles.readForIndex != active ||
            scheduleDisagrees()
        if (!stale) return
        atc3Manager.readBasalProfiles(active)
    }

    /** True when the rate delivered now is not the cached profile's, beyond one basal step. */
    private fun scheduleDisagrees(): Boolean {
        val card = pumpState.statusCard ?: return false
        if (card.tbrActive || card.notDelivering) return false
        val expected = pumpState.scheduledRateFromProfile(dateUtil.now(), card) ?: return false
        val disagrees = abs(expected - card.scheduledBasalRate) > pumpDescription.basalStep
        if (disagrees) {
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: the pump is delivering ${card.scheduledBasalRate} where the stored profile " +
                    "says $expected, reading the profiles again"
            )
            trace.event(
                Atc3TraceCat.DRV, "profile_drift",
                "pump" to card.scheduledBasalRate, "cached" to expected
            )
        }
        return disagrees
    }

    /** Everything the pump just said about itself, in one trace line. */
    private fun traceState() {
        val card = pumpState.statusCard ?: return
        val v2 = pumpState.statusV2
        trace.event(
            Atc3TraceCat.STATE, "pump",
            "battPct" to v2?.batteryPercent,
            "battV" to (v2?.batteryVolts ?: 0.0),
            "res" to card.reservoirUnits,
            "today" to card.deliveredTodayUnits,
            "snap" to card.snapshotAtMs,
            "susp" to card.suspended,
            "tbr" to card.tbrActive,
            "tbrRate" to card.tbrRate,
            "tbrMin" to card.tbrElapsedMinutes,
            "basal" to card.scheduledBasalRate,
            "iob" to (v2?.activeInsulinUnits ?: 0.0),
            "profile" to card.activeProfileIndex,
            "alarms" to card.activeAlarmCodes.joinToString("+").ifEmpty { "-" }
        )
    }

    // Pump state

    override val lastDataTime: Long get() = pumpState.lastConnection

    override val lastBolusTime: Long? get() = pumpState.lastBolus?.atMs

    override val lastBolusAmount: Double? get() = pumpState.lastBolus?.units

    /**
     * The rate the pump's schedule calls for, whatever the pump does now: `Pump` wants it free of
     * temporary basals and stops, which reach AAPS as their own records. While stopped it comes from the
     * schedule the driver holds; before any profile is read it is zero, which stops the loop.
     */
    override val baseBasalRate: Double
        get() {
            val card = pumpState.statusCard ?: return 0.0
            return if (card.notDelivering) pumpState.scheduledRateFromProfile(dateUtil.now(), card) ?: 0.0
            else card.scheduledBasalRate
        }

    override val reservoirLevel: Double get() = pumpState.reservoirUnits

    /** Charge, percent, worked out from the voltage, see [app.aaps.pump.atc3.protocol.Atc3StatusV2]. */
    override val batteryLevel: Int? get() = pumpState.batteryPercent

    /** The battery voltage, V; zero until read. */
    val batteryVolts: Double get() = pumpState.batteryVolts

    // Profile

    /** Put the AAPS basal schedule into the driver's slot, see [Atc3Const.DRIVER_PROFILE_INDEX]; done once the pump returns the same rates. */
    override fun setNewBasalProfile(profile: Profile): PumpEnactResult = runBlocking {
        tracked("profile") { setNewBasalProfileInner(profile) }
    }

    private suspend fun setNewBasalProfileInner(profile: Profile): PumpEnactResult {
        if (!atc3Connection.isConnected) {
            return notConnected()
        }
        refusedWhileLocked()?.let { return it }
        reconciliation.ensureHistoryFresh()
        val rates = Atc3PumpState.buildBasalSlots(profile) { PumpType.ATC3.determineCorrectBasalSize(it) }
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
            failed(failure)
        }
    }

    /** Whether the pump holds the wanted schedule in the active driver slot, within one basal step. */
    override fun isThisProfileSet(profile: Profile): Boolean {
        // Nothing read yet: a mismatch would queue a write that cannot be carried out.
        val stored = pumpState.profiles?.rates?.getOrNull(Atc3Const.DRIVER_PROFILE_INDEX) ?: return true
        if (pumpState.activeProfileIndex != Atc3Const.DRIVER_PROFILE_INDEX) return false
        val wanted = Atc3PumpState.buildBasalSlots(profile) { PumpType.ATC3.determineCorrectBasalSize(it) }
        return Atc3PumpState.rateArraysMatch(wanted, stored, pumpDescription.basalStep)
    }

    // Delivery

    /**
     * Deliver a bolus so that AAPS knows the insulin whatever happens next: written the moment the pump
     * accepts it, then followed; closed at the completion frame's amount, or at the pump's record when
     * cut short.
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
        // Carbs reach AAPS their own way; one here would be lost.
        require(detailedBolusInfo.carbs == 0.0) { detailedBolusInfo.toString() }
        // Only what the pump delivers whole, on its scale.
        val asked = detailedBolusInfo.insulin
        val requested = PumpType.ATC3.determineCorrectBolusSize(asked)
        if (abs(requested - asked) > 1e-9) {
            aapsLogger.warn(LTag.PUMP, "ATC3: a bolus of $asked U is not on the pump's step, delivering $requested U")
            trace.event(Atc3TraceCat.DRV, "bolus_step", "asked" to asked, "units" to requested)
            detailedBolusInfo.insulin = requested
        }
        if (requested <= 0.0) {
            return failed(rh.gs(R.string.atc3_bolus_zero))
        }
        if (!atc3Connection.isConnected) {
            return notConnected()
        }
        refusedWhileLocked()?.let { return it }

        // Nothing sent or written yet: the only place a hold costs nothing to cancel.
        holdApart()?.let { return it }

        // The status first: a bolus given elsewhere meanwhile is then accounted for, and the journals are
        // read when the count and the AAPS journal disagree.
        val statusRead = atc3Manager.readStatus() != null
        val resolved = reconciliation.reconcile(statusRead)
        trace.event(
            Atc3TraceCat.HIST, "bolus_baseline",
            "why" to resolved.verdict.trace(),
            "outcome" to resolved.resolution.name.lowercase()
        )
        smbRefused(detailedBolusInfo, resolved)?.let { return it }
        // A stopped pump turns every bolus down: said here, nothing sent.
        if (statusRead && pumpState.notDelivering) {
            aapsLogger.warn(LTag.PUMP, "ATC3: the pump is stopped, not sending the bolus")
            trace.event(Atc3TraceCat.DRV, "bolus_held_stopped", "units" to detailedBolusInfo.insulin)
            watchForResume()
            return failed(rh.gs(R.string.atc3_pump_suspended))
        }

        var temporaryId = 0L
        var startedAtMs = 0L
        val outcome = bolusDelivery.bolus(
            units = requested,
            onAccepted = { acceptedAt ->
                startedAtMs = acceptedAt
                // The next bolus waits until this start is a minute old, see [holdApart].
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
            return failed(comment)
        }
        // Closed by its completion frame at the frame's amount; the record is read now, so the row takes
        // the pump's minute at once rather than at some later read.
        if (outcome.completed && outcome.sawProgress &&
            atc3HistorySync.settleCompleted(temporaryId, outcome.reportedUnits)
        ) {
            pinSettledBolus(startedAtMs, requested)
            return judgeBolus(requested, outcome.reportedUnits, outcome.cancelled)
        }

        // Cut short: what went in is for the pump's history to say.
        val history = atc3Manager.readBolusHistoryUntil(Atc3Const.BOLUS_RECORD_POLL_ATTEMPTS) {
            atc3HistorySync.wouldResolve(it.records, temporaryId)
        }
        val confirmed = history?.let { atc3HistorySync.reconcileBoluses(it.records, it.recordCount) }?.confirmedUnits

        if (confirmed == null) {
            // Two things reach here: the history read without the bolus, which is evidence, or the history
            // not read at all, likely as the link cut the bolus, which is none: the pump goes on delivering.
            val asked = history != null
            // Recorded at what was asked; another connection is asked for so the pump's account follows soon.
            aapsLogger.error(
                LTag.PUMP,
                if (asked) "ATC3: the pump has not recorded this bolus yet"
                else "ATC3: the pump could not be asked about this bolus"
            )
            commandQueue.readStatus(rh.gs(R.string.atc3_bolus_unconfirmed), null)
            if (!asked && !outcome.cancelled) {
                // The link went, not the bolus: the pump delivers what it accepted. Answered as delivered, as its
                // row counts; the record corrects the row later, and a shortfall is said then.
                atc3HistorySync.answeredWhole(temporaryId)
                trace.event(Atc3TraceCat.DRV, "bolus_unconfirmed", "asked" to requested, "seen" to outcome.reportedUnits)
                return pumpEnactResultProvider.get().success(true).enacted(true).bolusDelivered(requested)
                    .comment(rh.gs(R.string.atc3_bolus_unconfirmed))
            }
            // Judged on the last progress: a bolus that stalled must not pass because its record is missing.
            val judged = judgeBolus(requested, outcome.reportedUnits, outcome.cancelled, fromRecord = false)
            // One that ran to the end and only lacks its record is said to be unconfirmed; one
            // the pump answered about without a record of it keeps the shortfall it showed.
            return if (judged.success || !asked) judged.comment(rh.gs(R.string.atc3_bolus_unconfirmed)) else judged
        }
        return judgeBolus(requested, confirmed, outcome.cancelled)
    }

    /**
     * Refuse an SMB the loop decided on from data that was not the pump's: a bolus reached AAPS
     * after the loop stamped its decision, or the comparison in front of this bolus did not stand.
     * A bolus the user asked for is theirs to decide on and is not refused here; a refused SMB is
     * decided again by the loop on the corrected data.
     *
     * @return the answer to give, or null when the SMB may go ahead
     */
    private fun smbRefused(info: DetailedBolusInfo, resolved: Atc3Reconciliation.Resolved): PumpEnactResult? {
        if (info.bolusType != BS.Type.SMB) return null
        val imported = resolved.reconciled?.newestImportedAtMs
        if (info.lastKnownBolusTime != 0L && imported != null && imported > info.lastKnownBolusTime) {
            aapsLogger.warn(LTag.PUMP, "ATC3: refusing the SMB, a bolus at $imported reached us after the loop decided at ${info.lastKnownBolusTime}")
            trace.event(Atc3TraceCat.DRV, "smb_refused", "importedAt" to imported, "lastBolus" to info.lastKnownBolusTime, "units" to info.insulin)
            return failed(rh.gs(R.string.atc3_smb_stale_iob))
        }
        if (resolved.stood) return null
        val why = resolved.resolution.name.lowercase()
        aapsLogger.warn(LTag.PUMP, "ATC3: refusing the SMB, the journal did not stand under the loop ($why)")
        trace.event(Atc3TraceCat.DRV, "smb_refused", "why" to why, "units" to info.insulin)
        return failed(rh.gs(R.string.atc3_smb_data_changed))
    }

    /**
     * Hold a bolus until the previous one's record can no longer be taken for its own, see
     * [Atc3BolusSpacing]: held, not refused, before anything is sent, so it costs no command budget and
     * a cancel meanwhile costs nothing. The cancel is seen through [BolusProgressData.stopPressed].
     *
     * @return null when the bolus may go, or the answer when it was cancelled
     */
    private suspend fun holdApart(): PumpEnactResult? {
        val holdMs = bolusSpacing.waitMs(dateUtil.now())
        if (holdMs <= 0L) return null
        aapsLogger.debug(
            LTag.PUMP,
            "ATC3: holding the bolus for ${holdMs}ms, the previous one started too recently for the records to be told apart"
        )
        return holdBolus(holdMs, "bolus_held")
    }

    /** The hold itself: wait [holdMs], watching the stop button. @return the answer when cancelled, else null */
    private suspend fun holdBolus(holdMs: Long, event: String): PumpEnactResult? {
        var remaining = holdMs
        while (remaining > 0L) {
            if (BolusProgressData.stopPressed) {
                aapsLogger.debug(LTag.PUMP, "ATC3: the bolus was cancelled while held, after ${holdMs - remaining}ms")
                trace.event(
                    Atc3TraceCat.DRV, "bolus_hold_cancelled",
                    "askedMs" to holdMs,
                    "heldMs" to (holdMs - remaining)
                )
                // Said as a cancel, not as a failure of the pump; still not enacted, as nothing left the reservoir.
                return failed(rh.gs(R.string.atc3_bolus_cancelled))
            }
            val step = minOf(remaining, BOLUS_HOLD_POLL_MS)
            delay(step)
            remaining -= step
        }
        trace.event(Atc3TraceCat.DRV, event, "ms" to holdMs)
        return null
    }

    /**
     * Read the record of a bolus just closed on its completion frame, so it dates the row once, now, and
     * is filed under the row's id. A record that does not turn up is left to the next read.
     */
    private suspend fun pinSettledBolus(startedAtMs: Long, requestedUnits: Double) {
        val history = atc3Manager.readBolusHistoryUntil(Atc3Const.BOLUS_RECORD_POLL_ATTEMPTS) {
            atc3HistorySync.holdsRecordOf(it.records, startedAtMs, requestedUnits)
        }
        val found = history != null && atc3HistorySync.holdsRecordOf(history.records, startedAtMs, requestedUnits)
        if (history != null) atc3HistorySync.reconcileBoluses(history.records, history.recordCount)
        if (!found) aapsLogger.warn(LTag.PUMP, "ATC3: the record of the bolus started at $startedAtMs is not in the history yet")
        trace.event(Atc3TraceCat.HIST, "bolus_record", "read" to (history != null), "found" to found)
    }

    /**
     * Judge a finished bolus by what the pump says it delivered: a step or more short is a failure,
     * unless the user stopped it.
     *
     * @param fromRecord true when [delivered] is the pump's record, false when it is the last progress reported
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
     * Stop the loop while: the pump's clock is [Atc3Const.CLOCK_MAX_CORRECTION_MS] or more from the
     * phone's ([Atc3ClockKeeper]); the pump delivers nothing twice running; it is held stopped for want
     * of an answer; no Bluetooth password is entered. Each lifts by itself once over.
     */
    override fun isLoopInvocationAllowed(value: Constraint<Boolean>): Constraint<Boolean> {
        if (clockKeeper.tooFarApart) value.set(false, rh.gs(R.string.atc3_clock_loop_blocked), this)
        // Told once and it happened again: the pump says it is delivering and the reservoir has
        // not moved through another round of the whole thing. Whatever the loop decides next would
        // be decided on insulin it believes went in, and the evidence is that it did not. Stop
        // until somebody has looked at the pump.
        if (reconciliation.deliveryStopped)
            value.set(false, rh.gs(R.string.atc3_no_delivery_loop_blocked), this)
        // No answer for half an hour: nothing is known of the pump, and nothing is decided for it
        // until it has answered and the time without an answer is written. See [Atc3LinkWatch].
        if (linkKeeper.isHeldStopped) value.set(false, rh.gs(R.string.atc3_link_loop_blocked), this)
        // No Bluetooth password entered in AAPS: the driver does not work with the pump, and
        // there is nothing for the loop to decide. See [Atc3Connection.isPasswordEntered].
        if (!atc3Connection.isPasswordEntered) value.set(false, rh.gs(R.string.atc3_password_loop_blocked), this)
        return value
    }

    /** The phone moved timezone or daylight saving: the clock follows at the next tick, see [Atc3ClockKeeper]; nothing is sent from here. */
    override fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {
        clockKeeper.phoneTimeChanged()
        aapsLogger.debug(LTag.PUMP, "ATC3: phone time changed, $timeChangeType, asking to read the pump")
        commandQueue.readStatus(rh.gs(R.string.atc3_clock_phone_time_changed), null)
    }

    /** Tell the user when the pump is stopped: AAPS says the loop is suspended, not that no insulin goes in. */
    private fun announceSuspension(suspended: Boolean) {
        if (suspended) {
            uiInteraction.addNotification(Notification.PUMP_SUSPENDED, rh.gs(R.string.atc3_pump_suspended), Notification.NORMAL)
        } else {
            rxBus.send(EventDismissNotification(Notification.PUMP_SUSPENDED))
        }
    }

    /**
     * Tell the user what the pump is raising now, from every status. Telling only: an alarm is not a
     * delivery state, except the two [stopOnDeliveryAlarm] handles, see [app.aaps.pump.atc3.protocol.Atc3Alarm].
     */
    private fun announceAlarms() {
        val codes = pumpState.activeAlarmCodes
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
     * Stop the pump under an alarm that stops delivery, [Atc3Alarm.stopsDelivery]: out of it the pump
     * resumes its old temporary basal by itself, or delivers part of each command under the daily limit.
     * Stopped, both sides agree, and delivery starts again when somebody starts it.
     */
    private fun stopOnDeliveryAlarm() {
        val card = pumpState.statusCard ?: return
        val alarm = card.activeAlarms.firstOrNull { it.stopsDelivery } ?: return
        // The pump's own flag, not notDelivering: whether the stop still has to be sent.
        if (card.suspended) return
        // A locked pump refuses the stop: said, to be unlocked by hand.
        if (card.locked) {
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
        Atc3Alarm.MOTOR_ERROR            -> R.string.atc3_alarm_motor_error
        Atc3Alarm.RESERVOIR_EMPTY        -> R.string.atc3_alarm_reservoir_empty
        Atc3Alarm.DAILY_LIMIT            -> R.string.atc3_alarm_daily_limit
    }

    /** Read the refills when the reservoir went up, and every [Atc3Const.ALARM_READ_INTERVAL_MS] for one the ticks missed. */
    private suspend fun readRefillsIfDue() {
        val now = dateUtil.now()
        val level = pumpState.reservoirUnits
        val wentUp = reservoirSeenUnits >= 0.0 && level > reservoirSeenUnits + Atc3Protocol.DOSE_SCALE
        reservoirSeenUnits = level
        // A refill is a beginning for the comparison, whether or not its record is read.
        if (wentUp) reconciliation.noteRefill()
        val due = wentUp || refillsReadAtMs == 0L || now - refillsReadAtMs >= Atc3Const.ALARM_READ_INTERVAL_MS
        if (!due) return
        val records = atc3Manager.readRefillHistory() ?: return
        refillsReadAtMs = now
        // One the ticks did not see is in the journal all the same.
        if (historyEvents.recordRefills(records) > 0) reconciliation.noteRefill()
    }

    /** Read the alarm history when the status shows one, and every [Atc3Const.ALARM_READ_INTERVAL_MS] for one that came and went. */
    private suspend fun readAlarmsIfDue() {
        val now = dateUtil.now()
        val raising = pumpState.activeAlarmCodes.isNotEmpty()
        val due = raising || alarmsReadAtMs == 0L || now - alarmsReadAtMs >= Atc3Const.ALARM_READ_INTERVAL_MS
        if (!due) return
        val records = atc3Manager.readAlarmHistory() ?: return
        alarmsReadAtMs = now
        historyEvents.recordAlarms(records)
    }

    override fun stopBolusDelivering() {
        aapsLogger.debug(LTag.PUMP, "ATC3: stopping bolus")
        if (!bolusDelivery.stopBolus()) aapsLogger.error(LTag.PUMP, "ATC3: could not stop the bolus")
    }

    // The profile only turns a rate into a percentage; this pump takes absolute rates.
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
        if (!atc3Connection.isConnected) {
            return notConnected()
        }
        refusedWhileLocked()?.let { return it }
        // The status this command is judged on: what was set by hand meanwhile is recorded first.
        atc3Manager.readStatus()
        // Only the loop's temporary basal is a decision computed from data.
        dataChangedUnderTheLoop(loops = tbrType == PumpSync.TemporaryBasalType.NORMAL)?.let { return it }
        val rate = PumpType.ATC3.determineCorrectBasalSize(absoluteRate)
        if (enforceNew && pumpState.tbrActive) {
            aapsLogger.debug(LTag.PUMP, "ATC3: cancelling the running temporary basal first")
            val cancel = atc3Manager.cancelTempBasal()
            cancel.failure?.let {
                aapsLogger.error(LTag.PUMP, "ATC3: could not clear the running temporary basal, $it")
                return failed(it)
            }
            val cancelledAt = cancel.acceptedAtMs.takeIf { it > 0L } ?: dateUtil.now()
            atc3HistorySync.tbrStopped(cancelledAt, journal = reconciliation.journal)
        }
        val result = atc3Manager.setTempBasal(rate, durationInMinutes)
        result.failure?.let {
            aapsLogger.error(LTag.PUMP, "ATC3: setTempBasalAbsolute failed, $it")
            return failed(it)
        }
        recordOwnTbr(result, tbrType)
        return pumpEnactResultProvider.get().success(true).enacted(true)
            .absolute(result.rate).duration(result.durationMinutes)
    }

    /** A temporary basal the pump has just accepted from this driver goes into the books and AAPS. */
    private suspend fun recordOwnTbr(result: Atc3TbrResult, type: PumpSync.TemporaryBasalType = PumpSync.TemporaryBasalType.NORMAL) {
        // The pump's own record of the command, so AAPS's row carries the pump's start; used only when it is this command.
        val record = atc3Manager.readActiveTbr()
        // Same rate and duration: how far its stamp sits from the acknowledgement feeds the clock watch.
        if (record != null && isSameCommand(record, result)) {
            val lagMs = result.acceptedAtMs - record.startTimestamp
            clockWatch.ownTbrStamped(lagMs, dateUtil.now())
            trace.event(Atc3TraceCat.TBR, "own_stamp", "lagMs" to lagMs, "clockSet" to clockWatch.needsSetting(dateUtil.now()))
        }
        val pumpStart = record?.takeIf { isCommandOf(it, result) }
        if (pumpStart == null) aapsLogger.debug(LTag.PUMP, "ATC3: the pump's start of the temporary basal just set is not known yet")
        atc3HistorySync.tbrStartedByAaps(result.acceptedAtMs, result.rate, result.durationMinutes, pumpStart, type = type, journal = reconciliation.journal)
    }

    /** Whether the pump's record is the command just accepted: same rate and duration, a start near the acknowledgement. */
    private fun isCommandOf(record: Atc3TbrStatus, result: Atc3TbrResult): Boolean =
        isSameCommand(record, result) && abs(record.startTimestamp - result.acceptedAtMs) <= OWN_TBR_START_MS

    /** Whether the pump's record has the same rate and duration, with a start it filled in. */
    private fun isSameCommand(record: Atc3TbrStatus, result: Atc3TbrResult): Boolean {
        val rate = record.rate ?: return false
        return Math.round(rate / Atc3Protocol.DOSE_SCALE) == Math.round(result.rate / Atc3Protocol.DOSE_SCALE) &&
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
     * @param enforceNew true for the user's own cancel, which goes through whatever the journals said;
     *   the loop's own cancel comes without it
     */
    private suspend fun cancelTempBasalInner(enforceNew: Boolean = false): PumpEnactResult {
        if (!atc3Connection.isConnected) {
            return notConnected()
        }
        // Asked, not cached: a cancel reported done without sending anything must never be.
        val card = atc3Manager.readStatus()
            ?: return notConnected()
        // A stopped pump delivers nothing, its temporary basal included: nothing to cancel, and the open
        // row is the stop, which a cancel must not end. The stop is still written from this status.
        if (card.notDelivering) {
            reconciliation.recordRunningState()
            aapsLogger.debug(LTag.PUMP, "ATC3: the pump is stopped, nothing to cancel")
            trace.event(Atc3TraceCat.DRV, "tbr_cancel_skipped", "why" to "suspended")
            // Often the only look at a stop: the resume is watched for from here.
            watchForResume()
            return pumpEnactResultProvider.get().success(true).enacted(false).isTempCancel(true)
        }
        dataChangedUnderTheLoop(loops = !enforceNew)?.let { return it }
        if (!card.tbrActive) {
            // Nothing runs: AAPS's open row, from a cancel on the pump, closes where it really ended.
            val open = atc3HistorySync.openTbr()
            val now = dateUtil.now()
            val endedAt =
                if (Atc3TbrTracker.needsEndRead(open, card.notDelivering, false, now))
                    atc3Manager.readFinishedTbr()?.let { atc3HistorySync.realEndOf(it) }
                else null
            val ranOut = open?.takeIf { !it.suspension }?.let { it.startedAtMs + it.ownDurationMs }?.takeIf { it <= now }
            atc3HistorySync.tbrStopped(endedAt ?: ranOut ?: now, byStamp = endedAt != null, journal = reconciliation.journal)
            return pumpEnactResultProvider.get().success(true).enacted(false).isTempCancel(true)
        }
        // Here rather than at the top: with nothing running there is nothing to send.
        refusedWhileLocked()?.let { return it }
        val cancel = atc3Manager.cancelTempBasal()
        cancel.failure?.let {
            aapsLogger.error(LTag.PUMP, "ATC3: cancelTempBasal failed, $it")
            return failed(it)
        }
        // The scheduled rate is back from the pump's acknowledgement of the cancel.
        val cancelledAt = cancel.acceptedAtMs.takeIf { it > 0L } ?: dateUtil.now()
        atc3HistorySync.tbrStopped(cancelledAt, journal = reconciliation.journal)
        return pumpEnactResultProvider.get().success(true).enacted(true).isTempCancel(true)
    }

    /**
     * Not offered: the loop does not need it, and the pump's record of one has no duration to rebuild
     * it from. One given on the pump is imported as an ordinary bolus, see [Atc3HistorySync].
     */
    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult =
        unsupported(R.string.atc3_not_supported_extended_bolus)

    /** Not offered, see [setExtendedBolus]. */
    override fun cancelExtendedBolus(): PumpEnactResult =
        unsupported(R.string.atc3_not_supported_extended_bolus)

    // Settings

    /**
     * The driver's own commands, carried on the AAPS command queue: settings, stopping and
     * resuming, the Bluetooth password, the check of a quiet link and the pairing read. Anything
     * else is answered with null, which the queue reports as unsupported.
     *
     * Suspend paths are bridged with runBlocking rather than launched, because the answer is the
     * return value; nothing below re-enters the queue.
     */
    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult? =
        when (customCommand) {
            is Atc3WriteSettings -> writeSettings(customCommand)
            is Atc3SetSuspended  -> runBlocking { setSuspended(customCommand.suspended) }
            is Atc3SetBtPassword -> setBtPassword(customCommand.password)
            is Atc3ProbeLink     -> pumpEnactResultProvider.get().success(atc3Manager.probeQuietLink()).enacted(false)
            is Atc3Pair          -> tracked("pair") { pumpEnactResultProvider.get().success(atc3Manager.readStatus() != null).enacted(false) }
            else                 -> {
                aapsLogger.error(LTag.PUMP, "ATC3: unsupported custom command ${customCommand.statusDescription}")
                null
            }
        }

    /** Stop or resume the pump on the user's word; the stop reaches AAPS from the next status, as one made on the keypad does. */
    private suspend fun setSuspended(suspended: Boolean): PumpEnactResult =
        tracked("suspend", "wanted" to suspended) { setSuspendedInner(suspended) }

    private suspend fun setSuspendedInner(suspended: Boolean): PumpEnactResult {
        if (!atc3Connection.isConnected) {
            return notConnected()
        }
        // Said as the screen shows it, not as the pump's blank refusal.
        refusedWhileLocked()?.let { return it }
        val failure = atc3Manager.setSuspended(suspended)
        if (failure != null) {
            aapsLogger.error(LTag.PUMP, "ATC3: could not ${if (suspended) "stop" else "resume"} the pump, $failure")
            return failed(failure)
        }
        // AAPS is told now: the stop has to reach insulin on board, and the loop has to stop.
        tick("suspend state changed")
        return pumpEnactResultProvider.get().success(true).enacted(true)
    }

    /**
     * Give the pump a new Bluetooth password and store it once acknowledged, before the link the pump
     * then drops comes back. Both candidates are stored, see [Atc3BtPassword.candidatesFor].
     */
    private fun setBtPassword(password: Int): PumpEnactResult =
        tracked("btPassword") {
            if (!atc3Connection.isConnected) {
                return@tracked notConnected()
            }
            refusedWhileLocked()?.let { return@tracked it }
            val failure = atc3Manager.setBtPassword(password)
            if (failure != null) {
                aapsLogger.error(LTag.PUMP, "ATC3: Bluetooth password change failed, $failure")
                return@tracked failed(failure)
            }
            val candidates = Atc3BtPassword.candidatesFor(password)
            preferences.put(Atc3StringKey.Atc3BtPassword, candidates[0])
            preferences.put(Atc3StringKey.Atc3BtPasswordAlternate, candidates[1])
            pumpEnactResultProvider.get().success(true).enacted(true)
        }

    private fun writeSettings(command: Atc3WriteSettings): PumpEnactResult =
        tracked("settings") { writeSettingsInner(command) }

    private fun writeSettingsInner(command: Atc3WriteSettings): PumpEnactResult {
        if (!atc3Connection.isConnected) {
            return notConnected()
        }
        refusedWhileLocked()?.let { return it }
        val failure = atc3Manager.writeSettings(command.settings)
        if (failure != null) {
            aapsLogger.error(LTag.PUMP, "ATC3: settings write failed, $failure")
            return failed(failure)
        }
        return pumpEnactResultProvider.get().success(true).enacted(true)
    }

    // Identity

    override fun manufacturer(): ManufacturerType = ManufacturerType.Atc3

    override fun model(): PumpType = PumpType.ATC3

    override fun serialNumber(): String = pumpState.serialNumber

    /** Built once: read on paths that run often. */
    override val pumpDescription: PumpDescription = PumpDescription().fillFor(PumpType.ATC3)

    override val isFakingTempsByExtendedBoluses: Boolean = false

    /** Read the pump's daily totals for AAPS; none are written unless one falls on the pump's today, since a wrong date is worse than none. */
    override fun loadTDDs(): PumpEnactResult = runBlocking { tracked("tdd") { loadTDDsInner() } }

    private suspend fun loadTDDsInner(): PumpEnactResult {
        if (!atc3Connection.isConnected) {
            return notConnected()
        }
        // The dates are checked against the pump's clock: read it now.
        if (atc3Manager.readStatus() == null) {
            return notConnected()
        }
        val stats = atc3Manager.readDailyStats()
        if (stats.isNullOrEmpty()) {
            aapsLogger.error(LTag.PUMP, "ATC3: the pump returned no daily totals")
            return failed(rh.gs(R.string.atc3_tdd_unavailable))
        }

        val usable = stats.filter { !it.isEmpty && it.isDatePlausible }
        if (usable.none { it.isSameDayAs(dateUtil.now()) }) {
            aapsLogger.error(
                LTag.PUMP,
                "ATC3: no daily total falls on the pump's own date, so the date bytes are not what " +
                    "they were taken for; writing none of them"
            )
            return failed(rh.gs(R.string.atc3_tdd_unavailable))
        }

        val written = historyEvents.recordDailyTotals(usable)
        aapsLogger.debug(LTag.PUMP, "ATC3: $written of ${usable.size} daily totals were new to AAPS")
        trace.event(Atc3TraceCat.HIST, "tdd", "days" to usable.size, "new" to written)
        return pumpEnactResultProvider.get().success(true).enacted(true)
    }

    override fun canHandleDST(): Boolean = false

    /**
     * The driver's own switches, also on the pump screen, here for AAPS's settings search. The pump's
     * own settings are on the pump screen, as the pump takes them whole; pairing is on the pump
     * selection screen.
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
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context, booleanKey = Atc3BooleanKey.ExactBasal,
                    title = R.string.atc3_exact_basal_title, summary = R.string.atc3_exact_basal_summary
                )
            )
        }
    }

    /** Trace one thing AAPS asked of the driver as a begin and an end, the end in a `finally`: a command that never returned shows. */
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

    private fun unsupported(comment: Int): PumpEnactResult = failed(rh.gs(comment))

    /** A command that was not carried out, and why. */
    private fun failed(comment: String): PumpEnactResult = pumpEnactResultProvider.get().success(false).enacted(false).comment(comment)

    private fun notConnected(): PumpEnactResult = failed(rh.gs(R.string.atc3_not_connected))

    /** A command the pump did not carry out: refused, or not answered. */
    private fun failed(failure: Atc3Failure): PumpEnactResult =
        failed(if (failure.refused) rh.gs(R.string.atc3_refused_by_pump) else failure.text)

    /**
     * Refuse a control command a locked pump would refuse anyway, saying the true reason: the pump is
     * unlocked on the pump. Reads go on while it is locked.
     */
    private fun refusedWhileLocked(): PumpEnactResult? {
        if (!pumpState.locked) return null
        aapsLogger.debug(LTag.PUMP, "ATC3: the pump is locked, not sending the command")
        trace.event(Atc3TraceCat.DRV, "locked")
        return failed(rh.gs(R.string.atc3_pump_locked))
    }

    private companion object {

        /** How often a stopped pump is asked for its status: under the minute the resume's report lasts. */
        const val RESUME_WATCH_MS = 50_000L

        /** How far the pump's stamp of our temporary basal may sit from our acknowledgement: a minute, and the clock. */
        const val OWN_TBR_START_MS = 90_000L

        /** How often a held bolus looks whether the user gave up on it. */
        const val BOLUS_HOLD_POLL_MS = 250L
    }
}
