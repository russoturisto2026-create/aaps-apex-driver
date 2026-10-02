package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.comm.CrcUtilTest.Companion.hexToBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The battery is the one figure a user asks about first, so both the voltage and the percentage
 * derived from it are pinned here. Active insulin is pinned across a bolus and its decay.
 */
class Atc3StatusV2Test {

    @Test
    fun `the voltage is read in hundredths of a volt`() {
        // Raw 0x8A, 138, is 1.38 V.
        val frames = Atc3ResponseParser().feed(
            hexToBytes("AA 1C 01 A3 0C AA 01 00 00 8A 01 03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 AC 4F")
        )
        val status = Atc3StatusV2.decode(frames[0])
        assertNotNull(status)
        assertEquals(1.38, status!!.batteryVolts, 1e-9)
    }

    @Test
    fun `active insulin is read across a bolus and its decay`() {
        // Three frames: idle at 1.450 U carried over from an earlier bolus, jumping to 2.425 U three
        // minutes after a fresh 1.000 U bolus, then decaying to 2.325 U twelve minutes later.
        val before = Atc3ResponseParser().feed(
            hexToBytes("AA 1C 01 A3 0C AA 3A 00 00 8A 00 03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 8D 51")
        )
        val afterBolus = Atc3ResponseParser().feed(
            hexToBytes("AA 1C 01 A3 0C AA 61 00 00 8A 00 03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 E4 77")
        )
        val decayed = Atc3ResponseParser().feed(
            hexToBytes("AA 1C 01 A3 0C AA 5D 00 00 8A 00 03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 B1 4E")
        )
        assertEquals(1.450, Atc3StatusV2.decode(before[0])!!.activeInsulinUnits, 1e-9)
        assertEquals(2.425, Atc3StatusV2.decode(afterBolus[0])!!.activeInsulinUnits, 1e-9)
        assertEquals(2.325, Atc3StatusV2.decode(decayed[0])!!.activeInsulinUnits, 1e-9)
    }

    @Test
    fun `the charge follows the voltage scale`() {
        // 3.5 % per hundredth of a volt, 52 % at 1.38 V.
        assertEquals(56, Atc3StatusV2(activeInsulinUnits = 0.0, batteryVolts = 1.39).batteryPercent)
        assertEquals(52, Atc3StatusV2(activeInsulinUnits = 0.0, batteryVolts = 1.38).batteryPercent)
        assertEquals(49, Atc3StatusV2(activeInsulinUnits = 0.0, batteryVolts = 1.37).batteryPercent)
    }

    @Test
    fun `the charge stays a percentage far outside the readings we have`() {
        assertEquals(100, Atc3StatusV2(activeInsulinUnits = 0.0, batteryVolts = 1.60).batteryPercent)
        assertEquals(0, Atc3StatusV2(activeInsulinUnits = 0.0, batteryVolts = 1.00).batteryPercent)
        assertEquals(0, Atc3StatusV2(activeInsulinUnits = 0.0, batteryVolts = 0.0).batteryPercent)
    }

    @Test
    fun `another object is not a Status V2`() {
        val frames = Atc3ResponseParser().feed(hexToBytes("AA 0A 00 A1 55 AA 00 00 EC 39"))
        assertNull(Atc3StatusV2.decode(frames[0]))
    }
}
