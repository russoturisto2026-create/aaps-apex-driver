package app.aaps.pump.apex.state

import app.aaps.core.interfaces.profile.Profile
import app.aaps.pump.apex.ApexConst
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexSettings
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.protocol.ApexStatusV2
import app.aaps.pump.apex.pulse.PictureSummary
import app.aaps.pump.apex.protocol.ApexVersion
import app.aaps.pump.apex.pulse.DayWindow
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last answers of the pump, as they came. Reports arrive on the Bluetooth thread and are read
 * on others, so each is held as one object put in place whole: a reader that needs two values of
 * one report takes the object once.
 */
@SingleIn(AppScope::class)
@Inject
class ApexPumpState() {

    /** One status report, whole: the status, when it arrived on the phone, and the settings carried in the same frame. */
    data class StatusCard(
        val status: ApexStatusV1,
        /** The frame as the pump sent it, for the store. */
        val raw: ByteArray,
        /** Phone clock when the frame arrived, milliseconds. */
        val readAtMs: Long,
        /** Null until a frame long enough to carry them has arrived. */
        val settings: ApexSettings?
    ) {

        val deliveredTodayUnits: Double get() = status.deliveredTodayUnits
        val reservoirUnits: Double get() = status.reservoirUnits
        val suspended: Boolean get() = status.suspended
        val activeAlarmCodes: List<Int> get() = status.activeAlarmCodes
        val tbrActive: Boolean get() = status.tbrActive
        val tbrRate: Double get() = if (status.tbrActive) status.tbrRate else 0.0
        val tbrDurationMinutes: Int get() = if (status.tbrActive) status.tbrDurationMinutes else 0
        val tbrElapsedMinutes: Int get() = if (status.tbrActive) status.tbrElapsedMinutes else 0
    }

    // Held in StateFlows: AAPS 4.0 and the pump screen watch these rather than poll them. A
    // StateFlow's value is safe to read and set from any thread, as @Volatile was.
    private val _statusCard = MutableStateFlow<StatusCard?>(null)
    private val _statusV2 = MutableStateFlow<ApexStatusV2?>(null)
    private val _batteryPercent = MutableStateFlow<Int?>(null)
    private val _lastConnection = MutableStateFlow(0L)
    private val _lastPicture = MutableStateFlow<PictureSummary?>(null)
    private val _reconcilePoint = MutableStateFlow<ReconcilePoint?>(null)
    private val _version = MutableStateFlow<ApexVersion?>(null)

    /** The last status report, null until one has arrived. */
    val statusCard: StatusCard? get() = _statusCard.value
    val statusCardFlow: StateFlow<StatusCard?> = _statusCard.asStateFlow()

    /** The last Status V2 report, null until one has arrived. */
    val statusV2: ApexStatusV2? get() = _statusV2.value

    /** Phone clock when the pump's last heartbeat came, milliseconds; 0 until one came. */
    @Volatile var lastHeartbeatAtMs: Long = 0L

    /** What the screen shows of the last picture read at a heartbeat; null until one was read. */
    var lastPicture: PictureSummary?
        get() = _lastPicture.value
        set(value) {
            _lastPicture.value = value
            when {
                value == null        -> _reconcilePoint.value = null
                value.reconciled     -> _reconcilePoint.value = ReconcilePoint(
                    atMs = ApexClock.minuteStart(value.heartbeatAtMs),
                    fromMs = value.dayFrom?.atMs,
                    counterUnits = value.counterUnits - (value.dayFrom?.counterUnits ?: 0.0),
                    dayBolusUnits = value.dayBolusUnits?.let { it - (value.dayFrom?.bolusUnits ?: 0.0) },
                    dayBasalUnits = value.dayBasalUnits?.let { basal -> value.dayTbrUnits?.let { basal + it - (value.dayFrom?.basalUnits ?: 0.0) } }
                )
            }
        }

    /**
     * The last moment today is compared at, the whole minute of a heartbeat: the heartbeat of minute M
     * brings the pump up to M-1:59.999. A heartbeat whose reconciliation ended with nothing put off,
     * the one moment every gap is laid side by side. A picture read while a bolus runs, ours or another's,
     * or with the journal put off, has insulin in the pump's count that AAPS has no row for yet, so it is not one. Kept here and not by the screen, so the screen has
     * one to show as soon as it opens.
     */
    val reconcilePointFlow: StateFlow<ReconcilePoint?> = _reconcilePoint.asStateFlow()

    /** Phone time of the last answer that told the driver the pump's state, milliseconds. */
    var lastConnection: Long
        get() = _lastConnection.value
        set(value) { _lastConnection.value = value }
    val lastConnectionFlow: StateFlow<Long> = _lastConnection.asStateFlow()

    @Volatile var serialNumber: String = ""

    /** Firmware and protocol versions, null until read: shown on the screen, they name the pump for what was probed and tell whether the pump can have a password. */
    var version: ApexVersion?
        get() = _version.value
        set(value) { _version.value = value }
    val versionFlow: StateFlow<ApexVersion?> = _version.asStateFlow()

