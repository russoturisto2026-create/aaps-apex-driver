package app.aaps.pump.apex.protocol

import java.util.Calendar
import java.util.TimeZone

/** The pump's main state report, Status V1. */
data class ApexStatusV1(
    /** The active basal profile, 0 based. */
    val activeProfileIndex: Int,
    /**
     * When the pump built this report, as the pump's wall clock read on a UTC calendar, whole seconds:
     * a key, not a moment. The account turns it into a moment through the stored clock offset. The
     * report is rebuilt once a minute and on an event: a stop or a resume, a cancel of a temporary
     * basal, a clock write; then this is that event to the second. A start of a temporary basal does
     * not rebuild it. The pump stamps its records with this time, not with the moment of the command.
     */
    val clockSeconds: Long,
    /** Insulin left in the reservoir, U. */
    val reservoirUnits: Double,
    /** Insulin delivered today, basal and bolus, U, as of [clockSeconds]. */
    val deliveredTodayUnits: Double,
    /** The last explicit stop, hour and minute, or null when the pump has none since it started. */
    val lastStop: LastStop?,
    /** Minutes the running temporary basal has been going, 0 while none runs. */
    val tbrElapsedMinutes: Int,
    /** The rate the schedule calls for now, U/h; 0 while stopped. */
    val scheduledBasalRate: Double,
    /** True while the pump is stopped and delivers nothing. */
    val suspended: Boolean,
    /** True while the pump is locked: it delivers and answers reads, and refuses every control command. */
    val locked: Boolean,
    val tbrActive: Boolean,
    /** U/h, meaningful only when [tbrActive]. */
    val tbrRate: Double,
    /** Meaningful only when [tbrActive]. */
    val tbrMode: Int,
    /**
     * Codes of the alarms the pump is raising, in slot order. Under any alarm the pump delivers
     * nothing and takes no command, until the user clears it on the pump.
     */
    val activeAlarmCodes: List<Int>,
    /** Minutes the temporary basal was started for, meaningful only when [tbrActive]. */
    val tbrDurationMinutes: Int,
    /** The battery indicator of the pump, in bars: 0 to 4. */
    val batteryBars: Int
) {

    /** The battery as a share of full: each bar is a quarter. */
    val batteryPercent: Int get() = (batteryBars * PERCENT_PER_BAR).coerceIn(0, 100)

    data class LastStop(val hour: Int, val minute: Int)

    internal object Offset {

        const val BATTERY_BARS = 2
        const val ACTIVE_PROFILE = 14
        const val CLOCK = 46
        const val RESERVOIR = 54
        const val DELIVERED_TODAY = 18
        const val LAST_STOP = 80
        const val TBR_ELAPSED = 88
        const val TBR_ACTIVE = 53
        const val ALARM_SLOT_FIRST = 58
        const val ALARM_SLOT_SECOND = 60
        const val SCHEDULED_BASAL = 78
        const val TBR_RATE = 82
        const val TBR_MODE = 84
        const val TBR_DURATION = 86
        const val LOCKED = 13
    }

    companion object {

        private const val SUSPENDED_MARKER = 0xFFFF
        private const val PERCENT_PER_BAR = 25

        /** The report, or null when the frame is too short for it. */
        fun decode(frame: ApexResponseFrame): ApexStatusV1? {
            if (!frame.has(Offset.SCHEDULED_BASAL, 2)) return null
            if (!frame.has(Offset.TBR_ELAPSED, 2)) return null

            val rawBasal = frame.u16le(Offset.SCHEDULED_BASAL)
            val suspended = rawBasal == SUSPENDED_MARKER

            val stopHour = frame.byteAt(Offset.LAST_STOP)
            val stopMinute = frame.byteAt(Offset.LAST_STOP + 1)
            return ApexStatusV1(
                activeProfileIndex = frame.byteAt(Offset.ACTIVE_PROFILE),
                clockSeconds = decodeClockSeconds(frame, Offset.CLOCK),
                reservoirUnits = frame.u24le(Offset.RESERVOIR) * ApexProtocol.RESERVOIR_SCALE,
                deliveredTodayUnits = frame.u16le(Offset.DELIVERED_TODAY) * ApexProtocol.DOSE_SCALE,
                // Reads 00 00 after a restart and before the first stop: no stop, not midnight.
                lastStop = if (stopHour == 0 && stopMinute == 0 || stopHour > 23 || stopMinute > 59) null
                else LastStop(stopHour, stopMinute),
                tbrElapsedMinutes = frame.u16le(Offset.TBR_ELAPSED),
                scheduledBasalRate =
                    if (suspended) 0.0 else rawBasal * ApexProtocol.DOSE_SCALE,
                suspended = suspended,
                locked = frame.byteAt(Offset.LOCKED) != 0,
                tbrActive = frame.byteAt(Offset.TBR_ACTIVE) != 0,
                tbrRate = frame.u16le(Offset.TBR_RATE) * ApexProtocol.DOSE_SCALE,
                tbrMode = frame.byteAt(Offset.TBR_MODE),
                activeAlarmCodes = decodeAlarmSlots(frame),
                tbrDurationMinutes = frame.u16le(Offset.TBR_DURATION),
                batteryBars = frame.byteAt(Offset.BATTERY_BARS)
            )
        }

        private fun decodeAlarmSlots(frame: ApexResponseFrame): List<Int> =
            listOf(Offset.ALARM_SLOT_FIRST, Offset.ALARM_SLOT_SECOND)
                .filter { frame.has(it, 2) }
                .map { frame.byteAt(it) }
                .filter { it != 0 }

        /** The six clock bytes of a wall clock key, the inverse of [decodeClockSeconds]. */
        fun encodeClock(clockSeconds: Long): ByteArray {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.timeInMillis = clockSeconds * 1000L
            return byteArrayOf(
                (calendar.get(Calendar.YEAR) - 2000).toByte(),
                (calendar.get(Calendar.MONTH) + 1).toByte(),
                calendar.get(Calendar.DAY_OF_MONTH).toByte(),
                calendar.get(Calendar.HOUR_OF_DAY).toByte(),
                calendar.get(Calendar.MINUTE).toByte(),
                calendar.get(Calendar.SECOND).toByte()
            )
        }

        /**
         * The six clock bytes read as whole seconds on a UTC calendar: the pump's wall clock as a key.
         * The account turns it into a moment through the stored offset; here it stays what the pump wrote.
         */
        fun decodeClockSeconds(frame: ApexResponseFrame, responseDataOffset: Int): Long {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.clear()
            calendar.set(
                2000 + frame.byteAt(responseDataOffset),
                frame.byteAt(responseDataOffset + 1) - 1, // Calendar months are 0 based
                frame.byteAt(responseDataOffset + 2),
                frame.byteAt(responseDataOffset + 3),
                frame.byteAt(responseDataOffset + 4),
                frame.byteAt(responseDataOffset + 5)
            )
            return calendar.timeInMillis / 1000L
        }

    }
}
