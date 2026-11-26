package app.aaps.pump.apex.protocol

/**
 * One day of the pump's daily statistics: what it delivered that day as boluses, as scheduled
 * basal and under temporary basals. The three add up to the pump's count of insulin delivered
 * today in Status V1, and this is the only place where that count is split. The newest day comes
 * first. Read at every heartbeat, two answers in a row tell how much went in between them, by kind.
 *
 * A day with no date is not a broken cell: the pump keeps days counted while its clock was unset,
 * and they come the same at every read, moving down the ring as days pass. A read made again tells
 * them from a broken cell, see `BrokenRecord`.
 */
data class ApexDailyRecord(
    /** Position in the answer, 0 the newest day. */
    val index: Int,
    /** Given as boluses this day, U. Grows as a bolus is delivered, not when it is ordered. */
    val bolusUnits: Double,
    /** Given as scheduled basal this day, U. Stands still while a temporary basal runs. */
    val basalUnits: Double,
    /** Given under temporary basals this day, U: all of it, not the difference to the schedule. */
    val tbrUnits: Double,
    /** The day: the pump's calendar date as a key, seconds of its midnight on a UTC calendar. */
    val dayClockSeconds: Long
) {

    /** The three kinds together: what Status V1 counts as delivered today. */
    val totalUnits: Double get() = bolusUnits + basalUnits + tbrUnits

    /** False for a day the pump keeps with no date: three zero bytes read as a day before the year 2000. */
    val hasDate: Boolean get() = dayClockSeconds >= YEAR_2000_SECONDS

    internal object Offset {

        const val BOLUS = 2
        const val BASAL = 4
        const val TBR = 6
        const val DATE = 8
    }

    companion object {

        const val RECORD_SIZE = 18

        /** 01.01.2000 00:00 UTC, seconds. */
        private const val YEAR_2000_SECONDS = 946_684_800L

        /** The newest-days read and the whole journal name the same record under different objects. */
        fun decode(frame: ApexResponseFrame): ApexDailyRecord? {
            val type = frame.objectType
            if (type != ApexProtocol.HistoryOpcode.LATEST_DAILY_STATS && type != ApexProtocol.HistoryOpcode.DAILY_STATS) return null
            if (frame.raw.size < RECORD_SIZE) return null
            return ApexDailyRecord(
                index = frame.recordIndex,
                bolusUnits = frame.u16le(Offset.BOLUS) * ApexProtocol.DOSE_SCALE,
                basalUnits = frame.u16le(Offset.BASAL) * ApexProtocol.DOSE_SCALE,
                tbrUnits = frame.u16le(Offset.TBR) * ApexProtocol.DOSE_SCALE,
                dayClockSeconds = ApexStamp.daySeconds(frame, Offset.DATE)
            )
        }

        /**
         * Where a day kept from an earlier answer stands in [newestFirst]: the day of its date. When
         * the pump lost its power, the day it was counting lost its date, and a new day of the same
         * date started from nothing above it. Such a new day holds less than the kept one in some
         * field, and the kept day goes on in the day with no date right under it, which holds at
         * least the kept fields. Null when the kept day is not in the answer, or the day of its date
         * went back and no such day stands under it.
         */
        fun indexOfKept(newestFirst: List<ApexDailyRecord>, kept: ApexDailyRecord): Int? {
            val at = newestFirst.indexOfFirst { it.hasDate && it.dayClockSeconds == kept.dayClockSeconds }
            if (at < 0) return null
            if (holdsKept(newestFirst[at], kept)) return at
            val cut = newestFirst.getOrNull(at + 1) ?: return null
            return (at + 1).takeIf { !cut.hasDate && holdsKept(cut, kept) }
        }

        /** Two frames carry the same day: its fields and date, whatever its position in the answer, its object byte and its CRC. */
        fun sameDay(a: ByteArray, b: ByteArray): Boolean = identity(a).contentEquals(identity(b))

        private fun identity(raw: ByteArray): ByteArray =
            raw.copyOfRange(ApexResponseFrame.INDEX_OFFSET + 1, (raw.size - ApexResponseFrame.CRC_BYTES).coerceAtLeast(ApexResponseFrame.INDEX_OFFSET + 1))

        /** [day] holds at least what [kept] held in every field: the same day counted on. */
        fun holdsKept(day: ApexDailyRecord, kept: ApexDailyRecord): Boolean =
            day.bolusUnits >= kept.bolusUnits - ApexProtocol.ROUNDING && day.basalUnits >= kept.basalUnits - ApexProtocol.ROUNDING && day.tbrUnits >= kept.tbrUnits - ApexProtocol.ROUNDING

    }
}
