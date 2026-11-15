package app.aaps.pump.apex.protocol

/**
 * The last temporary basal command the pump accepted, read for the one thing Status V1 does not
 * carry: when it started. It stays the last command after the temporary basal ends; whether one
 * runs, and at what absolute rate, is Status V1's to say. The start is the status snapshot time
 * when the command came: the start of its minute, or the second of an event in it, never the
 * second of the command itself.
 *
 * A pump that has had no temporary basal command yet, as after a reset, answers with the whole
 * frame of zeros: [decode] returns null for it.
 */
class ApexTbrStatus(
    /** When it started: the pump's wall clock as a key, see [ApexStamp]. What tells a renewed temporary basal from the running one. */
    val startClockSeconds: Long,
    /** The rate asked for, U/h, or null when a percentage was asked for: then there is no rate here. */
    val rate: Double?,
    /** The percentage asked for, or null when a rate was asked for. */
    val percent: Int?,
    /** Minutes it was started for, 0 when none. */
    val durationMinutes: Int,
    /** Delivered so far, U. */
    val deliveredUnits: Double,
    /** The frame as the pump sent it, for the store. */
    val raw: ByteArray
) {

    /** What was asked for, with its unit, for logs. */
    val amountAsked: String get() = ApexTbrFields.amountText(rate, percent)


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

        /** The whole frame of zeros: the pump has had no temporary basal command since a reset. */
        fun isNone(frame: ApexResponseFrame): Boolean =
            frame.objectType == ApexProtocol.ReadOpcode.TBR_ACTIVE && frame.raw.size >= FRAME_SIZE && ApexTbrFields.payloadIsZeros(frame)

        fun decode(frame: ApexResponseFrame): ApexTbrStatus? {
            if (frame.objectType != ApexProtocol.ReadOpcode.TBR_ACTIVE) return null
            // A frame that carries no record is short; its record count says nothing.
            if (frame.raw.size < FRAME_SIZE || ApexTbrFields.payloadIsZeros(frame)) return null
            val startClock = ApexTbrFields.startClockOffset(frame, Offset.START_CLOCK, Offset.START_CLOCK_REPEAT)
            // The mode says what the rate field holds, so it is read first.
            val absolute = frame.byteAt(Offset.MODE) ==
                ApexProtocol.TbrPayload.MODE_ABSOLUTE.toInt()
            val amount = frame.u16le(Offset.RATE)
            return ApexTbrStatus(
                startClockSeconds = ApexStamp.clockSeconds(frame, startClock),
                rate = ApexTbrFields.rate(absolute, amount),
                percent = ApexTbrFields.percent(absolute, amount),
                durationMinutes = ApexTbrFields.minutes(frame.u16le(Offset.DURATION)),
                deliveredUnits = frame.u16le(Offset.DELIVERED) * ApexProtocol.DOSE_SCALE,
                raw = frame.raw
            )
        }
    }
}

/** The pump's answer to the read of the last temporary basal command: [command] null when it has had none since a reset. */
class ApexLastTbrCommand(val command: ApexTbrStatus?, val raw: ByteArray)
