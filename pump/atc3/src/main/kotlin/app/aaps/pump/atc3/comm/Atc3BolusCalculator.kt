package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const

/**
 * The settings of the pump's own bolus calculator, object `0x07`, 254 bytes.
 *
 * Whether the calculator is on, which units it counts in, the active insulin time, and six twelve
 * slot tables. The write counterpart is `35/A1/15`.
 *
 * The loop does not need any of it — AAPS carries its own carb ratio, sensitivity and target. What
 * it is good for is a second opinion: the pump's own idea of those three quantities, read from the
 * pump rather than from the phone, which is the only way to notice that the two have drifted apart.
 *
 * **This object never fits one notification.** 254 bytes against a maximum notification payload of
 * 248, so the answer always arrives split and is reassembled by declared length. That is the
 * parser's business, not this class's.
 *
 * ```
 * header | enabled | unit flags | active insulin, minutes (2) | tables 1..6 (240) | crc of 0..243 (2) | frame crc
 * ```
 *
 * **Two things about the tables have to be got right or the numbers are quietly wrong.**
 *
 * A slot is `<value> <start>`, and `<start>` is the block's start in half hours from midnight. **A
 * slot whose start is 48 — that is 24:00 — is not in use**: the value beside it is left over from
 * an earlier edit. The slots in use come first and the parked ones follow, so a calculator set to
 * one block for the whole day reads as one slot at start 0 and eleven parked slots after it.
 *
 * The six tables are three quantities in two unit systems each, and **the members of a pair are
 * independent settings rather than conversions of one another**. A pump can hold 4.0 mmol/l in the
 * mmol table and 50 mg/dl in the mg/dl table at the same time, and those are different
 * sensitivities. So this class exposes the pair and the flag that chooses between them, and never
 * converts one into the other.
 */
data class Atc3BolusCalculator(
    /** Whether the pump's own calculator is switched on. */
    val enabled: Boolean,
    /** True when carbohydrates are counted in bread units, false when in grams. */
    val carbsInBreadUnits: Boolean,
    /** True when glucose is counted in mg/dl, false when in mmol/l. */
    val glucoseInMgdl: Boolean,
    /** How long the pump reckons insulin goes on working, minutes. */
    val activeInsulinMinutes: Int,
    /** Carb ratio in grams per unit, in force when [carbsInBreadUnits] is false. */
    val carbRatioGramsPerUnit: List<Slot>,
    /** Carb ratio in units per bread unit, in force when [carbsInBreadUnits] is true. */
    val carbRatioUnitsPerBreadUnit: List<Slot>,
    /** Sensitivity in mmol/l, in force when [glucoseInMgdl] is false. */
    val sensitivityMmol: List<Slot>,
    /** Sensitivity in mg/dl, in force when [glucoseInMgdl] is true. */
    val sensitivityMgdl: List<Slot>,
    /** Target glucose in mmol/l, in force when [glucoseInMgdl] is false. */
    val targetMmol: List<Slot>,
    /** Target glucose in mg/dl, in force when [glucoseInMgdl] is true. */
    val targetMgdl: List<Slot>
) {

    /**
     * One block of a table: a value and the time of day it starts at.
     *
     * Only slots in use are kept, so there is no "parked" state to check for downstream.
     */
    data class Slot(
        /** Value on the table's own scale, see [Atc3BolusCalculator]. */
        val value: Double,
        /** Half hours from midnight at which this block starts, 0 to 47. */
        val startHalfHour: Int
    ) {

        /** Minutes from midnight at which this block starts. */
        val startMinutes: Int get() = startHalfHour * 30
    }

    /** The carb ratio table the unit flag selects. */
    val carbRatioInForce: List<Slot>
        get() = if (carbsInBreadUnits) carbRatioUnitsPerBreadUnit else carbRatioGramsPerUnit

    /** The sensitivity table the unit flag selects. */
    val sensitivityInForce: List<Slot>
        get() = if (glucoseInMgdl) sensitivityMgdl else sensitivityMmol

    /** The target table the unit flag selects. */
    val targetInForce: List<Slot>
        get() = if (glucoseInMgdl) targetMgdl else targetMmol

    companion object {

        /** Size of the frame. */
        const val FRAME_SIZE = 254

        /** Slots in every table, in use or parked. */
        const val SLOTS = 12

        /** A start of 48 half hours, that is 24:00, marks a slot that is not in use. */
        const val PARKED_START = 48

        private fun slotsU16(frame: Atc3ResponseFrame, base: Int, scale: Double): List<Slot> =
            (0 until SLOTS).mapNotNull { i ->
                val start = frame.u16le(base + 4 * i + 2)
                if (start >= PARKED_START) null
                else Slot(frame.u16le(base + 4 * i) * scale, start)
            }

        private fun slotsByte(frame: Atc3ResponseFrame, base: Int, scale: Double): List<Slot> =
            (0 until SLOTS).mapNotNull { i ->
                val start = frame.byteAt(base + 2 * i + 1)
                if (start >= PARKED_START) null
                else Slot(frame.byteAt(base + 2 * i) * scale, start)
            }

        fun decode(frame: Atc3ResponseFrame): Atc3BolusCalculator? {
            if (frame.objectType != Atc3Const.ObjectType.BOLUS_CALCULATOR) return null
            if (frame.raw.size < FRAME_SIZE) return null
            val flags = frame.byteAt(Atc3Const.BolusCalculator.UNIT_FLAGS)
            return Atc3BolusCalculator(
                enabled = frame.byteAt(Atc3Const.BolusCalculator.ENABLED) != 0,
                carbsInBreadUnits = flags and Atc3Const.BolusCalculator.FLAG_BREAD_UNITS != 0,
                glucoseInMgdl = flags and Atc3Const.BolusCalculator.FLAG_MGDL != 0,
                activeInsulinMinutes = frame.u16le(Atc3Const.BolusCalculator.ACTIVE_INSULIN_MINUTES),
                carbRatioGramsPerUnit = slotsU16(frame, Atc3Const.BolusCalculator.TABLE_CARBS_GRAMS, 1.0),
                carbRatioUnitsPerBreadUnit = slotsU16(
                    frame, Atc3Const.BolusCalculator.TABLE_CARBS_BREAD_UNITS,
                    Atc3Const.BolusCalculator.BREAD_UNIT_SCALE
                ),
                sensitivityMmol = slotsU16(
                    frame, Atc3Const.BolusCalculator.TABLE_SENSITIVITY_MMOL,
                    Atc3Const.BolusCalculator.MMOL_SCALE
                ),
                sensitivityMgdl = slotsU16(frame, Atc3Const.BolusCalculator.TABLE_SENSITIVITY_MGDL, 1.0),
                targetMmol = slotsByte(
                    frame, Atc3Const.BolusCalculator.TABLE_TARGET_MMOL,
                    Atc3Const.BolusCalculator.MMOL_SCALE
                ),
                targetMgdl = slotsByte(frame, Atc3Const.BolusCalculator.TABLE_TARGET_MGDL, 1.0)
            )
        }
    }
}
