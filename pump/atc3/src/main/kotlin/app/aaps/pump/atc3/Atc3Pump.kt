package app.aaps.pump.atc3

import app.aaps.core.interfaces.profile.Profile
import app.aaps.pump.atc3.comm.Atc3Alarm
import app.aaps.pump.atc3.comm.Atc3Settings
import app.aaps.pump.atc3.comm.Atc3StatusV1
import app.aaps.pump.atc3.comm.Atc3StatusV2
import app.aaps.pump.atc3.comm.Atc3Version
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Everything the driver currently knows about the pump.
 *
 * Basal profiles are held exactly as they were read back from the pump: several profiles stored in
 * the pump, an active profile index reported by the pump, and comparisons made against what the
 * pump actually returned rather than against a cached copy of what was written.
 */
@Singleton
class Atc3Pump @Inject constructor() {

    /** Time of the last successful exchange with the pump, milliseconds. */
    var lastConnection: Long = 0L

    /**
     * The time of the last status snapshot, milliseconds. Not the pump's clock, and not a source of
     * time for anything: the pump rebuilds its status about once a minute and on actions that change
     * it, so this is the moment of that rebuild.
     *
     * Used for one thing only, with [statusReadAtMs] beside it: a snapshot more than
     * [Atc3Const.CLOCK_MAX_CORRECTION_MS] away from the phone at the moment it was read stops the
     * loop.
     */
    var snapshotAtMs: Long = 0L

    /** Phone clock at the moment the last status was read, milliseconds. */
    var statusReadAtMs: Long = 0L

    /**
     * Insulin delivered today as the pump counted it at [statusReadAtMs], units, basal and bolus
     * together. The figure the state check compares AAPS's own journal against.
     */
    var deliveredTodayUnits: Double = 0.0

    /** Hour and minute of the last explicit stop, or null when the pump reports none. */
    var lastStop: Atc3StatusV1.LastStop? = null

    /**
     * The time of the snapshot the stop itself rebuilt, to the second, from any status read while
     * the pump has been stopped since; null once it runs again, or while no read caught it.
     *
     * The pump keeps that snapshot only until its next rebuild a minute later. A read that caught
     * it -- the confirmation of a stop command, a status on the way past -- knew the moment of the
     * stop to the second, and the tick that records the stop can come minutes later, when the
     * snapshot names the minute alone. So the moment is kept here for it.
     */
    var stopSnapshotMs: Long? = null

    /** The last status as decoded, for the few questions only the whole frame can answer. */
    var lastStatus: Atc3StatusV1? = null

    /** Whole minutes the running temporary basal has been going, 0 while none runs. */
    var tbrElapsedMinutes: Int = 0

    /** Remaining insulin, units. */
    var reservoirUnits: Double = 0.0

    /** Basal rate scheduled by the active profile for the current slot, U/h. */
    var scheduledBasalRate: Double = 0.0

    /** Index of the profile currently selected in the pump, or null if not read yet. */
    var activeProfileIndex: Int? = null

    /** True while the pump is stopped and delivering nothing, basal included. */
    var suspended: Boolean = false

    /**
     * True while the pump is locked, so every control command it is sent will be refused.
     *
     * Reads go on working, which is why this is worth carrying: the driver keeps its picture of the
     * pump up to date and only has to hold back the commands. Unlocking is done on the pump itself
     * and there is no command for it.
     */
    var locked: Boolean = false

    /**
     * Insulin on board as the pump itself reckons it, units, 0 until Status V2 has been read.
     *
     * Not given to AAPS, which keeps its own and far better informed count. It is kept because it
     * is the pump's own answer to the same question, which makes it the first thing worth comparing
     * against when the two disagree.
     */
    var pumpActiveInsulin: Double = 0.0

    /** Battery voltage, volts, 0 until Status V2 has been read. */
    var batteryVolts: Double = 0.0

    /** Battery charge worked out from the voltage, percent, null until Status V2 has been read. */
    var batteryPercent: Int? = null

    var tbrActive: Boolean = false
    var tbrRate: Double = 0.0
    /**
     * The temporary basal duration field of Status V1, minutes: the length the running temporary
     * basal was started for. It does not count down.
     */
    var tbrDurationMinutes: Int = 0

