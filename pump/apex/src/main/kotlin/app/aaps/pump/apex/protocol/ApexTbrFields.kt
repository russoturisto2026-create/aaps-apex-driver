package app.aaps.pump.apex.protocol

/** What the three temporary basal frames tell alike: the last command, the last finished one and a journal record. */
internal object ApexTbrFields {

    private const val CLOCK_BYTES = 6

    /** The rate of a temporary basal asked as one, U/h; null when it was asked as a percentage. */
    fun rate(absolute: Boolean, amount: Int): Double? = if (absolute) amount * ApexProtocol.DOSE_SCALE else null

    /** The percentage of a temporary basal asked as one; null when it was asked as a rate. */
    fun percent(absolute: Boolean, amount: Int): Int? = if (absolute) null else amount

    /** Minutes of a duration given in the pump's steps. */
    fun minutes(steps: Int): Int = steps * ApexProtocol.TbrPayload.DURATION_UNIT_MINUTES

    /** What was asked for, with its unit, for logs. */
    fun amountText(rate: Double?, percent: Int?): String = rate?.let { "$it U/h" } ?: "$percent %"

    /** Which of a frame's two start clocks to read: the one at [repeat] when the one at [first] is empty. */
    fun startClockOffset(frame: ApexResponseFrame, first: Int, repeat: Int): Int =
        if ((0 until CLOCK_BYTES).all { frame.byteAt(first + it) == 0 }) repeat else first

    /** True when every byte between the frame head and the CRC is zero: the pump's "nothing yet" answer. */
    fun payloadIsZeros(frame: ApexResponseFrame): Boolean =
        (ApexResponseFrame.INDEX_OFFSET + 1 until frame.raw.size - ApexResponseFrame.CRC_BYTES).all { frame.raw[it] == 0.toByte() }
}
