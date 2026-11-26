package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.aaps.NoticeKind
import app.aaps.pump.apex.clock.ApexClock
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.store.Action
import kotlin.math.abs

/**
 * A faulty cell: a record that comes the same at every read and that the pump cannot have written so, see
 * [implausible], or that stands out of place, see [Suspicion]. Its dose is not taken: the statistics give it,
 * see [give], and it stands in the middle of its window, see [middle]. The same rules serve the reconciliation
 * at the heartbeat (`BolusReconciliation`) and a whole journal (`WholeJournal`): they differ only in what they hold the
 * records against, the move of the statistics since the last heartbeat, or whole days. The records of the pump
 * are what was done; our own picture of a bolus only finds its record and sets the most it may take.
 */
object FaultyCell {

    /** How much a record looks like a faulty cell. */
    enum class Suspicion {

        /** The pump can have written it so: it goes as it is. */
        SOUND,

        /**
         * Stamped before a record below it. A zone move can do that; a faulty cell can too, a new record
         * written over the date of an old one. Its dose is held against the statistics.
         */
        OUT_OF_PLACE,

        /** The pump cannot have written it so, see [implausible]: its dose is not taken, the statistics give it. */
        FAULTY
    }

    /** How [bolus], stamped at [atMs], looks; [belowMs]: the latest sound record below it, null when none. */
    fun suspicion(bolus: ApexBolusRecord, atMs: Long, belowMs: Long?, reservoir: Double?, nowMs: Long): Suspicion = when {
        implausible(bolus, atMs, reservoir, nowMs)  -> Suspicion.FAULTY
        belowMs != null && atMs < belowMs           -> Suspicion.OUT_OF_PLACE
        else                                        -> Suspicion.SOUND
    }

    /**
     * A record the pump cannot have written: no date or nothing delivered (it came so at every read, see
     * `BrokenRecord`), stamped in the future, above the reservoir, or more delivered than asked.
     */
    fun implausible(bolus: ApexBolusRecord, atMs: Long, reservoir: Double?, nowMs: Long): Boolean =
        bolus.flickers || atMs > nowMs + ApexClock.STAMP_AHEAD_MS || aboveReservoir(bolus.totalDeliveredUnits, reservoir) || bolus.deliveredAboveAsked

    /** More than the reservoir held; never true before the first status. */
    fun aboveReservoir(units: Double, reservoir: Double?): Boolean = reservoir?.let { units > it + ApexProtocol.ROUNDING } == true

    /**
     * Where a faulty record stands: the middle of the window between the sound records around it, so that its
     * insulin stays where it went in. A window turned inside out (the record above came stamped earlier) is
     * taken at its end.
     */
    fun middle(fromMs: Long, toMs: Long): Long = if (fromMs <= toMs) (fromMs + toMs) / 2 else toMs

    /**
     * Where the row of our bolus starts before the statistics set it: what the pump showed when its last
     * word was seen and it gave less than ordered, else the order, see [BolusAccount.seenRow].
     */
    fun startUnits(action: Action, account: BolusAccount): Double = account.seenRow(action)?.amountUnits ?: action.amount

    /**
     * Give [rest], U, to the rows [units] in their order: each moves by what is left, between 0 and its
     * [ceilings]. Returns what none could take. The statistics give only a sum: how it was shared among
     * several faulty records is not known, so the first one takes all it can, and IOB holds the whole sum.
     */
    fun give(units: MutableList<Double>, ceilings: List<Double>, rest: Double): Double {
        var left = rest
        for (i in units.indices) {
            if (abs(left) < ApexProtocol.HALF_STEP) return 0.0
            val was = units[i]
            units[i] = ApexProtocol.onStep((was + left).coerceIn(0.0, ceilings[i]))
            left = ApexProtocol.onStep(left - (units[i] - was))
        }
        return if (abs(left) < ApexProtocol.HALF_STEP) 0.0 else left
    }

    /**
     * The notice of one row: our bolus the statistics changed with no record of its own; a faulty record or a
     * row of ours the statistics changed, with its dose; a faulty record left at nothing, as unknown, unless
     * [sharedOn] another faulty record of the same span, which tells it, see [sharedNotice]. Null when the row
     * is sound and stands as it came.
     */
    fun notice(faulty: Boolean, ours: Boolean, hasRecord: Boolean, units: Double, atMs: Long, sharedOn: Boolean): AapsWrite.Notice? {
        val some = units >= ApexProtocol.HALF_STEP
        return when {
            !faulty            -> null
            ours && !hasRecord -> AapsWrite.Notice(NoticeKind.OURS_NO_RECORD, units = units, atMs = atMs)
            some || ours       -> AapsWrite.Notice(NoticeKind.JOURNAL_FAULT, units = units, atMs = atMs)
            sharedOn           -> null
            else               -> AapsWrite.Notice(NoticeKind.JOURNAL_FAULT_UNKNOWN, atMs = atMs)
        }
    }

    /** More than one faulty record shared a sum: the user is told the first one took it all, [units] at [atMs]. */
    fun sharedNotice(faultyCount: Int, units: Double, atMs: Long): AapsWrite.Notice? =
        if (faultyCount > 1) AapsWrite.Notice(NoticeKind.FAULTS_SHARED, units = units, atMs = atMs) else null
}
