package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const

/**
 * One basal profile as stored in the pump: 48 half hour rates.
 *
 * The pump answers a profile read with one frame per stored profile, each 104 bytes:
 *
 * ```
 * offset 0     0xAA
 * offset 1     104
 * offset 2     8, the number of profiles being sent
 * offset 3     0xA3
 * offset 4     0x08
 * offset 5     profile index, 0..7
 * offset 6..101 48 rates, uint16 little endian, 0.025 U/h per raw unit
 * offset 102..103 CRC
 * ```
 */
data class Atc3BasalProfile(
    /** Profile slot in the pump, 0 based. */
    val index: Int,
    /** 48 half hour rates in U/h, slot 0 starting at midnight. */
    val rates: DoubleArray
) {

    /** Total insulin the profile delivers over a day, in units. */
    val dailyUnits: Double get() = rates.sum() * 0.5

    /** Rate scheduled for the slot covering [secondsFromMidnight]. */
    fun rateAt(secondsFromMidnight: Int): Double =
        rates[(secondsFromMidnight / Atc3Const.BASAL_SLOT_SECONDS).coerceIn(0, Atc3Const.BASAL_SLOTS - 1)]

    override fun equals(other: Any?): Boolean =
        this === other || (other is Atc3BasalProfile && index == other.index && rates.contentEquals(other.rates))

    override fun hashCode(): Int = 31 * index + rates.contentHashCode()

    companion object {

        /** Frame size of a profile answer: header, index, 48 rates and CRC. */
        const val FRAME_SIZE = 6 + 2 * Atc3Const.BASAL_SLOTS + 2

        private const val RATES_OFFSET = 6

        /** Decode a profile frame, or return null when it is not one. */
        fun decode(frame: Atc3ResponseFrame): Atc3BasalProfile? {
            if (frame.objectType != Atc3Const.ReadOpcode.BASAL_PROFILES) return null
            if (frame.raw.size < FRAME_SIZE) return null
            val rates = DoubleArray(Atc3Const.BASAL_SLOTS) { slot ->
                val base = RATES_OFFSET + 2 * slot
                val raw = (frame.raw[base].toInt() and 0xFF) or ((frame.raw[base + 1].toInt() and 0xFF) shl 8)
                raw * Atc3Const.DOSE_SCALE
            }
            return Atc3BasalProfile(frame.recordIndex, rates)
        }
    }
}
