package app.aaps.pump.atc3.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Atc3PayloadTest {

    @Test
    fun `a bolus is its amount in pump steps, little endian, and a zero`() {
        // 2.5 U is 100 steps of 0.025 U.
        assertArrayEquals(byteArrayOf(100, 0, 0), Atc3Payload.bolus(2.5))
        // 10 U is 400 steps: 0x0190.
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x01, 0), Atc3Payload.bolus(10.0))
    }

    @Test
    fun `an absolute temporary basal is the mode, the quarter hours and the rate`() {
        // 1.2 U/h is 48 steps, for two quarter hours.
        assertArrayEquals(byteArrayOf(Atc3Protocol.TbrPayload.MODE_ABSOLUTE, 2, 48, 0), Atc3Payload.absoluteTbr(1.2, 2))
    }

    @Test
    fun `a basal profile is every rate in turn`() {
        val payload = Atc3Payload.basalProfile(DoubleArray(Atc3Protocol.BASAL_SLOTS) { if (it == 1) 6.4 else 0.5 })
        assertEquals(2 * Atc3Protocol.BASAL_SLOTS, payload.size)
        assertArrayEquals(byteArrayOf(20, 0, 0x00, 0x01), payload.copyOfRange(0, 4))
    }

    @Test
    fun `a stop is one and a resume is zero`() {
        assertArrayEquals(byteArrayOf(1), Atc3Payload.suspend(true))
        assertArrayEquals(byteArrayOf(0), Atc3Payload.suspend(false))
    }
}
