package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.Atc3Const

/**
 * A pump that has stopped answering.
 *
 * AAPS cannot tell a pump out of reach from one that has lost power, and the status of a pump about
 * to lose it says nothing: on the bench, 2026-10-05, a pump on a dying battery answered "running, no
 * alarms" at a full battery voltage, was gone for eight hours, and AAPS credited it the scheduled
 * basal all that time. So the driver does not wait to be told:
 *
 * - after [ALARM_AFTER_MS] without an answer the user is told, and again at every [ALARM_AFTER_MS];
 * - after [STOP_AFTER_MS] the pump is held to be stopped from its last answer on: the loop does not
 *   run and no basal is credited, see [Stop];
 * - the answer that ends it is a beginning. The pump's journals are read, the stretch without an
 *   answer is written as the pump itself accounts for it, see [account], and the comparison of the
 *   pump's count with the AAPS journal starts anew from that read. Nothing is compared across it.
 *
 * Pure arithmetic over plain values. The caller reads the pump and writes the rows.
 */
object Atc3LinkWatch {

    /** Silence after which the user is told, and the period the telling is repeated at. */
    const val ALARM_AFTER_MS = 15 * 60_000L

    /** Silence after which the pump is held to be stopped. */
    const val STOP_AFTER_MS = 30 * 60_000L

    /** How many times the user is to have been told by now: once at every [ALARM_AFTER_MS] of silence. */
    fun alarmsDue(silenceMs: Long): Int = if (silenceMs < ALARM_AFTER_MS) 0 else (silenceMs / ALARM_AFTER_MS).toInt()

    fun stopDue(silenceMs: Long): Boolean = silenceMs >= STOP_AFTER_MS

    /**
     * The pump's last answer, which a stop is counted from: the moment of that status read and the
     * pump's count of delivered insulin as it stood in it.
     */
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

        /** The pump's count went on through the silence: the pump was running, and the count is its basal. */
        COUNTED,

        /**
         * The count began anew on the same day, so there is no count of the silence to go on.
         *
         * What makes the pump start its count again inside a day is not established. On the bench,
         * 2026-10-05, it came back at nothing after hours on a dead battery, with a motor error
         * raised as the new battery went in; the same day it kept its count through eleven minutes
         * without a battery and no such error.
         */
        COUNT_RESET,

        /**
         * The pump's midnight lies in the silence and the day that ended could not be had from the
         * pump's journal of daily totals: nothing is known of the time before midnight.
         */
        BEFORE_MIDNIGHT_UNKNOWN
    }

    data class Account(val stretches: List<Stretch>, val outcome: Outcome)

    /**
     * What the pump delivered as basal between its last answer and the read that ended the silence.
     *
     * The pump's count is of "today", holds every bolus the journal does, and begins anew at the
     * pump's midnight -- and has been seen to inside a day, see [Outcome.COUNT_RESET]. So:
     *
     * - on the same day, the count having gone on: the count less the boluses is the basal. A pump
     *   that stood without power and kept its count is this case too, and comes out at nothing;
     * - on the same day, the count below where it stood: nothing says what went in, and the
     *   stretch is a stop;
     * - across one midnight, with the total of the day that ended: counted to that total and on
     *   from nothing, as [Atc3BasalPeriod] does;
     * - across midnight without that total: the time before midnight is a stop, since nothing says
     *   otherwise, and the time after it is the count less the boluses of the new day.
     *
     * @param bolusUnits the boluses learned since the pump's last answer
     * @param midnightMs the midnight the day of [readMs] began at
     * @param dayTotalUnits what the pump's journal of daily totals holds for the day of the last
     *   answer, when [readMs] is in the day after; null when it could not be had or more than one
     *   midnight lies between
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

    /** In whole steps of the pump, and never less than nothing: a bolus rounded up is not negative basal. */
    private fun steps(units: Double): Double = maxOf(0.0, Math.round(units / Atc3Const.DOSE_SCALE) * Atc3Const.DOSE_SCALE)

    private const val PULSE_EPSILON = 1e-6
}
