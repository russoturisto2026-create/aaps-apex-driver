package app.aaps.pump.apex.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The temporary basal as Status V1 tells it: whether one runs, at what rate, for how long, and how
 * many minutes of it are gone. Whole frames, as a pump sent them.
 */
class ApexStatusV1TbrTest {

    private fun decode(hex: String): ApexStatusV1 =
        ApexStatusV1.decode(ApexResponseParser().feed(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()).single())!!

    private fun key(hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2025, 0, 21, hour, minute, second) }.timeInMillis / 1000L

    /** The minute the running temporary basal began in: the minute of the status clock less the minutes gone. */
    private fun startMinute(status: ApexStatusV1): Long = status.clockSeconds - status.clockSeconds % 60 - status.tbrElapsedMinutes * 60L

    @Test
    fun `one minute into a temporary basal of zero for half an hour`() {
        val status = decode(
            "aa6001a300aa03000000060100010d04000000002c01d003000064000000a000f80200000000000000000000000000000000" +
                "1901150f3600000106f90000000000000000000000000000000000000000000024000e17000000001e000100a501"
        )
        assertThat(status.tbrActive).isTrue()
        assertThat(status.tbrRate).isWithin(1e-9).of(0.0)
        assertThat(status.tbrDurationMinutes).isEqualTo(30)
        assertThat(status.tbrElapsedMinutes).isEqualTo(1)
        assertThat(status.clockSeconds).isEqualTo(key(15, 54))
        assertThat(status.deliveredTodayUnits).isWithin(1e-9).of(24.4)
        assertThat(startMinute(status)).isEqualTo(key(15, 53))
    }

    @Test
    fun `the last minute of it still shows it running, with all its minutes gone`() {
        val status = decode(
            "aa6001a300aa03000000060100010d04000000002c01d003000064000000a000f80200000000000000000000000000000000" +
                "190115101700000106f90000000000000000000000000000000000000000000024000e17000000001e001e00ce69"
        )
        assertThat(status.tbrActive).isTrue()
        assertThat(status.tbrElapsedMinutes).isEqualTo(30)
        assertThat(startMinute(status)).isEqualTo(key(15, 53))
    }

    @Test
    fun `a rate asked as a rate reads with no minute gone right after the order`() {
        val status = decode(
            "aa6001a300aa03010000060100010d04000001002c01fe03000064000000a000f80200000000000000000000000000000000" +
                "190115122a00000156f4000000000000000000000000000000000000000000001d000e17580001001e00000031c8"
        )
        assertThat(status.tbrActive).isTrue()
        assertThat(status.tbrRate).isWithin(1e-9).of(2.2)
        assertThat(status.tbrDurationMinutes).isEqualTo(30)
        assertThat(status.tbrElapsedMinutes).isEqualTo(0)
        assertThat(startMinute(status)).isEqualTo(key(18, 42))
    }

    @Test
    fun `a temporary basal asked as a percentage shows the rate it comes to`() {
        // 110 % of a basal of 0.725 U/h, in whole steps of the pump.
        val status = decode(
            "aa6001a300aa03010000060100010d04000001002c01f903000064000000a000f80200000000000000000000000000000000" +
                "1901151220000001ecf4000000000000000000000000000000000000000000001d000e171f0000001e0001006a0f"
        )
        assertThat(status.tbrActive).isTrue()
        assertThat(status.tbrRate).isWithin(1e-9).of(0.775)
        assertThat(status.scheduledBasalRate).isWithin(1e-9).of(0.725)
        assertThat(status.tbrElapsedMinutes).isEqualTo(1)
        // Ordered in the last second of the minute before: one minute gone at the first second of the next.
        assertThat(startMinute(status)).isEqualTo(key(18, 31))
    }

    @Test
    fun `a whole frame with three bars gives three quarters`() {
        val status = decode(
            "aa6001a300aa03000000060100010d04000000002c01d003000064000000a000f80200000000000000000000000000000000" +
                "1901150f3600000106f90000000000000000000000000000000000000000000024000e17000000001e000100a501"
        )
        assertThat(status.batteryBars).isEqualTo(3)
        assertThat(status.batteryPercent).isEqualTo(75)
    }
}
