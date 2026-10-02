package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.comm.CrcUtilTest.Companion.hexToBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Decodes the pump's own bolus calculator settings, object `0x07`.
 *
 * [CALCULATOR] is a frame of this object with both unit flags clear. [FLAGS_SET] is [CALCULATOR]
 * with those two bytes changed and both checksums recomputed, to prove the decoder picks the right
 * table.
 *
 * The rule these tests exist for is the parked slot. Every table holds twelve whatever is in use,
 * and reading a parked one gives a block starting at midnight with a value that means nothing.
 */
class Atc3BolusCalculatorTest {

    private fun decode(hex: String): Atc3BolusCalculator? {
        val frames = Atc3ResponseParser().feed(hexToBytes(hex))
        assertEquals(1, frames.size)
        return Atc3BolusCalculator.decode(frames[0])
    }

    @Test
    fun `the header decodes to what the pump was set to`() {
        val calculator = decode(CALCULATOR)
        assertNotNull(calculator)
        assertFalse(calculator!!.enabled)
        assertFalse(calculator.carbsInBreadUnits)
        assertFalse(calculator.glucoseInMgdl)
        assertEquals(360, calculator.activeInsulinMinutes)
    }

    @Test
    fun `eleven parked slots out of twelve are dropped`() {
        // This pump holds one block per table and eleven parked ones after it. Read as real they
        // would be eleven more blocks, all starting at midnight, all saying something different.
        val calculator = decode(CALCULATOR)!!
        assertEquals(1, calculator.carbRatioGramsPerUnit.size)
        assertEquals(1, calculator.carbRatioUnitsPerBreadUnit.size)
        assertEquals(1, calculator.sensitivityMmol.size)
        assertEquals(1, calculator.sensitivityMgdl.size)
        assertEquals(1, calculator.targetMmol.size)
        assertEquals(1, calculator.targetMgdl.size)
    }

    @Test
    fun `each table is read on its own scale`() {
        val calculator = decode(CALCULATOR)!!
        assertEquals(15.0, calculator.carbRatioGramsPerUnit[0].value, 1e-9)
        // Raw 10 on the bread unit table is half a unit, not ten of them.
        assertEquals(0.50, calculator.carbRatioUnitsPerBreadUnit[0].value, 1e-9)
        // Raw 28 in tenths of a mmol is 2.8, and raw 50 in the mg/dl table is 50.
        assertEquals(2.8, calculator.sensitivityMmol[0].value, 1e-9)
        assertEquals(50.0, calculator.sensitivityMgdl[0].value, 1e-9)
        assertEquals(5.6, calculator.targetMmol[0].value, 1e-9)
        assertEquals(100.0, calculator.targetMgdl[0].value, 1e-9)
    }

    @Test
    fun `a block that starts at midnight starts at zero minutes`() {
        assertEquals(0, decode(CALCULATOR)!!.carbRatioGramsPerUnit[0].startMinutes)
    }

    @Test
    fun `the flags of this pump put the metric tables in force`() {
        val calculator = decode(CALCULATOR)!!
        assertEquals(calculator.carbRatioGramsPerUnit, calculator.carbRatioInForce)
        assertEquals(calculator.sensitivityMmol, calculator.sensitivityInForce)
        assertEquals(calculator.targetMmol, calculator.targetInForce)
    }

    @Test
    fun `the flags select the other member of each pair and change nothing else`() {
        val flagged = decode(FLAGS_SET)!!
        assertTrue(flagged.enabled)
        assertTrue(flagged.carbsInBreadUnits)
        assertTrue(flagged.glucoseInMgdl)
        assertEquals(flagged.carbRatioUnitsPerBreadUnit, flagged.carbRatioInForce)
        assertEquals(flagged.sensitivityMgdl, flagged.sensitivityInForce)
        assertEquals(flagged.targetMgdl, flagged.targetInForce)
        // The tables themselves are the same bytes, so selecting cannot be rewriting.
        assertEquals(decode(CALCULATOR)!!.sensitivityMmol, flagged.sensitivityMmol)
        assertEquals(decode(CALCULATOR)!!.carbRatioGramsPerUnit, flagged.carbRatioGramsPerUnit)
    }

