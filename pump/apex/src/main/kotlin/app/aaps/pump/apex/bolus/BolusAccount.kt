package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.store.Action
import app.aaps.pump.apex.store.ApexStore
import app.aaps.pump.apex.store.BolusCell
import app.aaps.pump.apex.clock.ApexClock
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexProtocol
import kotlin.math.abs

/**
 * The plain path of the bolus account, our own picture of each bolus of ours, see [Action]. The picture
 * lives in a cell of the driver's bolus journal, see [BolusCell].
 *
 * - The pump accepts our bolus: it gets its cell, and its row goes to AAPS at once, at the ordered dose,
 *   under a temporary number, see [commandAccepted]. More insulin on board than went in is less harm than less.
 * - While it runs, the picture keeps what the pump shows: its progress frames, the cancel.
 * - It ends: when the pump's last word on it was seen (the completion frame, or the accepted cancel
 *   after a progress frame) and it gave less than ordered, the row shows that at once, see [seenRow].
 * - At the heartbeat with no bolus running, the reconciliation pairs each new record of the journal with
 *   our picture by what was ordered and what was written, see [pair]. A pair is final: its row takes
 *   the record's key (see `BolusKey`) and dose, see [row]. What is left on either side is in [BolusReconciliation].
 */
class BolusAccount(private val store: ApexStore, private val serial: String) {

    /** Our boluses not reconciled yet, oldest first. */
    fun openActions(): List<Action> = store.cells(serial).filter { it.open }.mapNotNull { it.action }.sortedBy { it.acceptedAtMs }

    /**
     * The pump accepted a bolus of ours at [acceptedAtMs]: it gets its cell, with the place the journal
     * stood at by the records kept at the last heartbeat, and the row that goes to AAPS at once.
     */
    fun commandAccepted(acceptedAtMs: Long, units: Double, type: String): Pair<Action, AapsWrite.EarlyBolus> {
        val action = Action(acceptedAtMs = acceptedAtMs, amount = units, type = type, journalTopSeq = BolusCells(store, serial).topSeq())
        val cell = store.putCell(serial, BolusCell(action = action, inAaps = true))
        return cell.action!! to AapsWrite.EarlyBolus(action.tempId, acceptedAtMs, units, type)
    }

    /** The pump reported [seenUnits] delivered of our running bolus in a progress frame. */
    fun progress(action: Action, seenUnits: Double): Action =
        action.copy(seenUnits = seenUnits, frames = maxOf(action.frames, 1)).also { update(it) }

    /** Put the picture [action] back in its cell. */
    private fun update(action: Action) {
        val cell = store.cells(serial).firstOrNull { it.id == action.id } ?: return
        store.putCell(serial, cell.copy(action = action))
    }

    /**
     * Our bolus ended: [reportedUnits] is what the pump said last, after [frames] progress frames;
     * [cancelPressed] the user asked to stop it, [cancelAccepted] the pump accepted that, [completed] its
     * completion frame came.
     */
    fun finished(action: Action, reportedUnits: Double, frames: Int, cancelPressed: Boolean, cancelAccepted: Boolean, completed: Boolean): Action =
        action.copy(
            seenUnits = reportedUnits, frames = maxOf(action.frames, frames), cancelPressed = cancelPressed,
            cancelAccepted = cancelAccepted, completed = completed
        ).also { update(it) }

    /**
     * The row of our bolus set to what the pump showed, when its last word was seen and it gave less than
     * ordered; null otherwise: the row stays at the ordered dose until the reconciliation.
     */
    fun seenRow(action: Action): AapsWrite.Bolus? {
        val seen = action.seenUnits ?: return null
        // A completion frame with no dose in it and no progress frame before: nothing was seen.
        if (action.frames == 0 && seen <= 0.0) return null
        if (!action.lastWordSeen || seen > action.amount - ApexProtocol.HALF_STEP) return null
        return AapsWrite.Bolus(pumpId = null, timestampMs = action.acceptedAtMs, amountUnits = seen, type = action.type, tempId = action.tempId)
    }

    /**
     * Which of our pictures in [open] each of [records] is the record of, in the records' order: the
     * oldest picture whose place in the journal lies before the record, that the record is not stamped
     * before, that ordered what the record asks for, and that the record's delivered dose agrees with,
     * see [Action.agrees]. The pictures
     * taken are removed from [open]. A record with an extended part is nobody's here, and so is a record
     * with no date or that delivered nothing: a bolus that gave nothing leaves no record, such a record is
     * faulty, see `FaultyCell.implausible`.
     */
    fun pair(records: List<JournalEntry>, open: MutableList<Action>): List<Action?> =
        records.map { record ->
            val bolus = ApexBolusRecord.decode(record.frame)?.takeIf { !it.carriesExtendedPart && !it.flickers } ?: return@map null
            open.firstOrNull {
                it.journalTopSeq < record.seq && !stampedBefore(record, it) && ApexProtocol.sameDose(it.amount, bolus.requestedUnits) && it.agrees(bolus.deliveredUnits)
            }
                ?.also { open.remove(it) }
        }

    /**
     * A record stamped a minute or more before the minute the pump accepted [action] is not its record. The pump
     * stamps the minute the bolus started; one minute is left for its clock. A record the driver has not read in
     * time can stand above the place of our picture in the journal, so the place alone does not tell.
     */
    private fun stampedBefore(record: JournalEntry, action: Action): Boolean =
        record.atMs < ApexClock.minuteStart(action.acceptedAtMs) - ApexClock.MINUTE_MS

    /**
     * The final row of our picture [action] paired with [bolus]: the record's [key] and dose, the moment
     * and type of our command. What we saw of the bolus, its frames and the cancel, is never the dose:
     * it only finds the record, see [pair], and stands in for a faulty one, see `BolusReconciliation`.
     */
    fun row(bolus: ApexBolusRecord, action: Action, key: Long?, notBeforeMs: Long): AapsWrite.Bolus =
        AapsWrite.Bolus(key, maxOf(action.acceptedAtMs, notBeforeMs), bolus.deliveredUnits, action.type, tempId = action.tempId)

    /** The row of a record nobody here ordered: its [key], its minute, what it delivered, immediate and extended together. */
    fun foreignRow(record: JournalEntry, bolus: ApexBolusRecord, key: Long?, notBeforeMs: Long): AapsWrite.Bolus =
        AapsWrite.Bolus(key, maxOf(ApexClock.minuteStart(record.atMs), notBeforeMs), bolus.totalDeliveredUnits, AapsWrite.NORMAL)

    companion object {

        /**
         * How far a stopped bolus's record may stand from its last progress frame, U: two steps. The last frame
         * may come a portion before the stop, and the record of a stopped bolus may stand a step short of what
         * the frames showed: the two together.
         */
        private const val STOPPED_SLACK_UNITS = 2 * ApexProtocol.DOSE_SCALE

        /** True when [delivered] agrees with what the picture saw: within [STOPPED_SLACK_UNITS] of the last word, or anything up to the order when that was not seen. */
        private fun Action.agrees(delivered: Double): Boolean {
            if (delivered > amount + ApexProtocol.ROUNDING) return false
            val seen = seenUnits
            if (!lastWordSeen || seen == null) return true
            return abs(delivered - seen) <= STOPPED_SLACK_UNITS + ApexProtocol.ROUNDING
        }
    }
}
