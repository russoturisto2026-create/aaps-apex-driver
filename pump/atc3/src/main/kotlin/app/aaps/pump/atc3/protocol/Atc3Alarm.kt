package app.aaps.pump.atc3.protocol

/**
 * An alarm the pump can raise, numbered alike in the status and in the alarm history. An alarm says
 * that something needs a person; it is never read as a delivery state, except the two that
 * [stopsDelivery].
 */
enum class Atc3Alarm(val code: Int) {

    LOW_BATTERY(1),

    /** Blood glucose measurement required, the reminder the settings switch on. */
    BLOOD_GLUCOSE_REMINDER(2),

    BUTTON_ERROR(3),

    /** No delivery: the occlusion alarm. */
    NO_DELIVERY(8),

    MOTOR_ERROR(11),

    RESERVOIR_EMPTY(13),

    /** The daily dose limit is reached: the pump delivers nothing more that day, without saying it is stopped. */
    DAILY_LIMIT(14);

    /** True for an alarm under which the pump delivers nothing, basal included. */
    val stopsDelivery: Boolean get() = this == RESERVOIR_EMPTY || this == DAILY_LIMIT

    companion object {

        /** Or null for a code the driver does not know. */
        fun ofCode(code: Int): Atc3Alarm? = entries.firstOrNull { it.code == code }
    }
}

/** One alarm history record, placed to the minute. */
data class Atc3AlarmRecord(
    /** Position in the answer, 0 the newest; reused by the pump, never an identity. */
    val index: Int,
    /** When the pump raised it, on its clock, milliseconds. */
    val timestamp: Long,
    /** The same moment as a UTC-calendar key. */
    val pumpClockUtcSeconds: Long,
    /** The raw code, kept when the driver does not know it. */
    val code: Int
) {

    val alarm: Atc3Alarm? get() = Atc3Alarm.ofCode(code)

    companion object {

        const val FRAME_SIZE = 16
        private const val TIMESTAMP_OFFSET = 6
        private const val CODE_OFFSET = 12

        /** The record, or null when the frame is not one; the heartbeat shares the object number and is told apart by its frame. */
        fun decode(frame: Atc3ResponseFrame): Atc3AlarmRecord? {
            if (frame.objectType != Atc3Protocol.ObjectType.ALARM_RECORD) return null
            if (frame.frameId != Atc3Protocol.MODE_HISTORY) return null
            if (frame.raw.size < FRAME_SIZE) return null
            val clockOffset = TIMESTAMP_OFFSET - Atc3ResponseFrame.DATA_BASE_OFFSET
            return Atc3AlarmRecord(
                index = frame.recordIndex,
                timestamp = Atc3StatusV1.decodeClock(frame, clockOffset),
                pumpClockUtcSeconds = Atc3StatusV1.decodeClockUtcSeconds(frame, clockOffset),
                code = frame.u16le(CODE_OFFSET - Atc3ResponseFrame.DATA_BASE_OFFSET)
            )
        }
    }
}
