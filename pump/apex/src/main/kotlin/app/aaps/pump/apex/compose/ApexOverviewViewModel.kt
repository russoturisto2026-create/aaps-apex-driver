package app.aaps.pump.apex.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R as CoreUiR
import app.aaps.core.ui.compose.ScreenAction
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.pump.PumpCommunicationStatus
import app.aaps.core.ui.compose.pump.PumpInfoRow
import app.aaps.core.ui.compose.pump.PumpOverviewStateBuilder
import app.aaps.core.ui.compose.pump.PumpOverviewUiState
import app.aaps.core.ui.compose.pump.StatusBanner
import app.aaps.core.ui.compose.pump.tickerFlow
import app.aaps.pump.apex.ApexConst
import app.aaps.pump.apex.ApexPumpPlugin
import app.aaps.pump.apex.R
import app.aaps.pump.apex.account.AapsToday
import app.aaps.pump.apex.command.ApexSetupForAaps
import app.aaps.pump.apex.events.EventApexPumpDataChanged
import app.aaps.pump.apex.account.basal.ExactBasalMode
import app.aaps.pump.apex.keys.ApexIntKey
import app.aaps.pump.apex.keys.ApexStringKey
import app.aaps.pump.apex.link.ApexConnection
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.state.ApexPumpState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the screen asks the content around it to do. */
sealed class ApexOverviewEvent {

    /** Open the pump selection screen. */
    data object StartScan : ApexOverviewEvent()
}

/** One part of today's delivery: by the AAPS rows and by the pump, U; the pump's is null when it did not say. */
class DayLine(val aapsUnits: Double, val pumpUnits: Double?)

/** The day by the AAPS rows and by the pump, both from one start to one moment of the comparison. */
class DayTable(val atMs: Long, val fromText: String, val atText: String, val bolus: DayLine, val basal: DayLine, val total: DayLine)

/** The driver's own blocks of the screen, under the status card AAPS draws. */
@Immutable
class ApexScreenState(
    /** Last connection, last bolus, battery, reservoir. */
    val stateRows: List<PumpInfoRow> = emptyList(),
    /** Basal, temporary basal, the pump's state, the link, serial, firmware, driver. */
    val pumpRows: List<PumpInfoRow> = emptyList(),
    val day: DayTable? = null,
    val pumpChosen: Boolean = false
)

