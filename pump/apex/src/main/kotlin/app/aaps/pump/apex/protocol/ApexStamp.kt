package app.aaps.pump.apex.protocol

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * The stamp of a record: six bytes, year to second, or three for a date. Pumps write the numbers
 * in one of two forms: plain, each byte the number, or as decimal digits, the byte 0x25 for 25.
 * The form is not taken from the pump's version: the stamp tells it.
 *
 * A reading is possible when every number is in its range and the moment is not after now. Of two
 * possible readings the one nearer to now is taken: a year read in the wrong form lands six years
 * off or more, so the two never come close. A stamp that reads in neither form is read plain, as
 * it stands: that is a broken record, or a day the pump keeps with no date, and the deep looks
 * tell which.
 *
 * Status V1 does not come through here: its clock is plain.
 */
object ApexStamp {

    /** Six bytes at [responseDataOffset] as whole seconds on a UTC calendar: the pump's wall clock as a key. */
    fun clockSeconds(frame: ApexResponseFrame, responseDataOffset: Int, nowSeconds: Long = nowSeconds()): Long =
        read(IntArray(CLOCK_BYTES) { frame.byteAt(responseDataOffset + it) }, nowSeconds)

    /** Three bytes at [responseDataOffset], a date, as the seconds of that day's midnight on a UTC calendar. */
    fun daySeconds(frame: ApexResponseFrame, responseDataOffset: Int, nowSeconds: Long = nowSeconds()): Long =
        read(IntArray(DATE_BYTES) { frame.byteAt(responseDataOffset + it) }, nowSeconds)

    private fun read(plain: IntArray, nowSeconds: Long): Long {
        val latest = nowSeconds + AHEAD_SECONDS
        val readings = listOfNotNull(plain, digitsOf(plain))
            .filter { inRange(it) }
            .map { keyOf(it) }
            .filter { it <= latest }
        return readings.minByOrNull { abs(nowSeconds - it) } ?: keyOf(plain)
    }

    /** Each byte read as two decimal digits; null when a byte is not two digits. */
    private fun digitsOf(plain: IntArray): IntArray? {
        if (plain.any { it shr 4 > 9 || it and 0x0F > 9 }) return null
        return IntArray(plain.size) { (plain[it] shr 4) * 10 + (plain[it] and 0x0F) }
    }

    private fun inRange(fields: IntArray): Boolean =
        fields[MONTH] in 1..12 && fields[DAY] in 1..31 &&
            (fields.size == DATE_BYTES || fields[HOUR] <= 23 && fields[MINUTE] <= 59 && fields[SECOND] <= 59)

    /** Year (+2000), month, day and, when given, hour, minute, second as whole seconds on a UTC calendar. */
    private fun keyOf(fields: IntArray): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        // Calendar months are 0 based.
        if (fields.size == DATE_BYTES) calendar.set(2000 + fields[YEAR], fields[MONTH] - 1, fields[DAY])
        else calendar.set(2000 + fields[YEAR], fields[MONTH] - 1, fields[DAY], fields[HOUR], fields[MINUTE], fields[SECOND])
        return calendar.timeInMillis / 1000L
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private const val CLOCK_BYTES = 6
    private const val DATE_BYTES = 3
    private const val YEAR = 0
    private const val MONTH = 1
    private const val DAY = 2
    private const val HOUR = 3
    private const val MINUTE = 4
    private const val SECOND = 5

    /** How far after the phone's now a stamp may stand: the pump's wall clock may be in any zone, and a little fast. */
    private const val AHEAD_SECONDS = 2 * 24 * 3600L
}
