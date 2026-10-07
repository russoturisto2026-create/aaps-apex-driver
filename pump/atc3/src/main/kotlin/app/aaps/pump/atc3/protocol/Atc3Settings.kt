package app.aaps.pump.atc3.protocol

import app.aaps.pump.atc3.keys.Atc3IntNonKey
import java.io.Serializable
import kotlin.math.roundToInt

/**
 * The pump's own settings. The pump takes them only as a whole block, so a change is made to the
 * block read back from Status V1 and written whole; Status V1 reports every field but
 * [alarmDuration].
 */
data class Atc3Settings(
    val lowBolusSpeed: Boolean,
    val keypadLock: Boolean,
    val autoOff: Boolean,
    val basalPatterns: Boolean,
    val dailyLimitEnabled: Boolean,
    /** English, else Russian. */
    val english: Boolean,
    /** One of [ALARM_TYPE_SOUND] and its neighbours. */
    val alarmSignalType: Int,
    /** An index into [BRIGHTNESS_PERCENTS]. */
    val brightnessLevel: Int,
    val autoOffHours: Int,
    /** Low insulin warning, U. */
    val lowInsulinUnits: Int,
    /** Low insulin warning, half hours. */
    val lowInsulinHalfHours: Int,
    val extendedBolusAllowed: Boolean,
    val bgReminder: Boolean,
    /** Not reported by the pump: the driver remembers what it last wrote, see [decode]. */
    val alarmDuration: Int,
    /** Tenths of a second. */
    val screenTimeoutRaw: Int,
    /** U. */
    val dailyLimitUnits: Int,
    /** Raw units of 0.025 U/h. */
    val maxBasalRaw: Int,
    /** Raw units of 0.025 U. */
    val maxBolusRaw: Int
) : Serializable {

    val maxBasal: Double get() = maxBasalRaw * Atc3Protocol.DOSE_SCALE
    val maxBolus: Double get() = maxBolusRaw * Atc3Protocol.DOSE_SCALE

    val screenTimeoutSeconds: Int get() = (screenTimeoutRaw * SCREEN_TIMEOUT_SCALE).roundToInt()

    val lowInsulinHours: Double get() = lowInsulinHalfHours * LOW_INSULIN_HOUR_SCALE

    /** Or null for a level outside the known list. */
    val brightnessPercent: Int? get() = BRIGHTNESS_PERCENTS.getOrNull(brightnessLevel)

    fun withMaxBasal(units: Double) = copy(maxBasalRaw = toRaw(units))
    fun withMaxBolus(units: Double) = copy(maxBolusRaw = toRaw(units))
    fun withScreenTimeoutSeconds(seconds: Int) =
        copy(screenTimeoutRaw = (seconds / SCREEN_TIMEOUT_SCALE).roundToInt())

    fun withLowInsulinHours(hours: Double) =
        copy(lowInsulinHalfHours = (hours / LOW_INSULIN_HOUR_SCALE).roundToInt())

    /** True when every field the pump reports back matches [other]; [alarmDuration] is never reported, so it is left out. */
    fun mirroredFieldsMatch(other: Atc3Settings): Boolean = copy(alarmDuration = other.alarmDuration) == other

    /** How many separate settings differ from [other], for the screen that collects edits before the block is written. */
    fun differenceCount(other: Atc3Settings): Int =
        fields().zip(other.fields()).count { (mine, theirs) -> mine != theirs }

    private fun fields(): List<Any> = listOf(
        lowBolusSpeed, keypadLock, autoOff, basalPatterns, dailyLimitEnabled, english,
        alarmSignalType, brightnessLevel, autoOffHours, lowInsulinUnits, lowInsulinHalfHours,
        extendedBolusAllowed, bgReminder, alarmDuration, screenTimeoutRaw, dailyLimitUnits,
        maxBasalRaw, maxBolusRaw
    )

    fun toPayload(): ByteArray {
        val payload = ByteArray(Payload.PAYLOAD_LENGTH)
        var switches = 0
        if (lowBolusSpeed) switches = switches or BIT_LOW_BOLUS_SPEED
        if (keypadLock) switches = switches or BIT_KEYPAD_LOCK
        if (autoOff) switches = switches or BIT_AUTO_OFF
        if (basalPatterns) switches = switches or BIT_BASAL_PATTERNS
        if (dailyLimitEnabled) switches = switches or BIT_DAILY_LIMIT
        if (english) switches = switches or BIT_ENGLISH

        var bolusTypes = 0
        if (extendedBolusAllowed) bolusTypes = bolusTypes or BOLUS_TYPE_EXTENDED
        if (bgReminder) bolusTypes = bolusTypes or BOLUS_TYPE_BG_REMINDER

        payload[Payload.SWITCHES] = switches.toByte()
        payload[Payload.ALARM_SIGNAL_TYPE] = alarmSignalType.toByte()
        payload[Payload.BRIGHTNESS] = brightnessLevel.toByte()
        payload[Payload.AUTO_OFF_HOURS] = autoOffHours.toByte()
        payload[Payload.LOW_INSULIN_UNITS] = lowInsulinUnits.toByte()
        payload[Payload.LOW_INSULIN_HALF_HOURS] = lowInsulinHalfHours.toByte()
        payload[Payload.BOLUS_TYPES] = bolusTypes.toByte()
        payload[Payload.ALARM_DURATION] = alarmDuration.toByte()
        putU16(payload, Payload.SCREEN_TIMEOUT, screenTimeoutRaw)
        putU16(payload, Payload.DAILY_LIMIT_UNITS, dailyLimitUnits)
        putU16(payload, Payload.MAX_BASAL, maxBasalRaw)
        putU16(payload, Payload.MAX_BOLUS, maxBolusRaw)
        return payload
    }

    /** Where each field sits in the block the settings write carries. */
    internal object Payload {

        const val PAYLOAD_LENGTH = 16
        const val SWITCHES = 0
        const val ALARM_SIGNAL_TYPE = 1
        const val BRIGHTNESS = 2
        const val AUTO_OFF_HOURS = 3
        const val LOW_INSULIN_UNITS = 4
        const val LOW_INSULIN_HALF_HOURS = 5
        const val BOLUS_TYPES = 6
        const val ALARM_DURATION = 7
        const val SCREEN_TIMEOUT = 8
        const val DAILY_LIMIT_UNITS = 10
        const val MAX_BASAL = 12
        const val MAX_BOLUS = 14
    }

        /** Where the settings block sits inside Status V1. */
    internal object StatusOffset {

        const val ALARM_SIGNAL_TYPE = 3
        const val LOW_BOLUS_SPEED = 4
        const val BRIGHTNESS = 5
        const val BOLUS_TYPES = 6
        const val KEYPAD_LOCK = 7
        const val AUTO_OFF = 8
        const val AUTO_OFF_HOURS = 9
        const val LOW_INSULIN_UNITS = 10
        const val LOW_INSULIN_HALF_HOURS = 11
        const val BASAL_PATTERNS = 12
        const val DAILY_LIMIT_ENABLED = 15
        const val SCREEN_TIMEOUT = 16
        const val DAILY_LIMIT_UNITS = 22
        const val MAX_BASAL = 26
        const val MAX_BOLUS = 28
        const val LANGUAGE = 52
    }

    companion object {

        const val BIT_LOW_BOLUS_SPEED = 0x01
        const val BIT_KEYPAD_LOCK = 0x02
        const val BIT_AUTO_OFF = 0x04
        const val BIT_BASAL_PATTERNS = 0x08
        const val BIT_DAILY_LIMIT = 0x20
        const val BIT_ENGLISH = 0x40
        const val BOLUS_TYPE_EXTENDED = 0x01
        const val BOLUS_TYPE_BG_REMINDER = 0x02

        /** How far Status V1 shifts the bolus type bits up against the block written. */
        const val BOLUS_TYPES_STATUS_SHIFT = 1

        /** Percent, in the order the level indexes them. */
        val BRIGHTNESS_PERCENTS = intArrayOf(10, 30, 50, 60, 80, 100)

        /** Seconds per raw unit. */
        const val SCREEN_TIMEOUT_SCALE = 0.1

        /** Hours per raw unit. */
        const val LOW_INSULIN_HOUR_SCALE = 0.5

        /** Alarm signal type values. */
        const val ALARM_TYPE_SOUND = 0
        const val ALARM_TYPE_VIBRATION = 1
        const val ALARM_TYPE_BOTH = 2

        /** Alarm signal duration values. */
        const val ALARM_DURATION_LONG = 0
        const val ALARM_DURATION_NORMAL = 1
        const val ALARM_DURATION_SHORT = 2

        private fun toRaw(units: Double) = (units / Atc3Protocol.DOSE_SCALE).roundToInt()

        private fun putU16(payload: ByteArray, offset: Int, value: Int) {
            payload[offset] = (value and 0xFF).toByte()
            payload[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        /**
         * The settings out of a Status V1 frame, or null when it is too short for them.
         *
         * @param alarmDuration the one field Status V1 does not carry, as last written, see [app.aaps.pump.atc3.keys.Atc3IntNonKey.AlarmDuration]
         */
        fun decode(frame: Atc3ResponseFrame, alarmDuration: Int): Atc3Settings? {
            if (!frame.has(StatusOffset.LANGUAGE)) return null
            val switchAt = { offset: Int -> frame.byteAt(offset) != 0 }
            val bolusTypes = frame.byteAt(StatusOffset.BOLUS_TYPES) shr BOLUS_TYPES_STATUS_SHIFT
            return Atc3Settings(
                lowBolusSpeed = switchAt(StatusOffset.LOW_BOLUS_SPEED),
                keypadLock = switchAt(StatusOffset.KEYPAD_LOCK),
                autoOff = switchAt(StatusOffset.AUTO_OFF),
                basalPatterns = switchAt(StatusOffset.BASAL_PATTERNS),
                dailyLimitEnabled = switchAt(StatusOffset.DAILY_LIMIT_ENABLED),
                english = switchAt(StatusOffset.LANGUAGE),
                alarmSignalType = frame.byteAt(StatusOffset.ALARM_SIGNAL_TYPE),
                brightnessLevel = frame.byteAt(StatusOffset.BRIGHTNESS),
                autoOffHours = frame.byteAt(StatusOffset.AUTO_OFF_HOURS),
                lowInsulinUnits = frame.byteAt(StatusOffset.LOW_INSULIN_UNITS),
                lowInsulinHalfHours = frame.byteAt(StatusOffset.LOW_INSULIN_HALF_HOURS),
                extendedBolusAllowed = bolusTypes and BOLUS_TYPE_EXTENDED != 0,
                bgReminder = bolusTypes and BOLUS_TYPE_BG_REMINDER != 0,
                alarmDuration = alarmDuration,
                screenTimeoutRaw = frame.u16le(StatusOffset.SCREEN_TIMEOUT),
                dailyLimitUnits = frame.u16le(StatusOffset.DAILY_LIMIT_UNITS),
                maxBasalRaw = frame.u16le(StatusOffset.MAX_BASAL),
                maxBolusRaw = frame.u16le(StatusOffset.MAX_BOLUS)
            )
        }
    }
}
