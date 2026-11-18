package app.aaps.pump.apex.deep

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.account.AapsCarrier
import app.aaps.pump.apex.account.AapsToday
import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.NoticeKind
import app.aaps.pump.apex.account.ReferencePoints
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.pulse.PictureReader
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import kotlinx.coroutines.runBlocking

/**
 * The reads made when a bolus answer does not show the newest record kept from the last one.
 *
 * The problem. Each answer is laid over the copy of the last one by the newest record kept, see
 * [JournalSnapshot]. An answer that does not show that record cannot be laid: it may be one bad
 * answer of the pump, more records may have come than one answer carries (the pump was away from
 * AAPS, or many boluses were given on it), or the pump started its journal over. Taken as it is, it
 * would write the same boluses twice or lose some.
 *
 * When it applies. Only when the answer of a heartbeat or of our bolus does not show the newest
 * record kept. On the plain path every answer shows it, and nothing here runs.
 *
 * The way out, by the rules of [JournalRestartDeep]: the journal is read a second time. The second
 * answer laid over the copy is settled as usual. Two answers that differ settle nothing until the
 * next read. Two answers alike that hold fewer records than one answer carries: the pump started
 * over, its records are faulty cells. Else, when the boluses were last held against AAPS longer ago
 * than insulin acts, the account of boluses starts clean and the user is told. Else the whole
 * journal is read and laid over the copy; a whole journal that is not read settles nothing, up to
 * [JournalRestartDeep.WHOLE_JOURNAL_TRIES] heartbeats, and then, or when it does not show the copy
 * either, the account of boluses starts clean and the user is told.
 */
