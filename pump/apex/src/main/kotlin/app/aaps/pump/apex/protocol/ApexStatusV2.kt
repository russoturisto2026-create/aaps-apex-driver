package app.aaps.pump.apex.protocol

import kotlin.math.roundToInt

/** The pump's second status: its own insulin on board and the battery voltage. */
data class ApexStatusV2(
    /** Insulin on board as the pump itself counts it, U. */
    val activeInsulinUnits: Double,
    /** V. */
    val batteryVolts: Double
) {

    /** Charge, percent, derived from the voltage: the pump reports no percentage. */
    val batteryPercent: Int
        get() = ((batteryVolts - BATTERY_EMPTY_VOLTS) * BATTERY_PERCENT_PER_VOLT)
            .roundToInt()
            .coerceIn(0, 100)

    internal object Offset {

        const val ACTIVE_INSULIN = 2
        const val BATTERY_VOLTAGE = 5
    }

    companion object {

        /** The voltage the battery shows as empty at; the pump reports volts only, the percentage is a straight line from here. */
        const val BATTERY_EMPTY_VOLTS = 1.2305

        /** How many percent the charge scale moves per volt. */
        const val BATTERY_PERCENT_PER_VOLT = 350.0

        fun decode(frame: ApexResponseFrame): ApexStatusV2? {
            if (frame.objectType != ApexProtocol.ReadOpcode.STATUS_V2) return null
            if (!frame.has(Offset.BATTERY_VOLTAGE)) return null
            if (!frame.has(Offset.ACTIVE_INSULIN, 2)) return null
            return ApexStatusV2(
                activeInsulinUnits = frame.u16le(Offset.ACTIVE_INSULIN) * ApexProtocol.DOSE_SCALE,
                batteryVolts = frame.byteAt(Offset.BATTERY_VOLTAGE) * ApexProtocol.BATTERY_VOLTAGE_SCALE
            )
        }
    }
}
