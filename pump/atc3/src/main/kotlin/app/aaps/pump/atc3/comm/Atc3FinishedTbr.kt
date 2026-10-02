package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const

/**
 * The last temporary basal that finished, object `0x0B`, 34 bytes.
 *
 * Status V1 says that no temporary basal is running. It does not say **when** the one before it
 * ended, nor **how**. Both are here, and both matter.
 *
 * *When* matters because without it a temporary basal that ended between two polls is recorded in
 * AAPS as having ended at the poll. At a five minute poll that is five minutes of basal credited to
 * the wrong rate, and the direction of the error depends on the rate: a temporary basal above the
 * profile leaves AAPS believing more insulin went in than did, and one below the profile — a zero
 * most of all — leaves it believing less. The second is the dangerous direction, because insulin on
 * board that reads low is what lets the loop add more.
 *
 * *How* matters because [RESULT_CANCELLED_ON_PUMP] is the one signal that somebody stopped a
 * temporary basal by hand. Nothing else distinguishes that from one that simply ran out.
 *
 * Layout, see [Atc3Const.FinishedTbr]:
 *
 * ```
 * header | result | start clock (6) | start clock again (6) | mode | duration (2) | rate (2)
 *        | end clock (6) | delivered (2) | crc16
 * ```
 *
 * **The one byte fields sit differently here than in object `0x0A`**: the result comes before the
 * first clock rather than between the two copies. That is why this class does not share its offsets
 * with [Atc3TbrStatus].
 *
 * **The end clock is the moment it actually ended.** For one that ran its course that is its start
 * plus its duration rounded up to the next whole minute; for a cancelled one it is when the cancel
 * landed.
 *
 * **A running temporary basal is not in here, the one before it is.** So this object answers a
 * question about the past and never about the present, and it cannot be used to tell whether
 * anything is running — Status V1 offset 53 is the authority on that.
 */
data class Atc3FinishedTbr(
    /** How it ended: one of [Atc3Const.FinishedTbr.RESULT_COMPLETED] and its neighbours. */
    val result: Int,
    /** When it started, on the pump clock, milliseconds, local calendar. */
    val startTimestamp: Long,
    /** The same start as whole seconds through a UTC calendar, an identity rather than a time. */
    val startUtcSeconds: Long,
    /** When it ended, on the pump clock, milliseconds, local calendar. */
    val endTimestamp: Long,
    /** The same end as whole seconds through a UTC calendar. */
    val endUtcSeconds: Long,
    /** The rate it was started at, U/h, or null when it was started as a percentage. */
    val rate: Double?,
    /** The percentage it was started at, whole percent, or null when it was started as a rate. */
    val percent: Int?,
    /** How long it was started for, minutes. */
    val durationMinutes: Int,
    /** What it actually delivered, units. */
    val deliveredUnits: Double
) {

    /** It ran for the whole duration it was started for. */
    val completed: Boolean get() = result == Atc3Const.FinishedTbr.RESULT_COMPLETED

    /** Somebody stopped it on the pump's own keypad. The only signal that this happened. */
    val cancelledOnPump: Boolean get() = result == Atc3Const.FinishedTbr.RESULT_CANCELLED_ON_PUMP

    /** A command over the link stopped it, which is what this driver's own cancel sends. */
    val cancelledByCommand: Boolean get() = result == Atc3Const.FinishedTbr.RESULT_CANCELLED_BY_COMMAND

    /** What it was started at, in words, for logs and traces. */
    val amountAsked: String get() = rate?.let { "$it U/h" } ?: "$percent %"

    /** How it ended, in words, for logs and traces. */
    val resultText: String
        get() = when (result) {
            Atc3Const.FinishedTbr.RESULT_COMPLETED            -> "ran to its end"
            Atc3Const.FinishedTbr.RESULT_CANCELLED_ON_PUMP    -> "cancelled on the pump"
            Atc3Const.FinishedTbr.RESULT_CANCELLED_BY_COMMAND -> "cancelled by a command"
            else                                              -> "result $result"
        }

    companion object {

        /** Size of a frame that carries a record. */
        const val FRAME_SIZE = 34

        /** Length of one binary clock field. */
        private const val CLOCK_BYTES = 6

        /**
         * Which of the two start clocks to believe.
         *
         * Object `0x0A` leaves its first copy empty and fills only the second when a temporary
         * basal is started on the keypad while another one runs. The same pair of fields
         * is here, so the same guard is applied: it costs nothing when the two agree and is the
         * whole start when they do not. See [Atc3TbrStatus].
         */
        private fun startClockOffset(frame: Atc3ResponseFrame): Int =
            if ((0 until CLOCK_BYTES).all { frame.byteAt(Atc3Const.FinishedTbr.START_CLOCK + it) == 0 }) {
                Atc3Const.FinishedTbr.START_CLOCK_REPEAT
            } else {
                Atc3Const.FinishedTbr.START_CLOCK
            }

        fun decode(frame: Atc3ResponseFrame): Atc3FinishedTbr? {
            if (frame.objectType != Atc3Const.ObjectType.TBR_FINISHED) return null
            // A pump with no finished temporary basal answers short, as it does for object 0x0A.
            if (frame.raw.size < FRAME_SIZE) return null
            val startClock = startClockOffset(frame)
            val absolute = frame.byteAt(Atc3Const.FinishedTbr.MODE) ==
                Atc3Const.TbrPayload.MODE_ABSOLUTE.toInt()
            val amount = frame.u16le(Atc3Const.FinishedTbr.RATE)
            return Atc3FinishedTbr(
                result = frame.byteAt(Atc3Const.FinishedTbr.RESULT),
                startTimestamp = Atc3StatusV1.decodeClock(frame, startClock),
                startUtcSeconds = Atc3StatusV1.decodeClockUtcSeconds(frame, startClock),
                endTimestamp = Atc3StatusV1.decodeClock(frame, Atc3Const.FinishedTbr.END_CLOCK),
                endUtcSeconds = Atc3StatusV1.decodeClockUtcSeconds(frame, Atc3Const.FinishedTbr.END_CLOCK),
                rate = if (absolute) amount * Atc3Const.DOSE_SCALE else null,
                percent = if (absolute) null else amount,
                durationMinutes = frame.u16le(Atc3Const.FinishedTbr.DURATION) *
                    Atc3Const.TbrPayload.DURATION_UNIT_MINUTES,
                deliveredUnits = frame.u16le(Atc3Const.FinishedTbr.DELIVERED) * Atc3Const.DOSE_SCALE
            )
        }
    }
}
