package app.aaps.pump.atc3.clock

import java.util.Calendar

/** The local calendar questions of the driver: the pump's half hours and midnight are the phone's. */
internal object Atc3DayClock {

    const val HALF_HOUR_MS = 30 * 60_000L

    /** The start of the half hour of the clock [ms] lies in. */
    fun halfHourOf(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.MILLISECOND, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MINUTE, if (get(Calendar.MINUTE) < 30) 0 else 30)
    }.timeInMillis

    /** The midnight the day of [ms] began at. */
    fun dayStartOf(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun sameDay(a: Long, b: Long): Boolean = dayStartOf(a) == dayStartOf(b)
}
