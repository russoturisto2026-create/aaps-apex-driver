package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const

/**
 * One change to the effective basal schedule, object `0x02`, 110 bytes.
 *
 * Each record carries the whole schedule as it stood right after the change, not the difference.
 * Not one record per day either: a single day can carry several.
 *
 * **This is the only trace an edit to the rates of the profile in use leaves anywhere.** Status V1
 * says which profile is active; object `0x08` says what the profiles hold now. Neither says that
 * somebody changed the one being used — and a driver that re-reads the profiles only when the
 * active *index* changes will go on comparing against a copy that is no longer true. This object,
 * with its timestamp, is what says when that happened.
 *
 * Layout:
 *
 * ```
 * header, 110 bytes | change timestamp (6) | 48 rates, uint16 each (96) | crc16
 * ```
 *
 * The daily dose of such an entry is not stored: it is the schedule's own total, which is what
 * [dailyTotalUnits] works out.
 */
data class Atc3BasalChangeRecord(
    /** Zero based position of this record in the answer. */
    val index: Int,
    /** When the schedule changed, on the pump clock, milliseconds, local calendar. */
    val timestamp: Long,
    /** The same moment as whole seconds through a UTC calendar, an identity rather than a time. */
    val utcSeconds: Long,
    /** The 48 half hour rates as they stood right after the change, U/h. */
    val rates: DoubleArray
) {

    /**
     * What the schedule delivers in a day, units.
     *
     * Each rate stands for half an hour, so the sum is halved. The pump does not store this figure.
     */
    val dailyTotalUnits: Double get() = rates.sum() / 2.0

    /** The rate in force at a moment of the day, U/h. */
    fun rateAt(secondsFromMidnight: Int): Double =
        rates[(secondsFromMidnight / Atc3Const.BASAL_SLOT_SECONDS).coerceIn(0, Atc3Const.BASAL_SLOTS - 1)]

    // A DoubleArray field makes the generated equals compare references, which would make two
    // records with identical schedules unequal. Both are written out so the class behaves the way
    // its callers expect of a data class.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Atc3BasalChangeRecord) return false
        return index == other.index &&
            timestamp == other.timestamp &&
            utcSeconds == other.utcSeconds &&
            rates.contentEquals(other.rates)
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + utcSeconds.hashCode()
        result = 31 * result + rates.contentHashCode()
        return result
    }

    companion object {

        /** Size of one record frame. */
        const val RECORD_SIZE = 110

        fun decode(frame: Atc3ResponseFrame): Atc3BasalChangeRecord? {
            if (frame.objectType != Atc3Const.ObjectType.BASAL_CHANGE_RECORD) return null
            if (frame.raw.size < RECORD_SIZE) return null
            val rates = DoubleArray(Atc3Const.BASAL_SLOTS) { slot ->
                frame.u16le(Atc3Const.BasalChange.FIRST_RATE + 2 * slot) * Atc3Const.DOSE_SCALE
            }
            return Atc3BasalChangeRecord(
                index = frame.recordIndex,
                timestamp = Atc3StatusV1.decodeClock(frame, Atc3Const.BasalChange.CHANGE_CLOCK),
                utcSeconds = Atc3StatusV1.decodeClockUtcSeconds(frame, Atc3Const.BasalChange.CHANGE_CLOCK),
                rates = rates
            )
        }
    }
}
