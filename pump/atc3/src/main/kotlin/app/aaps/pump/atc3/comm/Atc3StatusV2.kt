package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const
import kotlin.math.roundToInt

/**
 * Decoded Status V2 frame, object `0x0C`.
 *
 * The pump reports the voltage of its cell and nothing else about the battery, in hundredths of a
 * volt: `0x8A`, 138, is 1.38 V. No byte carries a charge percentage.
 */
data class Atc3StatusV2(
    /** Active insulin (insulin on board) as the pump itself computes it, units. */
    val activeInsulinUnits: Double,
    /** Battery voltage in volts. */
    val batteryVolts: Double
) {

    /**
     * Battery charge, percent.
     *
     * Derived, not read: the pump sends only volts. The scale is a straight line of 3.5 % per
     * hundredth of a volt, 52 % at 1.38 V, clamped at both ends.
     */
    val batteryPercent: Int
        get() = ((batteryVolts - Atc3Const.BATTERY_EMPTY_VOLTS) * Atc3Const.BATTERY_PERCENT_PER_VOLT)
            .roundToInt()
            .coerceIn(0, 100)


    companion object {

        fun decode(frame: Atc3ResponseFrame): Atc3StatusV2? {
            if (frame.objectType != Atc3Const.ObjectType.STATUS_V2) return null
            if (!frame.has(Atc3Const.StatusV2.BATTERY_VOLTAGE)) return null
            if (!frame.has(Atc3Const.StatusV2.ACTIVE_INSULIN, 2)) return null
            return Atc3StatusV2(
                activeInsulinUnits = frame.u16le(Atc3Const.StatusV2.ACTIVE_INSULIN) * Atc3Const.DOSE_SCALE,
                batteryVolts = frame.byteAt(Atc3Const.StatusV2.BATTERY_VOLTAGE) * Atc3Const.BATTERY_VOLTAGE_SCALE
            )
        }
    }
}
