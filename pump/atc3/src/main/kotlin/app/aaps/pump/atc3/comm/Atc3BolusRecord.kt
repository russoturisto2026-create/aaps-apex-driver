package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const

/**
 * The four raw amounts of a bolus record, used as its identity.
 *
 * They are the integers straight off the wire, never the units derived from them: comparing
 * integers needs no tolerance, while comparing doubles that have been multiplied by the dose scale
 * and back would.
 */
data class Atc3BolusFingerprint(
    val rawRequested: Int,
    val rawDelivered: Int,
    val rawExtendedRequested: Int,
    val rawExtendedDelivered: Int
)

/**
 * One bolus history entry, object `0x21` from the periodic search or `0x01` from the full history.
 *
 * ```
 * frame offset 6..11   start clock, six binary bytes, seconds forced to 59
 * frame offset 12..13  immediate amount asked for, uint16, 0.025 U per raw unit
 * frame offset 14..15  immediate amount actually delivered, uint16
 * frame offset 16..17  extended amount asked for, uint16
 * frame offset 18..19  extended amount actually delivered, uint16
 * ```
 *
 * A bolus that ran to completion carries the same value in the asked and delivered fields; one
 * that was cut short carries what it really delivered in the second. Keeping the two apart is the
 * whole point: recording the requested amount for a bolus that was cut short would tell AAPS that
 * insulin was delivered which never left the pump.
 *
 * A standard bolus leaves the extended pair zero and an extended bolus leaves the immediate pair
 * zero, so reading only the immediate pair would record nothing at all for an extended bolus given
 * on the pump.
 */
data class Atc3BolusRecord(
    /** Position within one answer, 0 is the most recent. Never an identity, the pump reuses it. */
    val index: Int,
    /**
     * The minute the bolus **started**, at second 59, on the pump clock, milliseconds since the
     * epoch, local calendar.
     *
     * The start, not the end: the pump stamps the record with the minute the bolus began, the
     * minute of its answer `A1/55` to the command, and forces the seconds to 59. A bolus cut short
     * by an alarm is written later, hours later if the alarm stands that long, and still carries
     * its start.
     *
     * The minute is what a record is matched to a bolus of ours on, together with the dose asked
     * for ([app.aaps.pump.atc3.history.Atc3BolusReconciler]).
     */
    val timestamp: Long,
    /**
     * The same stamp as whole seconds read through a UTC calendar.
     *
     * This is an identity key, not a time. It has to stay put when the phone changes timezone or
     * enters daylight saving, otherwise every stored record would look new and be imported twice.
     */
    val pumpClockUtcSeconds: Long,
    val rawRequested: Int,
    val rawDelivered: Int,
    val rawExtendedRequested: Int = 0,
    val rawExtendedDelivered: Int = 0
) {

    /** Amount the pump was asked for as an immediate bolus, units. */
    val requestedUnits: Double get() = rawRequested * Atc3Const.DOSE_SCALE

    /** Amount the pump reports as actually delivered immediately, units. */
    val deliveredUnits: Double get() = rawDelivered * Atc3Const.DOSE_SCALE

    /** Amount asked for as an extended part, units, zero for a standard bolus. */
    val extendedRequestedUnits: Double get() = rawExtendedRequested * Atc3Const.DOSE_SCALE

    /** Amount the pump reports as delivered by the extended part, units. */
    val extendedDeliveredUnits: Double get() = rawExtendedDelivered * Atc3Const.DOSE_SCALE

    /** Everything this record asked for, immediate and extended together, units. */
    val totalRequestedUnits: Double get() = requestedUnits + extendedRequestedUnits

    /** Everything this record actually delivered, immediate and extended together, units. */
    val totalDeliveredUnits: Double get() = deliveredUnits + extendedDeliveredUnits

    /**
     * What AAPS is told this bolus delivered, units.
     *
     * Immediate and extended parts are added together and recorded as one ordinary bolus. The
     * record carries no duration for the extended part, so an AAPS extended bolus cannot be
     * reconstructed from it, and recording only the immediate half would hide real insulin and let
     * AAPS dose on top of it. Front loading the timing of an extended bolus overstates current IOB
     * for a while, which is the safer of the two errors.
     */
    val aapsUnits: Double get() = totalDeliveredUnits

    /** True when the record carries an extended part, that is an extended or a dual bolus. */
    val carriesExtendedPart: Boolean get() = rawExtendedRequested != 0 || rawExtendedDelivered != 0

    /** True when every amount is zero, so there is nothing to record. */
    val isEmptyRecord: Boolean
        get() = rawRequested == 0 && rawDelivered == 0 && rawExtendedRequested == 0 && rawExtendedDelivered == 0

    /** True when the pump delivered less than was asked for. */
    val isIncomplete: Boolean get() = totalDeliveredUnits < totalRequestedUnits - HALF_STEP

    val fingerprint: Atc3BolusFingerprint
        get() = Atc3BolusFingerprint(rawRequested, rawDelivered, rawExtendedRequested, rawExtendedDelivered)

    companion object {

        private const val TIMESTAMP_OFFSET = 6
        private const val REQUESTED_OFFSET = 12
        private const val DELIVERED_OFFSET = 14
        private const val EXTENDED_REQUESTED_OFFSET = 16
        private const val EXTENDED_DELIVERED_OFFSET = 18

        /** Smallest difference worth calling a shortfall rather than rounding. */
        private const val HALF_STEP = Atc3Const.DOSE_SCALE / 2

        const val FRAME_SIZE = 22

        /**
         * Decode a bolus record, or return null when the frame is not one.
         *
         * Both objects that carry this layout are accepted: `0x21`, the periodic latest bolus
         * search, and `0x01`, the full history. Their records are identical byte for byte.
         */
        fun decode(frame: Atc3ResponseFrame): Atc3BolusRecord? {
            if (frame.objectType != Atc3Const.ObjectType.LATEST_BOLUS &&
                frame.objectType != Atc3Const.ObjectType.BOLUS_RECORD
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
 * One whole answer to a bolus history read.
 *
 * [records] are the records of a single burst, in arrival order, and [recordCount] is the count
 * byte those frames carried.
 *
 * That count is what the pump holds, not what it sent: the periodic `0x21` search stops at ten
 * frames while still counting everything stored. Comparing the two is therefore how the driver
 * learns that records have aged out of the search and that the full history is worth asking for,
 * see [app.aaps.pump.atc3.history.Atc3BolusReconciler.recordsMissing].
 */
data class Atc3BolusHistory(
    val records: List<Atc3BolusRecord>,
    val recordCount: Int
)
