package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const

/**
 * One reservoir refill, object `0x04`, 18 bytes.
 *
 * **The only place the date a reservoir was started is reported.** Neither status frame carries it:
 * the refill and priming procedure itself happens on the pump, so only its result is reported.
 *
 * Two things make it worth reading.
 *
 * **A catheter prime is recorded here and nowhere else.** The insulin a prime uses appears in
 * neither the bolus history nor the bolus field of the daily statistics, and it does leave the
 * reservoir. Any accounting that adds up delivery from the bolus history alone is short by every
 * prime — and to the reservoir arithmetic a prime looks exactly like insulin somebody delivered
 * without telling the loop.
 *
 * **It tells a real refill from a level that merely went up.** A plunger drawn back without a
 * refill raises the reservoir figure just the same. Only this object says whether anything was
 * actually put in.
 *
 * Layout, see [Atc3Const.Refill]:
 *
 * ```
 * header, 18 bytes | clock (6) | amount (2) | type (2) | crc16
 * ```
 *
 * **An amount of zero is a real manual refill of nothing**, not a broken record.
 */
data class Atc3RefillRecord(
    /** Zero based position of this record in the answer. */
    val index: Int,
    /** When it happened, on the pump clock, milliseconds, local calendar. */
    val timestamp: Long,
    /** The same moment as whole seconds through a UTC calendar, an identity rather than a time. */
    val utcSeconds: Long,
    /** How much went in, units. Zero is a real answer. */
    val amountUnits: Double,
    /** Raw type field, [Atc3Const.Refill.TYPE_PRIME] or [Atc3Const.Refill.TYPE_MANUAL]. */
    val type: Int
) {

    /** A catheter prime, whose insulin is recorded in no other object. */
    val isPrime: Boolean get() = type == Atc3Const.Refill.TYPE_PRIME

    /** A manual reservoir refill. */
    val isManualRefill: Boolean get() = type == Atc3Const.Refill.TYPE_MANUAL

    /** What it was, in words, for logs and traces. */
    val typeText: String
        get() = when (type) {
            Atc3Const.Refill.TYPE_PRIME  -> "catheter prime"
            Atc3Const.Refill.TYPE_MANUAL -> "manual refill"
            else                         -> "type $type"
        }

    companion object {

        /** Size of one record frame. */
        const val RECORD_SIZE = 18

        fun decode(frame: Atc3ResponseFrame): Atc3RefillRecord? {
            if (frame.objectType != Atc3Const.ObjectType.REFILL_RECORD) return null
            if (frame.raw.size < RECORD_SIZE) return null
            return Atc3RefillRecord(
                index = frame.recordIndex,
                timestamp = Atc3StatusV1.decodeClock(frame, Atc3Const.Refill.REFILL_CLOCK),
                utcSeconds = Atc3StatusV1.decodeClockUtcSeconds(frame, Atc3Const.Refill.REFILL_CLOCK),
                amountUnits = frame.u16le(Atc3Const.Refill.AMOUNT) * Atc3Const.DOSE_SCALE,
                type = frame.u16le(Atc3Const.Refill.TYPE)
            )
        }
    }
}
