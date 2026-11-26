package app.aaps.pump.apex.protocol

/**
 * One bolus record of the pump's history. Asked and delivered amounts are kept apart, so that a
 * bolus cut short is recorded at what it delivered; an extended part is read as well, so that an
 * extended bolus given on the pump is not lost.
 *
 * A broken answer. A cell read while the pump writes it may come with a valid CRC and zeros from some
 * byte to the end: so the date may be gone, or the amounts, or both. Told by [flickers]; such an answer
 * is read again, see `BrokenRecord`. The next read of the cell shows the record.
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

    /**
     * True when a part of the bolus delivered more than it asked for, rounded up to the pump's step.
     * An app may ask for a dose that is not a whole number of steps: the pump gives it rounded up to
     * its step and records what it gave (asked 2.625 U, delivered 2.65 U). Above that the pump never
     * gives. A faulty cell may hold more: a new stamp over the amounts of an old record, so the asked and
     * the delivered amount come from two boluses. A part that asked for nothing is left out: a cancel sent with no bolus running
     * leaves "asked 0, delivered 0.05", see [flickers].
     */
    val deliveredAboveAsked: Boolean
        get() = rawRequested != 0 && rawDelivered > roundedUpToStep(rawRequested) ||
            rawExtendedRequested != 0 && rawExtendedDelivered > roundedUpToStep(rawExtendedRequested)

    /** True for an extended or a dual bolus. */
    val carriesExtendedPart: Boolean get() = rawExtendedRequested != 0 || rawExtendedDelivered != 0

    /**
     * True for a record that looks flickering: a cell read while the pump wrote it, zeros from some byte to
     * the end, so no date or nothing delivered. A bolus that delivered nothing leaves no record. It is told by
     * one thing only: it does not come again, so the answer is read again, see `ReadAgain.journal`. One that
     * comes the same at every read is a faulty cell, see `FaultyCell.implausible`. A record that asked
     * nothing but delivered (a cancel sent with no bolus running leaves "asked 0, delivered 0.05") is a real one.
     */
    val flickers: Boolean
        get() = dateBroken || rawDelivered == 0 && rawExtendedDelivered == 0

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

        /** Below 1 U the pump's step is 0.025 U, up to 15 U it is 0.05 U, above that 0.1 U; in raw units of 0.025 U. */
        private const val ONE_UNIT_RAW = 40
        private const val FIFTEEN_UNITS_RAW = 600

        /** [raw] rounded up to the step the pump gives a dose of that size in. */
        fun roundedUpToStep(raw: Int): Int {
            val step = when {
                raw < ONE_UNIT_RAW       -> 1
                raw <= FIFTEEN_UNITS_RAW -> 2
                else                     -> 4
            }
            return (raw + step - 1) / step * step
        }

        private fun u16(frame: ApexResponseFrame, frameOffset: Int): Int =
            (frame.raw[frameOffset].toInt() and 0xFF) or ((frame.raw[frameOffset + 1].toInt() and 0xFF) shl 8)
    }
}

