package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.comm.CrcUtilTest.Companion.hexToBytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Decodes Status V1 frames and asserts the values they carry.
 */
class Atc3StatusV1Test {

    private fun decoded(): Atc3StatusV1 {
        val frames = Atc3ResponseParser().feed(hexToBytes(CrcUtilTest.STATUS_V1_FRAME))
        assertEquals(1, frames.size)
        return Atc3StatusV1.decode(frames[0]).also { assertNotNull(it) }!!
    }

    @Test
    fun `the clock encoder is the exact inverse of the decoder`() {
        // They have to agree: a clock written from the phone that read back as a different time
        // would have the driver correcting it again on every connection, for ever.
        val moment = java.util.Calendar.getInstance()
            .apply { clear(); set(2026, 7, 21, 14, 37, 2) }.timeInMillis

        val bytes = Atc3StatusV1.encodeClock(moment)

        assertArrayEquals(byteArrayOf(26, 8, 21, 14, 37, 2), bytes)
    }

    @Test
    fun `a clock write decodes back to the time it sets`() {
        // These six bytes set the pump's clock to 14:37:02.
        val body = byteArrayOf(
            // Length counts the CRC too: six clock bytes plus the six byte header plus two.
            Atc3ResponseFrame.MARKER, 0x0E, 0x01, 0xA3.toByte(), 0x00, 0x00,
            0x1A, 0x08, 0x15, 0x0E, 0x25, 0x02
        )
        val frame = Atc3ResponseParser().feed(body + CrcUtil.crc16ModbusLeBytes(body)).single()

        val decoded = java.util.Calendar.getInstance().apply {
            timeInMillis = Atc3StatusV1.decodeClock(frame, 2)
        }

        assertEquals(2026, decoded.get(java.util.Calendar.YEAR))
        assertEquals(8, decoded.get(java.util.Calendar.MONTH) + 1)
        assertEquals(21, decoded.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(14, decoded.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(37, decoded.get(java.util.Calendar.MINUTE))
        assertEquals(2, decoded.get(java.util.Calendar.SECOND))
    }

    @Test
    fun `reservoir matches the pump display`() {
        // Raw 300725 is 300.725 U.
        assertEquals(300.725, decoded().reservoirUnits, 1e-9)
    }

    @Test
    fun `scheduled basal matches the pump display`() {
        // Raw 24 is 0.600 U/h.
        assertEquals(0.600, decoded().scheduledBasalRate, 1e-9)
    }

    @Test
    fun `active profile index is decoded`() {
        assertEquals(0, decoded().activeProfileIndex)
    }

    @Test
    fun `pump clock is decoded as plain binary bytes`() {
        // Bytes 1A 08 0F 11 1B 22 are decimal 26 8 15 17 27 34, not BCD.
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        calendar.timeInMillis = decoded().snapshotTime
        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(8, calendar.get(Calendar.MONTH) + 1)
        assertEquals(15, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(17, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(27, calendar.get(Calendar.MINUTE))
        assertEquals(34, calendar.get(Calendar.SECOND))
    }

    @Test
    fun `no temporary basal is running in this frame`() {
        assertFalse(decoded().tbrActive)
    }

    private fun decode(hex: String): Atc3StatusV1 {
        val frames = Atc3ResponseParser().feed(hexToBytes(hex))
        assertEquals(1, frames.size)
        return Atc3StatusV1.decode(frames[0]).also { assertNotNull(it) }!!
    }

    @Test
    fun `a suspended pump reports no basal rate rather than a nonsense one`() {
        // A frame from a stopped pump: the scheduled basal field reads 0xFFFF,
        // which taken as a rate would be 1638 U/h.
        val suspended = decode(SUSPENDED)
        assertTrue(suspended.suspended)
        assertEquals(0.0, suspended.scheduledBasalRate, 1e-9)

        // The same pump moments later, running again.
        val running = decode(RESUMED)
        assertFalse(running.suspended)
        assertEquals(0.675, running.scheduledBasalRate, 1e-9)
    }

    // The delivery counter and the last stop, the two fields the state check lives on

    @Test
    fun `delivered today is read from the snapshot`() {
        // Frame offsets 22..23 read C4 01 in both frames: raw 452, which is 11.300 U.
        assertEquals(11.3, decode(SUSPENDED).deliveredTodayUnits, 1e-9)
        assertEquals(11.3, decode(RESUMED).deliveredTodayUnits, 1e-9)
    }

    @Test
    fun `the snapshot rebuilt by the stop itself names the stop to the second`() {
        // Stopped at 14:41:55: the snapshot clock reads 14:41:55 and the last stop reads 14:41.
        val stopped = decode(SUSPENDED)
        assertEquals(Atc3StatusV1.LastStop(14, 41), stopped.lastStop)
        assertTrue(stopped.snapshotIsTheStop())
        assertEquals(stopped.snapshotTime, stopped.lastStopMoment())
        assertTrue(stopped.snapshotCarriesSeconds)
    }

    @Test
    fun `a later snapshot keeps the stop to the minute only`() {
        // Resumed at 14:42:18: the snapshot is the resume's own, the last stop still reads 14:41,
        // and the moment of the stop is now known to the minute and no better.
        val resumed = decode(RESUMED)
        assertEquals(Atc3StatusV1.LastStop(14, 41), resumed.lastStop)
        assertFalse(resumed.snapshotIsTheStop())
        val stopMinute = Calendar.getInstance().apply {
            timeInMillis = resumed.snapshotTime
            set(Calendar.MINUTE, 41)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(stopMinute, resumed.lastStopMoment())
        assertTrue(resumed.snapshotCarriesSeconds)
    }

    @Test
    fun `no stop at all reads as none rather than as midnight`() {
        // Frame offsets 84..85 read 00 00 in a frame with nothing raised.
        val status = decodePacked(nothingActive)
        assertEquals(null, status.lastStop)
        assertEquals(null, status.lastStopMoment())
    }

    @Test
    fun `the elapsed minutes of the temporary basal are read`() {
        // 00 00 in the frames here, where nothing is running, and the running case is pinned by
        // the byte position: the two bytes after the duration field.
        assertEquals(0, decode(RESUMED).tbrElapsedMinutes)
    }

    // The pump lock, which the driver would otherwise only discover by having a command refused

    @Test
    fun `an unlocked pump is reported as unlocked`() {
        // Every frame in this test is from an unlocked pump, so this is the value the field
        // carries rather than a default the decoder invented.
        assertFalse(decoded().locked)
        assertFalse(decode(RESUMED).locked)
    }

    @Test
    fun `a locked pump is reported as locked`() {
        // The same frame with data offset 13 set to 01, as a locked pump reports it: reads go on
        // being answered and every control command is refused.
        assertTrue(decode(withLock(RESUMED, locked = true)).locked)
    }

    @Test
    fun `the lock does not disturb the rest of the frame`() {
        // It sits among the mirrored settings bytes, one before the active profile index, so a
        // reading that was one byte out would show up here.
        val locked = decode(withLock(RESUMED, locked = true))
        assertEquals(0, locked.activeProfileIndex)
        assertEquals(0.675, locked.scheduledBasalRate, 1e-9)
        assertFalse(locked.suspended)
    }

    companion object {

        /** Where the lock byte sits in the raw frame, header included: data offset 13 plus four. */
        private const val LOCKED_FRAME_OFFSET = 17

        /** The same frame with the lock byte set or cleared, and its CRC made good again. */
        private fun withLock(hex: String, locked: Boolean): String {
            val frame = hexToBytes(hex)
            frame[LOCKED_FRAME_OFFSET] = if (locked) 1 else 0
            val body = frame.copyOfRange(0, frame.size - 2)
            return (body + CrcUtil.crc16ModbusLeBytes(body)).joinToString(" ") { "%02X".format(it) }
        }

        /** Status V1 of a suspended pump. */
        private const val SUSPENDED = """
            AA 60 01 A3 00 AA 03 01 01 04 04 01 01 01 23 0A
            00 00 00 01 58 02 C4 01 00 00 FA 00 00 00 64 00
            F0 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            00 00 1A 08 15 0E 29 37 01 00 A7 00 03 00 00 00
            00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            00 00 FF FF 0E 29 00 00 00 00 00 00 00 00 B0 0F
            """

        /** The same pump after delivery was resumed. */
        private const val RESUMED = """
            AA 60 01 A3 00 AA 03 01 01 04 04 01 01 01 23 0A
            00 00 00 01 58 02 C4 01 00 00 FA 00 00 00 64 00
            F0 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            00 00 1A 08 15 0E 2A 12 01 00 A7 00 03 00 00 00
            00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            00 00 1B 00 0E 29 00 00 00 00 00 00 00 00 19 7F
            """
    }

    /**
     * Status V1 while an occlusion alarm is up and unacknowledged: code 8 in the first slot.
     */
    private val occlusionActive =
        "AA6001A300AA0400000504000001050A000000000807CE10000064000000C800E0018C0048005C000000000000" +
            "00000000001A09040E0E0000001BA503000801000000000000000000000000000000000000280000000000" +
            "000000000000A986"

    /** The same pump four minutes earlier, nothing being raised. */
    private val nothingActive =
        "AA6001A300AA0400000504000001050A000000000807C110000064000000C800E0018C0048005C000000000000" +
            "00000000001A09040E0A06000060A603000000000000000000000000000000000000000000280000000000" +
            "000000000000D2E8"

    /**
     * The same as [decode] for a frame quoted the way the driver's log prints it, unspaced.
     *
     * [hexToBytes] wants the bytes separated; the frames below are written unspaced, as the
     * driver's log prints them.
     */
    private fun decodePacked(hex: String): Atc3StatusV1 = decode(hex.chunked(2).joinToString(" "))

    @Test
    fun `the occlusion alarm stands in the first active alarm slot`() {
        val status = decodePacked(occlusionActive)

        assertEquals(listOf(8), status.activeAlarmCodes)
        assertEquals(listOf(Atc3Alarm.NO_DELIVERY), status.activeAlarms)
        assertTrue(status.hasActiveAlarm)
    }

    @Test
    fun `an empty pair of slots is no alarm at all`() {
        val status = decodePacked(nothingActive)

        assertTrue(status.activeAlarmCodes.isEmpty())
        assertFalse(status.hasActiveAlarm)
    }

    @Test
    fun `an alarm says nothing about delivery`() {
        // The whole safety argument in one assertion: on this pump an occlusion is raised by the
        // pressure a bolus builds and cancels that bolus, so an alarm must never become a
        // suspension. Reading it as one would stop AAPS crediting basal the pump may still be
        // giving, which is the more dangerous of the two mistakes.
        val status = decodePacked(occlusionActive)

        assertTrue(status.hasActiveAlarm)
        assertFalse(status.suspended)
        assertEquals(1.0, status.scheduledBasalRate, 1e-9)
    }
}
