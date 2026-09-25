package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const

/**
 * The last temporary basal command the pump accepted, object `0x0A`.
 *
 * **The authority on the rate in force is Status V1, data 82..83.** It carries the absolute rate
 * the pump is really running whatever mode the command was given in, and that is what the driver
 * acts on. This object exists for the one thing Status V1 does not carry: when the temporary basal
 * started. Without it a temporary basal somebody started on the pump reaches AAPS stamped at the
 * moment the driver first noticed it, which at a fifteen minute poll interval can be a quarter of
 * an hour late, and AAPS misses that much altered basal in its insulin on board.
 *
 * It is also the *last command*, not a running temporary basal: it keeps returning the same record
 * byte for byte long after the temporary basal has ended, and it answers the same whether one is
 * running or not. Status V1 is what says that too.
 *
 * Layout, see [Atc3Const.ActiveTbr]:
 *
 * ```
 * header | start clock (6) | 00 | start clock again (6) | mode | duration (2) | rate (2) | delivered (2) | CRC
 * ```
 *
 * The start clock stays put for the whole run and moves only when the temporary basal is renewed,
 * so it is a real start time rather than a rolling "now". The delivered amount grows by one raw
 * unit per 0.025 U delivered.
 *
 * The duration is held in steps of 15 minutes, the same encoding the start command uses. That is
 * what gives a temporary basal somebody started on the pump a real end rather than a horizon the
 * driver keeps pushing out. The record is still closed as soon as the pump stops reporting the
 * temporary basal, so ending early is handled too.
 *
 * **A record count of zero does not mean nothing is running.** A full 28 byte record can carry
 * count `00`, so the count cannot be used to tell an empty answer from a real one and the frame
 * size is what decides.
 *
 * **The rate field is two different quantities depending on the mode byte before it**, which is why
 * this class exposes [rate] and [percent] separately instead of one number the caller has to
 * interpret. See [rate].
 */
data class Atc3TbrStatus(
    /** When the temporary basal started, on the pump clock, milliseconds, local calendar. */
    val startTimestamp: Long,
    /**
     * The same start as whole seconds read through a UTC calendar.
     *
     * An identity, not a time: it is what tells a renewed temporary basal from the one that was
     * already running, even when both run at the same rate.
     */
    val startUtcSeconds: Long,
    /**
     * The rate the command asked for, U/h, or null when it asked for a percentage instead.
     *
     * **Null is not "unknown", it is "the pump was not given a rate"**, and there is no rate to
     * invent from this object: a percentage command carries the percentage and nothing else. The
     * absolute rate that percentage works out to is in Status V1, data 82..83, and in object
     * `0x09`; the driver takes the rate it acts on from Status V1 and never from here.
     *
     * The field this comes from is the same two bytes in both modes, which is what makes the
     * distinction worth a type rather than a comment: a percentage temporary basal of 111 % reads
     * raw 111, and read as a rate that is 2.775 U/h — nearly three times the truth, and no more
     * obviously wrong than any other number.
     */
    val rate: Double?,
    /**
     * The percentage of the scheduled basal the command asked for, whole percent, or null when it
     * asked for an absolute rate. See [rate].
     */
    val percent: Int?,
    /** How long it was started for, minutes, or 0 when the pump reported none. */
    val durationMinutes: Int,
    /** How much this temporary basal has delivered so far, units. */
    val deliveredUnits: Double
) {

    /**
     * What the command asked for, in words, for logs and traces.
     *
     * A single string because a log line that printed both fields would print a null in one of them
     * every time, and because the unit is the whole point: `111 %` and `2.775 U/h` are the same two
     * bytes.
     */
    val amountAsked: String get() = rate?.let { "$it U/h" } ?: "$percent %"

    /**
     * True when the start clock could be the start of a temporary basal that is running now.
     *
     * An empty start clock decodes to 1999-11-30, before the moment AAPS adopted the pump, and AAPS
     * would refuse the whole record; every such record would also carry the same pump id. [decode]
     * already takes the second copy of the start when the first is empty, and this check disbelieves
     * any other start the pump cannot have meant, whatever put it there.
     *
     * An implausible start must not be passed on. A record stamped
     * where the driver noticed it is late by up to a poll interval; a record AAPS throws away is
     * missing entirely, and that is the worse of the two by a wide margin.
     *
     * @param pumpNow now on the pump's own clock, which is what [startTimestamp] is measured on
     */
    fun isStartPlausible(pumpNow: Long): Boolean {
        val age = pumpNow - startTimestamp
        return age > -CLOCK_SLACK_MS && age < Atc3Const.RECONCILE_MAX_AGE_MS
    }

    companion object {

        /** Size of a frame that actually carries a record. */
        const val FRAME_SIZE = 28

        /** Length of one binary clock field. */
        private const val CLOCK_BYTES = 6

        /**
         * How far into the future a start may sit before it is disbelieved, milliseconds.
         *
         * The two clocks are corrected for their measured difference, but that measurement moves
         * by tens of seconds between reads, so a start a minute ahead is ordinary noise rather
         * than a wrong reading.
         */
        private const val CLOCK_SLACK_MS = 60_000L

        /**
         * Which of the object's two start clocks to believe.
         *
         * The frame carries the start twice, at [Atc3Const.ActiveTbr.START_CLOCK] and again at
         * [Atc3Const.ActiveTbr.START_CLOCK_REPEAT], and normally the two are identical. But when a
         * temporary basal is started on the pump's own keypad while another one is already
         * running, the pump fills only the second copy and leaves the first all zero for as long
         * as that temporary basal runs.
         *
         * This keys off the empty field itself rather than off that rule, so it stays right
         * whatever else produces one. Reading the second copy costs nothing when the two agree and
         * is the whole record when they do not. Without it the start decodes to 1999-11-30, AAPS
         * refuses the record as older than the pump's own registration, and the temporary basal
         * never reaches the insulin on board at all.
         */
        private fun startClockOffset(frame: Atc3ResponseFrame): Int =
            if ((0 until CLOCK_BYTES).all { frame.byteAt(Atc3Const.ActiveTbr.START_CLOCK + it) == 0 }) {
                Atc3Const.ActiveTbr.START_CLOCK_REPEAT
            } else {
                Atc3Const.ActiveTbr.START_CLOCK
            }

        fun decode(frame: Atc3ResponseFrame): Atc3TbrStatus? {
            if (frame.objectType != Atc3Const.ObjectType.TBR_ACTIVE) return null
            // Nothing running answers short rather than with a count of zero, see above.
            if (frame.raw.size < FRAME_SIZE) return null
            val startClock = startClockOffset(frame)
            // The mode decides what the rate field is, so it is read first and the field is only
            // ever scaled on the branch where the scale applies.
            val absolute = frame.byteAt(Atc3Const.ActiveTbr.MODE) ==
                Atc3Const.TbrPayload.MODE_ABSOLUTE.toInt()
            val amount = frame.u16le(Atc3Const.ActiveTbr.RATE)
            return Atc3TbrStatus(
                startTimestamp = Atc3StatusV1.decodeClock(frame, startClock),
                startUtcSeconds = Atc3StatusV1.decodeClockUtcSeconds(frame, startClock),
                rate = if (absolute) amount * Atc3Const.DOSE_SCALE else null,
                percent = if (absolute) null else amount,
                durationMinutes = frame.u16le(Atc3Const.ActiveTbr.DURATION) *
                    Atc3Const.TbrPayload.DURATION_UNIT_MINUTES,
                deliveredUnits = frame.u16le(Atc3Const.ActiveTbr.DELIVERED) * Atc3Const.DOSE_SCALE
            )
        }
    }
}
