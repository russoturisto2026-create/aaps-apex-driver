package app.aaps.pump.atc3.state

import app.aaps.core.interfaces.profile.Profile
import app.aaps.pump.atc3.protocol.Atc3Alarm
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3Settings
import app.aaps.pump.atc3.protocol.Atc3StatusV1
import app.aaps.pump.atc3.protocol.Atc3StatusV2
import app.aaps.pump.atc3.protocol.Atc3Version
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Everything the driver knows about the pump now. Reports arrive on the Bluetooth thread and are
 * read on others, so each is held as one object put in place whole: a reader that needs two values
 * of one report takes the object once.
 */
@Singleton
class Atc3PumpState @Inject constructor() {

    /**
     * One status report, whole: the status, when it arrived on the phone, and the settings carried
     * in the same frame.
     */
    data class StatusCard(
        val status: Atc3StatusV1,
        /** Phone clock when the frame arrived, milliseconds. */
        val readAtMs: Long,
        /** Null until a frame long enough to carry them has arrived. */
        val settings: Atc3Settings?,
        /** The moment of the stop to the second, from the report the stop itself rebuilt, kept while the pump stays stopped. */
        val stopSnapshotMs: Long?
    ) {

        val snapshotAtMs: Long get() = status.snapshotTime
        val deliveredTodayUnits: Double get() = status.deliveredTodayUnits
        val reservoirUnits: Double get() = status.reservoirUnits
        val scheduledBasalRate: Double get() = status.scheduledBasalRate
        val activeProfileIndex: Int get() = status.activeProfileIndex
        val suspended: Boolean get() = status.suspended
        val locked: Boolean get() = status.locked
        val activeAlarms: List<Atc3Alarm> get() = status.activeAlarms
        val activeAlarmCodes: List<Int> get() = status.activeAlarmCodes
        val tbrActive: Boolean get() = status.tbrActive
        val tbrRate: Double get() = if (status.tbrActive) status.tbrRate else 0.0
        val tbrDurationMinutes: Int get() = if (status.tbrActive) status.tbrDurationMinutes else 0
        val tbrElapsedMinutes: Int get() = if (status.tbrActive) status.tbrElapsedMinutes else 0

        /** No insulin is leaving the pump: stopped, or held by an alarm that stops delivery. */
        val notDelivering: Boolean get() = status.suspended || status.activeAlarms.any { it.stopsDelivery }
    }

    /** The last status report, null until one has arrived. */
    @Volatile var statusCard: StatusCard? = null
        private set

    /** The last Status V2 report, null until one has arrived. */
    @Volatile var statusV2: Atc3StatusV2? = null
        private set

    /** Phone time of the last answer that told the driver the pump's state, milliseconds. */
    @Volatile var lastConnection: Long = 0L

    /** The day so far by the AAPS journal and by the pump's count, for the driver's screen; null until compared once. */
    data class DayAccount(
        val aapsUnits: Double,
        val pumpUnits: Double,
        val atMs: Long,
        /** Where the two are counted from when that is not the pump's midnight: the last beginning. */
        val sinceMs: Long? = null
    )

    @Volatile var dayAccount: DayAccount? = null

    /** The last bolus the driver recorded, for AAPS's display. */
    data class LastBolus(val atMs: Long, val units: Double)

    @Volatile var lastBolus: LastBolus? = null

    @Volatile var serialNumber: String = ""

    /** Firmware and protocol versions, null until read: they say whether the link can have a password. */
    @Volatile var version: Atc3Version? = null

    /** The basal profiles read from the pump, and the profile active when they were read. */
    class Profiles(val rates: Array<DoubleArray>, val readForIndex: Int?)

    @Volatile var profiles: Profiles? = null

    // Views of the last status report

