package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.account.tbr.TbrJournal
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.deep.BrokenRecordDeep
import app.aaps.pump.apex.deep.PlausibleRecordDeep
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.protocol.ApexStatusV2
import app.aaps.pump.apex.state.ApexAbilities
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Reads the pump's picture at a heartbeat, one object after the other. Status V1 first, as the
 * main object, then the daily statistics: together they say what the pump delivered since the last
 * heartbeat, by kind, see [BolusGate]. Status V2 carries the battery and nothing that moves fast:
 * it is read on the half hour, and once when it is not known yet, and only when the pump has it. The temporary basal facts come
 * next, by [TbrGate] and [TbrFactsReader]: the status is held against the status of the last
 * reconciliation, and the last finished temporary basal is read when they differ, or neither shows
 * one running. The bolus journal comes last, by [BolusGate]. When it is read,
 * the daily statistics are read once more after it: the bolus records are held against the field
 * read after them, so that a bolus that came between the two reads is in the field and not
 * missing from it, see `BolusJournal`. A pump without the daily statistics is not asked for them: its
 * bolus journal is read at every heartbeat, and the count of Status V1 stands for what they gave. The exchange keeps the pause between requests, see
 * `ApexConst.REQUEST_GAP_MS`.
 *
 * Every object is asked again while the pump is silent or its answer is not sound: one object by
 * the exchange, see `ApexExchange.ASKS`, a journal here, see [readJournal]. A pump that still does
 * not answer ends the picture: the exchange's own watch on the link counts the silence and drops a
 * dead link, and this heartbeat's reconciliation is skipped.
 */