    private val _diagnosis = MutableStateFlow<FirmwareDiagnosis?>(null)

    /** What the probe at the last link setup found in the firmware, see [FirmwareDiagnosis]; null before the first probe. */
    var diagnosis: FirmwareDiagnosis?
        get() = _diagnosis.value
        set(value) { _diagnosis.value = value }
    val diagnosisFlow: StateFlow<FirmwareDiagnosis?> = _diagnosis.asStateFlow()

    /** Whether the pump can have a Bluetooth password to change: its protocol is known and is the password protocol or newer. */
    val canChangePassword: Boolean get() = version?.protocolAtLeast(ApexConst.PASSWORD_PROTOCOL_MAJOR, ApexConst.PASSWORD_PROTOCOL_MINOR) == true

    /** The basal profiles read from the pump. */
    class Profiles(val rates: Array<DoubleArray>)

    @Volatile var profiles: Profiles? = null

    // Views of the last status report

    val reservoirUnits: Double get() = statusCard?.status?.reservoirUnits ?: 0.0
    val scheduledBasalRate: Double get() = statusCard?.status?.scheduledBasalRate ?: 0.0
    val activeProfileIndex: Int? get() = statusCard?.status?.activeProfileIndex
    val suspended: Boolean get() = statusCard?.status?.suspended ?: false
    val locked: Boolean get() = statusCard?.status?.locked ?: false

    /** The pump takes no command: it is stopped, has an alarm, or is locked. */
    val takesNoCommands: Boolean get() = suspended || activeAlarmCodes.isNotEmpty() || locked
    val tbrActive: Boolean get() = statusCard?.tbrActive ?: false
    val tbrRate: Double get() = statusCard?.tbrRate ?: 0.0
    val activeAlarmCodes: List<Int> get() = statusCard?.status?.activeAlarmCodes ?: emptyList()
    val settings: ApexSettings? get() = statusCard?.settings

    // Views of the last Status V2 report

    /** By voltage from Status V2 when the pump has given it; else by the bars of the last Status V1; null before any status. */
    val batteryPercent: Int? get() = _batteryPercent.value
    val batteryPercentFlow: StateFlow<Int?> = _batteryPercent.asStateFlow()

    private fun updateBattery() {
        _batteryPercent.value = statusV2?.batteryPercent ?: statusCard?.status?.batteryPercent
    }

    /** True once a status report has arrived, so the driver knows the pump state. */
    val isInitialized: Boolean get() = lastConnection > 0L

    /** Put a status report in place of the last one, with [settings] from the same frame when it carried them. */
    fun applyStatus(status: ApexStatusV1, raw: ByteArray, readAtMs: Long, settings: ApexSettings? = null) {
        _statusCard.value = StatusCard(status = status, raw = raw, readAtMs = readAtMs, settings = settings ?: statusCard?.settings)
        updateBattery()
        lastConnection = readAtMs
    }

    fun applyStatusV2(status: ApexStatusV2) {
        _statusV2.value = status
        updateBattery()
    }

    /** Where the comparison of the day starts; kept for the life of AAPS, see [DayWindow]. */
    val dayWindow = DayWindow()

    /**
     * The pump's count from [fromMs] to [atMs], U, and its parts, less what the pump had counted at
     * [fromMs], see [DayWindow]. A null [fromMs] is the phone's midnight.
     */
    class ReconcilePoint(val atMs: Long, val fromMs: Long?, val counterUnits: Double, val dayBolusUnits: Double?, val dayBasalUnits: Double?)

    /** Forget what was known of the pump: another pump, or this one paired afresh. */
    fun reset() {
        serialNumber = ""
        lastHeartbeatAtMs = 0L
        lastPicture = null
        dayWindow.reset()
        _statusCard.value = null
        _statusV2.value = null
        updateBattery()
        profiles = null
        lastConnection = 0L
    }

    companion object {

        /**
         * An AAPS profile as the pump's 48 half hour rates, each sampled at the start of its slot.
         *
         * @param roundToPumpStep applied to every rate
         */
        fun buildBasalSlots(profile: Profile, roundToPumpStep: (Double) -> Double): DoubleArray =
            DoubleArray(ApexProtocol.BASAL_SLOTS) { slot ->
                roundToPumpStep(profile.getBasalTimeFromMidnight(slot * ApexProtocol.BASAL_SLOT_SECONDS))
            }

        /** The slots whose rates are not the same on the pump's scale. */
        fun differingSlots(wanted: DoubleArray, fromPump: DoubleArray): List<Int> {
            if (wanted.size != fromPump.size) return wanted.indices.toList()
            return wanted.indices.filter { !ApexProtocol.sameDose(wanted[it], fromPump[it]) }
        }

        /** A slot as a time of day, slot 40 is 20:00. */
        fun slotLabel(slot: Int): String {
            val minutes = slot * 30
            return "%02d:%02d".format(minutes / 60, minutes % 60)
        }
    }
}
