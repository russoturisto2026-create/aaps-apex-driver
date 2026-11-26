package app.aaps.pump.apex.protocol

import app.aaps.pump.apex.state.ApexPumpState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.TimeZone

/** The form of a stamp is the pump's protocol: decimal digits on 4.10, plain bytes on a newer one. */
class ApexStampTest {

    private fun frame(hex: String) = ApexResponseFrame(hex.split(" ").map { it.toInt(16).toByte() }.toByteArray())

    private fun key(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(year, month - 1, day, hour, minute, second) }.timeInMillis / 1000L

    @AfterEach
    fun plainAgain() {
        ApexStamp.digits = false
    }

    @Test
    fun `the protocol sets the form - 4_10 digits, newer plain`() {
        assertThat(ApexVersion(listOf(6, 25), 4, 10, listOf(0, 0)).stampsInDigits).isTrue()
        assertThat(ApexVersion(listOf(6, 28), 4, 11, listOf(0, 0)).stampsInDigits).isFalse()
        assertThat(ApexVersion(listOf(1, 1, 1), 4, 12, listOf(0, 0)).stampsInDigits).isFalse()
    }

    @Test
    fun `the version read sets the form for the link`() {
        val state = ApexPumpState()
        state.version = ApexVersion(listOf(6, 25), 4, 10, listOf(0, 0))
        assertThat(ApexStamp.digits).isTrue()
        state.version = ApexVersion(listOf(6, 28), 4, 11, listOf(0, 0))
        assertThat(ApexStamp.digits).isFalse()
    }

    @Test
    fun `a record of a 4_10 pump reads in decimal digits`() {
        ApexStamp.digits = true
        // Every byte of the stamp is two decimal digits: 0x25 is the year 25, 0x13 the hour 13.
        val record = ApexBolusRecord.decode(frame("aa 16 80 a3 21 00 25 01 21 13 08 59 00 00 00 00 68 00 68 00 34 b5"))!!
        assertThat(record.clockSeconds).isEqualTo(key(2025, 1, 21, 13, 8, 59))
        assertThat(record.dateBroken).isFalse()
        assertThat(record.extendedDeliveredUnits).isWithin(1e-9).of(2.6)
    }

    @Test
    fun `a record of a newer pump reads plain`() {
        // Every byte of the stamp is the number itself: 0x1a is the year 26, 0x0f the minute 15.
        val record = ApexBolusRecord.decode(frame("aa 16 80 a3 21 00 1a 0b 0b 08 0f 3b 14 00 14 00 00 00 00 00 cb 30"))!!
        assertThat(record.clockSeconds).isEqualTo(key(2026, 11, 11, 8, 15, 59))
    }

    @Test
    fun `the same bytes read by the form, not by which reading is nearer to now`() {
        // 0x19 0x01 0x15 0x10 0x08 0x30: plain 21.01.2025 16:08:48, in digits 15.01.2019 10:08:30.
        val bytes = frame("aa 16 80 a3 21 00 19 01 15 10 08 30 00 00 00 00 00 00 00 00 00 00")
        assertThat(ApexStamp.clockSeconds(bytes, 2)).isEqualTo(key(2025, 1, 21, 16, 8, 48))
        ApexStamp.digits = true
        assertThat(ApexStamp.clockSeconds(bytes, 2)).isEqualTo(key(2019, 1, 15, 10, 8, 30))
    }

    @Test
    fun `a stamp that does not read in the form stays as it stands`() {
        ApexStamp.digits = true
        // 0x1a is not two digits: read plain, as it stands.
        val notDigits = frame("aa 16 80 a3 21 00 1a 0b 0b 08 0f 3b 00 00 00 00 00 00 00 00 00 00")
        assertThat(ApexStamp.clockSeconds(notDigits, 2)).isEqualTo(key(2026, 11, 11, 8, 15, 59))
        // No month and no day: the blank cell, and the day the pump keeps with no date.
        val blank = frame("aa 16 80 a3 21 00 00 00 00 00 00 3b 00 00 00 00 00 00 00 00 36 01")
        assertThat(ApexBolusRecord.decode(blank)!!.dateBroken).isTrue()
        assertThat(ApexStamp.daySeconds(blank, 2)).isLessThan(key(2000, 1, 1))
    }

    @Test
    fun `a date of three bytes reads in both forms`() {
        val plain = frame("aa 12 80 a3 26 00 00 00 00 00 00 00 1a 0b 0b 00 00 00")
        assertThat(ApexStamp.daySeconds(plain, 8)).isEqualTo(key(2026, 11, 11))
        ApexStamp.digits = true
        val digits = frame("aa 12 80 a3 26 00 00 00 00 00 00 00 25 01 21 00 00 00")
        assertThat(ApexStamp.daySeconds(digits, 8)).isEqualTo(key(2025, 1, 21))
    }
}