/**
 * The driver's screen: the pump's state, the pump and the driver, today's delivery by AAPS and by
 * the pump, and the button that sets the pump up for AAPS with the driver's switches.
 *
 * Nothing here reads the pump; the pump is read at its heartbeat only. The day table is counted once
 * for each heartbeat picture, at the moment of that heartbeat: the pump's count and the AAPS rows
 * are held against each other at the same moment, so a bolus given between two heartbeats does not
 * show as a gap.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class ApexOverviewViewModel(
    private val rh: ResourceHelper,
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val ch: ConcentrationHelper,
    private val preferences: Preferences,
    private val pumpState: ApexPumpState,
    private val connection: ApexConnection,
    private val plugin: ApexPumpPlugin,
    private val commandQueue: CommandQueue,
    private val aapsToday: AapsToday,
    rxBus: RxBus
) : ViewModel() {

    private val _events = MutableSharedFlow<ApexOverviewEvent>(extraBufferCapacity = 5)
    val events: SharedFlow<ApexOverviewEvent> = _events

    private val communicationStatus = PumpCommunicationStatus(rxBus, commandQueue, rh, viewModelScope)
    private val rowBuilder = PumpOverviewStateBuilder(rh)

    private val _refreshTrigger = MutableStateFlow(0L)
    private val _refreshing = MutableStateFlow(false)
    private val _dayTable = MutableStateFlow<DayTable?>(null)
    private val _exactBasal = MutableStateFlow(ExactBasalMode.of(preferences.get(ApexStringKey.ExactBasal)))
    private val _heartbeatPeriod = MutableStateFlow(preferences.get(ApexIntKey.HeartbeatPeriod).takeIf { it in ApexConst.HEARTBEAT_PERIODS } ?: ApexConst.HEARTBEAT_PERIOD_DEFAULT)

    /** True while the pull on the screen is being answered. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** The choice of the exact basal record. */
    val exactBasal: StateFlow<ExactBasalMode> = _exactBasal.asStateFlow()

    /** The period of the pump's heartbeat, minutes: it goes to the pump at the next heartbeat. */
    val heartbeatPeriod: StateFlow<Int> = _heartbeatPeriod.asStateFlow()

    /** The pump's maximum bolus and basal as its last status carried them, U and U/h; null before the first. */
    val pumpLimits: StateFlow<Pair<Double, Double>?> = pumpState.statusCardFlow
        .map { card -> card?.settings?.let { it.maxBolusRaw * ApexProtocol.DOSE_SCALE to it.maxBasalRaw * ApexProtocol.DOSE_SCALE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        // The driver says when anything it holds has changed; the link event marks the link going up
        // and down; the ticker is only for the ages that creep.
        rxBus.toFlow(EventApexPumpDataChanged::class)
            .onEach { _refreshTrigger.value = dateUtil.now() }
            .launchIn(viewModelScope)
        rxBus.toFlow(EventPumpStatusChanged::class)
            .onEach { _refreshTrigger.value = dateUtil.now() }
            .launchIn(viewModelScope)
        pumpState.reconcilePointFlow
            .onEach { point -> point?.let { countDay(it) } }
            .launchIn(viewModelScope)
    }

    /** The AAPS rows up to the moment of [point], against the pump's count read at it. */
    private suspend fun countDay(point: ApexPumpState.ReconcilePoint) {
        if (_dayTable.value?.atMs == point.atMs) return
        val fromMs = point.fromMs ?: MidnightTime.calc(point.atMs)
        val aaps = aapsToday.count(point.atMs, fromMs) ?: return
        _dayTable.value = DayTable(
            atMs = point.atMs,
            fromText = dateUtil.timeString(fromMs),
            atText = dateUtil.timeString(point.atMs),
            bolus = DayLine(onPumpStep(aaps.bolusUnits), point.dayBolusUnits),
            basal = DayLine(onPumpStep(aaps.basalUnits), point.dayBasalUnits),
            total = DayLine(onPumpStep(aaps.totalUnits), point.counterUnits)
        )
    }

    /**
     * The AAPS sum is rates over time and comes out between the pump's steps; it is shown on the
     * nearest step, so a thousandth of a unit is not shown as a gap.
     */
    private fun onPumpStep(units: Double): Double = Math.round(units / ApexProtocol.DOSE_SCALE) * ApexProtocol.DOSE_SCALE

    /** The status card and the toolbar actions; the rows are the driver's own blocks, see [screenState]. */
    val uiState: StateFlow<PumpOverviewUiState> = combine(
        _refreshTrigger,
        communicationStatus.refreshTrigger,
        tickerFlow(T.mins(1).msecs())
    ) { _, _, _ ->
        buildUiState()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), buildUiState())

    val screenState: StateFlow<ApexScreenState> = combine(
        _refreshTrigger,
        communicationStatus.refreshTrigger,
        _dayTable,
        tickerFlow(T.mins(1).msecs())
    ) { _, _, day, _ ->
        buildScreenState(day)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), buildScreenState(null))

    private fun isPumpChosen(): Boolean = preferences.get(ApexStringKey.ApexSerialNumber).isNotEmpty()

    private fun buildUiState(): PumpOverviewUiState {
        val chosen = isPumpChosen()
        return PumpOverviewUiState(
            statusBanner = banner(chosen) ?: communicationStatus.statusBanner(),
            queueStatus = communicationStatus.queueStatus(),
            managementActions = managementActions(chosen)
        )
    }

    private fun buildScreenState(day: DayTable?): ApexScreenState {
        if (!isPumpChosen()) return ApexScreenState()
        return ApexScreenState(stateRows = stateRows(), pumpRows = pumpRows(), day = day, pumpChosen = true)
    }

    /** The one thing worth saying above everything else: an alarm first, then a stopped pump, then a locked one. */
    private fun banner(chosen: Boolean): StatusBanner? = when {
        !chosen                                 -> StatusBanner(rh.gs(R.string.apex_no_pump_selected), StatusLevel.WARNING)
        pumpState.activeAlarmCodes.isNotEmpty() -> StatusBanner(rh.gs(R.string.apex_state_alarm), StatusLevel.CRITICAL)
        pumpState.suspended                     -> StatusBanner(rh.gs(R.string.apex_state_stopped), StatusLevel.WARNING)
        pumpState.locked                        -> StatusBanner(rh.gs(R.string.apex_state_locked), StatusLevel.WARNING)
        else                                    -> null
    }

    private fun stateRows(): List<PumpInfoRow> = rowBuilder.buildCommonRows(
        lastConnection = pumpState.lastConnection.takeIf { it > 0L }?.let { agoText(it) } ?: rh.gs(R.string.apex_never_connected),
        lastBolus = lastBolusText(),
        battery = pumpState.batteryPercent?.let { rh.gs(R.string.apex_percent_value, it) },
        reservoir = ch.insulinAmountString(PumpInsulin(pumpState.reservoirUnits))
    ).filter { it.visible }

    private fun pumpRows(): List<PumpInfoRow> {
        val none = rh.gs(R.string.apex_value_none)
        val common = rowBuilder.buildCommonRows(
            baseBasalRate = ch.basalRateString(PumpRate(pumpState.scheduledBasalRate), isAbsolute = true),
            tempBasalText = if (pumpState.tbrActive) ch.basalRateString(PumpRate(pumpState.tbrRate), isAbsolute = true) else "",
            serialNumber = plugin.serialNumber().ifEmpty { none }
        ).filter { it.visible }
        return common + buildList {
            add(PumpInfoRow(label = rh.gs(R.string.apex_label_firmware), value = pumpState.version?.firmwareText ?: none))
            add(PumpInfoRow(label = rh.gs(R.string.apex_label_protocol), value = pumpState.version?.protocolText ?: none))
            add(
                PumpInfoRow(
                    label = rh.gs(R.string.apex_label_link),
                    value = rh.gs(
                        when {
                            connection.isConnected  -> R.string.apex_link_connected
                            connection.isConnecting -> R.string.apex_link_connecting
                            else                    -> R.string.apex_link_disconnected
                        }
                    )
                )
            )
            add(
                PumpInfoRow(
                    label = rh.gs(R.string.apex_label_state),
                    value = rh.gs(
                        when {
                            pumpState.suspended                     -> R.string.apex_state_stopped
                            pumpState.activeAlarmCodes.isNotEmpty() -> R.string.apex_state_alarm
                            pumpState.locked                        -> R.string.apex_state_locked
                            pumpState.isInitialized                 -> R.string.apex_state_running
                            else                                    -> R.string.apex_value_none
                        }
                    ),
                    level = if (pumpState.suspended || pumpState.activeAlarmCodes.isNotEmpty()) StatusLevel.WARNING else StatusLevel.UNSPECIFIED
                )
            )
            add(PumpInfoRow(label = rh.gs(R.string.apex_label_driver), value = ApexConst.DRIVER_VERSION))
        }
    }

    /** The newest bolus of the pump's journal, as the last picture saw it. */
    private fun lastBolusText(): String? {
        val picture = pumpState.lastPicture ?: return null
        val units = picture.topBolusUnits ?: return null
        val at = picture.topBolusAtMs ?: return null
        // Says "4 U 5 min ago" itself, and answers null once it is too long ago to be worth saying.
        return ch.insulinAmountAgoString(PumpInsulin(units), at)
    }

    /** How long ago, in the units the number deserves. The plurals carry their own grammar. */
    private fun agoText(at: Long): String {
        val minutes = ((dateUtil.now() - at) / 60_000L).toInt()
        return when {
            minutes < 1    -> rh.gs(R.string.apex_just_now)
            minutes < 60   -> rh.gq(R.plurals.apex_minutes_ago, minutes, minutes)
            minutes < 1440 -> rh.gq(R.plurals.apex_hours_ago, minutes / 60, minutes / 60)
            else           -> rh.gq(R.plurals.apex_days_ago, minutes / 1440, minutes / 1440)
        }
    }

    private fun managementActions(chosen: Boolean): List<ScreenAction> = listOf(
        ScreenAction(
            label = rh.gs(if (chosen) R.string.apex_change_pump else R.string.apex_select_pump),
            icon = Icons.Filled.Bluetooth,
            onClick = { viewModelScope.launch { _events.emit(ApexOverviewEvent.StartScan) } }
        )
    )

    /** Whether the button that sets the pump up for AAPS can be pressed now. */
    fun canSetUpForAaps(): Boolean =
        preferences.get(ApexStringKey.ApexSerialNumber).isNotEmpty() && connection.isConnected && plugin.isInitialized()

    /** What the pull on the screen asks for: the link raised when it is down; the pump itself is read at its heartbeat. */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                val result = commandQueue.readStatus(rh.gs(CoreUiR.string.user_request))
                aapsLogger.debug(LTag.PUMP, "Apex: screen asked for a status read, ok=${result.success}")
            } finally {
                _refreshing.value = false
                _refreshTrigger.value = dateUtil.now()
            }
        }
    }

    /** The pump's settings AAPS needs, written in the queue's turn, see `ApexPumpSetup`. */
    fun setupForAaps() {
        viewModelScope.launch {
            val result = commandQueue.customCommand(ApexSetupForAaps())
            aapsLogger.debug(LTag.PUMP, "Apex: screen asked to set the pump up for AAPS, ok=${result.success}")
            _refreshTrigger.value = dateUtil.now()
        }
    }

    fun setExactBasal(mode: ExactBasalMode) {
        preferences.put(ApexStringKey.ExactBasal, mode.value)
        _exactBasal.value = mode
    }

    fun setHeartbeatPeriod(minutes: Int) {
        preferences.put(ApexIntKey.HeartbeatPeriod, minutes)
        _heartbeatPeriod.value = minutes
    }
}