    val lastStatus: Atc3StatusV1? get() = statusCard?.status
    val statusReadAtMs: Long get() = statusCard?.readAtMs ?: 0L
    val snapshotAtMs: Long get() = statusCard?.status?.snapshotTime ?: 0L
    val deliveredTodayUnits: Double get() = statusCard?.status?.deliveredTodayUnits ?: 0.0
    val lastStop: Atc3StatusV1.LastStop? get() = statusCard?.status?.lastStop
    val stopSnapshotMs: Long? get() = statusCard?.stopSnapshotMs
    val reservoirUnits: Double get() = statusCard?.status?.reservoirUnits ?: 0.0
    val scheduledBasalRate: Double get() = statusCard?.status?.scheduledBasalRate ?: 0.0
    val activeProfileIndex: Int? get() = statusCard?.status?.activeProfileIndex
    val suspended: Boolean get() = statusCard?.status?.suspended ?: false
    val locked: Boolean get() = statusCard?.status?.locked ?: false
    val tbrActive: Boolean get() = statusCard?.tbrActive ?: false
    val tbrRate: Double get() = statusCard?.tbrRate ?: 0.0
    val tbrDurationMinutes: Int get() = statusCard?.tbrDurationMinutes ?: 0
    val tbrElapsedMinutes: Int get() = statusCard?.tbrElapsedMinutes ?: 0
    val activeAlarms: List<Atc3Alarm> get() = statusCard?.status?.activeAlarms ?: emptyList()
    val activeAlarmCodes: List<Int> get() = statusCard?.status?.activeAlarmCodes ?: emptyList()
    val notDelivering: Boolean get() = statusCard?.notDelivering ?: false
    val settings: Atc3Settings? get() = statusCard?.settings

    // Views of the last Status V2 report

    val pumpActiveInsulin: Double get() = statusV2?.activeInsulinUnits ?: 0.0
    val batteryVolts: Double get() = statusV2?.batteryVolts ?: 0.0
    val batteryPercent: Int? get() = statusV2?.batteryPercent

    /** True once a status report has arrived, so the driver knows the pump state. */
    val isInitialized: Boolean get() = lastConnection > 0L

    /** Put a status report in place of the last one, with [settings] from the same frame when it carried them. */
    fun applyStatus(status: Atc3StatusV1, readAtMs: Long, settings: Atc3Settings? = null) {
        val before = statusCard
        statusCard = StatusCard(
            status = status,
            readAtMs = readAtMs,
            settings = settings ?: before?.settings,
            stopSnapshotMs = when {
                !status.suspended          -> null
                status.snapshotIsTheStop() -> status.snapshotTime
                else                       -> before?.stopSnapshotMs
            }
        )
        lastConnection = readAtMs
    }

    fun applyStatusV2(status: Atc3StatusV2) {
        statusV2 = status
    }

    /** What the active profile schedules for [nowMs], U/h, or null when not known: a stopped pump does not report it. */
    fun scheduledRateFromProfile(nowMs: Long, card: StatusCard? = statusCard): Double? {
        val index = card?.activeProfileIndex ?: return null
        val rates = profiles?.rates?.getOrNull(index) ?: return null
        val midnight = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val slot = ((nowMs - midnight) / 1000L / Atc3Protocol.BASAL_SLOT_SECONDS).toInt()
        return rates.getOrNull(slot.coerceIn(0, Atc3Protocol.BASAL_SLOTS - 1))
    }

    /** Forget what was known of the pump: another pump, or this one paired afresh. */
    fun reset() {
        statusCard = null
        statusV2 = null
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
            DoubleArray(Atc3Protocol.BASAL_SLOTS) { slot ->
                roundToPumpStep(profile.getBasalTimeFromMidnight(slot * Atc3Protocol.BASAL_SLOT_SECONDS))
            }

        /** Whether a wanted profile matches the one read back, within [tolerance]: exact comparison would rewrite the profile for ever over rounding. */
        fun rateArraysMatch(wanted: DoubleArray, fromPump: DoubleArray, tolerance: Double): Boolean {
            if (wanted.size != fromPump.size) return false
            for (index in wanted.indices) {
                if (abs(wanted[index] - fromPump[index]) > tolerance) return false
            }
            return true
        }

        /** The slots whose rates differ by more than [tolerance]. */
        fun differingSlots(wanted: DoubleArray, fromPump: DoubleArray, tolerance: Double): List<Int> {
            if (wanted.size != fromPump.size) return wanted.indices.toList()
            return wanted.indices.filter { abs(wanted[it] - fromPump[it]) > tolerance }
        }

        /** A slot as a time of day, slot 40 is 20:00. */
        fun slotLabel(slot: Int): String {
            val minutes = slot * 30
            return "%02d:%02d".format(minutes / 60, minutes % 60)
        }
    }
}
