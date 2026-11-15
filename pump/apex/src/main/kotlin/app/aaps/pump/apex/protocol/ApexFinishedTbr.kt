package app.aaps.pump.apex.protocol

/** The pump's answer to the read of the last finished temporary basal: [finished] null when none has finished since a reset. */
class ApexFinishedTbrAnswer(val finished: ApexFinishedTbr?)

/**
 * The last temporary basal that finished: when it really ended and how, for closing a temporary
 * basal that ended between two looks at the pump. Never the one running now. The end is the
 * status snapshot time when the cancel came, the start of its minute as a rule, not the second of
 * the cancel.
 *
 * A pump on which no temporary basal has finished yet, as after a reset, answers with the whole
 * frame of zeros, not with a short one: [decode] returns null for it.
 */
data class ApexFinishedTbr(
    val result: Int,
    /** When it started: the pump's wall clock as a key, see [ApexStamp]. */
    val startClockSeconds: Long,
    /** When it ended, the same way. */
    val endClockSeconds: Long,
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

    /** What it was started at, with its unit, for logs. */
    val amountAsked: String get() = ApexTbrFields.amountText(rate, percent)

    /** How it ended, for logs. */
    val resultText: String
        get() = when (result) {
            Offset.RESULT_COMPLETED            -> "ran to its end"
            Offset.RESULT_CANCELLED_ON_PUMP    -> "cancelled on the pump"
            Offset.RESULT_CANCELLED_BY_COMMAND -> "cancelled by a command"
            else                               -> "result $result"
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

        fun decode(frame: ApexResponseFrame): ApexFinishedTbr? {
            if (frame.objectType != ApexProtocol.ReadOpcode.TBR_FINISHED) return null
            // A pump with nothing finished answers short, or with a whole frame of zeros.
            if (frame.raw.size < FRAME_SIZE || ApexTbrFields.payloadIsZeros(frame)) return null
            val startClock = ApexTbrFields.startClockOffset(frame, Offset.START_CLOCK, Offset.START_CLOCK_REPEAT)
            val absolute = frame.byteAt(Offset.MODE) ==
                ApexProtocol.TbrPayload.MODE_ABSOLUTE.toInt()
            val amount = frame.u16le(Offset.RATE)
            return ApexFinishedTbr(
                result = frame.byteAt(Offset.RESULT),
                startClockSeconds = ApexStamp.clockSeconds(frame, startClock),
                endClockSeconds = ApexStamp.clockSeconds(frame, Offset.END_CLOCK),
                rate = ApexTbrFields.rate(absolute, amount),
                percent = ApexTbrFields.percent(absolute, amount),
                durationMinutes = ApexTbrFields.minutes(frame.u16le(Offset.DURATION)),
                deliveredUnits = frame.u16le(Offset.DELIVERED) * ApexProtocol.DOSE_SCALE
            )
        }
    }
}
