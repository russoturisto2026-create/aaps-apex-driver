package app.aaps.pump.atc3.history

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.manager.Atc3Manager
import app.aaps.pump.atc3.protocol.Atc3BolusHistory
import app.aaps.pump.atc3.protocol.Atc3BolusRecord
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor

/**
 * A slot of the bolus journal the pump did not write, known by what it holds. A real record can carry
 * neither that date nor that place, so the bytes tell it from every other record.
 *
 * @param afterMs the phone's time of the record next older in the journal: the bolus came after it
 * @param settled true once the bolus the slot was taken for is accounted for, or the user told it cannot be
 */
@Serializable
data class JournalFault(
    val pumpClockUtcSeconds: Long,
    val rawRequested: Int,
    val rawDelivered: Int,
    val afterMs: Long,
    val foundAtMs: Long,
    val settled: Boolean = false
) {

    fun matches(record: Atc3BolusRecord): Boolean =
        record.pumpClockUtcSeconds == pumpClockUtcSeconds &&
            record.rawRequested == rawRequested &&
            record.rawDelivered == rawDelivered

    fun sameAs(other: JournalFault): Boolean =
        other.pumpClockUtcSeconds == pumpClockUtcSeconds &&
            other.rawRequested == rawRequested &&
            other.rawDelivered == rawDelivered
}

/**
 * What the driver does with a slot of the bolus journal the pump failed to write.
 *
 * The journal is a ring: a new record takes the slot of the oldest. A slot the pump failed to write
 * keeps the record the ring should have dropped, with that old bolus's date and doses. The bolus the
 * slot was taken for is lost to the journal. It is not lost to the driver: a bolus of ours was seen
 * delivered to an amount, and anyone else's moved the pump's count. That amount is written as a bolus
 * without delivery, the user is told on the screen and in the treatment history, and the slot itself
 * is never taken for a record: not written, not deleted, not compared with the old row it repeats.
 * Nothing stops the pump or the loop over it.
 *
 * A slot is suspected when its date goes back behind the record next older in the journal; the pump
 * writes in order. A clock put back does the same for minutes, so the suspect is confirmed against the
 * whole journal, once: dated before every other record, it is the one the ring dropped. Confirmed
 * faults are kept in the ledger, passed over at every later read without a word, and stay unsettled
 * there until the bolus is accounted for, so a process that dies in between takes it up again.
 */
