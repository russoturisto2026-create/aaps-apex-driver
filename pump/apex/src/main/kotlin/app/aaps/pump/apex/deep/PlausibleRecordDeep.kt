package app.aaps.pump.apex.deep

import app.aaps.pump.apex.account.journal.JournalEntry
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.state.ApexPumpState

/**
 * A record, a day or a row the pump cannot have made, told by the last status seen.
 *
 * The problem. The pump's memory can break down, and then an answer carries anything behind a
 * right CRC: the broken cells of [BrokenRecordDeep] are the forms seen so far, not the only ones
 * possible. A number taken from such an answer as it is becomes a row in AAPS, and a row of a
 * hundred units stops the loop's insulin for a day.
 *
 * The rule. The bounds come from the pump's own status, not from settings. The reservoir bound is the
 * larger of the reservoir at the last reconciliation and at the newest status read: a cartridge
 * changed between the two would else make a sound bolus out of bounds.
 * - A record stamped later than now by more than [FUTURE_MARGIN_MS] was not written by this pump:
 *   the heartbeat keeps its clock within half a second, and a test may move it by seconds. The
 *   moment is taken as the account would place it, through the clock log, not the raw stamp: after a
 *   change of zone the old stamps stand hours from the new clock and are still in the past.
 * - A bolus record that delivered more than the reservoir held at the last status.
 * - A move of the bolus field of the daily statistics above that reservoir.
 * - A row above that reservoir, whatever path it came by.
 * There is no bound from the past: records older than the copy are real after a lost link or a
 * change of the battery, and an old record in a new cell is the faulty cell [BolusDeep] knows.
 *
 * The way out. A record out of bounds is read again like a broken-looking one, see
 * [BrokenRecordDeep]; when it comes the same every time it is a faulty cell of the span, see
 * [BolusDeep]: its row is by our command and the daily statistics, what the cell holds is not
 * taken, and it is never the place later records are measured from, see [JournalSnapshot].
 * Statistics out of bounds cannot close a span: the rows stay as the records say. A row out of
 * bounds is not written, and the user is told. An answer with no record at all is silence: the
 * pump keeps every record it ever wrote, so nothing is settled and the copy is kept.
 */
class PlausibleRecordDeep(
    private val store: ApexStore,
    private val serial: String,
    private val nowMs: Long,
    /** The reservoir of the newest status read, U, see `ApexPumpState.statusCard`; null when none was read. */
    newestReservoirUnits: Double?
) {

    private val lastStatus: ApexResponseFrame? = store.snapshot(serial, SnapshotKind.STATUS)?.frames?.firstOrNull()?.let { ApexResponseFrame(it) }

    /** The reservoir bound, U: the larger of the last reconciliation and the newest status read; null before the first status. */
    val reservoirUnits: Double? = listOfNotNull(lastStatus?.let { ApexStatusV1.decode(it) }?.reservoirUnits, newestReservoirUnits).maxOrNull()

    /** A moment later than now by more than the margin: no record is stamped there. */
    fun inFuture(atMs: Long): Boolean = atMs > nowMs + FUTURE_MARGIN_MS

    /** More than the reservoir bound; never true before the first status. */
    fun aboveReservoir(units: Double): Boolean = reservoirUnits?.let { units > it + ApexProtocol.ROUNDING } == true

    /** A bolus record the pump cannot have written at [atMs], its moment. */
    fun implausible(bolus: ApexBolusRecord, atMs: Long): Boolean = inFuture(atMs) || aboveReservoir(bolus.totalDeliveredUnits)

    /** A record new in an answer the pump cannot have written. */
    fun implausible(entry: JournalEntry): Boolean = ApexBolusRecord.decode(entry.frame)?.let { implausible(it, entry.atMs) } == true

    /**
     * The first of the bolus records [current], newest first and not yet numbered, that the pump
     * cannot have written; null when none. The records are placed as the account will number them,
     * above the records seen so far, so that each stamp is read in the offset it was made in.
     */
    fun implausibleBolus(current: List<ApexBolusRecord>, offsetMs: Long): ApexBolusRecord? {
        val topSeq = JournalSnapshot(store, serial).topSeq()
        return current.withIndex().firstOrNull { (i, bolus) ->
            implausible(bolus, ApexClock.momentOfRecord(store, serial, topSeq + current.size - i, bolus.clockSeconds, offsetMs))
        }?.value
    }

    companion object {

        /** The bounds for records read now, by the newest status of [pumpState]. */
        fun now(store: ApexStore, serial: String, pumpState: ApexPumpState): PlausibleRecordDeep =
            PlausibleRecordDeep(store, serial, System.currentTimeMillis(), pumpState.statusCard?.status?.reservoirUnits)

        /** How far past now a stamp may stand before it is not a stamp. */
        const val FUTURE_MARGIN_MS = ApexClock.STAMP_AHEAD_MS
    }
}
