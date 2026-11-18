package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.account.tbr.TbrJournal
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.deep.BrokenRecordDeep
import app.aaps.pump.apex.deep.PlausibleRecordDeep
import app.aaps.pump.apex.deep.ReadAgainDeep
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
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
 * Every object is asked again while the pump is silent or its answer is not sound, by one rule, see
 * [ReadAgainDeep]. A pump that still does
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

    /** Status V1 alone, the first read of every reconciliation: whether a bolus runs is asked from it before anything else is read. Null when the pump fell silent. */
    fun readStatus(): ApexPumpState.StatusCard? = manager.readStatus() ?: silent("Status V1")

    /**
     * The rest of the picture after [status], the Status V1 of this heartbeat read first, see [readStatus]:
     * null when the pump fell silent or a journal could not be read soundly. [serial] empty reads both
     * journals all the same. [readAll]: every object is read whatever the objects before it show, the
     * bolus journal and the last finished temporary basal too: the clock is written after this heartbeat.
     */
    fun read(heartbeatAtMs: Long, serial: String, status: ApexPumpState.StatusCard, readAll: Boolean = false): PumpPicture? {
        val part = readBasalPart(heartbeatAtMs, serial, status, readAll) ?: return null
        val bolusGate = when {
            serial.isEmpty() -> BolusGate.Decision(true, "no pump known yet")
            readAll          -> BolusGate.Decision(true, READ_ALL)
            else             -> BolusGate(store, serial).decide(part.status.deliveredTodayUnits, part.days)
        }
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

    /**
     * The numbers of [picture] read anew, after the journal was read once more:
     * Status V1, then the daily statistics when the picture has them, so that they count every record
     * the journal showed. Null when the pump did not answer: there is no reconciliation then.
     */
    fun refresh(picture: PumpPicture): PumpPicture? {
        val status = manager.readStatus() ?: return silent("Status V1 read again")
        val days = if (picture.days == null) null else readJournal("daily statistics read again", manager::readDailyStats, BrokenRecordDeep::brokenDay) ?: return null
        return picture.withNumbers(status, days)
    }

    /** The objects of the picture before the bolus journal: everything but the bolus part. */
    private class BasalPart(
        val heartbeatAtMs: Long, val status: ApexPumpState.StatusCard,
        val days: JournalRead<ApexDailyRecord>?, val tbr: TbrFacts?, val tbrGate: TbrGate.Decision
    ) {

        /** [daysAfter]: the daily statistics read after the bolus journal when it was read, else [days]. */
        fun picture(boluses: JournalRead<ApexBolusRecord>?, bolusGate: BolusGate.Decision, daysAfter: JournalRead<ApexDailyRecord>?) =
            PumpPicture(heartbeatAtMs, status, boluses, bolusGate, daysAfter, tbr, tbrGate, System.currentTimeMillis())
    }

    private fun readBasalPart(heartbeatAtMs: Long, serial: String, status: ApexPumpState.StatusCard, readAll: Boolean): BasalPart? {
        val abilities = ApexAbilities.of(store, serial, pumpState.version?.firmwareText)
        val days = if (abilities.dayRecord) readJournal("daily statistics", manager::readDailyStats, BrokenRecordDeep::brokenDay) ?: return null else null
        val hasStatusV2 = abilities.statusV2
        // Status V2 goes to the pump state as it is read; the picture does not hold it.
        if (hasStatusV2 && (pumpState.statusV2 == null || HeartbeatGrid.onHalfHour(HeartbeatGrid.minuteOf(heartbeatAtMs)))) manager.readStatusV2() ?: return silent("Status V2")

        val tbrGate = when {
            serial.isEmpty() -> TbrGate.Decision(false, "no pump known yet")
            readAll          -> TbrGate.Decision(true, READ_ALL)
            else             -> TbrGate.decide(TbrJournal.lastOf(store, serial)?.status, status.status)
        }
        aapsLogger.info(LTag.PUMP, "Apex: last finished temporary basal ${if (tbrGate.read) "read" else "not read"}, ${tbrGate.reason}")
        trace.event(ApexTraceCat.STATE, "tbr_gate", "read" to tbrGate.read, "reason" to tbrGate.reason)
        val tbr = if (tbrGate.read) tbrReader.read(serial) ?: return null else null
        return BasalPart(heartbeatAtMs, status, days, tbr, tbrGate)
    }

    /** One journal, [what], read by the one rule of reading again, see [ReadAgainDeep.journal]. */
    fun <T> readJournal(
        what: String,
        read: () -> ApexJournalAnswer<T>?,
        brokenIn: (ApexJournalAnswer<T>) -> ApexRead<T>?
    ): JournalRead<T>? = ReadAgainDeep.journal(aapsLogger, what, read, manager::pumpNotWorking, brokenIn)

    /** The copy of a journal the driver keeps, newest first; null when there is none yet. */
    private fun kept(serial: String, kind: SnapshotKind): List<ByteArray>? = store.snapshot(serial, kind)?.frames

    /** The bounds of the pump's status, for the records read now. */
    private fun bounds(serial: String): PlausibleRecordDeep =
        PlausibleRecordDeep.now(store, serial, pumpState)

    /** The offset the pump's clock stands in now. */
    private fun offsetMs(serial: String): Long = ApexClock.offsetMs(store, serial, ApexClock.phoneOffsetMs(System.currentTimeMillis()))

    private fun <T> silent(what: String): T? {
        aapsLogger.warn(LTag.PUMP, "Apex: no answer to the read of the $what, the picture of this heartbeat is dropped")
        return null
    }

    companion object {

        /** Why every object is read: the clock is written after this heartbeat, and a write closes a period. */
        const val READ_ALL = "the clock is written after this heartbeat, everything before it is read"
    }
}
