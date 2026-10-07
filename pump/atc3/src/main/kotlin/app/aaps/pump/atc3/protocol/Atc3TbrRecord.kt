package app.aaps.pump.atc3.protocol

/**
 * One temporary basal the pump has finished, with what it really delivered: the answer to which
 * rates ran between two looks at the pump, including one started and cancelled meanwhile.
 */
data class Atc3TbrRecord(
    /** Position in the answer, 0 the newest. */
    val index: Int,
    /** When it started, on the pump's clock, milliseconds. */
    val startTimestamp: Long,
    /** The same start as a UTC-calendar key, what tells two records of the same rate and length apart. */
    val startUtcSeconds: Long,
    /** U/h, or null when it was started as a percentage. */
    val rate: Double?,
    /** Or null when it was started as a rate. */
    val percent: Int?,
    val durationMinutes: Int,
    /** What it delivered, U, a temporary basal cancelled early included. */
    val deliveredUnits: Double
) {

    /** What it was started at, with its unit, for logs. */
    val amountAsked: String get() = rate?.let { "$it U/h" } ?: "$percent %"

    internal object Offset {

        const val START_CLOCK = 2
        const val MODE = 8
        const val DURATION = 10
        const val RATE = 12
        const val DELIVERED = 14
    }

    companion object {

        const val RECORD_SIZE = 22

        fun decode(frame: Atc3ResponseFrame): Atc3TbrRecord? {
            if (frame.objectType != Atc3Protocol.ObjectType.TBR_RECORD) return null
            if (frame.raw.size < RECORD_SIZE) return null
            // The mode says what the rate field holds, so it is read first.
            val absolute = frame.u16le(Offset.MODE) ==
                Atc3Protocol.TbrPayload.MODE_ABSOLUTE.toInt()
            val amount = frame.u16le(Offset.RATE)
            return Atc3TbrRecord(
                index = frame.recordIndex,
                startTimestamp = Atc3StatusV1.decodeClock(frame, Offset.START_CLOCK),
                startUtcSeconds = Atc3StatusV1.decodeClockUtcSeconds(frame, Offset.START_CLOCK),
                rate = if (absolute) amount * Atc3Protocol.DOSE_SCALE else null,
                percent = if (absolute) null else amount,
                durationMinutes = frame.u16le(Offset.DURATION) *
                    Atc3Protocol.TbrPayload.DURATION_UNIT_MINUTES,
                deliveredUnits = frame.u16le(Offset.DELIVERED) * Atc3Protocol.DOSE_SCALE
            )
        }
    }
}
