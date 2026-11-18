package app.aaps.pump.apex

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.di.PumpDriver
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.OwnDatabasePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.determineCorrectBasalSize
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.pump.mapState
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.icons.IcPluginVirtualPump
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.pump.apex.command.ApexBolusDelivery
import app.aaps.pump.apex.command.ApexPair
import app.aaps.pump.apex.command.ApexSetBtPassword
import app.aaps.pump.apex.command.ApexSetupForAaps
import app.aaps.pump.apex.command.BolusCommand
import app.aaps.pump.apex.command.TbrCommand
import app.aaps.pump.apex.compose.ApexComposeContent
import app.aaps.pump.apex.deep.ApexHeartbeatOverdue
import app.aaps.pump.apex.deep.HeartbeatOverdueDeep
import app.aaps.pump.apex.keys.ApexBooleanKey
import app.aaps.pump.apex.keys.ApexIntKey
import app.aaps.pump.apex.keys.ApexIntNonKey
import app.aaps.pump.apex.keys.ApexStringKey
import app.aaps.pump.apex.link.ApexConnection
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.pulse.ApexHeartbeatTick
import app.aaps.pump.apex.pulse.ApexLinkSetup
import app.aaps.pump.apex.pulse.HeartbeatTick
import app.aaps.pump.apex.state.AbilityProbe
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.store.ApexDatabase
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey as MetroIntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The Apex pump in AAPS, third driver.
 *
 * The pump is read at its own heartbeat and nowhere else: the heartbeat queues one reconciliation
 * turn on the AAPS queue, so nothing else of the driver is on the air while it runs. Between
 * heartbeats the loop decides on the rows in AAPS and its commands run as they come. The link is
 * held: the heartbeat comes over it. A bolus or a temporary basal is confirmed in its own turn,
 * by the pump's record or state, see [BolusCommand] and [TbrCommand].
 *
 * Not written yet: the verification of the picture, step 2 of the reconciliation.
 */