    @Test
    fun `the members of a pair are read apart and never converted`() {
        // 2.8 mmol/l is 50.4 mg/dl, so on this pump the pair happens to be one setting expressed
        // twice. It is not guaranteed to be: the pump can hold two unrelated values, and a decoder
        // that converted one into the other would invent a sensitivity the pump does not have. The
        // decoder keeps them apart on principle.
        val calculator = decode(CALCULATOR)!!
        assertEquals(2.8, calculator.sensitivityMmol[0].value, 1e-9)
        assertEquals(50.0, calculator.sensitivityMgdl[0].value, 1e-9)
    }

    @Test
    fun `refuses a frame of another object`() {
        // A temporary basal history record.
        assertNull(decode(TBR_RECORD))
    }

    companion object {

        /**
         * Calculator off, carbohydrates in grams, glucose in mmol/l, insulin active 360 minutes.
         * One block per table from midnight: 15 g/U, 0.50 U/BU, 2.8 mmol/l, 50 mg/dl, target
         * 5.6 mmol/l and 100 mg/dl, with eleven parked slots behind each.
         */
        private const val CALCULATOR = """
            AA FE 01 A3 07 AA 00 00 68 01 0F 00 00 00 0F 00
            30 00 0F 00 30 00 0F 00 30 00 0F 00 30 00 0F 00
            30 00 0F 00 30 00 0F 00 30 00 0F 00 30 00 0F 00
            30 00 0F 00 30 00 0F 00 30 00 0A 00 00 00 0A 00
            30 00 0A 00 30 00 0A 00 30 00 0A 00 30 00 0A 00
            30 00 0A 00 30 00 0A 00 30 00 0A 00 30 00 0A 00
            30 00 0A 00 30 00 0A 00 30 00 1C 00 00 00 1C 00
            30 00 1C 00 30 00 1C 00 30 00 1C 00 30 00 1C 00
            30 00 1C 00 30 00 1C 00 30 00 1C 00 30 00 1C 00
            30 00 1C 00 30 00 1C 00 30 00 32 00 00 00 32 00
            30 00 32 00 30 00 32 00 30 00 32 00 30 00 32 00
            30 00 32 00 30 00 32 00 30 00 32 00 30 00 32 00
            30 00 32 00 30 00 32 00 30 00 38 00 38 30 38 30
            38 30 38 30 38 30 38 30 38 30 38 30 38 30 38 30
            38 30 64 00 64 30 64 30 64 30 64 30 64 30 64 30
            64 30 64 30 64 30 64 30 64 30 09 E7 9A 02
            """

        /**
         * [CALCULATOR] with the calculator switched on and both unit flags set, checksums recomputed.
         *
         * It exercises the branch that picks a table.
         */
        private const val FLAGS_SET = """
            AA FE 01 A3 07 AA 01 11 68 01 0F 00 00 00 0F 00
            30 00 0F 00 30 00 0F 00 30 00 0F 00 30 00 0F 00
            30 00 0F 00 30 00 0F 00 30 00 0F 00 30 00 0F 00
            30 00 0F 00 30 00 0F 00 30 00 0A 00 00 00 0A 00
            30 00 0A 00 30 00 0A 00 30 00 0A 00 30 00 0A 00
            30 00 0A 00 30 00 0A 00 30 00 0A 00 30 00 0A 00
            30 00 0A 00 30 00 0A 00 30 00 1C 00 00 00 1C 00
            30 00 1C 00 30 00 1C 00 30 00 1C 00 30 00 1C 00
            30 00 1C 00 30 00 1C 00 30 00 1C 00 30 00 1C 00
            30 00 1C 00 30 00 1C 00 30 00 32 00 00 00 32 00
            30 00 32 00 30 00 32 00 30 00 32 00 30 00 32 00
            30 00 32 00 30 00 32 00 30 00 32 00 30 00 32 00
            30 00 32 00 30 00 32 00 30 00 38 00 38 30 38 30
            38 30 38 30 38 30 38 30 38 30 38 30 38 30 38 30
            38 30 64 00 64 30 64 30 64 30 64 30 64 30 64 30
            64 30 64 30 64 30 64 30 64 30 9D 30 9A 02
            """

        /** Object 0x27, a temporary basal history record: another object. */
        private const val TBR_RECORD = "AA 16 32 A3 27 00 1A 08 17 17 03 00 01 00 02 00 50 00 1C 00 42 3E"
    }
}
