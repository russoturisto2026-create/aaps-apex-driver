package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.statistics.StatisticsDays
import app.aaps.pump.apex.store.ApexStore
import app.aaps.pump.apex.store.BolusCell
import app.aaps.pump.apex.clock.ApexClock
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexResponseFrame

/**
 * A record new in an answer: its number, which only grows, its frame, its stamp as a key, the offset
 * of the pump's clock it was stamped in, and its moment.
 */
class JournalEntry(val seq: Long, val frame: ApexResponseFrame, val clockSeconds: Long, val atMs: Long, val offsetMs: Long = 0L)

/**
 * The driver's own bolus journal: the cells, see [BolusCell]. Each answer of the pump is laid over
 * the records the cells hold (see [JournalOverlay]) to find the records new since; each new record
 * gets the next number and a cell of its own. The reconciliation then fills the cells: what went in,
 * when, whether the record was faulty, whether its row is in AAPS, see [settled].
 *
 * The cells keep as many records as the pump's journal holds, see [prune]. Only the records of the
 * journal the pump keeps now are laid over: a journal started anew starts from its first number, see
 * [restart].
 *
 * A flickering record (nothing delivered, or no date) never comes here: the answer is read again until it
 * goes, see `BrokenRecord`. One that came the same at every read is what the pump holds: it gets its cell
 * like any record, and the reconciliation takes it as a faulty cell.
 */
class BolusCells(private val store: ApexStore, private val serial: String) {

    /** One record of an answer: its frame and its stamp as the protocol decoded it. */
    class Answered(val frame: ApexResponseFrame, val clockSeconds: Long)

    /**
     * The records new in an answer, oldest first, now numbered. On a journal started anew (see
     * [restart]) every record is new.
     *
     * @param previousTopAtMs the latest moment among the records of the last answer that this one
     *   repeats: a new record before it is out of place. Null when the answer repeats none of them.
     *   A record stamped in the future does not count: it is a faulty cell.
     * @param previousReadAtMs when the last answer was read: the new records came after it. Null when none was read.
     */
    class Added(
        val records: List<JournalEntry>, val previousTopAtMs: Long?, val previousReadAtMs: Long? = null
    )

    /**
     * Lay [answer], newest first, over the records the cells hold under the clock offset [offsetMs], and
     * give each new record its cell.
     *
     * @return the new records; null when the cells hold no answer yet (the first answer is the whole journal,
     *   see [restart]), when the answer does not show the newest record kept, or the cells lost the newest
     *   record given: nothing is kept then
     */
    fun take(answer: List<Answered>, offsetMs: Long, nowMs: Long): Added? {
        if (readAtMs() == null) return null
        val cells = journalCells()
        // The newest record given always has its cell: without it the base lost cells, and the records
        // above the ones left would be taken as new. The empty journal kept (see restart) has none to lose.
        if (cells.lastOrNull()?.seq != topSeq() && journalFrom() <= topSeq()) return null
        val newCount = JournalOverlay.newOfFrames(answer.map { it.frame }, cells.map { it.bytes!! }) ?: return null
        return keep(answer, newCount, past = false, offsetMs, nowMs)
    }

    /** Keep [read] as the start of the journal read whole, see `WholeJournal`: its records are numbered anew above the cells. */
    fun restart(answer: List<Answered>, offsetMs: Long, nowMs: Long): Added {
        store.putState(serial, FROM_KEY, (topSeq() + 1).toString())
        return keep(answer, answer.size, past = true, offsetMs, nowMs)
    }

