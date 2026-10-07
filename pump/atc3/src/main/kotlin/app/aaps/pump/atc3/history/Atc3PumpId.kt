package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.basal.Atc3BasalPeriod
import app.aaps.pump.atc3.link.Atc3LinkWatch

/**
 * The ids the driver gives AAPS for pump events: the pump numbers nothing, so an id is allocated once,
 * when a record is first seen, and kept in [Atc3HistoryLedger]. `id / 1000` is the second the event
 * belongs to; a kind digit keeps a bolus and a temporary basal of one second apart, and a bump digit
 * a second the pump gave to another record.
 */
object Atc3PumpId {

    const val KIND_BOLUS = 0L
    const val KIND_TBR_START = 1L
    const val KIND_TBR_END = 2L

    /** A pending bolus settled without the pump ever writing a record for it. */
    const val KIND_RETRACTION = 3L

    /** The basal of a passed half hour by the pump's count, see [Atc3BasalPeriod]. */
    const val KIND_BASAL_FACT = 4L

    /** The stop a pump is held in for want of an answer, see [Atc3LinkWatch]. */
    const val KIND_LINK_STOP = 5L

    /** How far an id may be bumped away from a second another record already holds. */
    const val MAX_BUMP = 9

    fun of(pumpClockMs: Long, kind: Long, bump: Int = 0): Long =
        (pumpClockMs / 1000L) * 1000L + kind * 10L + bump.coerceIn(0, MAX_BUMP)

    /** The start id of a temporary basal at [pumpClockMs], one bump past [previous] when that began in the same second: several of one minute share a start. */
    fun tbrStartAfter(pumpClockMs: Long, previous: Long?): Long {
        val base = of(pumpClockMs, KIND_TBR_START)
        if (previous == null || previous / 10L != base / 10L) return base
        return of(pumpClockMs, KIND_TBR_START, (previous % 10L).toInt() + 1)
    }

    /** The end id of a temporary basal's start id; two of one minute must not share one. */
    fun tbrEndOf(startId: Long): Long = startId + (KIND_TBR_END - KIND_TBR_START) * 10L
}
