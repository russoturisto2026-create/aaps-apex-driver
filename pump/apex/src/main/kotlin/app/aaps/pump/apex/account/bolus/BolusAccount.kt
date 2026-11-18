package app.aaps.pump.apex.account.bolus

import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.journal.JournalEntry
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.Action
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.deep.PlausibleRecordDeep
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexProtocol

/**
 * The plain path of the bolus account: the pump's record of our bolus becomes its row. Our bolus is
 * a new record after the place the journal stood at when the command went, and the record asks for
 * what we ordered: the order is the identity, while our bolus runs the pump takes no other. The row
 * takes the record's minute and the second of our command. Records the plain tick did not expect,
 * and a command the records outran, go to the deep look, [BolusDeep].
 */
class BolusAccount(private val store: ApexStore, private val serial: String) {

    /**
     * True when the records new in [added] are the ones our boluses wait for: no more of them than
     * commands without a record, none older than the record before them, none with an extended part,
     * none the pump cannot have written (see `PlausibleRecordDeep`), and each asks for what one of
     * the waiting commands ordered. A command left without a record is closed all the same, see
     * [closeAll].
     */
    fun expects(added: JournalSnapshot.Added, nowMs: Long, newestReservoirUnits: Double? = null): Boolean {
        val open = openActions().toMutableList()
        val previousTopAtMs = added.previousTopAtMs ?: return false
        val bounds = PlausibleRecordDeep(store, serial, nowMs, newestReservoirUnits)
        return added.records.size <= open.size &&
            added.records.all { it.atMs >= previousTopAtMs && ApexBolusRecord.decode(it.frame)?.carriesExtendedPart == false && !bounds.implausible(it) } &&
            match(added.records, open).all { it != null }
    }

    /**
     * Turn the records of our boluses into rows: each record is matched with the oldest waiting
     * command that ordered what it asks for. [expects] has said that they are ours.
     */
    fun settle(added: JournalSnapshot.Added, notBeforeMs: Long, nowMs: Long): List<AapsWrite> {
        if (added.records.isEmpty() || added.firstRead) return emptyList()
        val open = openActions().toMutableList()
        val actions = match(added.records, open)
        val idBase = idBase(nowMs)
        return added.records.mapIndexedNotNull { i, record ->
            val bolus = ApexBolusRecord.decode(record.frame) ?: return@mapIndexedNotNull null
            val action = checkNotNull(actions[i]) { "record ${record.seq} is not ours" }
            recordFound(action, record.seq)
            noteSettled(bolus.deliveredUnits)
            row(record, bolus, action, idBase, notBeforeMs)
        }
    }

    /**
     * Which of our commands in [open] each of [records] is the record of, in the records' order. A
     * record is of a command when it asks for what the command ordered, and it is newer than the
     * place the journal stood at when the command went; the oldest such command is taken, and the
     * commands taken are removed from [open]. A record that asks for something else is nobody's,
     * whatever commands still wait.
     */
    internal fun match(records: List<JournalEntry>, open: MutableList<Action>): List<Action?> =
        records.map { record ->
            val bolus = ApexBolusRecord.decode(record.frame) ?: return@map null
            open.firstOrNull { it.journalTopSeq < record.seq && ApexProtocol.sameDose(it.amount, bolus.requestedUnits) }?.also { open.remove(it) }
        }

    /**
     * The row of [record]: the record's minute, its delivered units, and when [action] is ours, the
     * second of our command and its type. A row is never dated before AAPS registered the pump.
     */
    internal fun row(record: JournalEntry, bolus: ApexBolusRecord, action: Action?, idBase: Long, notBeforeMs: Long): AapsWrite.Bolus {
        val minuteStart = ApexClock.minuteStart(record.atMs)
        val timestamp = if (action != null) minuteStart + action.acceptedAtMs - ApexClock.minuteStart(action.acceptedAtMs) else minuteStart
        return AapsWrite.Bolus(idBase + record.seq, maxOf(timestamp, notBeforeMs), bolus.deliveredUnits, action?.type ?: AapsWrite.NORMAL)
    }

    /** The pump's record of [action] is record number [seq]: the action waits for nothing more. */
    internal fun recordFound(action: Action, seq: Long) = store.updateAction(serial, action.copy(recordSeq = seq, done = true))

    /**
     * The boluses settled since the last heartbeat, the units each row holds, in the order settled:
     * ours, others' found with them, and the bolus of a faulty cell. The daily statistics of the next
     * heartbeat count them all: their sum is what the bolus field moves by when no other bolus came.
     */
    fun settledSinceHeartbeat(): List<Double> =
        store.state(serial, STATE_SETTLED_SINCE_HEARTBEAT)?.split(LIST_SEPARATOR)?.mapNotNull { it.toDoubleOrNull() } ?: emptyList()

    /** One more bolus has its row: [units] delivered. */
    internal fun noteSettled(units: Double) =
        store.putState(serial, STATE_SETTLED_SINCE_HEARTBEAT, (settledSinceHeartbeat() + units).joinToString(LIST_SEPARATOR))

    /** The heartbeat kept a day of statistics that counts every bolus settled so far: the list starts over. */
    fun clearSettledSinceHeartbeat() = store.putState(serial, STATE_SETTLED_SINCE_HEARTBEAT, null)

    /** Our bolus commands still waiting for their record, oldest first. */
    internal fun openActions(): List<Action> = store.actions(serial).filter { !it.done }.sortedBy { it.acceptedAtMs }

    /** Note that the pump accepted a bolus of ours: the record comes later and is found by its place. */
    fun commandAccepted(acceptedAtMs: Long, units: Double, type: String): Action =
        store.addAction(
            serial,
            Action(
                acceptedAtMs = acceptedAtMs,
                amount = units,
                type = type,
                journalTopSeq = JournalSnapshot(store, serial).topSeq()
            )
        )

    /** The pump reported progress of our running bolus. */
    fun progress(action: Action, seenUnits: Double) = store.updateAction(serial, action.copy(seenUnits = seenUnits))

    /** The pump closed our bolus: [reportedUnits] is what it delivered. */
    fun finished(action: Action, reportedUnits: Double) = store.updateAction(serial, action.copy(seenUnits = reportedUnits))

    /**
     * The journal answer after our commands is settled: every command is closed, found or not, and
     * dropped. The ones still open had no record in the answer; they are returned, so that the log
     * can tell. What went in for them is in the move of the bolus field of the daily statistics.
     */
    fun closeAll(): List<Action> {
        val open = openActions()
        store.dropActions(serial)
        return open
    }

    /**
     * The base every row number of this pump stands on: set once, at the first row, so that numbers
     * of two installations never meet.
     */
    internal fun idBase(nowMs: Long): Long {
        store.state(serial, STATE_ID_BASE)?.toLongOrNull()?.let { return it }
        store.putState(serial, STATE_ID_BASE, nowMs.toString())
        return nowMs
    }

    companion object {

        const val STATE_ID_BASE = "pumpIdBase"
        const val STATE_SETTLED_SINCE_HEARTBEAT = "bolusesSettledSinceHeartbeat"
        private const val LIST_SEPARATOR = ";"

    }
}
