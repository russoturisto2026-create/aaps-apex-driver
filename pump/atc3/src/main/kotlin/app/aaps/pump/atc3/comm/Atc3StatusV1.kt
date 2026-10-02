package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const
import java.util.Calendar
import java.util.TimeZone

/**
 * Decoded Status V1 frame, the pump's main state report.
 *
 * Bytes whose meaning is not known are left undecoded rather than guessed at.
 */
data class Atc3StatusV1(
    /** Index of the currently active basal profile, 0 based. */
    val activeProfileIndex: Int,
    /**
     * When the pump built this snapshot, milliseconds since the epoch, from the six clock bytes.
     *
     * Not the pump's clock and never a source of time. The pump rebuilds its status once a minute
     * and on anything that changes it -- a stop, a resume, a clock write, a bolus -- so this is the
     * moment every other field of the frame belongs to, and on a rebuild forced by a stop or a
     * resume it is that event to the second.
     */
    val snapshotTime: Long,
    /** Remaining insulin in the reservoir, in units. */
    val reservoirUnits: Double,
    /**
     * Insulin delivered today, basal and bolus together, units, as it stood at [snapshotTime].
     *
     * The pump's own count of what it put in, and the only field that stops when delivery stops.
     * Object 0x06 breaks it down and adds back up to it exactly. Compared against what AAPS
     * counts for the same interval, see [app.aaps.pump.atc3.history.Atc3StateCheck].
     */
    val deliveredTodayUnits: Double,
    /**
     * Hour and minute of the last explicit stop, as the pump keeps them, or null when the field
     * reads 00 00, which it does after a restart and before any stop.
     *
     * A stop by alarm does not move it. The second of the stop is not here: it is in
     * [snapshotTime] when the stop itself rebuilt the snapshot, and lost at the next rebuild.
     */
    val lastStop: LastStop?,
    /** Whole minutes the running temporary basal has been going, 0 while none runs. */
    val tbrElapsedMinutes: Int,
    /** Basal rate the active profile schedules for the current slot, in U/h, 0 while suspended. */
    val scheduledBasalRate: Double,
    /**
     * True while the pump is stopped and delivering nothing at all.
     *
     * The pump reports this by putting `0xFFFF` where the scheduled basal rate goes, from the moment
     * a stop is acknowledged until the pump resumes.
     *
     * Reading that value as a rate would tell AAPS the pump is running at 1638 U/h, so this is not
     * a nicety: a driver that does not recognise it reports nonsense at the worst moment.
     */
    val suspended: Boolean,
    /**
     * True while the pump is locked.
     *
     * A locked pump is not a stopped one: it goes on delivering and it goes on answering reads. What
     * it refuses is every control command, the clock write and the settings write included, and it
     * refuses them with the ordinary refusal frame that says nothing about why.
     *
     * Without this field the driver could only find out by being refused, which costs an exchange
     * and reaches the user as a command that failed for no stated reason.
     */
    val locked: Boolean,
    /** True while a temporary basal is running. */
    val tbrActive: Boolean,
    /** Temporary basal rate in U/h, meaningful only when [tbrActive]. */
    val tbrRate: Double,
    /** Raw temporary basal mode byte, meaningful only when [tbrActive]. */
    val tbrMode: Int,
    /**
     * Codes of the alarms the pump reports as active in this snapshot, in slot order.
     *
     * Two slots, each a code and a flag, both `00 00` while nothing is active. Raw codes rather
     * than [Atc3Alarm] values, so an alarm this driver has never seen is still carried rather than
     * dropped; [activeAlarms] is the named half.
     *
     * **This says an alarm is being raised, not that delivery has stopped.** An occlusion is raised
     * by the pressure a bolus builds and cancels that bolus; nothing here may be turned into a
     * suspension or a basal rate. See [Atc3Alarm].
     *
     * An occlusion stands in the first slot as `08 01` for as long as the alarm is up and
     * unacknowledged, and the slot is back to `00 00` once it is acknowledged on the pump.
     */
    val activeAlarmCodes: List<Int>,
    /** Temporary basal duration in minutes, meaningful only when [tbrActive]. */
    val tbrDurationMinutes: Int
) {

    /** The active alarms this driver has a name for, in slot order. */
    val activeAlarms: List<Atc3Alarm> get() = activeAlarmCodes.mapNotNull { Atc3Alarm.ofCode(it) }

    /** The hour and minute of the last explicit stop, as two bytes of the frame. */
    data class LastStop(val hour: Int, val minute: Int) {

        /** The same as minutes since midnight, for comparing with a snapshot's own minute. */
        val minuteOfDay: Int get() = hour * 60 + minute
    }

    /**
     * True when this snapshot was rebuilt by the stop itself, so that [snapshotTime] is the moment of
     * the stop to the second: the snapshot's minute is the minute of the last stop.
     *
     * The periodic rebuild lands on the whole minute, so a stop at 13:51:30 gives a snapshot of
     * 13:51:30 and then one of 13:52:00; the first names the stop's minute and the second does not.
     */
    fun snapshotIsTheStop(): Boolean {
        val stop = lastStop ?: return false
        val calendar = Calendar.getInstance().apply { timeInMillis = snapshotTime }
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE) == stop.minuteOfDay
    }

    /**
     * The moment of the last stop on the day of this snapshot, milliseconds, or null when the
     * pump reports none.
     *
     * To the second when the snapshot is the stop's own, see [snapshotIsTheStop]; otherwise the
     * minute the pump keeps, at its first second. A stop minute later than the snapshot's own is
     * from the day before and is not used: what the driver needs is the stop that is in force.
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

    /**
     * True when the snapshot was rebuilt by an event rather than by the minute: the clock carries
     * seconds, and the periodic rebuild does not. One event in sixty lands on the whole minute and
     * reads as periodic here; nothing worse than a moment a minute late follows from that.
     */
    val snapshotCarriesSeconds: Boolean
        get() = Calendar.getInstance().apply { timeInMillis = snapshotTime }.get(Calendar.SECOND) != 0

    /** True while the pump is raising anything at all. */
    val hasActiveAlarm: Boolean get() = activeAlarmCodes.isNotEmpty()

    companion object {

        /** What the pump writes where the scheduled basal rate goes while it is stopped. */
        private const val SUSPENDED_MARKER = 0xFFFF

        /**
         * Decode a Status V1 frame, or return null when the frame is too short to hold every
         * confirmed field.
         */
        fun decode(frame: Atc3ResponseFrame): Atc3StatusV1? {
            if (!frame.has(Atc3Const.StatusV1.SCHEDULED_BASAL, 2)) return null
            if (!frame.has(Atc3Const.StatusV1.TBR_ELAPSED, 2)) return null

            val rawBasal = frame.u16le(Atc3Const.StatusV1.SCHEDULED_BASAL)
            val suspended = rawBasal == SUSPENDED_MARKER

            val stopHour = frame.byteAt(Atc3Const.StatusV1.LAST_STOP)
            val stopMinute = frame.byteAt(Atc3Const.StatusV1.LAST_STOP + 1)
            return Atc3StatusV1(
                activeProfileIndex = frame.byteAt(Atc3Const.StatusV1.ACTIVE_PROFILE),
                snapshotTime = decodeClock(frame, Atc3Const.StatusV1.CLOCK),
                reservoirUnits = frame.u24le(Atc3Const.StatusV1.RESERVOIR) * Atc3Const.RESERVOIR_SCALE,
                deliveredTodayUnits = frame.u16le(Atc3Const.StatusV1.DELIVERED_TODAY) * Atc3Const.DOSE_SCALE,
                // 00 00 is "none" rather than midnight: the field reads so after a restart and
                // before the first stop, and a stop exactly at midnight is indistinguishable from
                // it. A stop the pump is still in is seen in the scheduled rate anyway.
                lastStop = if (stopHour == 0 && stopMinute == 0 || stopHour > 23 || stopMinute > 59) null
                else LastStop(stopHour, stopMinute),
                tbrElapsedMinutes = frame.u16le(Atc3Const.StatusV1.TBR_ELAPSED),
                scheduledBasalRate =
                    if (suspended) 0.0 else rawBasal * Atc3Const.DOSE_SCALE,
                suspended = suspended,
                locked = frame.byteAt(Atc3Const.StatusV1.LOCKED) != 0,
                tbrActive = frame.byteAt(Atc3Const.StatusV1.TBR_ACTIVE) != 0,
                tbrRate = frame.u16le(Atc3Const.StatusV1.TBR_RATE) * Atc3Const.DOSE_SCALE,
                tbrMode = frame.byteAt(Atc3Const.StatusV1.TBR_MODE),
                activeAlarmCodes = decodeAlarmSlots(frame),
                tbrDurationMinutes = frame.u16le(Atc3Const.StatusV1.TBR_DURATION)
            )
        }

        /**
         * The codes standing in the two active alarm slots, in slot order.
         *
         * A slot holds a code and then a flag; an empty slot is `00 00`. Only the code is taken:
         * the flag reads `01` in a filled slot and carries nothing beyond that.
         */
        private fun decodeAlarmSlots(frame: Atc3ResponseFrame): List<Int> =
            listOf(Atc3Const.StatusV1.ALARM_SLOT_FIRST, Atc3Const.StatusV1.ALARM_SLOT_SECOND)
                .filter { frame.has(it, 2) }
                .map { frame.byteAt(it) }
                .filter { it != 0 }

        /**
         * The six clock bytes for an instant, the exact inverse of [decodeClock].
         *
         * Kept beside the decoder on purpose: the two have to agree, or a clock written from the
         * phone would read back as a different time and the driver would keep correcting it.
         */
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

        /**
         * Decode the pump clock: six ordinary binary bytes, year(+2000) month day hour minute
         * second, not BCD.
         */
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
         * Decode the pump clock as whole seconds on a UTC calendar.
         *
         * This is an identity key, not a time. The same six bytes read through the phone's current
         * timezone move by an hour when daylight saving starts or the phone crosses a border, which
         * would make every stored history record look like a new one. Read as UTC they never move.
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
         * The identity key an instant on the pump's clock would carry, the mirror of
         * [decodeClockUtcSeconds].
         *
         * A record's key is the pump's wall-clock digits read through a UTC calendar, so it is the
         * real epoch second plus the phone's offset from UTC. Anything the driver wants to compare
         * with such a key -- "was this record ours?" -- has to be brought onto the same scale
         * first, and this does it: take the instant in real epoch milliseconds, add the offset in
         * force at that instant, and cut to whole seconds. A plain epoch second compared against a
         * record's key is a whole timezone offset out and never matches.
         *
         * It holds because the pump keeps the same wall clock as the phone. That is not an
         * assumption but a thing the driver maintains: [app.aaps.pump.atc3.Atc3PumpPlugin]
         * corrects the pump's clock whenever it drifts, and stops the loop when it is too far out
         * to correct.
         *
         * @param millis the instant on the pump's clock, in real milliseconds since the epoch
         */
        fun wallClockUtcSeconds(millis: Long): Long =
            (millis + TimeZone.getDefault().getOffset(millis)) / 1000L
    }
}