    private fun keep(answer: List<Answered>, newCount: Int, past: Boolean, offsetMs: Long, nowMs: Long): Added {
        val previousReadAtMs = readAtMs()?.takeIf { it > 0 }
        // The records below the new ones are the cells kept, newest first, each in the offset it came in.
        val keptNewestFirst = if (past) emptyList() else journalCells().asReversed()
        val previousTopAtMs = answer.drop(newCount).zip(keptNewestFirst).mapNotNull { (it, cell) -> cell.offsetMs?.let { o -> ApexClock.momentOf(it.clockSeconds, o) } }
            .filter { it <= nowMs + ApexClock.STAMP_AHEAD_MS }.maxOrNull().takeIf { !past }
        val records = number(answer.take(newCount), offsetMs, byStamp = past, nowMs)
        records.forEach { store.putCell(serial, BolusCell(seq = it.seq, bytes = it.frame.raw, offsetMs = it.offsetMs)) }
        store.putState(serial, READ_AT_KEY, nowMs.toString())
        return Added(records, previousTopAtMs = previousTopAtMs, previousReadAtMs = previousReadAtMs)
    }

    /** The count of records given so far: the number of the newest one. */
    fun topSeq(): Long = store.state(serial, COUNTER_KEY)?.toLongOrNull() ?: 0L

    /** When the last answer was read; null before the first. */
    fun readAtMs(): Long? = store.state(serial, READ_AT_KEY)?.toLongOrNull()

    /** How many records the pump said its journal holds at the last answer kept; null before the first. */
    fun recordCount(): Int? = store.state(serial, COUNT_KEY)?.toIntOrNull()

    /** The pump said its journal holds [count] records. */
    fun countSeen(count: Int) = store.putState(serial, COUNT_KEY, count.toString())

    /** The moment of the newest record with a date that the cells hold; null when they hold none. */
    fun newestDatedAtMs(): Long? = journalCells().asReversed().firstNotNullOfOrNull { cell ->
        val record = ApexBolusRecord.decode(ApexResponseFrame(cell.bytes!!))?.takeIf { !it.dateBroken }
        record?.let { r -> cell.offsetMs?.let { ApexClock.momentOf(r.clockSeconds, it) } }
    }

    /** The records of the journal the pump keeps now, as it gave them, newest first. Null before the first answer. */
    fun keptFrames(): List<ByteArray>? = if (readAtMs() == null) null else journalCells().asReversed().map { it.bytes!! }

    /**
     * The key in AAPS of each record the cells hold, by its number, see [BolusKey]: its repeat is counted over
     * the records the cells hold before it. Taken once for a reconciliation: every cell is read for it.
     */
    fun keys(): Map<Long, Long> {
        val repeats = HashMap<Triple<Long, Int, Int>, Int>()
        return journalCells().mapNotNull { cell ->
            val record = ApexBolusRecord.decode(ApexResponseFrame(cell.bytes!!)) ?: return@mapNotNull null
            val same = BolusKey.sameAs(record)
            val repeat = repeats.getOrDefault(same, 0).also { repeats[same] = it + 1 }
            cell.seq!! to BolusKey.of(record, repeat)
        }.toMap()
    }

    /** The number of the first record of the journal the pump keeps now, see [restart]. */
    private fun journalFrom(): Long = store.state(serial, FROM_KEY)?.toLongOrNull() ?: 0L

    /** The cells with a record of the journal the pump keeps now, oldest first. */
    private fun journalCells(): List<BolusCell> {
        val from = journalFrom()
        return store.cells(serial).filter { it.bytes != null && (it.seq ?: 0L) >= from }.sortedBy { it.seq }
    }

    /**
     * The reconciliation filled the cells: each [Filled] goes to its cell. A record that is the record of
     * our bolus moves into our bolus's cell, and its own cell goes.
     */
    fun settled(filled: List<Filled>) {
        val cells = store.cells(serial)
        for (fill in filled) {
            val record = fill.seq?.let { seq -> cells.firstOrNull { it.seq == seq } }
            val ours = fill.actionId?.let { id -> cells.firstOrNull { it.id == id } }
            if (ours != null) {
                // The cell of the record goes first: the number is the key of one cell only.
                if (record != null) store.dropCells(serial, listOf(record.id))
                store.putCell(
                    serial, ours.copy(
                        seq = record?.seq, bytes = record?.bytes, offsetMs = record?.offsetMs, units = fill.units, atMs = fill.atMs, faulty = fill.faulty, inAaps = true,
                        settledAtMs = fill.settledAtMs, dayBolusUnits = fill.dayBolusUnits
                    )
                )
            } else if (record != null) {
                store.putCell(
                    serial,
                    record.copy(units = fill.units, atMs = fill.atMs, faulty = fill.faulty, inAaps = fill.inAaps, settledAtMs = fill.settledAtMs, dayBolusUnits = fill.dayBolusUnits)
                )
            }
        }
    }

