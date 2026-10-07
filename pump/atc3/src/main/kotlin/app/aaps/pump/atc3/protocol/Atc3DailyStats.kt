package app.aaps.pump.atc3.protocol

import app.aaps.pump.atc3.Atc3PumpPlugin
import java.util.Calendar

/**
 * One day of the pump's own totals. Its date is trusted only once the answer has a day that is the
 * pump's today, see [app.aaps.pump.atc3.Atc3PumpPlugin.loadTDDs]: totals filed under the wrong day
 * are worse than none.
 */
data class Atc3DailyStats(
    val year: Int,
    val month: Int,
    val day: Int,
    val bolusUnits: Double,
    val basalUnits: Double,
    val tbrUnits: Double
) {

    /** The total daily dose. */
    val totalUnits: Double get() = bolusUnits + basalUnits + tbrUnits

    /** Basal as AAPS counts it: scheduled and temporary together. */
    val basalWithTbrUnits: Double get() = basalUnits + tbrUnits

    /** True for a day before the pump was in use: all zero, and not to be imported as a day without insulin. */
    val isEmpty: Boolean get() = month == 0 && day == 0

    /** True when the date bytes can be a date. */
    val isDatePlausible: Boolean
        get() = year in PLAUSIBLE_YEARS && month in 1..12 && day in 1..31

    /** Local midnight at the start of this day, how AAPS stamps a daily dose. */
    fun startOfDayMillis(): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, 0, 0, 0)
    }.timeInMillis

    fun isSameDayAs(millis: Long): Boolean {
        val other = Calendar.getInstance().apply { timeInMillis = millis }
        return year == other.get(Calendar.YEAR) &&
            month == other.get(Calendar.MONTH) + 1 &&
            day == other.get(Calendar.DAY_OF_MONTH)
    }

    internal object Offset {

        const val BOLUS = 2
        const val BASAL = 4
        const val TBR = 6
        const val DATE = 8
    }

    companion object {

        const val FRAME_SIZE = 15

        private val PLAUSIBLE_YEARS = 2000..2099

        fun decode(frame: Atc3ResponseFrame): Atc3DailyStats? {
            if (frame.objectType != Atc3Protocol.ObjectType.DAILY_STATS) return null
            if (frame.raw.size < FRAME_SIZE) return null
            return Atc3DailyStats(
                year = 2000 + frame.byteAt(Offset.DATE),
                month = frame.byteAt(Offset.DATE + 1),
                day = frame.byteAt(Offset.DATE + 2),
                bolusUnits = frame.u16le(Offset.BOLUS) * Atc3Protocol.DOSE_SCALE,
                basalUnits = frame.u16le(Offset.BASAL) * Atc3Protocol.DOSE_SCALE,
                tbrUnits = frame.u16le(Offset.TBR) * Atc3Protocol.DOSE_SCALE
            )
        }
    }
}
