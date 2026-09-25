package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.comm.CrcUtilTest.Companion.hexToBytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reads the settings block out of a Status V1 frame and checks every field.
 *
 * The frame is that of a suspended pump, the same bytes [Atc3StatusV1Test] decodes the pump state
 * from.
 */
class Atc3SettingsTest {

    private val alarmDuration = Atc3Const.Settings.ALARM_DURATION_NORMAL

    private fun decoded(): Atc3Settings {
        val frames = Atc3ResponseParser().feed(hexToBytes(SUSPENDED_STATUS_V1))
        assertEquals(1, frames.size)
        return Atc3Settings.decode(frames[0], alarmDuration).also { assertNotNull(it) }!!
    }

    @Test
    fun `the limits are read on the dose scale`() {
        // The maximum basal is 2.5 U/h, raw 100, and the maximum bolus 6.0 U, raw 240.
        assertEquals(100, decoded().maxBasalRaw)
        assertEquals(2.5, decoded().maxBasal, 1e-9)
        assertEquals(240, decoded().maxBolusRaw)
        assertEquals(6.0, decoded().maxBolus, 1e-9)
    }

    @Test
    fun `the low insulin thresholds are read in units and half hours`() {
        // 35 U, raw 0x23; five hours, raw 0x0A half hours.
        assertEquals(35, decoded().lowInsulinUnits)
        assertEquals(10, decoded().lowInsulinHalfHours)
        assertEquals(5.0, decoded().lowInsulinHours, 1e-9)
    }

    @Test
    fun `the daily dose limit matches the client`() {
        assertTrue(decoded().dailyLimitEnabled)
        assertEquals(250, decoded().dailyLimitUnits)
    }

    @Test
    fun `the screen timeout is read as tenths of a second`() {
        // 60 s, raw 600.
        assertEquals(600, decoded().screenTimeoutRaw)
        assertEquals(60, decoded().screenTimeoutSeconds)
    }

    @Test
    fun `the brightness level indexes the percentage list`() {
        // Level 4 is 80 %.
        assertEquals(4, decoded().brightnessLevel)
        assertEquals(80, decoded().brightnessPercent)
    }

    @Test
    fun `the switches are read one status byte each`() {
        val settings = decoded()
        assertTrue(settings.lowBolusSpeed)
        assertTrue(settings.keypadLock)
        assertTrue(settings.autoOff)
        assertEquals(1, settings.autoOffHours)
        assertFalse(settings.basalPatterns)
        assertTrue(settings.english)
        assertEquals(Atc3Const.Settings.ALARM_TYPE_VIBRATION, settings.alarmSignalType)
    }

    @Test
    fun `the bolus type bits are shifted back down`() {
        // Status carries the payload byte doubled: payload 02, reminder on and extended off,
        // reads back as 04.
        val settings = decoded()
        assertFalse(settings.extendedBolusAllowed)
        assertTrue(settings.bgReminder)
    }

    @Test
    fun `the alarm duration is what the caller remembered, the pump does not report it`() {
        assertEquals(alarmDuration, decoded().alarmDuration)
        assertEquals(
            Atc3Const.Settings.ALARM_DURATION_SHORT,
            Atc3Settings.decode(
                Atc3ResponseParser().feed(hexToBytes(SUSPENDED_STATUS_V1))[0],
                Atc3Const.Settings.ALARM_DURATION_SHORT
            )!!.alarmDuration
        )
    }

    @Test
    fun `what was read encodes back to the payload that would have written it`() {
        val payload = decoded().toPayload()
        assertEquals(Atc3Const.Settings.PAYLOAD_LENGTH, payload.size)
        assertArrayEquals(
            byteArrayOf(
                // low bolus speed, keypad lock, auto off, daily dose limit and English
                0x67,
                0x01,                   // vibration
                0x04,                   // brightness 80 %
                0x01,                   // auto off after one hour
                0x23,                   // low insulin at 35 U
                0x0A,                   // low insulin five hours ahead
                0x02,                   // blood glucose reminder on, extended bolus off
                0x01,                   // normal alarm duration, the remembered value
                0x58, 0x02,             // screen timeout 60.0 s
                0xFA.toByte(), 0x00,    // daily dose limit 250 U
                0x64, 0x00,             // maximum basal 2.5 U/h
                0xF0.toByte(), 0x00     // maximum bolus 6.0 U
            ),
            payload
        )
    }

    @Test
    fun `a read back that differs only in the alarm duration still counts as a match`() {
        val wanted = decoded().copy(alarmDuration = Atc3Const.Settings.ALARM_DURATION_LONG)
        assertTrue(wanted.mirroredFieldsMatch(decoded()))
        assertFalse(wanted.copy(maxBolusRaw = 320).mirroredFieldsMatch(decoded()))
    }

    @Test
    fun `changing a value goes through the pump's own scales`() {
        val settings = decoded()
        assertEquals(320, settings.withMaxBolus(8.0).maxBolusRaw)
        assertEquals(160, settings.withMaxBasal(4.0).maxBasalRaw)
        assertEquals(300, settings.withScreenTimeoutSeconds(30).screenTimeoutRaw)
        assertEquals(4, settings.withLowInsulinHours(2.0).lowInsulinHalfHours)
    }

    companion object {

        /**
         * Status V1 of a suspended pump, the same frame as in [Atc3StatusV1Test], with every
         * setting away from its default.
         */
        private const val SUSPENDED_STATUS_V1 = """
            AA 60 01 A3 00 AA 03 01 01 04 04 01 01 01 23 0A
            00 00 00 01 58 02 C4 01 00 00 FA 00 00 00 64 00
            F0 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            00 00 1A 08 15 0E 29 37 01 00 A7 00 03 00 00 00
            00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            00 00 FF FF 0E 29 00 00 00 00 00 00 00 00 B0 0F
            """
    }
}