    /**
     * What the reconciliation made of one cell: the record [seq], our bolus [actionId], what went in and
     * when, whether the record was faulty, whether the row went to AAPS; when the reconciliation ran and the
     * bolus field of the statistics for the day of the bolus then, see [StatisticsDays.bolusOfDay].
     */
    class Filled(
        val seq: Long?, val actionId: Long?, val units: Double, val atMs: Long, val faulty: Boolean, val inAaps: Boolean,
        val settledAtMs: Long? = null, val dayBolusUnits: Double? = null
    )

    /**
     * Keep as many records as the pump's journal holds, [capacity]: a cell whose record is that many
     * below the newest goes, and so does a cell of ours with no record, once reconciled, when the place
     * its record would have had is that far down.
     */
    fun prune(capacity: Long = CAPACITY) {
        val floor = topSeq() - capacity
        store.dropCells(serial, store.cells(serial).filter {
            val seq = it.seq
            if (seq != null) seq <= floor else !it.open && (it.action?.journalTopSeq ?: 0L) <= floor
        }.map { it.id })
    }

    /**
     * Number [newestFirst] above the newest record given. [byStamp]: the records of a journal taken whole
     * (the first answer, a journal started anew) came before their new numbers, and the clock log counted
     * them by the old ones: each stands at its own stamp, in the offset of the span of the clock log the
     * stamp falls in. After the zone moved back, a stamp of the old clock fits the new span too, an hour or
     * more ahead: the span that puts it no later than the record above it wins. When no span does, it stays
     * at its stamp: the records around it never move it, a faulty cell with an old stamp is found by the
     * reconciliation.
     */
    private fun number(newestFirst: List<Answered>, offsetMs: Long, byStamp: Boolean, nowMs: Long): List<JournalEntry> {
        var aboveMs = Long.MAX_VALUE
        val byStampOffsets = if (!byStamp) emptyList() else newestFirst.map {
            val bounded = ApexClock.momentOfStamp(store, serial, it.clockSeconds, offsetMs, nowMs, aboveMs)
            val moment = if (bounded <= aboveMs) bounded else ApexClock.momentOfStamp(store, serial, it.clockSeconds, offsetMs, nowMs)
            aboveMs = moment
            it.clockSeconds * 1000L - moment
        }.asReversed()
        var seq = topSeq()
        val entries = newestFirst.asReversed().mapIndexed { i, it ->
            ++seq
            val offset = if (byStamp) byStampOffsets[i] else ApexClock.offsetOfBolusRecord(store, serial, seq, offsetMs)
            JournalEntry(seq, it.frame, it.clockSeconds, ApexClock.momentOf(it.clockSeconds, offset), offset)
        }
        if (entries.isNotEmpty()) store.putState(serial, COUNTER_KEY, seq.toString())
        return entries
    }

    companion object {

        /** The count of records given so far. */
        const val COUNTER_KEY = "recordsSeen.BOLUS"

        /** When the last bolus answer was read. */
        const val READ_AT_KEY = "bolusJournalReadAtMs"

        /** The number of the first record of the journal the pump keeps now. */
        const val FROM_KEY = "bolusJournalFromSeq"

        /** How many records the pump said its journal holds, see [recordCount]. */
        const val COUNT_KEY = "bolusJournalRecordCount"

        /** How many records the pump's journal holds. */
        const val CAPACITY = 128L

        /** The records of a bolus journal answer, each with its stamp. */
        fun boluses(answer: ApexJournalAnswer<ApexBolusRecord>): List<Answered> = answer.entries.map { Answered(it.frame, it.record.clockSeconds) }
    }
}
