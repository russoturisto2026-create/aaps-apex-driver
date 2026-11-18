package app.aaps.pump.apex.deep

import app.aaps.pump.apex.account.bolus.BolusCountMark
import app.aaps.pump.apex.account.bolus.BolusSpanEnd
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.pulse.BolusGate

/**
 * How much insulin went in as boluses over a span, by the pump's own totals, and not by its records.
 *
 * The problem. The bolus rows come from the journal records, one row per record. The records alone
 * cannot show what they lack: a faulty cell holds an old or impossible record in place of a real
 * bolus, a record can be missing, and a bolus given by another client is known only when its record
 * is read. A second source is needed to close a faulty cell and to see that the records and the
 * pump disagree. That source is the pump's total of the day, and it has to be turned into "boluses
 * over this span".
 *
 * When it applies. Only when the records are held against the pump: to give a faulty cell its dose,
 * see [BolusDeep] and [BolusByDaysDeep], and to check the sound records at a heartbeat, see
 * [BolusReconcileDeep]. A heartbeat whose new records are all ours, and sound, does not need it.
 *
 * The way out.
 * - A pump with the daily statistics: the move of their bolus field since the day kept, see
 *   [BolusGate.bolusMovedSinceKept].
 * - A pump without them: the move of its count of the day since the mark, see [BolusCountMark], less
 *   the basal the rates order over the span, in whole steps of the pump and never below zero. The
 *   count is good to a step or two of basal over a span, well inside the 0.1 U the sound records are
 *   checked by.
 * - The pump's day changed, or the count went back: the count cannot be used, and the move of the
 *   reservoir stands in its place. The reservoir is not known at both ends, or it rose (a fill or a
 *   change): nothing can close the span, ever.
 * - AAPS cannot tell the basal of the span yet: not now, a later read over a longer span can.
 * - No count is kept: this is the start, nothing was compared before it, and nothing can close the
 *   span, see `Reconciliation`.
 */
class BolusMoveDeep(
    private val store: ApexStore,
    private val serial: String,
    /** The basal the rates order from the first moment to the second, U; null while AAPS cannot tell. */
    private val basalBetween: (startMs: Long, endMs: Long) -> Double?
) {

    /** Where the move of a span came from. */
    enum class Source(val text: String) {
        DAY_RECORD("the bolus field of the daily statistics"),
        COUNT("the count less the basal"),
        RESERVOIR("the reservoir less the basal")
    }

    /** What is known of the span. */
    sealed interface Answer {

        /** False when the answer cannot be settled until a later read. */
        val closes: Boolean get() = true

        /** [units] went in as boluses over the span, by [source]; [undatedDay]: a day with no date stands among the days summed. */
        class Units(val units: Double, val source: Source, val undatedDay: Boolean = false) : Answer {

            /** True when the move is the count's or the reservoir's, which close a faulty cell only. */
            val byCount: Boolean get() = source != Source.DAY_RECORD
        }

        /** The statistics cannot tell: no day kept, or the day kept is not in the answer. */
        data object Unknown : Answer

        /** The count cannot be used yet, for [why]; a later read over a longer span can. */
        class NotYet(val why: String) : Answer {

            override val closes: Boolean get() = false
        }

        /** Neither the count nor the reservoir can ever close this span, for [why]. */
        class Never(val why: String) : Answer
    }

    /** What [end] tells of the span; null [end] is nothing read, which cannot close a span yet. */
    fun over(end: BolusSpanEnd?): Answer = when (end) {
        null                      -> Answer.NotYet("the pump gave neither statistics nor count")
        is BolusSpanEnd.DayRecord -> byDays(end)
        is BolusSpanEnd.Count     -> byCount(end)
    }

    private fun byDays(end: BolusSpanEnd.DayRecord): Answer {
        val gate = BolusGate(store, serial)
        val moved = gate.bolusMovedSinceKept(end.days) ?: return Answer.Unknown
        return Answer.Units(moved, Source.DAY_RECORD, undatedDay = gate.undatedSinceKept(end.days))
    }

    private fun byCount(end: BolusSpanEnd.Count): Answer {
        val mark = BolusCountMark(store, serial).get() ?: return Answer.Never("no count was kept before this start")
        if (end.atMs <= mark.atMs) return Answer.Never("the phone's clock went back since the count kept")
        val countWhy = when {
            end.count.pumpDay != mark.count.pumpDay                         -> "the pump's day changed since the count kept"
            end.count.units < mark.count.units - ApexProtocol.DOSE_SCALE / 2 -> "the pump's count went back"
            else                                                            -> null
        }
        val source = if (countWhy == null) Source.COUNT else Source.RESERVOIR
        val movedUnits = if (countWhy == null) end.count.units - mark.count.units else reservoirMove(mark.reservoirUnits, end.reservoirUnits)
            ?: return Answer.Never("$countWhy, and the reservoir cannot tell: it is not known at both ends, or it went up")
        val basal = basalBetween(mark.atMs, end.atMs) ?: return Answer.NotYet("AAPS cannot tell the basal of the span")
        val movedSteps = Math.round(movedUnits / ApexProtocol.DOSE_SCALE)
        val basalSteps = Math.round(basal / ApexProtocol.DOSE_SCALE)
        return Answer.Units(maxOf(0L, movedSteps - basalSteps) * ApexProtocol.DOSE_SCALE, source)
    }

    /** What the reservoir lost from [from] to [now], U; null when it is not known at both ends or it rose (it was filled or changed). */
    private fun reservoirMove(from: Double?, now: Double?): Double? {
        if (from == null || now == null || now > from + ApexProtocol.DOSE_SCALE / 2) return null
        return maxOf(0.0, from - now)
    }
}
