package app.aaps.pump.atc3.protocol

import app.aaps.pump.atc3.Atc3Const

/**
 * The last temporary basal command the pump accepted, read for the one thing Status V1 does not
 * carry: when it started. It stays the last command after the temporary basal ends; whether one
 * runs, and at what absolute rate, is Status V1's to say.
 */
data class Atc3TbrStatus(
    /** When it started, on the pump's clock, milliseconds. */
    val startTimestamp: Long,
    /** The same start as a UTC-calendar key, see [Atc3StatusV1.decodeClockUtcSeconds]: what tells a renewed temporary basal from the running one. */
    val startUtcSeconds: Long,
    /** The rate asked for, U/h, or null when a percentage was asked for: then there is no rate here. */
    val rate: Double?,
    /** The percentage asked for, or null when a rate was asked for. */
    val percent: Int?,
    /** Minutes it was started for, 0 when none. */
    val durationMinutes: Int,
    /** Delivered so far, U. */
    val deliveredUnits: Double
) {

    /** What was asked for, with its unit, for logs. */
    val amountAsked: String get() = rate?.let { "$it U/h" } ?: "$percent %"

    /**
     * True when the start could belong to a temporary basal running now, on the pump's clock
     * [pumpNow]: an empty or future start is not passed on, since AAPS would drop the whole record.
     */
    fun isStartPlausible(pumpNow: Long): Boolean {
        val age = pumpNow - startTimestamp
        return age > -CLOCK_SLACK_MS && age < Atc3Const.RECONCILE_MAX_AGE_MS
    }

    internal object Offset {

        const val START_CLOCK = 2
        const val START_CLOCK_REPEAT = 9
        const val DURATION = 16
        const val MODE = 15
        const val RATE = 18
        const val DELIVERED = 20
    }

    companion object {

        const val FRAME_SIZE = 28
        private const val CLOCK_BYTES = 6

        /** How far ahead of the pump's clock a start may sit and still be believed, the clocks' measuring noise. */
        private const val CLOCK_SLACK_MS = 60_000L

        /** Which of the two start clocks to read: the second when the first is empty. */
        private fun startClockOffset(frame: Atc3ResponseFrame): Int =
            if ((0 until CLOCK_BYTES).all { frame.byteAt(Offset.START_CLOCK + it) == 0 }) {
                Offset.START_CLOCK_REPEAT
            } else {
                Offset.START_CLOCK
            }

        fun decode(frame: Atc3ResponseFrame): Atc3TbrStatus? {
            if (frame.objectType != Atc3Protocol.ObjectType.TBR_ACTIVE) return null
            // A frame that carries no record is short; its record count says nothing.
            if (frame.raw.size < FRAME_SIZE) return null
            val startClock = startClockOffset(frame)
            // The mode says what the rate field holds, so it is read first.
            val absolute = frame.byteAt(Offset.MODE) ==
                Atc3Protocol.TbrPayload.MODE_ABSOLUTE.toInt()
            val amount = frame.u16le(Offset.RATE)
            return Atc3TbrStatus(
                startTimestamp = Atc3StatusV1.decodeClock(frame, startClock),
                startUtcSeconds = Atc3StatusV1.decodeClockUtcSeconds(frame, startClock),
                rate = if (absolute) amount * Atc3Protocol.DOSE_SCALE else null,
                percent = if (absolute) null else amount,
                durationMinutes = frame.u16le(Offset.DURATION) *
                    Atc3Protocol.TbrPayload.DURATION_UNIT_MINUTES,
                deliveredUnits = frame.u16le(Offset.DELIVERED) * Atc3Protocol.DOSE_SCALE
            )
        }
    }
}