    /**
     * Alarms the pump was raising at the last status read, in slot order.
     *
     * Empty is the ordinary state. This is what the user is told about, and for most of them that
     * is where it ends: they are raised by the pressure a bolus builds and say nothing about basal,
     * so reading them as a stop would make the loop stop crediting insulin the pump is still
     * delivering. The ones that are a stop say so in [Atc3Alarm.stopsDelivery]; see [notDelivering].
     */
    var activeAlarms: List<Atc3Alarm> = emptyList()

    /**
     * Codes of those alarms, including any this driver has no name for.
     *
     * Kept beside [activeAlarms] so an unknown code still reaches the user as a number rather than
     * disappearing.
     */
    var activeAlarmCodes: List<Int> = emptyList()

    /**
     * True when no insulin is leaving the pump, whether it admits to being stopped or not.
     *
     * [suspended] is the pump's own word for it and is kept as it comes. It is not the whole truth:
     * under a reservoir-empty alarm the pump goes on reporting itself as running, with a temporary
     * basal counting down, and delivers nothing: its delivery counter stands still for as long as
     * the alarm is up and moves again once it clears.
     *
     * This is what AAPS is told, because crediting insulin that is not being delivered is the worst
     * way to be wrong about this pump.
     *
     * The alarm is what this reads, and the reservoir level is not an alternative to it. The pump
     * stops delivering while still reporting units left, at a level that is not its own low insulin
     * setting, so a test for an empty reservoir would never fire and the level cannot be hard-coded.
     */
    val notDelivering: Boolean get() = suspended || activeAlarms.any { it.stopsDelivery }

    var lastBolusTime: Long? = null
    var lastBolusAmount: Double? = null

    var serialNumber: String = ""

    /**
     * Firmware and protocol versions from the handshake, null until it has been read.
     *
     * The firmware version is what says whether this pump has a Bluetooth password at all, which is
     * the difference between a link the user can protect and one nobody can.
     */
    var version: Atc3Version? = null

    /**
     * True when the firmware is older than [Atc3Const.MINIMUM_FIRMWARE], which the driver refuses
     * to run a pump on. False until the version has been read.
     */
    val firmwareTooOld: Boolean get() = version?.isAtLeast(Atc3Const.MINIMUM_FIRMWARE) == false

    /**
     * The pump's own settings, null until Status V1 has been read.
     *
     * Null is what keeps the settings screens inert before the pump has been heard from: a
     * settings write replaces the whole block, so changing one field without knowing the other
     * fifteen would overwrite them with guesses.
     */
    var settings: Atc3Settings? = null

    /**
     * Basal profiles as read back from the pump: [Atc3Const.PROFILE_COUNT] profiles of
     * [Atc3Const.BASAL_SLOTS] half hour rates in U/h. Null until a read back has been received.
     */
    var pumpProfiles: Array<DoubleArray>? = null

    /**
     * Which profile the pump had selected when [pumpProfiles] was read, null when never read.
     *
     * The stored profiles only change when AAPS writes them or somebody edits them on the pump, so
     * they are not worth an exchange in every connection. What has to be noticed is the pump
     * switching to a different profile, and Status V1 already says that in every status at no
     * cost — so the expensive read is made only when the two disagree.
     */
    var profilesReadForIndex: Int? = null

    /** True once a status frame has been decoded, so the driver knows the pump state. */
    val isInitialized: Boolean get() = lastConnection > 0L

    fun applyStatus(status: Atc3StatusV1, now: Long) {
        lastStatus = status
        snapshotAtMs = status.snapshotTime
        statusReadAtMs = now
        reservoirUnits = status.reservoirUnits
        deliveredTodayUnits = status.deliveredTodayUnits
        lastStop = status.lastStop
        stopSnapshotMs = when {
            !status.suspended         -> null
            status.snapshotIsTheStop() -> status.snapshotTime
            else                      -> stopSnapshotMs
        }
        tbrElapsedMinutes = if (status.tbrActive) status.tbrElapsedMinutes else 0
        scheduledBasalRate = status.scheduledBasalRate
        activeProfileIndex = status.activeProfileIndex
        suspended = status.suspended
        locked = status.locked
        tbrActive = status.tbrActive
        tbrRate = if (status.tbrActive) status.tbrRate else 0.0
        tbrDurationMinutes = if (status.tbrActive) status.tbrDurationMinutes else 0
        activeAlarmCodes = status.activeAlarmCodes
        activeAlarms = status.activeAlarms
        lastConnection = now
    }

