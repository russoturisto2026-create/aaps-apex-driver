package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const

/**
 * An alarm the pump can raise.
 *
 * The same numbering is used in two places: the active alarm slots of Status V1, which say what is
 * being raised at this moment, and the alarm history object `0x03`, which is the record of what has
 * been raised.
 *
 * **An alarm says something needs a person, and nothing else.** It must never be turned into a
 * delivery state: an occlusion is raised by the pressure a bolus builds, the pump cancels that
 * bolus, and basal is not what trips it. A driver that reads an alarm as "the pump is stopped"
 * stops crediting insulin the pump may well be delivering, which is the more dangerous of the two
 * mistakes. What was actually delivered is read afterwards from the pump's own journals.
 */
enum class Atc3Alarm(val code: Int) {

    /** Low battery. */
    LOW_BATTERY(1),

    /**
     * Blood glucose measurement required, the reminder the settings block switches on.
     *
     * An ordinary alarm despite being a reminder: the pump holds it until it is acknowledged on the
     * keypad and writes it to the history like any other.
     */
    BLOOD_GLUCOSE_REMINDER(2),

    /** Button error. */
    BUTTON_ERROR(3),

    /**
     * No delivery — the occlusion alarm.
     *
     * The only way an occlusion reaches a client: the pressure figure the pump draws on its own
     * screen is never sent.
     */
    NO_DELIVERY(8),

    /** Reservoir empty. */
    RESERVOIR_EMPTY(13),

    /**
     * The daily dose limit set on the pump has been reached.
     *
     * The pump does not refuse the command that crosses the limit: it delivers up to the limit,
     * stops the bolus there, and delivers nothing more that day. Like an empty reservoir it is a
     * stop the pump does not admit to in its stopped flag.
     */
    DAILY_LIMIT(14);

    /** True for an alarm under which the pump delivers nothing, basal included. */
    val stopsDelivery: Boolean get() = this == RESERVOIR_EMPTY || this == DAILY_LIMIT

    companion object {

        /** The alarm with this code, or null for a code this driver does not know. */
        fun ofCode(code: Int): Atc3Alarm? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One alarm history record, object `0x03`.
 *
 * ```
 * header, 16 bytes | clock, six binary bytes | code, uint16 | crc16
 * ```
 *
 * The clock carries whole minutes, with zero seconds, so the timestamp places an alarm to the
 * minute and no finer.
 */
data class Atc3AlarmRecord(
    /** Position within one answer, 0 is the most recent. Never an identity, the pump reuses it. */
    val index: Int,
    /** When the pump raised it, milliseconds since the epoch, local calendar, whole minutes. */
    val timestamp: Long,
    /** The same instant as whole seconds through a UTC calendar, for use as an identity key. */
    val pumpClockUtcSeconds: Long,
    /** The raw code, kept even when it is one this driver does not know. */
    val code: Int
) {

    /** The alarm, or null for a code that is not in [Atc3Alarm]. */
    val alarm: Atc3Alarm? get() = Atc3Alarm.ofCode(code)

    companion object {

        const val FRAME_SIZE = 16

        private const val TIMESTAMP_OFFSET = 6
        private const val CODE_OFFSET = 12

        /**
         * Decode an alarm record, or return null when the frame is not one.
         *
         * The object type is not enough on its own: `0x03` is also the heartbeat's object, and that
         * arrives on frame id `0xA5` with six declared bytes. Requiring the history mode and the
         * full sixteen bytes keeps the two apart.
         */
        fun decode(frame: Atc3ResponseFrame): Atc3AlarmRecord? {
            if (frame.objectType != Atc3Const.ObjectType.ALARM_RECORD) return null
            if (frame.frameId != Atc3Const.MODE_HISTORY) return null
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
