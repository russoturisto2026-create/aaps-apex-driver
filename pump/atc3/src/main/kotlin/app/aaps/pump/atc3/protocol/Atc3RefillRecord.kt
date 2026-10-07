package app.aaps.pump.atc3.protocol

/**
 * One reservoir refill or catheter prime. A prime's insulin is recorded nowhere else, and a refill
 * here is what tells a real one from a reservoir level that merely went up. A refill of 0 U is real.
 */
data class Atc3RefillRecord(
    /** Position in the answer, 0 the newest. */
    val index: Int,
    /** When it happened, on the pump's clock, milliseconds. */
    val timestamp: Long,
    /** The same moment as a UTC-calendar key. */
    val utcSeconds: Long,
    /** How much went in, U. */
    val amountUnits: Double,
    val type: Int
) {

    val isPrime: Boolean get() = type == Offset.TYPE_PRIME

    val isManualRefill: Boolean get() = type == Offset.TYPE_MANUAL

    /** What it was, for logs. */
    val typeText: String
        get() = when (type) {
            Offset.TYPE_PRIME  -> "catheter prime"
            Offset.TYPE_MANUAL -> "manual refill"
            else                         -> "type $type"
        }

    internal object Offset {

        const val REFILL_CLOCK = 2
        const val AMOUNT = 8
        const val TYPE = 10
        const val TYPE_PRIME = 0
        const val TYPE_MANUAL = 1
    }

    companion object {

        const val RECORD_SIZE = 18

        fun decode(frame: Atc3ResponseFrame): Atc3RefillRecord? {
            if (frame.objectType != Atc3Protocol.ObjectType.REFILL_RECORD) return null
            if (frame.raw.size < RECORD_SIZE) return null
            return Atc3RefillRecord(
                index = frame.recordIndex,
                timestamp = Atc3StatusV1.decodeClock(frame, Offset.REFILL_CLOCK),
                utcSeconds = Atc3StatusV1.decodeClockUtcSeconds(frame, Offset.REFILL_CLOCK),
                amountUnits = frame.u16le(Offset.AMOUNT) * Atc3Protocol.DOSE_SCALE,
                type = frame.u16le(Offset.TYPE)
            )
        }
    }
}
