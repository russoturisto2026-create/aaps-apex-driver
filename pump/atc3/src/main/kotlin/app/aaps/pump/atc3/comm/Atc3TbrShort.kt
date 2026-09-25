package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const

/**
 * The last temporary basal command in short form, object `0x09`, 16 bytes.
 *
 * The same command object `0x0A` carries, minus the start clock and plus the minutes elapsed. Two
 * things it has that `0x0A` does not:
 *
 * **The rate is always an absolute rate**, percentage mode included, where it holds the rate the
 * percentage worked out to: 111 % against a scheduled 1.000 U/h reads raw 44, that is 1.100 U/h.
 * Object `0x0A` carries the percentage itself and leaves the caller to find the rate elsewhere. So
 * for a percentage temporary basal set on the keypad this object says, on its own, what is actually
 * being delivered.
 *
 * **The elapsed field says how far in it is.** With the duration beside it that gives how much is
 * left, which nothing else states outright.
 *
 * Layout, see [Atc3Const.TbrShort]:
 *
 * ```
 * header | rate (2) | mode (2) | duration (2) | elapsed minutes (2) | crc16
 * ```
 *
 * **It answers in both states, so it is not a "temporary basal is running" flag.** Once nothing
 * runs, duration and elapsed both read zero while the rate keeps its last value. A non-zero
 * duration here agrees with Status V1 offset 53, but it is a second-hand reading of that flag and
 * offset 53 is the authority.
 */
data class Atc3TbrShort(
    /**
     * The rate being delivered, U/h.
     *
     * Absolute in both modes, unlike [Atc3TbrStatus.rate], which is null for a percentage command.
     */
    val rateUnitsPerHour: Double,
    /** True when the command was given as an absolute rate, false when it was a percentage. */
    val absolute: Boolean,
    /** How long it was started for, minutes. Zero once nothing is running. */
    val durationMinutes: Int,
    /** Whole minutes since it started. Zero once nothing is running. */
    val elapsedMinutes: Int
) {

    /**
     * Minutes left to run, or zero when nothing is running.
     *
     * Coerced at zero because the pump can report an elapsed figure that has caught up with the
     * duration in the moment before it clears both.
     */
    val remainingMinutes: Int get() = (durationMinutes - elapsedMinutes).coerceAtLeast(0)

    /**
     * True when the fields describe something still running.
     *
     * **Not to be used as the running flag** — Status V1 offset 53 is the authority, and this is a
     * second-hand reading of it. It is here for logs and for cross-checking that authority, which
     * is the only thing a second source is good for.
     */
    val looksRunning: Boolean get() = durationMinutes > 0

    companion object {

        /** Size of a frame that carries a record. */
        const val FRAME_SIZE = 16

        fun decode(frame: Atc3ResponseFrame): Atc3TbrShort? {
            if (frame.objectType != Atc3Const.ObjectType.TBR_SHORT) return null
            if (frame.raw.size < FRAME_SIZE) return null
            return Atc3TbrShort(
                rateUnitsPerHour = frame.u16le(Atc3Const.TbrShort.RATE) * Atc3Const.DOSE_SCALE,
                absolute = frame.u16le(Atc3Const.TbrShort.MODE) ==
                    Atc3Const.TbrPayload.MODE_ABSOLUTE.toInt(),
                durationMinutes = frame.u16le(Atc3Const.TbrShort.DURATION) *
                    Atc3Const.TbrPayload.DURATION_UNIT_MINUTES,
                elapsedMinutes = frame.u16le(Atc3Const.TbrShort.ELAPSED)
            )
        }
    }
}
