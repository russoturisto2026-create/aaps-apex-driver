package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const

/**
 * One temporary basal the pump has finished, object `0x27`, 22 bytes.
 *
 * **This is the journal of the basal rate**, and the only answer to "what was actually running
 * between two of our polls". Status V1 says what runs now; object `0x0A` says what the last command
 * asked for; neither says anything about a temporary basal that was started and cancelled while the
 * driver was not looking. A temporary basal set on the keypad or by another client and cancelled
 * a few minutes later leaves no trace anywhere else at all.
 *
 * The field that makes it worth reading is [deliveredUnits]. The pump says what the temporary basal
 * put in, so a gap in the driver's own arithmetic is closed from the pump's own figure instead of
 * reconstructed out of rates and durations — which is the reconstruction that goes wrong whenever
 * the rate changed between polls.
 *
 * Layout, see [Atc3Const.TbrRecord]:
 *
 * ```
 * header | start clock (6) | mode (2) | duration (2) | rate (2) | delivered (2) | crc16
 * ```
 *
 * **Only finished temporary basals are in here.** The one running now is not, and does not need to
 * be: Status V1 carries its rate and object `0x0A` its start and duration. The two together cover a
 * gap of any length without a hole in the middle.
 *
 * **The answer stops at ten frames** however many records the pump declares, in common with every
 * other `+0x20` object. That makes the journal cheap enough to read whenever the books do not
 * balance.
 *
 * **The rate field is two different quantities depending on the mode byte before it**, exactly as
 * in [Atc3TbrStatus], which is why [rate] and [percent] are separate rather than one number the
 * caller has to interpret. A percentage temporary basal of 150 % reads raw 150; taken for a rate
 * that would be 3.750 U/h.
 */
data class Atc3TbrRecord(
    /** Zero based position of this record in the answer. */
    val index: Int,
    /** When it started, on the pump clock, milliseconds, local calendar. */
    val startTimestamp: Long,
    /**
     * The same start as whole seconds through a UTC calendar.
     *
     * An identity rather than a time, used to tell one record from another when both ran at the
     * same rate for the same length.
     */
    val startUtcSeconds: Long,
    /** The rate it was started at, U/h, or null when it was started as a percentage. */
    val rate: Double?,
    /** The percentage it was started at, whole percent, or null when it was started as a rate. */
    val percent: Int?,
    /** How long it was started for, minutes. */
    val durationMinutes: Int,
    /**
     * What it actually delivered, units.
     *
     * The pump's own figure, and the reason this object is worth reading. It already accounts for
     * a temporary basal that was cancelled early: a 30 minute one stopped after four gives what
     * those four minutes put in, not what thirty would have.
     */
    val deliveredUnits: Double
) {

    /** What it was started at, in words, for logs and traces. See [Atc3TbrStatus.amountAsked]. */
    val amountAsked: String get() = rate?.let { "$it U/h" } ?: "$percent %"

    companion object {

        /** Size of one record frame. */
        const val RECORD_SIZE = 22

        fun decode(frame: Atc3ResponseFrame): Atc3TbrRecord? {
            if (frame.objectType != Atc3Const.ObjectType.TBR_RECORD) return null
            if (frame.raw.size < RECORD_SIZE) return null
            // The mode decides what the rate field means, so it is read before the field is scaled.
            val absolute = frame.u16le(Atc3Const.TbrRecord.MODE) ==
                Atc3Const.TbrPayload.MODE_ABSOLUTE.toInt()
            val amount = frame.u16le(Atc3Const.TbrRecord.RATE)
            return Atc3TbrRecord(
                index = frame.recordIndex,
                startTimestamp = Atc3StatusV1.decodeClock(frame, Atc3Const.TbrRecord.START_CLOCK),
                startUtcSeconds = Atc3StatusV1.decodeClockUtcSeconds(frame, Atc3Const.TbrRecord.START_CLOCK),
                rate = if (absolute) amount * Atc3Const.DOSE_SCALE else null,
                percent = if (absolute) null else amount,
                durationMinutes = frame.u16le(Atc3Const.TbrRecord.DURATION) *
                    Atc3Const.TbrPayload.DURATION_UNIT_MINUTES,
                deliveredUnits = frame.u16le(Atc3Const.TbrRecord.DELIVERED) * Atc3Const.DOSE_SCALE
            )
        }
    }
}
