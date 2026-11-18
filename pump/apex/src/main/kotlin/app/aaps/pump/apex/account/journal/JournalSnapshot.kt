package app.aaps.pump.apex.account.journal

import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.Snapshot
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.deep.PlausibleRecordDeep
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexResponseFrame

/** A record new in an answer: its number, which only grows, its frame, and its stamp as a key and as a moment. */
class JournalEntry(val seq: Long, val frame: ApexResponseFrame, val clockSeconds: Long, val atMs: Long)

/**
 * One pump journal as the driver follows it: the last answer is kept, the next answer is laid over it
 * (see [JournalOverlay]) to find the records new since, and then takes its place. Every new record
 * gets the next number; the count of records given so far is the only thing kept besides the answer.
 */
class JournalSnapshot(private val store: ApexStore, private val serial: String) {

    /** The bolus journal: the only journal the driver follows by its records. */
    private val kind = SnapshotKind.BOLUS

    /** One record of an answer: its frame and its stamp as the protocol decoded it. */
    class Answered(val frame: ApexResponseFrame, val clockSeconds: Long)

    /**
     * The records new in an answer, oldest first, now numbered. On the first read of a pump, and on a
     * journal started anew (see [restart]), every record is new and [firstRead] is true: they are the
     * past, not events. On a journal the pump started over (see [startOver]) every record is new and
     * [startedOver] is true: they are events, but none of them can be placed against the last answer.
     *
     * @param previousTopAtMs the latest moment among the records of the last answer that this one
     *   repeats: a new record before it is out of place. Null when the answer repeats none of them.
     *   A record stamped in the future does not count: it is a faulty cell, see [PlausibleRecordDeep].
     * @param previousReadAtMs when the last answer was read: the new records came after it. Null when none is kept.
     */
    class Added(
        val records: List<JournalEntry>, val firstRead: Boolean, val previousTopAtMs: Long?, val startedOver: Boolean = false, val previousReadAtMs: Long? = null
    )

    /**
     * Lay [answer], newest first, over the last answer under the clock offset [offsetMs], and keep
     * [answer] as the last one.
     *
     * @return the new records; null when the answer does not show the newest record of the last one: nothing is kept then
     */
    fun take(answer: List<Answered>, offsetMs: Long, nowMs: Long): Added? {
        val kept = store.snapshot(serial, kind) ?: return keep(answer, answer.size, past = true, offsetMs, nowMs)
        val newCount = JournalOverlay.newOnTop(
            answer.map { it.frame.recordIdentity() },
            kept.frames.asReversed().map { ApexResponseFrame(it).recordIdentity() }
        ) ?: return null
        return keep(answer, newCount, past = false, offsetMs, nowMs)
    }

    /**
     * Put [record], the pump's last bolus record read alone, on top of the last answer kept, as the one
     * record new since it, and keep the result as the last answer. Only when the top of the kept answer
     * is known to be the record right below it: read alone just before, see `BolusCommand`. Null when no
     * answer is kept, or [record] is the kept top itself: nothing is new.
     */
    fun takeOnTop(record: Answered, offsetMs: Long, nowMs: Long): Added? {
        val kept = store.snapshot(serial, kind) ?: return null
        val below = kept.frames.map { ApexResponseFrame(it) }
        if (below.firstOrNull()?.recordIdentity()?.contentEquals(record.frame.recordIdentity()) == true) return null
        val keptAnswered = below.map { Answered(it, ApexBolusRecord.decode(it)?.clockSeconds ?: return null) }
        return keep(listOf(record) + keptAnswered, newCount = 1, past = false, offsetMs, nowMs)
    }

    /** True when the newest record of the last answer kept is [frame]'s record. */
    fun topIs(frame: ApexResponseFrame): Boolean =
        store.snapshot(serial, kind)?.frames?.firstOrNull()?.let { ApexResponseFrame(it).recordIdentity().contentEquals(frame.recordIdentity()) } == true

    /**
     * Keep [answer] as the start of a new journal: nothing of the last answer is left in the pump, see
     * `JournalRestartDeep`. Its records are numbered and taken as the past, as on a first read.
     */
    fun restart(answer: List<Answered>, offsetMs: Long, nowMs: Long): Added = keep(answer, answer.size, past = true, offsetMs, nowMs)

    /**
     * Keep [answer] as the start of a new journal: the pump started its journal over, and every
     * record of it is new. Unlike [restart], the records are events of the span.
     */
    fun startOver(answer: List<Answered>, offsetMs: Long, nowMs: Long): Added =
        keep(answer, answer.size, past = false, offsetMs, nowMs).let { Added(it.records, firstRead = false, previousTopAtMs = null, startedOver = true, previousReadAtMs = it.previousReadAtMs) }

    private fun keep(answer: List<Answered>, newCount: Int, past: Boolean, offsetMs: Long, nowMs: Long): Added {
        val topBefore = topSeq()
        val previousReadAtMs = store.snapshot(serial, kind)?.readAtMs?.takeIf { it > 0 }
        // The records below the new ones are the last answer's, newest first: the first has the number topBefore.
        val previousTopAtMs = if (past) null else answer.drop(newCount).mapIndexed { i, it -> momentOf(it.clockSeconds, topBefore - i, offsetMs) }
            .filter { it <= nowMs + PlausibleRecordDeep.FUTURE_MARGIN_MS }.maxOrNull()
        val records = number(answer.take(newCount), offsetMs)
        store.saveSnapshot(serial, kind, Snapshot(answer.take(ApexProtocol.ALIAS_ANSWER_FRAMES).map { it.frame.raw }, nowMs))
        return Added(records, firstRead = past, previousTopAtMs = previousTopAtMs, previousReadAtMs = previousReadAtMs)
    }

    /** Where the journal stands: the last answer and the count of records given so far, for [rollBack]. */
    class Mark(val snapshot: Snapshot?, val topSeq: Long)

    fun mark(): Mark = Mark(store.snapshot(serial, kind), topSeq())

    /** Put the journal back where [mark] found it: the records taken since are new again at the next answer. */
    fun rollBack(mark: Mark) {
        mark.snapshot?.let { store.saveSnapshot(serial, kind, it) }
        store.putState(serial, counterKey, mark.topSeq.toString())
    }

    /** The count of records given so far: the number of the newest one. */
    fun topSeq(): Long = store.state(serial, counterKey)?.toLongOrNull() ?: 0L

    private fun number(newestFirst: List<Answered>, offsetMs: Long): List<JournalEntry> {
        var seq = topSeq()
        val entries = newestFirst.asReversed().map {
            ++seq
            JournalEntry(seq, it.frame, it.clockSeconds, momentOf(it.clockSeconds, seq, offsetMs))
        }
        if (entries.isNotEmpty()) store.putState(serial, counterKey, seq.toString())
        return entries
    }

    /** The moment of a stamp of record number [seq]: in the offset the pump stood in when it stamped it. */
    private fun momentOf(clockSeconds: Long, seq: Long, offsetMs: Long): Long =
        ApexClock.momentOfRecord(store, serial, seq, clockSeconds, offsetMs)

    private val counterKey get() = "$COUNTER_KEY.${kind.name}"

    companion object {

        const val COUNTER_KEY = "recordsSeen"

        /** The records of a bolus journal answer, each with its stamp. */
        fun boluses(answer: ApexJournalAnswer<ApexBolusRecord>): List<Answered> = answer.entries.map { Answered(it.frame, it.record.clockSeconds) }
    }
}
