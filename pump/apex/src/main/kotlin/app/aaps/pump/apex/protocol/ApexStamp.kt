package app.aaps.pump.apex.protocol

import java.util.Calendar
import java.util.TimeZone

/**
 * The stamp of a record: six bytes, year to second, or three for a date. Pumps write the numbers
 * in one of two forms by their protocol: a 4.10 pump as decimal digits, the byte 0x25 for 25, a
 * newer one plain, each byte the number, see [ApexVersion.stampsInDigits]. The form is set once for
 * the link, when the version is read, see [digits]; every record of the pump reads in it, the
 * copies kept in the store too, since they are of the same pump.
 *
 * A stamp that does not read in the form, a byte that is not two digits, is read plain, as it
 * stands: that is a broken record, or a day the pump keeps with no date, and the reconciliation tells
 * which.
 *
 * Status V1 does not come through here: its clock is plain on every protocol.
 */
object ApexStamp {

    /** True when the pump on the link writes its stamps as decimal digits. Set when the version is read. */
    @Volatile var digits: Boolean = false

    /** Six bytes at [responseDataOffset] as whole seconds on a UTC calendar: the pump's wall clock as a key. */
    fun clockSeconds(frame: ApexResponseFrame, responseDataOffset: Int): Long =
        read(IntArray(CLOCK_BYTES) { frame.byteAt(responseDataOffset + it) })

    /** Three bytes at [responseDataOffset], a date, as the seconds of that day's midnight on a UTC calendar. */
    fun daySeconds(frame: ApexResponseFrame, responseDataOffset: Int): Long =
        read(IntArray(DATE_BYTES) { frame.byteAt(responseDataOffset + it) })

    private fun read(plain: IntArray): Long {
        val fields = if (digits) digitsOf(plain) ?: plain else plain
        return keyOf(if (inRange(fields)) fields else plain)
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

    private const val CLOCK_BYTES = 6
    private const val DATE_BYTES = 3
    private const val YEAR = 0
    private const val MONTH = 1
    private const val DAY = 2
    private const val HOUR = 3
    private const val MINUTE = 4
    private const val SECOND = 5
}