class JournalReadAgainDeep(
    private val aapsLogger: AAPSLogger,
    private val store: ApexStore,
    private val manager: ApexManager,
    private val reader: PictureReader,
    private val carrier: AapsCarrier,
    private val aapsToday: AapsToday,
    private val trace: ApexTrace
) {

    /**
     * [first] did not show the newest record kept: the journal is read again, see the rules above.
     * [bounds] are the bounds for records read now, see [PlausibleRecordDeep]. Null when nothing is kept.
     */
    fun readAgain(
        serial: String, journal: JournalSnapshot, first: ApexJournalAnswer<ApexBolusRecord>, offsetMs: Long, nowMs: Long, bounds: PlausibleRecordDeep
    ): JournalSnapshot.Added? {
        aapsLogger.warn(LTag.PUMP, "Apex: the bolus answer does not show the last record kept, reading the journal again")
        val second = reader.readJournal("bolus journal again", manager::readBolusHistory) { BrokenRecordDeep.brokenBolus(it, bounds = bounds, offsetMs = offsetMs) }?.answer ?: return null
        journal.take(JournalSnapshot.boluses(second), offsetMs, nowMs)?.let { return it.also { wholeNotNeeded(serial) } }
        if (!JournalRestartDeep.startsAnew(first, second)) {
            aapsLogger.warn(LTag.PUMP, "Apex: two bolus answers differ and neither shows the last record kept, nothing is settled until the next read")
            trace.event(ApexTraceCat.STATE, "bolus_journal_unlaid", "first" to first.entries.size, "second" to second.entries.size)
            return null
        }
        if (JournalRestartDeep.startedOver(second)) {
            aapsLogger.error(LTag.PUMP, "Apex: two bolus answers alike show nothing of the last record kept, and the pump holds only ${second.entries.size} records: the pump started its journal over, a fault")
            trace.event(ApexTraceCat.STATE, "bolus_journal_started_over", "records" to second.entries.size, "count" to second.recordCount)
            return journal.startOver(JournalSnapshot.boluses(second), offsetMs, nowMs)
        }
        val lastHeldMs = lastHeldMs(serial)
        if (JournalRestartDeep.actsNoMore(lastHeldMs, nowMs, runBlocking { aapsToday.insulinActsMs() })) {
            aapsLogger.error(LTag.PUMP, "Apex: two bolus answers alike show nothing of the last record kept, and the boluses were last held against AAPS at $lastHeldMs, longer ago than insulin acts: the account of boluses starts clean, the user is told")
            trace.event(ApexTraceCat.STATE, "bolus_journal_restart", "records" to second.entries.size, "held" to lastHeldMs)
            return startClean(serial, journal, second, NoticeKind.JOURNAL_CLEAN_START, offsetMs, nowMs)
        }
        aapsLogger.warn(LTag.PUMP, "Apex: two bolus answers alike show nothing of the last record kept, and the boluses were held against AAPS at $lastHeldMs: reading the whole journal")
        val kept = store.snapshot(serial, SnapshotKind.BOLUS)?.frames
        val whole = reader.readJournal("whole bolus journal", manager::readWholeBolusHistory) { BrokenRecordDeep.brokenBolus(it, kept, bounds, offsetMs) }?.answer
        if (whole == null) {
            val unread = (store.state(serial, STATE_WHOLE_UNREAD)?.toIntOrNull() ?: 0) + 1
            trace.event(ApexTraceCat.STATE, "bolus_journal_whole_unread", "times" to unread)
            if (unread < JournalRestartDeep.WHOLE_JOURNAL_TRIES) {
                store.putState(serial, STATE_WHOLE_UNREAD, unread.toString())
                aapsLogger.error(LTag.PUMP, "Apex: the whole bolus journal was not read, time $unread of ${JournalRestartDeep.WHOLE_JOURNAL_TRIES}: nothing is settled until the next heartbeat")
                return null
            }
            aapsLogger.error(LTag.PUMP, "Apex: the whole bolus journal was not read $unread times in a row, a fault: the user is told, the account of boluses starts clean")
            return startClean(serial, journal, second, NoticeKind.JOURNAL_UNLAID, offsetMs, nowMs)
        }
        journal.take(JournalSnapshot.boluses(whole), offsetMs, nowMs)?.let {
            aapsLogger.warn(LTag.PUMP, "Apex: the whole bolus journal of ${whole.entries.size} records shows the last record kept, ${it.records.size} records are new above it")
            trace.event(ApexTraceCat.STATE, "bolus_journal_whole", "records" to whole.entries.size, "new" to it.records.size)
            wholeNotNeeded(serial)
            return it
        }
        aapsLogger.error(LTag.PUMP, "Apex: the whole bolus journal of ${whole.entries.size} records shows nothing of the last record kept, a fault: the user is told, the account of boluses starts clean")
        trace.event(ApexTraceCat.STATE, "bolus_journal_whole_unlaid", "records" to whole.entries.size)
        return startClean(serial, journal, whole, NoticeKind.JOURNAL_UNLAID, offsetMs, nowMs)
    }

    /** The answer lay over the copy: the count of heartbeats the whole journal stayed unread starts over. */
    fun wholeNotNeeded(serial: String) {
        if (store.state(serial, STATE_WHOLE_UNREAD) != null) store.putState(serial, STATE_WHOLE_UNREAD, null)
    }

    /**
     * The account of boluses starts clean from [answer]: its records are the past, every reference
     * point starts from zero with it, see [ReferencePoints], and the user is told why, [notice].
     */
    private fun startClean(
        serial: String, journal: JournalSnapshot, answer: ApexJournalAnswer<ApexBolusRecord>, notice: NoticeKind, offsetMs: Long, nowMs: Long
    ): JournalSnapshot.Added {
        val added = store.transaction {
            ReferencePoints.drop(store, serial)
            store.putState(serial, STATE_WHOLE_UNREAD, null)
            journal.restart(JournalSnapshot.boluses(answer), offsetMs, nowMs)
        }
        carrier.carry(AapsWrite.Notice(notice, atMs = nowMs), serial)
        return added
    }

    /**
     * When the boluses of this pump were last held against AAPS: the last bolus answer laid over
     * the copy, or the last day of the statistics kept, whichever is later. Null when neither is kept.
     */
    private fun lastHeldMs(serial: String): Long? =
        listOfNotNull(store.snapshot(serial, SnapshotKind.BOLUS)?.readAtMs, store.snapshot(serial, SnapshotKind.DAILY)?.readAtMs).filter { it > 0 }.maxOrNull()

    private companion object {

        /** How many heartbeats in a row the whole journal was asked for and not read. */
        const val STATE_WHOLE_UNREAD = "wholeBolusJournalUnread"
    }
}
