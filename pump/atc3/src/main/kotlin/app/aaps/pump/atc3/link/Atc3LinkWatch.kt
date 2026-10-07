package app.aaps.pump.atc3.link

import app.aaps.pump.atc3.protocol.Atc3Protocol

/**
 * A pump that stopped answering: AAPS cannot tell it from one out of reach, and a pump about to
 * lose power reports nothing first. So:
 *
 * - after [ALARM_AFTER_MS] of silence the user is told, and again at every [ALARM_AFTER_MS];
 * - after [STOP_AFTER_MS] the pump is held to be stopped from its last answer: the loop does not
 *   run and no basal is credited, see [Stop];
 * - the answer that ends it is a beginning: the silence is written as the pump accounts for it,
 *   see [account], and the comparison with the AAPS journal starts anew.
 *
 * Arithmetic only; the caller reads the pump and writes the rows.
 */
object Atc3LinkWatch {

    /** Silence after which the user is told, and the period of telling again. */
    const val ALARM_AFTER_MS = 15 * 60_000L

    /** Silence after which the pump is held to be stopped. */
    const val STOP_AFTER_MS = 30 * 60_000L

    /** How many times the user is to have been told by now. */
    fun alarmsDue(silenceMs: Long): Int = if (silenceMs < ALARM_AFTER_MS) 0 else (silenceMs / ALARM_AFTER_MS).toInt()

    fun stopDue(silenceMs: Long): Boolean = silenceMs >= STOP_AFTER_MS

    /** The pump's last answer, a stop is counted from: its moment and the pump's count in it. */
    data class Stop(val fromReadMs: Long, val fromCounterUnits: Double) {

        fun encode(): String = "$fromReadMs;$fromCounterUnits"

        companion object {

            fun decode(text: String): Stop? {
                val parts = text.split(';')
                val readMs = parts.getOrNull(0)?.toLongOrNull() ?: return null
                val counter = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
                return Stop(readMs, counter)
            }
        }
    }

    /** The basal of one stretch of the time without an answer. */
    data class Stretch(val fromMs: Long, val toMs: Long, val units: Double)

    enum class Outcome {

        /** The count went on through the silence: the pump ran, and the count is its basal. */
        COUNTED,

        /** The count began anew within the day: nothing says what went in. */
        COUNT_RESET,

        /** The pump's midnight lies in the silence and the day's total could not be had: nothing is known before midnight. */
        BEFORE_MIDNIGHT_UNKNOWN
    }

    data class Account(val stretches: List<Stretch>, val outcome: Outcome)

    /**
     * The basal the pump delivered between its last answer and the read that ended the silence, from
     * its count of the day less the boluses: on the same day the count that went on is the basal, and a
     * count that fell back says nothing; across one midnight the day's total joins the two counts, and
     * without it the time before midnight is a stop.
     *
     * @param bolusUnits the boluses learned since the last answer
     * @param midnightMs the midnight the day of [readMs] began at
     * @param dayTotalUnits the daily total of the last answer's day when [readMs] is the next day; null when not had or more than one midnight lies between
     * @param bolusSinceMidnightUnits the boluses of the day of [readMs]
     */
    fun account(
        stop: Stop,
        readMs: Long,
        counterUnits: Double,
        bolusUnits: Double,
        midnightMs: Long,
        dayTotalUnits: Double?,
        bolusSinceMidnightUnits: Double
    ): Account {
        if (stop.fromReadMs >= midnightMs) {
            return if (counterUnits + PULSE_EPSILON < stop.fromCounterUnits)
                Account(listOf(Stretch(stop.fromReadMs, readMs, 0.0)), Outcome.COUNT_RESET)
            else
                Account(listOf(Stretch(stop.fromReadMs, readMs, steps(counterUnits - stop.fromCounterUnits - bolusUnits))), Outcome.COUNTED)
        }
        if (dayTotalUnits != null && dayTotalUnits + PULSE_EPSILON >= stop.fromCounterUnits) {
            val counted = dayTotalUnits - stop.fromCounterUnits + counterUnits
            return Account(listOf(Stretch(stop.fromReadMs, readMs, steps(counted - bolusUnits))), Outcome.COUNTED)
        }
        return Account(
            listOf(
                Stretch(stop.fromReadMs, midnightMs, 0.0),
                Stretch(midnightMs, readMs, steps(counterUnits - bolusSinceMidnightUnits))
            ).filter { it.toMs > it.fromMs },
            Outcome.BEFORE_MIDNIGHT_UNKNOWN
        )
    }

    /** In whole pump steps, and never below nothing: a bolus rounded up is not negative basal. */
    private fun steps(units: Double): Double = maxOf(0.0, Math.round(units / Atc3Protocol.DOSE_SCALE) * Atc3Protocol.DOSE_SCALE)

    private const val PULSE_EPSILON = 1e-6
}