    /**
     * What the active profile schedules for this moment, U/h, or null while it is not known.
     *
     * Status V1 reports the scheduled rate directly, but not while the pump is stopped: it puts its
     * "stopped" marker in that field instead. The schedule itself has not changed, though, and the
     * driver holds it, so this is where the rate comes from then.
     */
    fun scheduledRateFromProfile(nowMs: Long): Double? {
        val rates = pumpProfiles?.getOrNull(activeProfileIndex ?: return null) ?: return null
        val midnight = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val slot = ((nowMs - midnight) / 1000L / Atc3Const.BASAL_SLOT_SECONDS).toInt()
        return rates.getOrNull(slot.coerceIn(0, Atc3Const.BASAL_SLOTS - 1))
    }

    fun applyStatusV2(status: Atc3StatusV2) {
        pumpActiveInsulin = status.activeInsulinUnits
        batteryVolts = status.batteryVolts
        batteryPercent = status.batteryPercent
    }

    fun reset() {
        lastConnection = 0L
        activeAlarms = emptyList()
        activeAlarmCodes = emptyList()
        suspended = false
        locked = false
        pumpActiveInsulin = 0.0
        batteryVolts = 0.0
        batteryPercent = null
        snapshotAtMs = 0L
        statusReadAtMs = 0L
        deliveredTodayUnits = 0.0
        lastStop = null
        stopSnapshotMs = null
        lastStatus = null
        tbrElapsedMinutes = 0
        reservoirUnits = 0.0
        scheduledBasalRate = 0.0
        activeProfileIndex = null
        tbrActive = false
        tbrRate = 0.0
        tbrDurationMinutes = 0
        pumpProfiles = null
        profilesReadForIndex = null
        settings = null
    }

    companion object {

        /**
         * Convert an AAPS profile into the pump's fixed 48 half hour slots.
         *
         * AAPS profiles use arbitrary segment boundaries, so each slot is sampled at its start
         * rather than mapping change points across, which needs no contiguity checks.
         *
         * @param roundToPumpStep applied to every sampled rate, normally
         *        `PumpType.ATC3::determineCorrectBasalSize`
         */
        fun buildBasalSlots(profile: Profile, roundToPumpStep: (Double) -> Double): DoubleArray =
            DoubleArray(Atc3Const.BASAL_SLOTS) { slot ->
                roundToPumpStep(profile.getBasalTimeFromMidnight(slot * Atc3Const.BASAL_SLOT_SECONDS))
            }

        /** Encode 48 half hour rates as the pump's raw units. */
        fun ratesToRaw(rates: DoubleArray): IntArray =
            IntArray(rates.size) { (rates[it] / Atc3Const.DOSE_SCALE).roundToInt() }

        /**
         * Compare a wanted profile against what the pump reported.
         *
         * A tolerance of one basal step is used. An exact comparison
         * would report a difference for every rounding artefact and make AAPS rewrite the profile
         * in a loop, because a permanently false result re-triggers the profile write.
         */
        fun rateArraysMatch(wanted: DoubleArray, fromPump: DoubleArray, tolerance: Double): Boolean {
            if (wanted.size != fromPump.size) return false
            for (index in wanted.indices) {
                if (abs(wanted[index] - fromPump[index]) > tolerance) return false
            }
            return true
        }

        /** Indices whose rates differ by more than [tolerance], for diagnostics. */
        fun differingSlots(wanted: DoubleArray, fromPump: DoubleArray, tolerance: Double): List<Int> {
            if (wanted.size != fromPump.size) return wanted.indices.toList()
            return wanted.indices.filter { abs(wanted[it] - fromPump[it]) > tolerance }
        }

        /** Human readable slot label, for example slot 40 is 20:00. */
        fun slotLabel(slot: Int): String {
            val minutes = slot * 30
            return "%02d:%02d".format(minutes / 60, minutes % 60)
        }
    }
}
