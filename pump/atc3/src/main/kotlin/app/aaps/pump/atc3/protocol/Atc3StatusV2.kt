package app.aaps.pump.atc3.protocol

import app.aaps.pump.atc3.Atc3Const
import kotlin.math.roundToInt

/** The pump's second status: its own insulin on board and the battery voltage. */
data class Atc3StatusV2(
    /** Insulin on board as the pump itself counts it, U. */
    val activeInsulinUnits: Double,
    /** V. */
    val batteryVolts: Double
) {

    /** Charge, percent, derived from the voltage: the pump reports no percentage. */
    val batteryPercent: Int
        get() = ((batteryVolts - Atc3Const.BATTERY_EMPTY_VOLTS) * Atc3Const.BATTERY_PERCENT_PER_VOLT)
            .roundToInt()
            .coerceIn(0, 100)

    internal object Offset {

        const val ACTIVE_INSULIN = 2
        const val BATTERY_VOLTAGE = 5
    }

    companion object {

        fun decode(frame: Atc3ResponseFrame): Atc3StatusV2? {
            if (frame.objectType != Atc3Protocol.ObjectType.STATUS_V2) return null
            if (!frame.has(Offset.BATTERY_VOLTAGE)) return null
            if (!frame.has(Offset.ACTIVE_INSULIN, 2)) return null
            return Atc3StatusV2(
                activeInsulinUnits = frame.u16le(Offset.ACTIVE_INSULIN) * Atc3Protocol.DOSE_SCALE,
                batteryVolts = frame.byteAt(Offset.BATTERY_VOLTAGE) * Atc3Protocol.BATTERY_VOLTAGE_SCALE
            )
        }
    }
}
