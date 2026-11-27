package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.bolus.JournalOverlay
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.protocol.ApexResponseFrame

/**
 * A journal answer with a broken record in it, and how such an answer is told from a sound one.
 *
 * The problem. The pump keeps each journal as a ring of cells. When it writes a new record it
 * first moves the ring, then fills the newest cell. A read that lands between the two returns the
 * cell as it is at that instant: zeros from some byte to the end of the record. An old record
 * left in a new cell that stays there is another fault, a faulty cell, see `BolusReconciliation`. The pump
 * does not flag this; the CRC is right and the answer looks like any other. The next read shows the record.
 *
 * The rule. A day of the daily statistics flickers by the hints of [brokenDay]. A record of
 * the bolus journal is broken when it flickers: no date, or nothing delivered, see
 * `ApexBolusRecord.flickers`. A bolus of somebody else may end right at the heartbeat, so its cell can
 * be read half written even with no bolus of ours running. Only a new record can be half written: one the
 * driver's cells already hold is not judged again.
 *
 * The way out. A broken cell never comes twice: the next read shows the record. So an answer with
 * a broken record is read again by the one rule of reading again, see [ReadAgain]; a record broken at
 * one read and sound at another is taken from the read where it came sound, by its number. An answer that
 * comes the same at all three reads, see [ReadAgain], is not a broken cell but what the pump holds: it is
 * handed on as it is, for the reconciliation to judge. A bolus record is then a faulty cell, see `FaultyCell`.
 * Answers that keep changing are not trusted, and the heartbeat's reconciliation is skipped.
 *
 * The closed days of the statistics are held against the driver's copy only at the start: a heartbeat does not take them, see `StatisticsDays`.
 */
object BrokenRecord {


    /**
     * The days of a statistics answer that hint at a flicker, held against [kept], the frames of the copy, newest
     * first; empty when none does. The new days and the day kept last flicker when they have no month or no
     * day, the date 01.01.01 the field holds before a date is written, or nothing in any of the three fields;
     * a new date above the day kept last may hold nothing: a day the pump just opened, at its midnight.
     * The day kept last gone on with its date lost, the pump's power lost today, is not one; nor is the day kept last
     * read again the same as in the copy: the same as before, it does not flicker. [closedToo], the
     * start: a closed day not the same as in the copy flickers too. With nothing kept, or the day kept last not
     * in the answer, every day is new. A day that comes the same at all three reads is what the pump holds, see [ReadAgain].
     */
    fun brokenDay(answer: ApexJournalAnswer<ApexDailyRecord>, kept: List<ByteArray>, closedToo: Boolean): List<ApexRead<ApexDailyRecord>> {
        val top = kept.firstOrNull()?.let { ApexDailyRecord.decode(ApexResponseFrame(it)) }
        val at = top?.let { ApexDailyRecord.indexOfKept(answer.entries.map { e -> e.record }, it) }
        val fresh = if (at == null) answer.entries else answer.entries.take(at + 1)
        val hinted = fresh.filterIndexed { i, e ->
            val opened = top != null && at != null && i < at && e.record.hasDate && e.record.dayClockSeconds > top.dayClockSeconds
            !(i == at && (!e.record.hasDate || ApexDailyRecord.sameDay(e.frame.raw, kept.first()))) && dayFlickers(e, emptyIsSound = opened)
        }
        if (at == null || !closedToo) return hinted
        val closed = answer.entries.drop(at + 1).filterIndexed { k, e -> kept.getOrNull(k + 1)?.let { !ApexDailyRecord.sameDay(e.frame.raw, it) } ?: false }
        return hinted + closed
    }

    /**
     * A day of nothing at all is a hint too: a day read while the pump writes it may come so; but not
     * [emptyIsSound], a new date the pump just opened, which holds nothing by its nature.
     */
    private fun dayFlickers(day: ApexRead<ApexDailyRecord>, emptyIsSound: Boolean = false): Boolean =
        stampHasNoDate(day.frame, ApexDailyRecord.Offset.DATE) || dateNotWritten(day.frame, ApexDailyRecord.Offset.DATE) ||
            !emptyIsSound && day.record.bolusUnits == 0.0 && day.record.basalUnits == 0.0 && day.record.tbrUnits == 0.0

    /** The date at [dataOffset] is 01.01.01: what the field holds before a date is written. */
    private fun dateNotWritten(frame: ApexResponseFrame, dataOffset: Int): Boolean =
        frame.has(dataOffset, 3) && (0..2).all { frame.byteAt(dataOffset + it) == 1 }

    /**
     * The flickering records of a bolus journal answer that are new above [kept], the records the driver's cells
     * hold, newest first; empty when none flickers. A record the cells hold is a faulty cell taken before: it is
     * not read again. With nothing kept, or an answer that cannot be laid over the cells, every flickering record counts.
     */
    fun brokenBolus(answer: ApexJournalAnswer<ApexBolusRecord>, kept: List<ByteArray>?): List<ApexRead<ApexBolusRecord>> {
        val flickering = answer.entries.filter { it.record.flickers }
        if (flickering.isEmpty() || kept.isNullOrEmpty()) return flickering
        val newCount = JournalOverlay.newOfFrames(answer.entries.map { it.frame }, kept.asReversed())
            ?: return flickering
        val fresh = answer.entries.take(newCount)
        return flickering.filter { record -> fresh.any { it === record } }
    }

    /** Two answers carry the same records, byte for byte. */
    fun <T> sameAnswer(a: ApexJournalAnswer<T>, b: ApexJournalAnswer<T>): Boolean =
        a.entries.size == b.entries.size && a.entries.zip(b.entries).all { (x, y) -> x.frame.raw.contentEquals(y.frame.raw) }

    /** The stamp at [dataOffset] (year, month, day, ...) has a zero month or a zero day. */
    private fun stampHasNoDate(frame: ApexResponseFrame, dataOffset: Int): Boolean =
        !frame.has(dataOffset, 3) || frame.byteAt(dataOffset + 1) == 0 || frame.byteAt(dataOffset + 2) == 0
}
