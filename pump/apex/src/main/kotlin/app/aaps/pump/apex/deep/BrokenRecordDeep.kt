package app.aaps.pump.apex.deep

import app.aaps.pump.apex.account.journal.JournalOverlay
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
 * left in a new cell that stays there is another fault, a faulty cell, see [BolusDeep]. The pump
 * does not flag this; the CRC is right and the answer
 * looks like any other. Over 350 thousand answers it was seen in 16 cells:
 * 11 in the bolus journal, 4 in the temporary basal journal, 1 in the alarm journal, twice in two
 * journals two seconds apart. Never twice in the same cell: the next read shows the record.
 *
 * The rule. A record is broken when its stamp has no month or no day: no real record is dated
 * that way. A bolus record is broken too when it asks for nothing, immediate and extended both:
 * a bolus that delivered nothing leaves no record, so a record of nothing was never written by
 * the pump. A temporary basal record is broken when its duration is zero: no temporary basal is
 * ordered for no time. Its rate may be zero, that is a real order. The daily statistics
 * have no order to check, only the date; a day with no date that comes the same every time is
 * the pump's own record of a day its clock was unset.
 *
 * The way out. A broken cell never comes twice: the next read shows the record. So an answer with
 * a broken record is read again, with the same pause as any other request, up to
 * [READS_PER_JOURNAL] reads in all. An answer that comes the same way every time is not a broken
 * cell but what the pump holds: it is handed on as it is, marked, for the reconciliation to judge
 * (an old record in a new cell, the pump's fault record). Answers that keep changing are not
 * trusted, and the heartbeat's reconciliation is skipped.
 *
 * Only the current data is read again for. In a journal that is the records above the copy the
 * driver keeps: a broken-looking cell inside the copy was judged when it came, and the next read
 * shows it the same. In the daily statistics it is today, the first record: the older days do
 * not change, and a day cut by a power loss stays with no date.
 *
 * A record the pump cannot have written, by the last status seen, looks broken too and is read
 * again the same way, see [PlausibleRecordDeep]; one that comes the same every time is a faulty
 * cell, see [BolusDeep].
 */
object BrokenRecordDeep {

    /** How many times one journal is read at one heartbeat before the driver stops trying. */
    const val READS_PER_JOURNAL = 3

    /**
     * The first record above the copy [kept] that looks broken, or out of the [bounds] of the last
     * status under the clock offset [offsetMs]; null when they all look sound.
     */
    fun brokenBolus(
        answer: ApexJournalAnswer<ApexBolusRecord>, kept: List<ByteArray>? = null,
        bounds: PlausibleRecordDeep? = null, offsetMs: Long = 0L
    ): ApexRead<ApexBolusRecord>? {
        val current = current(answer, kept)
        current.firstOrNull { it.record.looksBroken }?.let { return it }
        val outOfBounds = bounds?.implausibleBolus(current.map { it.record }, offsetMs) ?: return null
        return current.first { it.record === outOfBounds }
    }

    /** Today, the first day of the answer, when it looks broken; null when it looks sound. */
    fun brokenDay(answer: ApexJournalAnswer<ApexDailyRecord>): ApexRead<ApexDailyRecord>? =
        answer.entries.take(1).firstOrNull { stampHasNoDate(it.frame, ApexDailyRecord.Offset.DATE) }

    /**
     * The records of [answer] above the copy [kept], newest first as the store holds it. The whole
     * answer when there is no copy, or when the answer does not lay over it.
     */
    fun <T> current(answer: ApexJournalAnswer<T>, kept: List<ByteArray>?): List<ApexRead<T>> {
        if (kept.isNullOrEmpty()) return answer.entries
        val newCount = JournalOverlay.newOnTop(answer.entries.map { it.frame.recordIdentity() }, kept.asReversed().map { ApexResponseFrame(it).recordIdentity() })
        return if (newCount == null) answer.entries else answer.entries.take(newCount)
    }

    /** Two answers carry the same records, byte for byte. */
    fun <T> sameAnswer(a: ApexJournalAnswer<T>, b: ApexJournalAnswer<T>): Boolean =
        a.entries.size == b.entries.size && a.entries.zip(b.entries).all { (x, y) -> x.frame.raw.contentEquals(y.frame.raw) }

    /** The stamp at [dataOffset] (year, month, day, ...) has a zero month or a zero day. */
    private fun stampHasNoDate(frame: ApexResponseFrame, dataOffset: Int): Boolean =
        !frame.has(dataOffset, 3) || frame.byteAt(dataOffset + 1) == 0 || frame.byteAt(dataOffset + 2) == 0
}
