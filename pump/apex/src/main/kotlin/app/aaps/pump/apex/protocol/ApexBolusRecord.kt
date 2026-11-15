package app.aaps.pump.apex.protocol

/**
 * One bolus record of the pump's history. Asked and delivered amounts are kept apart, so that a
 * bolus cut short is recorded at what it delivered; an extended part is read as well, so that an
 * extended bolus given on the pump is not lost.
 *
 * A broken answer. A cell read while the pump writes it comes with a valid CRC and zeros from some
 * byte to the end: the whole record but byte 5 of the stamp (always 0x3B), once with the year left,
 * once with the whole stamp and both amounts zero, once with the stamp and the asked amount left
 * and the delivered amount zero. Told by [looksBroken]; such an answer is read again, see
 * `BrokenRecordDeep`. The next read of the cell shows the record.
 */
data class ApexBolusRecord(
    /** Position in the answer, 0 the newest; reused by the pump, never an identity. */
    val index: Int,
    /**
     * The pump's stamp: its wall clock as a key, see [ApexStamp]. The minute is
     * the minute the bolus started; the seconds are the record's place in that minute, not a time.
     */
    val clockSeconds: Long,
    val rawRequested: Int,
    val rawDelivered: Int,
    val rawExtendedRequested: Int = 0,
    val rawExtendedDelivered: Int = 0,
    /** True when the stamp has no date: the month or the day byte is zero. */
    val dateBroken: Boolean = false
) {

    /** Asked for as an immediate bolus, U. */
    val requestedUnits: Double get() = rawRequested * ApexProtocol.DOSE_SCALE

    /** Delivered immediately, U. */
    val deliveredUnits: Double get() = rawDelivered * ApexProtocol.DOSE_SCALE

    /** Asked for as an extended part, U; zero for a standard bolus. */
    val extendedRequestedUnits: Double get() = rawExtendedRequested * ApexProtocol.DOSE_SCALE

    /** Delivered by the extended part, U. */
    val extendedDeliveredUnits: Double get() = rawExtendedDelivered * ApexProtocol.DOSE_SCALE

    val totalDeliveredUnits: Double get() = deliveredUnits + extendedDeliveredUnits

    /** True for an extended or a dual bolus. */
    val carriesExtendedPart: Boolean get() = rawExtendedRequested != 0 || rawExtendedDelivered != 0

    /**
     * True for a record that may be a cell the pump read wrongly: no date, nothing asked for, or
     * nothing delivered. A bolus that delivered nothing leaves no record, so a record of one is a
     * cell cut by zeros after the asked amount. A broken record is told by one thing only: it does
     * not come again. The answer is asked once more, see `PictureReader.readJournal`; a record that
     * repeats is a real one (a cancel sent with no bolus running leaves "asked 0, delivered 0.05" in
     * the journal).
     */
    val looksBroken: Boolean
        get() = dateBroken || rawRequested == 0 && rawExtendedRequested == 0 || rawDelivered == 0 && rawExtendedDelivered == 0

    companion object {

        private const val TIMESTAMP_OFFSET = 6
        private const val REQUESTED_OFFSET = 12
        private const val DELIVERED_OFFSET = 14
        private const val EXTENDED_REQUESTED_OFFSET = 16
        private const val EXTENDED_DELIVERED_OFFSET = 18
        const val FRAME_SIZE = 22

        /** The record, or null when the frame is not one; the periodic search and the full history carry the same layout. */
        fun decode(frame: ApexResponseFrame): ApexBolusRecord? {
            if (frame.objectType != ApexProtocol.HistoryOpcode.LATEST_BOLUS &&
                frame.objectType != ApexProtocol.HistoryOpcode.BOLUS_HISTORY
            ) return null
            if (frame.raw.size < FRAME_SIZE) return null
            val clockOffset = TIMESTAMP_OFFSET - ApexResponseFrame.DATA_BASE_OFFSET
            return ApexBolusRecord(
                index = frame.recordIndex,
                clockSeconds = ApexStamp.clockSeconds(frame, clockOffset),
                rawRequested = u16(frame, REQUESTED_OFFSET),
                rawDelivered = u16(frame, DELIVERED_OFFSET),
                rawExtendedRequested = u16(frame, EXTENDED_REQUESTED_OFFSET),
                rawExtendedDelivered = u16(frame, EXTENDED_DELIVERED_OFFSET),
                dateBroken = frame.raw[TIMESTAMP_OFFSET + 1] == 0.toByte() || frame.raw[TIMESTAMP_OFFSET + 2] == 0.toByte()
            )
        }

        private fun u16(frame: ApexResponseFrame, frameOffset: Int): Int =
            (frame.raw[frameOffset].toInt() and 0xFF) or ((frame.raw[frameOffset + 1].toInt() and 0xFF) shl 8)
    }
}

