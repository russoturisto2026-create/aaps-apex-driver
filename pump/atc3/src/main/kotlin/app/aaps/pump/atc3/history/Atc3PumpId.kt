package app.aaps.pump.atc3.history

/**
 * Identifiers the driver hands to AAPS for pump events.
 *
 * The pump numbers nothing. A record carries no id, no sequence number and no field saying who
 * asked for it, so the only identity available is the one the driver assigns itself. An id is
 * allocated once, when a record is first seen, and kept in [Atc3HistoryLedger] from then on; it is
 * never re-derived from a freshly read timestamp, because the pump can move a stored record's
 * timestamp by one second when it inserts a correction record next to it.
 *
 * The layout keeps ids readable in a log: `id / 1000` is the pump clock second the event belongs
 * to. The kind digit keeps a bolus and a temporary basal that begin in the same second apart. The
 * bump digit exists because the pump can hand a vacated second to another record.
 */
object Atc3PumpId {

    const val KIND_BOLUS = 0L
    const val KIND_TBR_START = 1L
    const val KIND_TBR_END = 2L

    /** A pending bolus settled without the pump ever writing a record for it. */
    const val KIND_RETRACTION = 3L

    /** How far an id may be bumped away from a second another record already holds. */
    const val MAX_BUMP = 9

    fun of(pumpClockMs: Long, kind: Long, bump: Int = 0): Long =
        (pumpClockMs / 1000L) * 1000L + kind * 10L + bump.coerceIn(0, MAX_BUMP)

    /**
     * The start id of a temporary basal beginning at [pumpClockMs], one bump past [previous] when
     * that one began in the same second.
     *
     * The pump keeps the start of a temporary basal to the whole minute, so several set within one
     * minute all carry the same start. Under one id AAPS would take each for the one before and
     * rewrite it.
     */
    fun tbrStartAfter(pumpClockMs: Long, previous: Long?): Long {
        val base = of(pumpClockMs, KIND_TBR_START)
        if (previous == null || previous / 10L != base / 10L) return base
        return of(pumpClockMs, KIND_TBR_START, (previous % 10L).toInt() + 1)
    }

    /**
     * The end id belonging to a temporary basal's start id: the same second and the same bump. AAPS
     * takes an end id it already holds as an end already written, so two temporary basals of one
     * minute must not share one.
     */
    fun tbrEndOf(startId: Long): Long = startId + (KIND_TBR_END - KIND_TBR_START) * 10L
}
