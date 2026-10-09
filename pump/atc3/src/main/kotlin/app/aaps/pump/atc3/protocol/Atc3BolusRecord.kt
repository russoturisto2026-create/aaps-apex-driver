package app.aaps.pump.atc3.protocol

/**
 * One bolus record of the pump's history. Asked and delivered amounts are kept apart, so that a
 * bolus cut short is recorded at what it delivered; an extended part is read as well, so that an
 * extended bolus given on the pump is not lost.
 */
data class Atc3BolusRecord(
    /** Position in the answer, 0 the newest; reused by the pump, never an identity. */
    val index: Int,
    /**
     * The pump's stamp on the phone's time, milliseconds: the minute the bolus started, with the
     * seconds being the record's place in that minute, not a time. The row is dated by the minute,
     * see [app.aaps.pump.atc3.history.Atc3BolusReconciler].
     */
    val timestamp: Long,
    /** The same stamp as a UTC-calendar key, see [Atc3StatusV1.decodeClockUtcSeconds]. */
    val pumpClockUtcSeconds: Long,
    val rawRequested: Int,
    val rawDelivered: Int,
    val rawExtendedRequested: Int = 0,
    val rawExtendedDelivered: Int = 0
) {

    /** Asked for as an immediate bolus, U. */
    val requestedUnits: Double get() = rawRequested * Atc3Protocol.DOSE_SCALE

    /** Delivered immediately, U. */
    val deliveredUnits: Double get() = rawDelivered * Atc3Protocol.DOSE_SCALE

    /** Asked for as an extended part, U; zero for a standard bolus. */
    val extendedRequestedUnits: Double get() = rawExtendedRequested * Atc3Protocol.DOSE_SCALE

    /** Delivered by the extended part, U. */
    val extendedDeliveredUnits: Double get() = rawExtendedDelivered * Atc3Protocol.DOSE_SCALE

    val totalRequestedUnits: Double get() = requestedUnits + extendedRequestedUnits

    val totalDeliveredUnits: Double get() = deliveredUnits + extendedDeliveredUnits

    /** True for an extended or a dual bolus. */
    val carriesExtendedPart: Boolean get() = rawExtendedRequested != 0 || rawExtendedDelivered != 0

    /** True when every amount is zero: nothing to record. */
    val isEmptyRecord: Boolean
        get() = rawRequested == 0 && rawDelivered == 0 && rawExtendedRequested == 0 && rawExtendedDelivered == 0

    companion object {

        private const val TIMESTAMP_OFFSET = 6
        private const val REQUESTED_OFFSET = 12
        private const val DELIVERED_OFFSET = 14
        private const val EXTENDED_REQUESTED_OFFSET = 16
        private const val EXTENDED_DELIVERED_OFFSET = 18
        const val FRAME_SIZE = 22

        /** The record, or null when the frame is not one; the periodic search and the full history carry the same layout. */
        fun decode(frame: Atc3ResponseFrame): Atc3BolusRecord? {
            if (frame.objectType != Atc3Protocol.ObjectType.LATEST_BOLUS &&
                frame.objectType != Atc3Protocol.ObjectType.BOLUS_RECORD
            ) return null
            if (frame.raw.size < FRAME_SIZE) return null
            val clockOffset = TIMESTAMP_OFFSET - Atc3ResponseFrame.DATA_BASE_OFFSET
            return Atc3BolusRecord(
                index = frame.recordIndex,
                timestamp = Atc3StatusV1.decodeClock(frame, clockOffset),
                pumpClockUtcSeconds = Atc3StatusV1.decodeClockUtcSeconds(frame, clockOffset),
                rawRequested = u16(frame, REQUESTED_OFFSET),
                rawDelivered = u16(frame, DELIVERED_OFFSET),
                rawExtendedRequested = u16(frame, EXTENDED_REQUESTED_OFFSET),
                rawExtendedDelivered = u16(frame, EXTENDED_DELIVERED_OFFSET)
            )
        }

        private fun u16(frame: Atc3ResponseFrame, frameOffset: Int): Int =
            (frame.raw[frameOffset].toInt() and 0xFF) or ((frame.raw[frameOffset + 1].toInt() and 0xFF) shl 8)
    }
}

/**
 * One answer to a bolus history read: its records in arrival order, and the record count the pump
 * declared. A count above the records sent says the answer is the newest part only, see
 * [app.aaps.pump.atc3.history.Atc3BolusReconciler.recordsMissing].
 */
data class Atc3BolusHistory(
    val records: List<Atc3BolusRecord>,
    val recordCount: Int
)
