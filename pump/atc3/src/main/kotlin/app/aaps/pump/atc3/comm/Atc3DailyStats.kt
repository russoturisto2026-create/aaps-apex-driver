package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const
import java.util.Calendar

/**
 * One day of totals, object `0x06`.
 *
 * Bolus, basal and temporary basal are three separate amounts, and all three count towards the
 * day's total.
 *
 * The three date bytes are read as the first three of the pump's ordinary clock format, year since
 * 2000 then month then day, and that reading is **checked before it is used**: see
 * [app.aaps.pump.atc3.Atc3PumpPlugin.loadTDDs], which refuses the whole answer unless one of the
 * records lands on the day the pump's own clock says it is. Guessing a date would put a day's worth
 * of insulin on the wrong day, which is worse than reporting nothing.
 */
data class Atc3DailyStats(
    val year: Int,
    val month: Int,
    val day: Int,
    val bolusUnits: Double,
    val basalUnits: Double,
    val tbrUnits: Double
) {

    /** Everything the pump delivered that day, which is what AAPS calls the total daily dose. */
    val totalUnits: Double get() = bolusUnits + basalUnits + tbrUnits

    /** Basal as AAPS counts it: the scheduled rate plus whatever a temporary basal changed it to. */
    val basalWithTbrUnits: Double get() = basalUnits + tbrUnits

    /**
     * True for a day the pump was not yet in use.
     *
     * Those records carry zeroes, and importing them would tell AAPS the user took no insulin at
     * all on days it knows nothing about. A month of zero is what gives them away; the year byte is
     * zero too, but that decodes to 2000 rather than to nothing.
     */
    val isEmpty: Boolean get() = month == 0 && day == 0

    /** True when the date bytes could not be a date at all. */
    val isDatePlausible: Boolean
        get() = year in PLAUSIBLE_YEARS && month in 1..12 && day in 1..31

    /** Midnight local time at the start of this day, which is how AAPS stamps a daily dose. */
    fun startOfDayMillis(): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, 0, 0, 0)
    }.timeInMillis

    /** True when this record is for the same day as [millis] on the local calendar. */
    fun isSameDayAs(millis: Long): Boolean {
        val other = Calendar.getInstance().apply { timeInMillis = millis }
        return year == other.get(Calendar.YEAR) &&
            month == other.get(Calendar.MONTH) + 1 &&
            day == other.get(Calendar.DAY_OF_MONTH)
    }

    companion object {

        /** Smallest frame that carries a whole record. */
        const val FRAME_SIZE = 15

        private val PLAUSIBLE_YEARS = 2000..2099

        fun decode(frame: Atc3ResponseFrame): Atc3DailyStats? {
            if (frame.objectType != Atc3Const.ObjectType.DAILY_STATS) return null
            if (frame.raw.size < FRAME_SIZE) return null
            return Atc3DailyStats(
                year = 2000 + frame.byteAt(Atc3Const.DailyStats.DATE),
                month = frame.byteAt(Atc3Const.DailyStats.DATE + 1),
                day = frame.byteAt(Atc3Const.DailyStats.DATE + 2),
                bolusUnits = frame.u16le(Atc3Const.DailyStats.BOLUS) * Atc3Const.DOSE_SCALE,
                basalUnits = frame.u16le(Atc3Const.DailyStats.BASAL) * Atc3Const.DOSE_SCALE,
                tbrUnits = frame.u16le(Atc3Const.DailyStats.TBR) * Atc3Const.DOSE_SCALE
            )
        }
    }
}
