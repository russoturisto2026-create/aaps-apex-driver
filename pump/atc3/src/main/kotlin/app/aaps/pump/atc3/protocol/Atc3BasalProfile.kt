package app.aaps.pump.atc3.protocol

/** One basal profile stored in the pump: 48 half hour rates. */
data class Atc3BasalProfile(
    /** Slot in the pump, 0 based. */
    val index: Int,
    /** U/h, slot 0 starting at midnight. */
    val rates: DoubleArray
) {

    /** U per day. */
    val dailyUnits: Double get() = rates.sum() * 0.5

    /** Rate scheduled for the slot covering [secondsFromMidnight]. */
    fun rateAt(secondsFromMidnight: Int): Double =
        rates[(secondsFromMidnight / Atc3Protocol.BASAL_SLOT_SECONDS).coerceIn(0, Atc3Protocol.BASAL_SLOTS - 1)]

    override fun equals(other: Any?): Boolean =
        this === other || (other is Atc3BasalProfile && index == other.index && rates.contentEquals(other.rates))

    override fun hashCode(): Int = 31 * index + rates.contentHashCode()

    companion object {

        const val FRAME_SIZE = 6 + 2 * Atc3Protocol.BASAL_SLOTS + 2
        private const val RATES_OFFSET = 6

        /** The profile, or null when the frame is not one. */
        fun decode(frame: Atc3ResponseFrame): Atc3BasalProfile? {
            if (frame.objectType != Atc3Protocol.ReadOpcode.BASAL_PROFILES) return null
            if (frame.raw.size < FRAME_SIZE) return null
            val rates = DoubleArray(Atc3Protocol.BASAL_SLOTS) { slot ->
                val base = RATES_OFFSET + 2 * slot
                val raw = (frame.raw[base].toInt() and 0xFF) or ((frame.raw[base + 1].toInt() and 0xFF) shl 8)
                raw * Atc3Protocol.DOSE_SCALE
            }
            return Atc3BasalProfile(frame.recordIndex, rates)
        }
    }
}
