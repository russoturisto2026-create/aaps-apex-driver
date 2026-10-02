package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const
import java.io.Serializable
import kotlin.math.roundToInt

/**
 * The pump's own settings, as `35/A1/32` writes them and as Status V1 reports them back.
 *
 * The pump has no way to change one setting on its own: `35/A1/32` replaces all sixteen bytes at
 * once. So a settings change always starts from what the pump currently holds, which is why this
 * is read out of Status V1 first and then written back with a single field replaced. Offering a
 * control before the pump has been read would mean writing fifteen guesses alongside the one
 * value the user asked for.
 *
 * Status V1 mirrors every field except [alarmDuration]; see [decode] for what that costs.
 */
data class Atc3Settings(
    /** Set for the low bolus speed, clear for the normal one. */
    val lowBolusSpeed: Boolean,
    val keypadLock: Boolean,
    val autoOff: Boolean,
    val basalPatterns: Boolean,
    val dailyLimitEnabled: Boolean,
    /** Set for English, clear for Russian. */
    val english: Boolean,
    /** Alarm signal type, one of [Atc3Const.Settings.ALARM_TYPE_SOUND] and its neighbours. */
    val alarmSignalType: Int,
    /** Screen brightness as an index into [Atc3Const.Settings.BRIGHTNESS_PERCENTS]. */
    val brightnessLevel: Int,
    /** Auto off delay in whole hours. */
    val autoOffHours: Int,
    /** Low insulin warning threshold in whole units. */
    val lowInsulinUnits: Int,
    /** Low insulin warning threshold in half hours. */
    val lowInsulinHalfHours: Int,
    val extendedBolusAllowed: Boolean,
    val bgReminder: Boolean,
    /** Alarm signal duration. Not reported by the pump, see [decode]. */
    val alarmDuration: Int,
    /** Screen timeout in tenths of a second. */
    val screenTimeoutRaw: Int,
    /** Daily dose limit in whole units. */
    val dailyLimitUnits: Int,
    /** Maximum basal rate in raw units of 0.025 U/h. */
    val maxBasalRaw: Int,
    /** Maximum bolus in raw units of 0.025 U. */
    val maxBolusRaw: Int
) : Serializable {

    val maxBasal: Double get() = maxBasalRaw * Atc3Const.DOSE_SCALE
    val maxBolus: Double get() = maxBolusRaw * Atc3Const.DOSE_SCALE

    /** Screen timeout in whole seconds. */
    val screenTimeoutSeconds: Int get() = (screenTimeoutRaw * Atc3Const.Settings.SCREEN_TIMEOUT_SCALE).roundToInt()

    /** Low insulin time threshold in hours. */
    val lowInsulinHours: Double get() = lowInsulinHalfHours * Atc3Const.Settings.LOW_INSULIN_HOUR_SCALE

    /** Screen brightness in percent, or null for a level outside the known list. */
    val brightnessPercent: Int? get() = Atc3Const.Settings.BRIGHTNESS_PERCENTS.getOrNull(brightnessLevel)

    fun withMaxBasal(units: Double) = copy(maxBasalRaw = toRaw(units))
    fun withMaxBolus(units: Double) = copy(maxBolusRaw = toRaw(units))
    fun withScreenTimeoutSeconds(seconds: Int) =
        copy(screenTimeoutRaw = (seconds / Atc3Const.Settings.SCREEN_TIMEOUT_SCALE).roundToInt())

    fun withLowInsulinHours(hours: Double) =
        copy(lowInsulinHalfHours = (hours / Atc3Const.Settings.LOW_INSULIN_HOUR_SCALE).roundToInt())

    /**
     * True when every field the pump reports back matches [other].
     *
     * [alarmDuration] is left out because Status V1 does not carry it, so a read back can never
     * either confirm or contradict it. Comparing it would make every settings write look failed.
     */
    fun mirroredFieldsMatch(other: Atc3Settings): Boolean = copy(alarmDuration = other.alarmDuration) == other

    /**
     * How many individual settings differ from [other].
     *
     * The pump takes the block whole, so a screen that collects edits has to be able to say how
     * many of them are waiting; the payload cannot be counted byte by byte because its first byte
     * carries six separate switches.
     */
    fun differenceCount(other: Atc3Settings): Int =
        fields().zip(other.fields()).count { (mine, theirs) -> mine != theirs }

    private fun fields(): List<Any> = listOf(
        lowBolusSpeed, keypadLock, autoOff, basalPatterns, dailyLimitEnabled, english,
        alarmSignalType, brightnessLevel, autoOffHours, lowInsulinUnits, lowInsulinHalfHours,
        extendedBolusAllowed, bgReminder, alarmDuration, screenTimeoutRaw, dailyLimitUnits,
        maxBasalRaw, maxBolusRaw
    )

    /** The 16 byte payload of `35/A1/32`. */
    fun toPayload(): ByteArray {
        val payload = ByteArray(Atc3Const.Settings.PAYLOAD_LENGTH)
        var switches = 0
        if (lowBolusSpeed) switches = switches or Atc3Const.Settings.BIT_LOW_BOLUS_SPEED
        if (keypadLock) switches = switches or Atc3Const.Settings.BIT_KEYPAD_LOCK
        if (autoOff) switches = switches or Atc3Const.Settings.BIT_AUTO_OFF
        if (basalPatterns) switches = switches or Atc3Const.Settings.BIT_BASAL_PATTERNS
        if (dailyLimitEnabled) switches = switches or Atc3Const.Settings.BIT_DAILY_LIMIT
        if (english) switches = switches or Atc3Const.Settings.BIT_ENGLISH

        var bolusTypes = 0
        if (extendedBolusAllowed) bolusTypes = bolusTypes or Atc3Const.Settings.BOLUS_TYPE_EXTENDED
        if (bgReminder) bolusTypes = bolusTypes or Atc3Const.Settings.BOLUS_TYPE_BG_REMINDER

        payload[Atc3Const.Settings.SWITCHES] = switches.toByte()
        payload[Atc3Const.Settings.ALARM_SIGNAL_TYPE] = alarmSignalType.toByte()
        payload[Atc3Const.Settings.BRIGHTNESS] = brightnessLevel.toByte()
        payload[Atc3Const.Settings.AUTO_OFF_HOURS] = autoOffHours.toByte()
        payload[Atc3Const.Settings.LOW_INSULIN_UNITS] = lowInsulinUnits.toByte()
        payload[Atc3Const.Settings.LOW_INSULIN_HALF_HOURS] = lowInsulinHalfHours.toByte()
        payload[Atc3Const.Settings.BOLUS_TYPES] = bolusTypes.toByte()
        payload[Atc3Const.Settings.ALARM_DURATION] = alarmDuration.toByte()
        putU16(payload, Atc3Const.Settings.SCREEN_TIMEOUT, screenTimeoutRaw)
        putU16(payload, Atc3Const.Settings.DAILY_LIMIT_UNITS, dailyLimitUnits)
        putU16(payload, Atc3Const.Settings.MAX_BASAL, maxBasalRaw)
        putU16(payload, Atc3Const.Settings.MAX_BOLUS, maxBolusRaw)
        return payload
    }

    companion object {

        private fun toRaw(units: Double) = (units / Atc3Const.DOSE_SCALE).roundToInt()

        private fun putU16(payload: ByteArray, offset: Int, value: Int) {
            payload[offset] = (value and 0xFF).toByte()
            payload[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        /**
         * Read the settings out of a Status V1 frame.
         *
         * @param alarmDuration what to report for the one field Status V1 does not carry. The
         *        pump never sends it, so the driver can only remember what it last wrote; see
         *        [app.aaps.pump.atc3.keys.Atc3IntNonKey.AlarmDuration].
         * @return null when the frame is too short to hold the whole block
         */
        fun decode(frame: Atc3ResponseFrame, alarmDuration: Int): Atc3Settings? {
            if (!frame.has(Atc3Const.StatusV1.LANGUAGE)) return null
            val switchAt = { offset: Int -> frame.byteAt(offset) != 0 }
            val bolusTypes = frame.byteAt(Atc3Const.StatusV1.BOLUS_TYPES) shr Atc3Const.Settings.BOLUS_TYPES_STATUS_SHIFT
            return Atc3Settings(
                lowBolusSpeed = switchAt(Atc3Const.StatusV1.LOW_BOLUS_SPEED),
                keypadLock = switchAt(Atc3Const.StatusV1.KEYPAD_LOCK),
                autoOff = switchAt(Atc3Const.StatusV1.AUTO_OFF),
                basalPatterns = switchAt(Atc3Const.StatusV1.BASAL_PATTERNS),
                dailyLimitEnabled = switchAt(Atc3Const.StatusV1.DAILY_LIMIT_ENABLED),
                english = switchAt(Atc3Const.StatusV1.LANGUAGE),
                alarmSignalType = frame.byteAt(Atc3Const.StatusV1.ALARM_SIGNAL_TYPE),
                brightnessLevel = frame.byteAt(Atc3Const.StatusV1.BRIGHTNESS),
                autoOffHours = frame.byteAt(Atc3Const.StatusV1.AUTO_OFF_HOURS),
                lowInsulinUnits = frame.byteAt(Atc3Const.StatusV1.LOW_INSULIN_UNITS),
                lowInsulinHalfHours = frame.byteAt(Atc3Const.StatusV1.LOW_INSULIN_HALF_HOURS),
                extendedBolusAllowed = bolusTypes and Atc3Const.Settings.BOLUS_TYPE_EXTENDED != 0,
                bgReminder = bolusTypes and Atc3Const.Settings.BOLUS_TYPE_BG_REMINDER != 0,
                alarmDuration = alarmDuration,
                screenTimeoutRaw = frame.u16le(Atc3Const.StatusV1.SCREEN_TIMEOUT),
                dailyLimitUnits = frame.u16le(Atc3Const.StatusV1.DAILY_LIMIT_UNITS),
                maxBasalRaw = frame.u16le(Atc3Const.StatusV1.MAX_BASAL),
                maxBolusRaw = frame.u16le(Atc3Const.StatusV1.MAX_BOLUS)
            )
        }
    }
}