@Singleton
class Atc3JournalFault @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val dateUtil: DateUtil,
    private val uiInteraction: UiInteraction,
    private val pumpSync: PumpSync,
    private val atc3Manager: Atc3Manager,
    private val atc3HistorySync: Atc3HistorySync,
    private val pumpState: Atc3PumpState,
    private val trace: Atc3Trace
) {

    /** The answer with the faulty slots taken out, and the faults found new in it, newest first. */
    data class Screened(val history: Atc3BolusHistory, val found: List<JournalFault>)

    /** The boluses of ours a fault stood for, by when the pump accepted them, and the faults left to the count. */
    data class Settled(val confirmed: Map<Long, Double>, val left: List<JournalFault>)

    /** Suspects checked against the whole journal and found to be a clock put back: not looked up again while the process lives. */
    private val notFaults = HashSet<Triple<Long, Int, Int>>()

    /**
     * Take the faulty slots out of [history]. A suspect not known yet is checked against the whole
     * journal, one read; when the pump does not answer that read, the suspect is held out of this pass
     * and offered again by the next.
     */
    fun screen(history: Atc3BolusHistory): Screened {
        val known = atc3HistorySync.knownFaults()
        val unknown = suspects(history.records).filterNot { (record, _) -> known.any { it.matches(record) } || keyOf(record) in notFaults }
        val found = ArrayList<JournalFault>()
        var heldBack = emptyList<Atc3BolusRecord>()
        if (unknown.isNotEmpty()) {
            val whole = if (history.records.size >= history.recordCount) history else atc3Manager.readFullBolusHistory()
            if (whole == null) {
                heldBack = unknown.map { it.first }
                aapsLogger.warn(LTag.PUMP, "ATC3: ${unknown.size} record(s) dated back and the whole journal did not answer; left for the next read")
            } else {
                val confirmed = confirm(unknown.map { it.first }, whole)
                for ((record, older) in unknown) {
                    val stamp = dateUtil.dateAndTimeString(record.timestamp)
                    if (record in confirmed) {
                        found.add(JournalFault(record.pumpClockUtcSeconds, record.rawRequested, record.rawDelivered, older.timestamp, dateUtil.now()))
                        aapsLogger.error(
                            LTag.PUMP,
                            "ATC3: the pump did not write a bolus record: the slot holds $stamp, " +
                                "${record.requestedUnits}/${record.deliveredUnits} U, from before the whole journal"
                        )
                        trace.event(
                            Atc3TraceCat.HIST, "journal_fault",
                            "at" to record.timestamp, "req" to record.requestedUnits, "del" to record.deliveredUnits, "after" to older.timestamp
                        )
                    } else {
                        notFaults.add(keyOf(record))
                        aapsLogger.debug(LTag.PUMP, "ATC3: record $stamp is dated behind the one before it; a clock put back, not a fault")
                    }
                }
                if (found.isNotEmpty()) atc3HistorySync.rememberFaults(found)
            }
        }
        val faults = known + found
        val cleaned = history.records.filterNot { record -> record in heldBack || faults.any { it.matches(record) } }
        return Screened(Atc3BolusHistory(cleaned, history.recordCount), found)
    }

    /**
     * Account for the unsettled [faults] by the boluses of ours waiting for their record: a bolus
     * accepted after the record the slot follows, and seen delivering, takes the fault, and its row is
     * made of the amount it was last seen delivered to. The bolus leaves the waiting only once its row is
     * in; a refused row leaves both for the next read. A fault no bolus of ours answers for is left to
     * the count.
     */
    suspend fun settleOwn(faults: List<JournalFault>): Settled {
        val confirmed = HashMap<Long, Double>()
        val left = ArrayList<JournalFault>()
        for (fault in faults) {
            val own = atc3HistorySync.expectedSeenAfter(fault.afterMs - 60_000L)
            if (own == null) {
                left.add(fault)
                continue
            }
            val pumpId = atc3HistorySync.writeUnrecorded(own.acceptedAtMs, own.seenUnits, own.type)
            if (pumpId == null) {
                aapsLogger.error(LTag.PUMP, "ATC3: the row for our unrecorded bolus was refused; the bolus and the fault wait for the next read")
                continue
            }
            atc3HistorySync.dropExpected(own.acceptedAtMs)
            atc3HistorySync.markFaultSettled(fault)
            confirmed[own.acceptedAtMs] = own.seenUnits
            aapsLogger.error(
                LTag.PUMP,
                "ATC3: our bolus of ${own.units} U accepted at ${own.acceptedAtMs} got no record; ${own.seenUnits} U written from the last progress seen"
            )
            trace.event(Atc3TraceCat.HIST, "fault_row", "own" to true, "units" to own.seenUnits, "at" to own.acceptedAtMs, "asked" to own.units)
            tell(rh.gs(R.string.atc3_journal_fault_own, dateUtil.timeString(own.acceptedAtMs), own.seenUnits), own.acceptedAtMs)
        }
        return Settled(confirmed, left)
    }

    /**
     * Account for the faults left to the count by what the count holds beyond the journal: whole pulses
     * of it become one bolus without delivery at the read that holds it, [readMs]. Less than a pulse, or
     * more than the pump gives in one bolus, is nothing to write, and the user is told the amount is
     * unknown. Either way the loop decided without that insulin.
     *
     * @param unexplainedUnits what the pump's count holds beyond the AAPS journal and the part settled already, U
     * @param maxBolusUnits the most the pump gives in one bolus, or null when its settings were not read
     * @return when the bolus is placed, for the loop to decide again, or null when nothing was settled
     */
    suspend fun settleByCount(faults: List<JournalFault>, unexplainedUnits: Double, readMs: Long, maxBolusUnits: Double?): Long? {
        if (faults.isEmpty()) return null
        val afterMs = faults.maxOf { it.afterMs }
        val units = floor(unexplainedUnits / Atc3Protocol.DOSE_SCALE + Atc3Protocol.COUNT_EPSILON) * Atc3Protocol.DOSE_SCALE
        val tooMuch = maxBolusUnits != null && units > maxBolusUnits + Atc3Protocol.COUNT_EPSILON
        val between = rh.gs(R.string.atc3_journal_fault_between, dateUtil.timeString(afterMs), dateUtil.timeString(readMs))
        if (units < Atc3Protocol.DOSE_SCALE || tooMuch) {
            aapsLogger.error(LTag.PUMP, "ATC3: a bolus after $afterMs got no record and the count shows ${"%.3f".format(unexplainedUnits)} U beyond the journal; nothing written")
            trace.event(Atc3TraceCat.HIST, "fault_row", "own" to false, "units" to 0.0, "beyond" to unexplainedUnits, "after" to afterMs)
            tell(rh.gs(R.string.atc3_journal_fault_unknown, between), readMs)
            faults.forEach { atc3HistorySync.markFaultSettled(it) }
            return readMs
        }
        val pumpId = atc3HistorySync.writeUnrecorded(readMs, units, BS.Type.NORMAL) ?: return null
        faults.forEach { atc3HistorySync.markFaultSettled(it) }
        aapsLogger.error(LTag.PUMP, "ATC3: a bolus after $afterMs got no record; $units U written from the pump's count at $readMs, id $pumpId")
        trace.event(Atc3TraceCat.HIST, "fault_row", "own" to false, "units" to units, "after" to afterMs, "at" to readMs)
        tell(rh.gs(R.string.atc3_journal_fault_count, between, units), readMs)
        return readMs
    }

    /** Tell the user on the screen and in the treatment history. The pump runs on. */
    private fun tell(text: String, atMs: Long) {
        uiInteraction.addNotification(Notification.PUMP_SYNC_ERROR, text, Notification.URGENT)
        pumpSync.insertTherapyEventIfNewWithTimestamp(atMs, TE.Type.NOTE, text, atMs, PumpType.ATC3, pumpState.serialNumber)
    }

    companion object {

        /**
         * The records dated behind the record next older in the journal, each with that older record.
         * The answer's index counts from the newest record; empty slots are no records.
         */
        fun suspects(records: List<Atc3BolusRecord>): List<Pair<Atc3BolusRecord, Atc3BolusRecord>> =
            records.filterNot { it.isEmptyRecord }
                .sortedBy { it.index }
                .zipWithNext()
                .filter { (newer, older) -> newer.pumpClockUtcSeconds < older.pumpClockUtcSeconds }

        /**
         * Of [suspects], those dated before every other record of the whole journal [whole]: the ring
         * dropped that record. A record is the same in both answers by its bytes, not by its index: the
         * short answer counts from the newest record, the whole journal from its own.
         */
        fun confirm(suspects: List<Atc3BolusRecord>, whole: Atc3BolusHistory): List<Atc3BolusRecord> {
            val others = whole.records.filterNot { record -> record.isEmptyRecord || suspects.any { same(it, record) } }
            val oldest = others.minOfOrNull { it.pumpClockUtcSeconds } ?: return emptyList()
            return suspects.filter { it.pumpClockUtcSeconds < oldest }
        }

        private fun same(a: Atc3BolusRecord, b: Atc3BolusRecord): Boolean =
            a.pumpClockUtcSeconds == b.pumpClockUtcSeconds && a.rawRequested == b.rawRequested && a.rawDelivered == b.rawDelivered

        private fun keyOf(record: Atc3BolusRecord) = Triple(record.pumpClockUtcSeconds, record.rawRequested, record.rawDelivered)
    }
}
