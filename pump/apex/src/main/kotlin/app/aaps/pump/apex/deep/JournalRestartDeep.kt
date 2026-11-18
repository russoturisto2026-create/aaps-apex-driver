package app.aaps.pump.apex.deep

import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexProtocol

/**
 * A bolus answer that does not show the newest record kept from the last one. It may be a single
 * bad answer of the pump, or more records came than one answer carries. One answer does not tell
 * them apart, so the journal is read a second time. When the second answer shows the kept record,
 * it is settled as usual. When the two differ, the pump answered badly: nothing is settled, and
 * the next read tries again. When the two are alike, record for record, and neither shows the
 * kept record, more records came than one answer carries, and what is done depends on when the
 * boluses of this pump were last held against AAPS, by the journal or by the statistics:
 *
 * - Longer ago than the insulin acts, see [actsNoMore]: the pump was away from AAPS long. The
 *   account of boluses starts clean: the records are the past, nothing of them is written, and
 *   every reference point starts from zero. The user is told that the insulin on board may be wrong.
 * - Not so long ago: every record counts. The whole journal of the pump is read and laid over the
 *   copy, and the records above the copy are settled as usual. A whole journal that is not read
 *   settles nothing, and the next heartbeat reads it again, [WHOLE_JOURNAL_TRIES] heartbeats in all.
 * - The whole journal shows nothing of the copy, or stays unread: a fault of the pump or of the
 *   driver. The user is told, and the account of boluses starts clean the same way.
 *
 * Two answers alike with fewer records than one answer carries are another thing: the pump holds no
 * more records than these, it started its journal over. That is a fault, not the past: its records
 * are faulty cells of the span, and the span is closed by the daily statistics, see [BolusDeep].
 * The answer is kept as the copy, so the next answer lays over it.
 */
object JournalRestartDeep {

    /** True when the two answers that showed nothing of the copy say the journal starts anew: they are alike, record for record. */
    fun <T> startsAnew(first: ApexJournalAnswer<T>, second: ApexJournalAnswer<T>): Boolean = BrokenRecordDeep.sameAnswer(first, second)

    /**
     * True when the boluses of the pump were last held against AAPS, at [lastHeldMs], longer ago
     * at [nowMs] than both the insulin in use acts, [insulinActsMs], and [ACTS_NO_MORE_MS]: the
     * larger of the two counts. True when they never were.
     */
    fun actsNoMore(lastHeldMs: Long?, nowMs: Long, insulinActsMs: Long?): Boolean =
        lastHeldMs == null || nowMs - lastHeldMs > maxOf(insulinActsMs ?: 0L, ACTS_NO_MORE_MS)

    /** The shortest time insulin is taken to act, whatever insulin AAPS tells: five hours. */
    const val ACTS_NO_MORE_MS = 5 * 60 * 60_000L

    /** How many heartbeats in a row the whole journal is asked for before its silence is a fault. */
    const val WHOLE_JOURNAL_TRIES = 3

    /** True when [answer], which shows nothing of the copy, holds every record the pump has, and they are fewer than one answer carries: the pump started its journal over. */
    fun <T> startedOver(answer: ApexJournalAnswer<T>): Boolean =
        answer.entries.size < ApexProtocol.ALIAS_ANSWER_FRAMES && answer.recordCount <= answer.entries.size
}
