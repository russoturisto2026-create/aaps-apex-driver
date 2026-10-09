package app.aaps.pump.atc3.basal

import app.aaps.pump.atc3.clock.Atc3DayClock
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.protocol.Atc3Protocol
import kotlinx.serialization.Serializable

/**
 * The half hour as the window of the basal account. The pump delivers a rate in portions on a count
 * of its own, so what was ordered and what went in drift apart inside the half hour; what does not
 * drift is the pump's count of the day. So:
 *
 * - inside the window AAPS holds the rows as they were ordered;
 * - the first read in a new half hour of the clock closes the window before it; the driver asks for
 *   that read itself, see [msToNextRead];
 * - boluses first: a window is closed only with every bolus in it matched to its record, and the
 *   rest of the count is its basal;
 * - a bolus that ran across the end has the window closed again, up to a later read;
 * - across the pump's midnight the day's total joins the two counts.
 *
 * The window's start is also where the comparison of the count with the journal starts from, see
 * [app.aaps.pump.atc3.check.Atc3Reconciliation]. Arithmetic only; the keeper reads the pump and
 * keeps the window on disk.
 */
object Atc3BasalPeriod {

    /** A status read a window is counted from or to. */
    @Serializable
    data class Mark(
        val readMs: Long,
        val counterUnits: Double,
        /** Boluses learned after this moment are the window's; see [Atc3HistorySync.bolusesLearnedAfter]. */
        val learnedAfterMs: Long
    )

    /** The window closed last, kept so that a bolus learned later that ran across its end can close it again. */
    @Serializable
    data class Closed(
        val start: Mark,
        val endReadMs: Long,
        /** When it was closed: a bolus learned after this that began by [endReadMs] ran across its end. */
        val closedAtMs: Long
    )

    /** The window under way and the one closed last, kept across a restart. */
    @Serializable
    data class State(val start: Mark? = null, val closed: Closed? = null)

    /** A window closed by the pump's count: what the keeper found, handed on to [Atc3BasalSpread]. */
    data class Window(
        val fromMs: Long,
        val toMs: Long,
        /** What the pump counted over the window, boluses included. */
        val pumpUnits: Double,
        /** The boluses of the window, by their records. */
        val bolusUnits: Double,
        /** The count less the boluses, in whole pump steps: the basal that went in. */
        val basalUnits: Double,
        /** What the rows order over the window, basal only; null while no profile runs. */
        val orderedUnits: Double?,
        /** True when the window had been closed before and is closed again, up to a later read. */
        val again: Boolean
    ) {

        /** How far the basal that went in sits from what the rows order, or null while no profile runs. */
        val differenceUnits: Double? get() = orderedUnits?.let { basalUnits - it }
    }

    /** What a status read comes to for the window under way. */
    sealed interface Step {

        /** Not a boundary yet. */
        data object Wait : Step

        /** The window ends at this read: [countedUnits] went in over it, [units] of it basal. */
        data class Close(val units: Double, val countedUnits: Double) : Step

        /** A boundary, but something is still owed; the next read asks again. */
        data class Postpone(val why: String) : Step

        /** There is no count to close from: the window begins anew at this read. */
        data class Restart(val why: String) : Step
    }

    /**
     * @param bolusUnits the boluses learned since the window began
     * @param settled true when the bolus journal was read now and no bolus of ours waits for its record
     * @param dayTotalUnits the daily total of the window's first day, when this read is in the next; null when not had
     * @param force true to close at this read whatever it is: a window closed again
     */
    fun step(
        start: Mark,
        readMs: Long,
        counterUnits: Double,
        bolusUnits: Double,
        settled: Boolean,
        dayTotalUnits: Double? = null,
        force: Boolean = false
    ): Step {
        if (readMs <= start.readMs) return Step.Wait
        val newDay = dayChanged(start.readMs, readMs)
        if (!force && !isDue(start.readMs, readMs)) return Step.Wait
        // More than one midnight in between: the driver was not there, and there is no window.
        if (newDay && !Atc3DayClock.sameDay(start.readMs + Atc3DayClock.DAY_MS, readMs)) return Step.Restart("days_apart")
        if (!settled) return Step.Postpone("boluses")
        val counted =
            if (newDay) (dayTotalUnits ?: return Step.Postpone("day_total")) - start.counterUnits + counterUnits
            else counterUnits - start.counterUnits
        // The count holds every bolus the journal does: the rest is basal, in whole pump steps.
        return Step.Close(Math.round((counted - bolusUnits) / Atc3Protocol.DOSE_SCALE) * Atc3Protocol.DOSE_SCALE, counted)
    }

    /** True when [readMs] ends the window begun at [startMs]: it is at or past the next half hour of the clock. */
    fun isDue(startMs: Long, readMs: Long): Boolean = readMs >= Atc3DayClock.halfHourOf(startMs) + Atc3DayClock.HALF_HOUR_MS

    /** True when the pump's midnight lies between the two. */
    fun dayChanged(startMs: Long, readMs: Long): Boolean = !Atc3DayClock.sameDay(startMs, readMs)

    /** How long to wait before asking the pump for the read a window ends at: a little past the next half hour. */
    fun msToNextRead(nowMs: Long): Long = Atc3DayClock.halfHourOf(nowMs) + Atc3DayClock.HALF_HOUR_MS + AFTER_BOUNDARY_MS - nowMs

    /** How far past the half hour the read is asked for, so that it falls after it on either clock. */
    const val AFTER_BOUNDARY_MS = 3_000L
}
