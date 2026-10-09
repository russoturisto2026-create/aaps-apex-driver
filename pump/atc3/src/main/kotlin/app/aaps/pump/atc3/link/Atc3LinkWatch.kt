package app.aaps.pump.atc3.link

import kotlinx.serialization.Serializable

/**
 * A pump that stopped answering: AAPS cannot tell it from one out of reach, and a pump about to
 * lose power reports nothing first. So:
 *
 * - after [ALARM_AFTER_MS] of silence the user is told, and again at every [ALARM_AFTER_MS];
 * - after [STOP_AFTER_MS] the pump is held to be stopped from its last answer, see [Stop]: the loop
 *   does not run and no basal is credited, a row of none standing for the silence;
 * - the answer that ends it closes that row and lifts the stop; what the pump delivered meanwhile is
 *   not written back, the window closed at that answer shows it, see
 *   [app.aaps.pump.atc3.basal.Atc3BasalPeriod].
 *
 * Arithmetic only; the caller reads the pump.
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
    @Serializable
    data class Stop(val fromReadMs: Long, val fromCounterUnits: Double)
}
