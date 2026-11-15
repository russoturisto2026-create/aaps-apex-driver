package app.aaps.pump.apex.deep

import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.NoticeKind
import app.aaps.pump.apex.account.bolus.BolusAccount
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexProtocol

/**
 * The bolus records the plain path did not expect, and the row each one gets. The plain path,
 * [BolusAccount], knows one thing: the record of our own bolus, at its place after our command.
 * Every other record the journal can show is here, each with its own rule.
 *
 * The records that can be read are settled first, each as it says: its minute, what the pump
 * delivered. A record of ours takes the second and the type of our command, see
 * [BolusAccount.match]. A record nobody here asked for is a bolus given on the pump, or by another
 * application, type NORMAL; so is a record that asks another dose than our waiting command: it can
 * be read, so it is a bolus of its own. The record with nothing asked and a little delivered
 * belongs here too: a cancel sent while no bolus runs leaves "asked 0, delivered 0.05" in the
 * journal, and the pump's count moves by it. Such a record looks like a broken cell at first; the
 * read made again tells them apart, see [BrokenRecordDeep], and a record that repeats is this one.
 *
 * A record with an extended part: an extended or a dual bolus given on the pump. The driver does
 * not order these, so it is always somebody else's. It gets one ordinary row with everything the
 * pump delivered, immediate and extended together, at the record's minute; that the insulin was
 * spread over time is lost, and the log says so.
 *
 * A faulty cell: a new record older than the records of the last answer, or a new record the pump
 * cannot have written (stamped in the future, or more than the reservoir held, see
 * [PlausibleRecordDeep]). A journal the pump started over has nothing of the last answer to place
 * its records against: every record of it is a faulty cell. What a faulty cell holds is never taken.
 *
 * All the faulty cells of an answer are one row. Its dose is what the move of the bolus field
 * leaves after the records that can be read, see [BolusByDaysDeep]: the pump does not always say
 * how far a bolus went. Its moment is the moment of our command when a command of ours stands
 * before the cells and no record took it, the earliest of them when there are more. Else it is the
 * middle of the window the cells stand in by their place in the journal: from the last read, or
 * from the record below them, to the record above them, or to the moment this read is compared at.
 * When a record stands among the cells, the window is the whole span. The row never stands after
 * the moment of the comparison: the pump's count of that moment holds the bolus already. Nothing
 * of this turns the loop off; the user is told, see [noticeFor].
 */
class BolusDeep(private val store: ApexStore, private val serial: String) {

    /**
     * What the look found: the rows to write. [faultyRowId] names the one row of the faulty cells,
     * written with nothing until the move of the span gives its dose; null when there was no faulty cell.
     */
    class Settled(val writes: List<AapsWrite>, val faultyRowId: Long? = null)

    /**
     * Each record new in [added], oldest first, set straight by the rules above.
     *
     * @param canClose asked only when a faulty cell is found: false when the span cannot be closed yet (the daily
     *   statistics were not read, or the count is not usable yet), null is returned and nothing is changed, so that
     *   the next answer shows it again
     * @param notBeforeMs the moment AAPS registered this pump: a row dated before it is refused by the core
     * @param comparedAtMs the moment this read is compared at, the heartbeat: the window of the faulty cells ends there
     */
    fun settleFaults(added: JournalSnapshot.Added, canClose: () -> Boolean, notBeforeMs: Long, nowMs: Long, comparedAtMs: Long = nowMs): Settled? {
        if (added.records.isEmpty() || added.firstRead) return Settled(emptyList())
        val previousTopAtMs = added.previousTopAtMs
        val bounds = PlausibleRecordDeep(store, serial, nowMs)
        val faulty = when {
            added.startedOver       -> added.records
            previousTopAtMs == null -> added.records.filter { bounds.implausible(it) }
            else                    -> added.records.filter { it.atMs < previousTopAtMs || bounds.implausible(it) }
        }
        if (faulty.isNotEmpty() && !canClose()) return null
        val account = BolusAccount(store, serial)
        val open = account.openActions().toMutableList()
        val readable = added.records.filter { it !in faulty }
        val actions = account.match(readable, open)
        val idBase = account.idBase(nowMs)
        val writes = ArrayList<AapsWrite>()
        // The records that can be read, each as its place in the journal and its moment: they set the window of the faulty cells.
        val known = ArrayList<Pair<Long, Long>>()

        for ((i, record) in readable.withIndex()) {
            val bolus = ApexBolusRecord.decode(record.frame) ?: continue
            val action = actions[i]
            if (action != null) account.recordFound(action, record.seq)
            val row = if (bolus.carriesExtendedPart) account.row(record, bolus, action = null, idBase, notBeforeMs).copy(amountUnits = bolus.totalDeliveredUnits)
            else account.row(record, bolus, action, idBase, notBeforeMs)
            account.noteSettled(row.amountUnits)
            writes.add(row)
            known.add(record.seq to row.timestampMs)
        }
        if (faulty.isEmpty()) return Settled(writes)
        // A faulty cell stands for the oldest command of ours placed before it that no record took.
        val ours = faulty.mapNotNull { cell -> open.firstOrNull { it.journalTopSeq < cell.seq }?.also { open.remove(it); account.recordFound(it, cell.seq) } }
        val first = ours.minByOrNull { it.acceptedAtMs }
        val atMs = first?.acceptedAtMs ?: middleOfWindow(faulty.first().seq, faulty.last().seq, known, added.previousReadAtMs ?: comparedAtMs, comparedAtMs)
        val row = AapsWrite.Bolus(idBase + faulty.first().seq, maxOf(atMs, notBeforeMs), 0.0, first?.type ?: AapsWrite.NORMAL)
        writes.add(row)
        return Settled(writes, faultyRowId = row.pumpId)
    }

    /**
     * The middle of the window the faulty cells from [firstSeq] to [lastSeq] stand in: from [lastReadMs], or the
     * latest record below them, to [endMs], or the earliest record above them. A record among the cells makes the
     * window the whole span. [known] holds each record as its place in the journal and its moment.
     */
    private fun middleOfWindow(firstSeq: Long, lastSeq: Long, known: List<Pair<Long, Long>>, lastReadMs: Long, endMs: Long): Long {
        val whole = (minOf(lastReadMs, endMs) + endMs) / 2
        if (known.any { (seq, _) -> seq in firstSeq..lastSeq }) return whole
        val fromMs = (known.filter { (seq, _) -> seq < firstSeq }.map { it.second } + lastReadMs).max()
        val toMs = (known.filter { (seq, _) -> seq > lastSeq }.map { it.second } + endMs).min()
        return if (fromMs <= toMs) (fromMs + toMs) / 2 else whole
    }

    /**
     * The row of the faulty cells in [writes], held against the move of the span, is dropped when it
     * has nothing, and the user is told: its dose, or that the dose is not known.
     */
    fun noticeFor(writes: List<AapsWrite>, faultyRowId: Long): List<AapsWrite> {
        val row = writes.filterIsInstance<AapsWrite.Bolus>().firstOrNull { it.pumpId == faultyRowId } ?: return writes
        val notice = if (row.amountUnits >= ApexProtocol.DOSE_SCALE / 2) AapsWrite.Notice(NoticeKind.JOURNAL_FAULT, units = row.amountUnits, atMs = row.timestampMs)
        else AapsWrite.Notice(NoticeKind.JOURNAL_FAULT_UNKNOWN, atMs = row.timestampMs)
        return writes.filter { it !== row || row.amountUnits >= ApexProtocol.DOSE_SCALE / 2 } + notice
    }
}
