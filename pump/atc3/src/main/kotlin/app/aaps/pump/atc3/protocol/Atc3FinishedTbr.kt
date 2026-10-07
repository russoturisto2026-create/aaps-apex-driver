package app.aaps.pump.atc3.protocol

/**
 * The last temporary basal that finished: when it really ended and how, for closing a temporary
 * basal that ended between two looks at the pump. Never the one running now.
 */
data class Atc3FinishedTbr(
    val result: Int,
    /** When it started, on the pump's clock, milliseconds. */
    val startTimestamp: Long,
    /** The same start as a UTC-calendar key. */
    val startUtcSeconds: Long,
    /** When it ended, on the pump's clock, milliseconds. */
    val endTimestamp: Long,
    /** The same end as a UTC-calendar key. */
    val endUtcSeconds: Long,
    /** U/h, or null when it was started as a percentage. */
    val rate: Double?,
    /** Or null when it was started as a rate. */
    val percent: Int?,
    val durationMinutes: Int,
    /** What it delivered, U. */
    val deliveredUnits: Double
) {

    /** It ran for its whole duration. */
    val completed: Boolean get() = result == Offset.RESULT_COMPLETED

    /** Somebody stopped it on the pump's keypad. */
    val cancelledOnPump: Boolean get() = result == Offset.RESULT_CANCELLED_ON_PUMP

    /** A command over the link stopped it, as this driver's cancel does. */
    val cancelledByCommand: Boolean get() = result == Offset.RESULT_CANCELLED_BY_COMMAND

    /** What it was started at, with its unit, for logs. */
    val amountAsked: String get() = rate?.let { "$it U/h" } ?: "$percent %"

    /** How it ended, for logs. */
    val resultText: String
        get() = when (result) {
            Offset.RESULT_COMPLETED            -> "ran to its end"
            Offset.RESULT_CANCELLED_ON_PUMP    -> "cancelled on the pump"
            Offset.RESULT_CANCELLED_BY_COMMAND -> "cancelled by a command"
            else                                              -> "result $result"
        }

    internal object Offset {

        const val RESULT = 2
        const val RESULT_COMPLETED = 0x01
        const val RESULT_CANCELLED_ON_PUMP = 0x02
        const val RESULT_CANCELLED_BY_COMMAND = 0x03
        const val START_CLOCK = 3
        const val START_CLOCK_REPEAT = 9
        const val MODE = 15
        const val DURATION = 16
        const val RATE = 18
        const val END_CLOCK = 20
        const val DELIVERED = 26
    }

    companion object {

        const val FRAME_SIZE = 34
        private const val CLOCK_BYTES = 6

        /** Which of the two start clocks to read: the second when the first is empty. */
        private fun startClockOffset(frame: Atc3ResponseFrame): Int =
            if ((0 until CLOCK_BYTES).all { frame.byteAt(Offset.START_CLOCK + it) == 0 }) {
                Offset.START_CLOCK_REPEAT
            } else {
                Offset.START_CLOCK
            }

        fun decode(frame: Atc3ResponseFrame): Atc3FinishedTbr? {
            if (frame.objectType != Atc3Protocol.ObjectType.TBR_FINISHED) return null
            // A pump with nothing finished answers short.
            if (frame.raw.size < FRAME_SIZE) return null
            val startClock = startClockOffset(frame)
            val absolute = frame.byteAt(Offset.MODE) ==
                Atc3Protocol.TbrPayload.MODE_ABSOLUTE.toInt()
            val amount = frame.u16le(Offset.RATE)
            return Atc3FinishedTbr(
                result = frame.byteAt(Offset.RESULT),
                startTimestamp = Atc3StatusV1.decodeClock(frame, startClock),
                startUtcSeconds = Atc3StatusV1.decodeClockUtcSeconds(frame, startClock),
                endTimestamp = Atc3StatusV1.decodeClock(frame, Offset.END_CLOCK),
                endUtcSeconds = Atc3StatusV1.decodeClockUtcSeconds(frame, Offset.END_CLOCK),
                rate = if (absolute) amount * Atc3Protocol.DOSE_SCALE else null,
                percent = if (absolute) null else amount,
                durationMinutes = frame.u16le(Offset.DURATION) *
                    Atc3Protocol.TbrPayload.DURATION_UNIT_MINUTES,
                deliveredUnits = frame.u16le(Offset.DELIVERED) * Atc3Protocol.DOSE_SCALE
            )
        }
    }
}
