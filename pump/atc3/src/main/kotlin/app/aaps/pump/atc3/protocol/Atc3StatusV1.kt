package app.aaps.pump.atc3.protocol

import app.aaps.pump.atc3.check.Atc3StateCheck
import java.util.Calendar
import java.util.TimeZone

/** The pump's main state report, Status V1. */
data class Atc3StatusV1(
    /** The active basal profile, 0 based. */
    val activeProfileIndex: Int,
    /**
     * When the pump built this report, milliseconds: the moment every other field belongs to, not the
     * pump's clock. On a report rebuilt by a stop or a resume it is that event to the second.
     */
    val snapshotTime: Long,
    /** Insulin left in the reservoir, U. */
    val reservoirUnits: Double,
    /**
     * Insulin delivered today, basal and bolus, U, as of [snapshotTime]: what the comparison with the
     * AAPS journal is made against, see [app.aaps.pump.atc3.check.Atc3StateCheck].
     */
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
     * Codes of the alarms the pump is raising, in slot order; codes the driver has no name for are kept.
     * An alarm being raised says nothing about delivery, see [Atc3Alarm].
     */
    val activeAlarmCodes: List<Int>,
    /** Minutes the temporary basal was started for, meaningful only when [tbrActive]. */
    val tbrDurationMinutes: Int
) {

    /** The raised alarms the driver has a name for. */
    val activeAlarms: List<Atc3Alarm> get() = activeAlarmCodes.mapNotNull { Atc3Alarm.ofCode(it) }

    data class LastStop(val hour: Int, val minute: Int) {

        val minuteOfDay: Int get() = hour * 60 + minute
    }

    /** True when this report was rebuilt by the last stop itself, so that [snapshotTime] is its second. */
    fun snapshotIsTheStop(): Boolean {
        val stop = lastStop ?: return false
        val calendar = Calendar.getInstance().apply { timeInMillis = snapshotTime }
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE) == stop.minuteOfDay
    }

    /**
     * The moment of the last stop on this report's day, or null: to the second when [snapshotIsTheStop],
     * else the minute. A stop later in the day than the report is the day before's and is not used.
     */
    fun lastStopMoment(): Long? {
        val stop = lastStop ?: return null
        if (snapshotIsTheStop()) return snapshotTime
        val calendar = Calendar.getInstance().apply {
            timeInMillis = snapshotTime
            set(Calendar.HOUR_OF_DAY, stop.hour)
            set(Calendar.MINUTE, stop.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis.takeIf { it <= snapshotTime }
    }

    /** True when the report was rebuilt by an event rather than by the minute. */
    val snapshotCarriesSeconds: Boolean
        get() = Calendar.getInstance().apply { timeInMillis = snapshotTime }.get(Calendar.SECOND) != 0

    val hasActiveAlarm: Boolean get() = activeAlarmCodes.isNotEmpty()

    internal object Offset {

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

        /** The report, or null when the frame is too short for it. */
        fun decode(frame: Atc3ResponseFrame): Atc3StatusV1? {
            if (!frame.has(Offset.SCHEDULED_BASAL, 2)) return null
            if (!frame.has(Offset.TBR_ELAPSED, 2)) return null

            val rawBasal = frame.u16le(Offset.SCHEDULED_BASAL)
            val suspended = rawBasal == SUSPENDED_MARKER

            val stopHour = frame.byteAt(Offset.LAST_STOP)
            val stopMinute = frame.byteAt(Offset.LAST_STOP + 1)
            return Atc3StatusV1(
                activeProfileIndex = frame.byteAt(Offset.ACTIVE_PROFILE),
                snapshotTime = decodeClock(frame, Offset.CLOCK),
                reservoirUnits = frame.u24le(Offset.RESERVOIR) * Atc3Protocol.RESERVOIR_SCALE,
                deliveredTodayUnits = frame.u16le(Offset.DELIVERED_TODAY) * Atc3Protocol.DOSE_SCALE,
                // Reads 00 00 after a restart and before the first stop: no stop, not midnight.
                lastStop = if (stopHour == 0 && stopMinute == 0 || stopHour > 23 || stopMinute > 59) null
                else LastStop(stopHour, stopMinute),
                tbrElapsedMinutes = frame.u16le(Offset.TBR_ELAPSED),
                scheduledBasalRate =
                    if (suspended) 0.0 else rawBasal * Atc3Protocol.DOSE_SCALE,
                suspended = suspended,
                locked = frame.byteAt(Offset.LOCKED) != 0,
                tbrActive = frame.byteAt(Offset.TBR_ACTIVE) != 0,
                tbrRate = frame.u16le(Offset.TBR_RATE) * Atc3Protocol.DOSE_SCALE,
                tbrMode = frame.byteAt(Offset.TBR_MODE),
                activeAlarmCodes = decodeAlarmSlots(frame),
                tbrDurationMinutes = frame.u16le(Offset.TBR_DURATION)
            )
        }

        private fun decodeAlarmSlots(frame: Atc3ResponseFrame): List<Int> =
            listOf(Offset.ALARM_SLOT_FIRST, Offset.ALARM_SLOT_SECOND)
                .filter { frame.has(it, 2) }
                .map { frame.byteAt(it) }
                .filter { it != 0 }

        /** The six clock bytes of an instant, the inverse of [decodeClock]. */
        fun encodeClock(millis: Long): ByteArray {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = millis
            return byteArrayOf(
                (calendar.get(Calendar.YEAR) - 2000).toByte(),
                (calendar.get(Calendar.MONTH) + 1).toByte(),
                calendar.get(Calendar.DAY_OF_MONTH).toByte(),
                calendar.get(Calendar.HOUR_OF_DAY).toByte(),
                calendar.get(Calendar.MINUTE).toByte(),
                calendar.get(Calendar.SECOND).toByte()
            )
        }

        fun decodeClock(frame: Atc3ResponseFrame, responseDataOffset: Int): Long {
            val calendar = Calendar.getInstance()
            calendar.clear()
            calendar.set(
                2000 + frame.byteAt(responseDataOffset),
                frame.byteAt(responseDataOffset + 1) - 1, // Calendar months are 0 based
                frame.byteAt(responseDataOffset + 2),
                frame.byteAt(responseDataOffset + 3),
                frame.byteAt(responseDataOffset + 4),
                frame.byteAt(responseDataOffset + 5)
            )
            return calendar.timeInMillis
        }

        /**
         * The clock read as whole seconds on a UTC calendar: a key that names a record, not a time, so
         * that a timezone or daylight saving change does not make stored records look new.
         */
        fun decodeClockUtcSeconds(frame: Atc3ResponseFrame, responseDataOffset: Int): Long {
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

        /**
         * The key an instant on the pump's clock carries, see [decodeClockUtcSeconds], for comparing an
         * instant of the phone's with a record of the pump's. Holds while the pump keeps the phone's
         * wall clock, which the driver maintains.
         */
        fun wallClockUtcSeconds(millis: Long): Long =
            (millis + TimeZone.getDefault().getOffset(millis)) / 1000L
    }
}