// Pump plugin registration: the key range 1000-1200 is the pump drivers.
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@PumpDriver
@MetroIntKey(1175)
@SingleIn(AppScope::class)
@Inject
class ApexPumpPlugin(
    aapsLogger: AAPSLogger,
    override val rh: ResourceHelper,
    preferences: Preferences,
    commandQueue: CommandQueue,
    notificationManager: NotificationManager,
    private val pumpState: ApexPumpState,
    private val manager: ApexManager,
    private val abilityProbe: AbilityProbe,
    private val connection: ApexConnection,
    private val database: ApexDatabase,
    private val heartbeatTick: HeartbeatTick,
    private val heartbeatOverdue: HeartbeatOverdueDeep,
    private val bolusCommand: BolusCommand,
    private val bolusDelivery: ApexBolusDelivery,
    private val tbrCommand: TbrCommand,
    private val pumpSetup: ApexPumpSetup,
    private val pumpClock: ApexPumpClock,
    private val trace: ApexTrace,
    private val rxBus: RxBus,
    private val blePreCheck: BlePreCheck,
    private val pumpEnactResultProvider: () -> PumpEnactResult
) : PumpPluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.PUMP)
        .icon(IcPluginVirtualPump)
        .composeContent { _ ->
            ApexComposeContent(
                pluginName = rh.gs(R.string.apex_pump),
                blePreCheck = blePreCheck
            )
        }
        .pluginName(TextRef.AndroidRes(R.string.apex_pump))
        .description(TextRef.AndroidRes(R.string.apex_description)),
    ownPreferences = ApexStringKey.entries + ApexBooleanKey.entries + ApexIntKey.entries + ApexIntNonKey.entries,
    aapsLogger, rh, preferences, commandQueue, notificationManager
), Pump, OwnDatabasePlugin {

    override val pumpDescription: PumpDescription = PumpDescription().fillFor(PumpType.APEX)

    /** The Maintenance screen of AAPS clears the driver's database with the rest. */
    override fun clearAllTables() = database.clearAllTables()

    /** The watch on the link going up, for as long as the plugin runs. */
    private var linkWatch: Job? = null

    /** The watch over the pump's heartbeat, for as long as the plugin runs, see [HeartbeatOverdueDeep]. */
    private var beatWatch: Job? = null

    override suspend fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.PUMP, "Apex: driver version ${ApexConst.DRIVER_VERSION}")
        if (pumpState.serialNumber.isEmpty()) pumpState.serialNumber = preferences.get(ApexStringKey.ApexSerialNumber)
        connection.askOnHeartbeat = { onHeartbeat() }
        // A new link is set up once, in its turn on the queue, before the first heartbeat is answered.
        linkWatch = rxBus.toFlow(EventPumpStatusChanged::class)
            .filter { it.status == EventPumpStatusChanged.Status.CONNECTED }
            .collectResilient(pluginScope, aapsLogger, LTag.PUMP) { commandQueue.customCommand(ApexLinkSetup()) }
        heartbeatOverdue.start(System.currentTimeMillis())
        beatWatch = pluginScope.launch {
            while (isActive) {
                delay(HeartbeatOverdueDeep.CHECK_MS)
                // The asking is launched: the read itself runs in its turn on the queue.
                if (heartbeatOverdue.check(System.currentTimeMillis())) launch { commandQueue.customCommand(ApexHeartbeatOverdue()) }
            }
        }
    }

    override suspend fun onStop() {
        linkWatch?.cancel()
        linkWatch = null
        beatWatch?.cancel()
        beatWatch = null
        connection.askOnHeartbeat = null
        super.onStop()
    }

    /**
     * The heartbeat came: the reconciliation is queued, so that it runs in its turn among the
     * commands. While our bolus runs there is no reconciliation: the bolus command notes the
     * heartbeat and answers it once the bolus is settled, see `BolusCommand`. A queue busy with another command, or
     * with one waiting, gets the reconciliation queued behind it: it runs late by the length of
     * that command, not skipped. One waiting reconciliation is enough for any number of heartbeats.
     */
    private fun onHeartbeat() {
        val now = System.currentTimeMillis()
        pumpState.lastHeartbeatAtMs = now
        if (bolusDelivery.heartbeatCame(now)) {
            aapsLogger.debug(LTag.PUMP, "Apex: heartbeat during our bolus, answered when the bolus ends")
            return
        }
        val busy = commandQueue.performing()?.status() ?: "${commandQueue.size()} waiting".takeIf { commandQueue.size() > 0 }
        if (busy != null) {
            if (commandQueue.isCustomCommandInQueue(ApexHeartbeatTick::class)) {
                aapsLogger.debug(LTag.PUMP, "Apex: heartbeat while a reconciliation already waits behind $busy, nothing more queued")
                trace.event(ApexTraceCat.STATE, "heartbeat_skipped", "performing" to busy)
                return
            }
            aapsLogger.debug(LTag.PUMP, "Apex: heartbeat while the queue is busy with $busy, the reconciliation is queued behind it")
            trace.event(ApexTraceCat.STATE, "heartbeat_queued_late", "performing" to busy)
        }
        // The queue is asked from the Bluetooth thread: the asking is launched, the turn itself runs in the queue.
        pluginScope.launch { commandQueue.customCommand(ApexHeartbeatTick(now)) }
    }

    // The link

    override fun isInitialized(): Boolean = pumpState.isInitialized

    /** Stopped, under an alarm or locked: the pump takes no command and delivers nothing. */
    override fun isSuspended(): Boolean = pumpState.takesNoCommands
    override fun isBusy(): Boolean = manager.isBusy
    override fun isConnected(): Boolean = connection.isConnected
    override fun isConnecting(): Boolean = connection.isConnecting
    override fun isHandshakeInProgress(): Boolean = false

    override fun connect(reason: String) {
        connection.connect(reason)
    }

    override fun disconnect(reason: String) {
        connection.disconnect(reason)
    }

    override fun stopConnecting() = connection.disconnect("stopConnecting")

    /**
     * The core asks for the pump's status: an empty tick. The pump is read only at its heartbeat.
     * When the link was down, the queue raised it before calling here, and that is all this request
     * is for: the heartbeat was long overdue, else the link would have been held.
     */
    override suspend fun getPumpStatus(reason: String) {
        aapsLogger.debug(LTag.PUMP, "Apex: getPumpStatus ($reason), nothing to do: the pump is read at its heartbeat")
    }

    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult? = when (customCommand) {
        is ApexHeartbeatTick -> pumpEnactResultProvider().success(heartbeatTick.run(customCommand.heartbeatAtMs)).enacted(false)
        is ApexLinkSetup     -> pumpEnactResultProvider().success(linkSetup()).enacted(false)
        is ApexHeartbeatOverdue -> pumpEnactResultProvider().success(heartbeatOverdue.ask()).enacted(false)
        is ApexPair          -> pumpEnactResultProvider().success(manager.readStatus() != null).enacted(false)
        is ApexSetupForAaps  -> pumpSetup.setupForAaps()
        is ApexSetBtPassword -> pumpSetup.changeBtPassword()
        else                 -> {
            aapsLogger.error(LTag.PUMP, "Apex: unsupported custom command ${customCommand.statusDescription}")
            null
        }
    }

    /** What a new link needs: the heartbeat period, the version once, which optional objects the pump has, asked each time, and the start reconciliation when no status is kept, see [HeartbeatTick.startIfNeeded]. True when the pump answered. */
    private fun linkSetup(): Boolean {
        if (!connection.isConnected) return false
        manager.ensureHeartbeatPeriod()
        if (pumpState.version == null) manager.readVersion()
        abilityProbe.probe()
        heartbeatTick.startIfNeeded()
        return true
    }

    /** The phone's time moved: the pump's clock follows at the next heartbeat, see [ApexPumpClock]. */
    override suspend fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) = pumpClock.onPhoneTimeChanged(timeChangeType)

    // What the pump last said

    override val lastDataTime: StateFlow<Long> = pumpState.lastConnectionFlow

    /** The pump's records are the bolus history; the core's own "last bolus" is not kept here. */
    override val lastBolusTime: StateFlow<Long?> = MutableStateFlow<Long?>(null).asStateFlow()
    override val lastBolusAmount: StateFlow<PumpInsulin?> = MutableStateFlow<PumpInsulin?>(null).asStateFlow()
    override val baseBasalRate: PumpRate get() = PumpRate(pumpState.scheduledBasalRate)
    override val reservoirLevel: StateFlow<PumpInsulin> = pumpState.statusCardFlow.mapState { PumpInsulin(it?.status?.reservoirUnits ?: 0.0) }
    override val batteryLevel: StateFlow<Int?> = pumpState.batteryPercentFlow
    override fun serialNumber(): String = pumpState.serialNumber.ifEmpty { preferences.get(ApexStringKey.ApexSerialNumber) }

    // Insulin

    /** Stopped, under an alarm, locked, or with extended boluses allowed on the pump: no insulin command goes. */
    private fun refusedByPumpState(): PumpEnactResult? = when {
        !connection.isConnected                          -> failed(rh.gs(R.string.apex_not_connected))
        isSuspended()                                    -> failed(rh.gs(R.string.apex_pump_not_taking_commands))
        !heartbeatTick.startIfNeeded()                  -> failed(rh.gs(R.string.apex_no_reconciliation))
        pumpState.settings?.extendedBolusAllowed == true -> {
            notificationManager.post(NotificationId.PUMP_ERROR, rh.gs(R.string.apex_extended_allowed), level = NotificationLevel.URGENT)
            failed(rh.gs(R.string.apex_extended_allowed))
        }

        else                                             -> null
    }

    override suspend fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        require(detailedBolusInfo.carbs == 0.0) { detailedBolusInfo.toString() }
        refusedByPumpState()?.let { return it }
        val result = bolusCommand.deliver(detailedBolusInfo, serialNumber())
        return pumpEnactResultProvider().success(result.success).enacted(result.enacted).bolusDelivered(result.deliveredUnits)
            .also { r -> result.comment?.let { r.comment(it) } }
    }

    override fun stopBolusDelivering() {
        bolusDelivery.stopBolus()
    }

    override suspend fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        refusedByPumpState()?.let { return it }
        val rate = PumpType.APEX.determineCorrectBasalSize(absoluteRate)
        val outcome = tbrCommand.set(serialNumber(), rate, durationInMinutes, tbrType)
        outcome.failure?.let { return failed(it) }
        return pumpEnactResultProvider().success(true).enacted(true).absolute(outcome.rate).duration(outcome.durationMinutes)
    }

    override suspend fun setTempBasalPercent(percent: Int, durationInMinutes: Int, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult =
        failed(rh.gs(R.string.apex_tbr_percent_unsupported))

    override suspend fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        refusedByPumpState()?.let { return it }
        val outcome = tbrCommand.cancel(serialNumber())
        outcome.failure?.let { return failed(it) }
        return pumpEnactResultProvider().success(true).enacted(outcome.enacted).isTempCancel(true)
    }

    override suspend fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult = failed(rh.gs(R.string.apex_extended_unsupported))
    override suspend fun cancelExtendedBolus(): PumpEnactResult = failed(rh.gs(R.string.apex_extended_unsupported))

    /** Not written yet: the daily totals will come from the pump's daily statistics. */
    override suspend fun loadTDDs(): PumpEnactResult = failed(rh.gs(R.string.apex_not_ready))

    // Basal profile

    /** The AAPS profile goes into the pump's slot and is read back; AAPS keeps the two equal, and tells the user the outcome itself. */
    override suspend fun setNewBasalProfile(profile: PumpProfile): PumpEnactResult {
        if (!connection.isConnected) return failed(rh.gs(R.string.apex_not_connected))
        val rates = ApexPumpState.buildBasalSlots(profile) { PumpType.APEX.determineCorrectBasalSize(it) }
        val failure = manager.writeBasalProfile(rates)
        return if (failure == null) {
            pumpEnactResultProvider().success(true).enacted(true)
        } else {
            aapsLogger.error(LTag.PUMP, "Apex: setNewBasalProfile failed, $failure")
            failed(failure)
        }
    }

    override fun isThisProfileSet(profile: PumpProfile): Boolean {
        // Nothing read yet: a mismatch would queue a write that cannot be carried out.
        val stored = pumpState.profiles?.rates?.getOrNull(ApexConst.DRIVER_PROFILE_INDEX) ?: return true
        if (pumpState.activeProfileIndex != ApexConst.DRIVER_PROFILE_INDEX) return false
        val wanted = ApexPumpState.buildBasalSlots(profile) { PumpType.APEX.determineCorrectBasalSize(it) }
        return ApexPumpState.differingSlots(wanted, stored).isEmpty()
    }

    override fun manufacturer(): ManufacturerType = PumpType.APEX.manufacturer()
    override fun model(): PumpType = PumpType.APEX
    override val isFakingTempsByExtendedBoluses: Boolean = false
    override fun canHandleDST(): Boolean = true

    private fun failed(comment: String): PumpEnactResult = pumpEnactResultProvider().success(false).enacted(false).comment(comment)

    // Settings

    /** The driver's own switches, for the AAPS settings list and its search. The pump is picked on the pump screen. */
    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "apex_settings",
        title = TextRef.AndroidRes(R.string.apex_pump),
        items = listOf(ApexBooleanKey.HoldLink, ApexBooleanKey.Trace),
        icon = pluginDescription.icon
    )
}
