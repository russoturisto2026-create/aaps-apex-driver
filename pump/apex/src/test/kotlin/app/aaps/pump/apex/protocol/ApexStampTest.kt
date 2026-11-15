package app.aaps.pump.apex.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.TimeZone

/** A stamp tells its own form: plain bytes or decimal digits. */
class ApexStampTest {

    private fun frame(hex: String) = ApexResponseFrame(hex.split(" ").map { it.toInt(16).toByte() }.toByteArray())

    private fun key(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(year, month - 1, day, hour, minute, second) }.timeInMillis / 1000L

    @Test
    fun `a record of a pump that writes decimal digits reads as its date`() {
        // Every byte of the stamp is two decimal digits: 0x25 is the year 25, 0x13 the hour 13.
        val record = ApexBolusRecord.decode(frame("aa 16 80 a3 21 00 25 01 21 13 08 59 00 00 00 00 68 00 68 00 34 b5"))!!
        assertThat(record.clockSeconds).isEqualTo(key(2025, 1, 21, 13, 8, 59))
        assertThat(record.dateBroken).isFalse()
        assertThat(record.extendedDeliveredUnits).isWithin(1e-9).of(2.6)
    }

    @Test
    fun `a record of a pump that writes plain bytes reads as its date`() {
        // Every byte of the stamp is the number itself: 0x1a is the year 26, 0x0f the minute 15.
        val record = ApexBolusRecord.decode(frame("aa 16 80 a3 21 00 1a 0b 0b 08 0f 3b 14 00 14 00 00 00 00 00 cb 30"))!!
        assertThat(record.clockSeconds).isEqualTo(key(2026, 11, 11, 8, 15, 59))
    }

    @Test
    fun `of two possible readings the one nearer to now is taken`() {
        // 0x19 0x01 0x15: plain 21.01.2025, in digits 15.01.2019.
        val bytes = frame("aa 16 80 a3 21 00 19 01 15 10 08 30 00 00 00 00 00 00 00 00 00 00")
        assertThat(ApexStamp.clockSeconds(bytes, 2, nowSeconds = key(2025, 2, 1))).isEqualTo(key(2025, 1, 21, 16, 8, 48))
        // 0x32: in the year 2032 a pump of decimal digits writes it, and plain it would be 2050.
        val later = frame("aa 16 80 a3 21 00 32 01 15 10 08 30 00 00 00 00 00 00 00 00 00 00")
        assertThat(ApexStamp.clockSeconds(later, 2, nowSeconds = key(2032, 2, 1))).isEqualTo(key(2032, 1, 15, 10, 8, 30))
        // 0x20: in 2032 a pump of plain bytes writes it; in digits it would be 2020, twelve years back.
        val plain = frame("aa 16 80 a3 21 00 20 01 15 10 08 30 00 00 00 00 00 00 00 00 00 00")
        assertThat(ApexStamp.clockSeconds(plain, 2, nowSeconds = key(2032, 2, 1))).isEqualTo(key(2032, 1, 21, 16, 8, 48))
    }

    @Test
    fun `a stamp that reads in neither form stays as it stands`() {
        // No month and no day: the blank cell, and the day the pump keeps with no date.
        val blank = frame("aa 16 80 a3 21 00 00 00 00 00 00 3b 00 00 00 00 00 00 00 00 36 01")
        assertThat(ApexBolusRecord.decode(blank)!!.dateBroken).isTrue()
        assertThat(ApexStamp.daySeconds(blank, 2)).isLessThan(key(2000, 1, 1))
    }

    @Test
    fun `a date of three bytes reads in both forms`() {
        val digits = frame("aa 12 80 a3 26 00 00 00 00 00 00 00 25 01 21 00 00 00")
        assertThat(ApexStamp.daySeconds(digits, 8, nowSeconds = key(2025, 2, 1))).isEqualTo(key(2025, 1, 21))
        val plain = frame("aa 12 80 a3 26 00 00 00 00 00 00 00 1a 0b 0b 00 00 00")
        assertThat(ApexStamp.daySeconds(plain, 8, nowSeconds = key(2026, 11, 12))).isEqualTo(key(2026, 11, 11))
    }
}