@SingleIn(AppScope::class)
@Inject
class PictureReader(
    private val aapsLogger: AAPSLogger,
    private val manager: ApexManager,
    private val store: ApexStore,
    private val pumpState: ApexPumpState,
    private val tbrReader: TbrFactsReader,
    private val trace: ApexTrace
) {

    /** The picture, or null when the pump fell silent or a journal could not be read soundly. [serial] empty reads both journals all the same. */
    fun read(heartbeatAtMs: Long, serial: String): PumpPicture? {
        val part = readBasalPart(heartbeatAtMs, serial) ?: return null
        val bolusGate = if (serial.isEmpty()) BolusGate.Decision(true, "no pump known yet") else BolusGate(store, serial).decide(part.status.deliveredTodayUnits, part.days)
        aapsLogger.info(
            LTag.PUMP,
            "Apex: bolus journal ${if (bolusGate.read) "read" else "not read"}, ${bolusGate.reason}; since the last heartbeat bolus ${bolusGate.bolusMovedUnits} basal ${bolusGate.basalMovedUnits} temporary ${bolusGate.tbrMovedUnits} U, sum gap ${bolusGate.sumGapUnits} U, our boluses ${bolusGate.ourBolusUnits}"
        )
        trace.event(
            ApexTraceCat.STATE, "bolus_gate", "read" to bolusGate.read, "reason" to bolusGate.reason,
            "bolus" to bolusGate.bolusMovedUnits, "basal" to bolusGate.basalMovedUnits, "tbr" to bolusGate.tbrMovedUnits, "gap" to bolusGate.sumGapUnits,
            "ours" to bolusGate.ourBolusUnits
        )
        val boluses = if (bolusGate.read) readJournal("bolus journal", manager::readBolusHistory) { BrokenRecordDeep.brokenBolus(it, kept(serial, SnapshotKind.BOLUS), bounds(serial), offsetMs(serial)) } ?: return null else null
        val days = if (boluses == null || part.days == null) part.days else readJournal("daily statistics after the bolus journal", manager::readDailyStats, BrokenRecordDeep::brokenDay) ?: return null
        return part.picture(boluses, bolusGate, days)
    }

    /** The last finished temporary basal, see [TbrFactsReader]; null when the read got no sound answer. */
    fun tbrFacts(serial: String): TbrFacts? = tbrReader.read(serial)

    /**
     * The picture read while our bolus runs: the basal and temporary basal part only. The bolus
     * journal is not read and the bolus gate is not asked: the record comes when the bolus ends,
     * and the bolus command reads it then. See `HeartbeatTick.runDuringBolus`.
     */
    fun readDuringBolus(heartbeatAtMs: Long, serial: String): PumpPicture? {
        val part = readBasalPart(heartbeatAtMs, serial) ?: return null
        return part.picture(boluses = null, BolusGate.Decision(false, "our bolus runs, its record comes when it ends"), part.days)
    }

    /** The objects of the picture before the bolus journal: everything but the bolus part. */
    private class BasalPart(
        val heartbeatAtMs: Long, val status: ApexPumpState.StatusCard, val statusV2: ApexStatusV2?,
        val days: JournalRead<ApexDailyRecord>?, val tbr: TbrFacts?, val tbrGate: TbrGate.Decision
    ) {

        /** [daysAfter]: the daily statistics read after the bolus journal when it was read, else [days]. */
        fun picture(boluses: JournalRead<ApexBolusRecord>?, bolusGate: BolusGate.Decision, daysAfter: JournalRead<ApexDailyRecord>?) =
            PumpPicture(heartbeatAtMs, status, statusV2, boluses, bolusGate, daysAfter, tbr, tbrGate, System.currentTimeMillis())
    }

    private fun readBasalPart(heartbeatAtMs: Long, serial: String): BasalPart? {
        val status = manager.readStatus() ?: return silent("Status V1")
        val abilities = ApexAbilities.of(store, serial, pumpState.version?.firmwareText)
        val days = if (abilities.dayRecord) readJournal("daily statistics", manager::readDailyStats, BrokenRecordDeep::brokenDay) ?: return null else null
        val hasStatusV2 = abilities.statusV2
        val statusV2 = if (hasStatusV2 && (pumpState.statusV2 == null || HeartbeatGrid.onHalfHour(HeartbeatGrid.minuteOf(heartbeatAtMs)))) manager.readStatusV2() ?: return silent("Status V2") else null

        val tbrGate = if (serial.isEmpty()) TbrGate.Decision(false, "no pump known yet") else TbrGate.decide(TbrJournal.lastOf(store, serial)?.status, status.status)
        aapsLogger.info(LTag.PUMP, "Apex: last finished temporary basal ${if (tbrGate.read) "read" else "not read"}, ${tbrGate.reason}")
        trace.event(ApexTraceCat.STATE, "tbr_gate", "read" to tbrGate.read, "reason" to tbrGate.reason)
        val tbr = if (tbrGate.read) tbrReader.read(serial) ?: return null else null
        return BasalPart(heartbeatAtMs, status, statusV2, days, tbr, tbrGate)
    }

    /**
     * One journal, read again while it brings nothing sound: no answer, an answer with no record at
     * all, which is silence too (see `PlausibleRecordDeep`), or a record that looks broken. All of
     * these count in one number of reads, [BrokenRecordDeep.READS_PER_JOURNAL]. Null when no answer
     * came by then, or the answers kept changing and none came sound, or not every read was answered
     * and so the broken record cannot be told from a bad read. The bolus journal after our own bolus
     * is read the same way, see `BolusJournal`.
     */
    fun <T> readJournal(
        what: String,
        read: () -> ApexJournalAnswer<T>?,
        brokenIn: (ApexJournalAnswer<T>) -> ApexRead<T>?
    ): JournalRead<T>? {
        var last: ApexJournalAnswer<T>? = null
        var sameEveryTime = true
        var answered = 0
        repeat(BrokenRecordDeep.READS_PER_JOURNAL) { attempt ->
            val answer = read()
            if (answer == null || answer.entries.isEmpty()) {
                aapsLogger.warn(LTag.PUMP, "Apex: the $what read ${attempt + 1} of ${BrokenRecordDeep.READS_PER_JOURNAL} got ${if (answer == null) "no answer" else "no record at all, taken as silence"}")
                return@repeat
            }
            answered++
            val broken = brokenIn(answer) ?: return JournalRead(answer, repeatedBroken = false)
            aapsLogger.warn(LTag.PUMP, "Apex: the $what answer has a record that looks broken at position ${broken.frame.recordIndex}, read ${attempt + 1} of ${BrokenRecordDeep.READS_PER_JOURNAL}: ${hex(broken.frame.raw)}")
            if (last != null && !BrokenRecordDeep.sameAnswer(last!!, answer)) sameEveryTime = false
            last = answer
        }
        if (last == null) return silent(what)
        if (answered < BrokenRecordDeep.READS_PER_JOURNAL) {
            aapsLogger.error(LTag.PUMP, "Apex: the $what answers came broken and not every read was answered, this heartbeat is skipped")
            return null
        }
        if (sameEveryTime) {
            aapsLogger.warn(LTag.PUMP, "Apex: the $what answer came the same every time, the broken-looking record is what the pump holds")
            return JournalRead(last!!, repeatedBroken = true)
        }
        aapsLogger.error(LTag.PUMP, "Apex: the $what answers kept changing and none came sound, this heartbeat is skipped")
        return null
    }

    /** The copy of a journal the driver keeps, newest first; null when there is none yet. */
    private fun kept(serial: String, kind: SnapshotKind): List<ByteArray>? = store.snapshot(serial, kind)?.frames

    /** The bounds of the last status seen, for the records read now. */
    private fun bounds(serial: String): PlausibleRecordDeep = PlausibleRecordDeep(store, serial, System.currentTimeMillis())

    /** The offset the pump's clock stands in now. */
    private fun offsetMs(serial: String): Long = ApexClock.offsetMs(store, serial, ApexClock.phoneOffsetMs(System.currentTimeMillis()))

    private fun <T> silent(what: String): T? {
        aapsLogger.warn(LTag.PUMP, "Apex: no answer to the read of the $what, the picture of this heartbeat is dropped")
        return null
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    companion object {
    }
}
