package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.basal.BasalPeriodKeeper
import app.aaps.pump.apex.basal.TbrFacts
import app.aaps.pump.apex.basal.TbrFactsReader
import app.aaps.pump.apex.basal.TbrGate
import app.aaps.pump.apex.bolus.BolusCells
import app.aaps.pump.apex.bolus.BolusGate
import app.aaps.pump.apex.store.ApexStore
import app.aaps.pump.apex.basal.TbrReconciliation
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.statistics.Delivered
import app.aaps.pump.apex.statistics.StatisticsDays
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
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
 * one running. With the exact basal record on, they are not read at a heartbeat that closes its period,
 * see [BasalPeriodKeeper.closesAt]: the statistics are the row then. The bolus journal comes last, by [BolusGate]. When it is read,
 * the daily statistics are read once more after it: the bolus records are held against the field
 * read after them, so that a bolus that came between the two reads is in the field and not
 * missing from it, see `BolusReconciliation`. The exchange keeps the pause between requests, see
 * `ApexConst.REQUEST_GAP_MS`.
 *
 * Every object is asked again while the pump is silent or its answer is not sound, by one rule, see
 * [ReadAgain]. A pump that still does
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
    private val basalPeriod: BasalPeriodKeeper,
    private val trace: ApexTrace
) {

    /** Status V1 alone, the first read of every reconciliation: whether a bolus runs is asked from it before anything else is read. Null when the pump fell silent. */
    fun readStatus(): ApexPumpState.StatusCard? = manager.readStatus() ?: silent("Status V1")

    /**
     * The rest of the picture after [status], the Status V1 of this heartbeat read first, see [readStatus]:
     * null when the pump fell silent or a journal could not be read soundly. [serial] empty reads both
     * journals all the same. [readAll]: not null, every object is read whatever the objects before it show,
     * the bolus journal and the last finished temporary basal too, see [ReadAll].
     */
    fun read(heartbeatAtMs: Long, serial: String, status: ApexPumpState.StatusCard, readAll: ReadAll? = null): PumpPicture? {
        val part = readBasalPart(heartbeatAtMs, serial, status, readAll) ?: return null
        val bolusGate = when {
            serial.isEmpty()   -> BolusGate.Decision(true, "no pump known yet")
            readAll != null    -> BolusGate.Decision(true, readAll.reason)
            else               -> BolusGate(store, serial).decide(part.status.deliveredTodayUnits, part.days)
        }
        aapsLogger.info(
            LTag.PUMP,
            "Apex: bolus journal ${if (bolusGate.read) "read" else "not read"}, ${bolusGate.reason}; since the last heartbeat bolus ${bolusGate.bolusMovedUnits} basal ${bolusGate.basalMovedUnits} temporary ${bolusGate.tbrMovedUnits} U, sum gap ${bolusGate.sumGapUnits} U"
        )
        trace.event(
            ApexTraceCat.STATE, "bolus_gate", "read" to bolusGate.read, "reason" to bolusGate.reason,
            "bolus" to bolusGate.bolusMovedUnits, "basal" to bolusGate.basalMovedUnits, "tbr" to bolusGate.tbrMovedUnits, "gap" to bolusGate.sumGapUnits
        )
        val boluses = if (bolusGate.read) readBolusJournal("bolus journal", manager::readBolusHistory, serial) ?: return null else null
        val days = if (boluses == null) part.days else readJournal("daily statistics after the bolus journal", manager::readDailyStats, daysBroken(serial, readAll)) ?: return null
        return part.picture(boluses, bolusGate, days, start = readAll == ReadAll.START)
    }

    /** The objects of the picture before the bolus journal: everything but the bolus part. */
    private class BasalPart(
        val heartbeatAtMs: Long, val status: ApexPumpState.StatusCard,
        val days: JournalRead<ApexDailyRecord>, val tbr: TbrFacts?, val tbrGate: TbrGate.Decision
    ) {

        /** [daysAfter]: the daily statistics read after the bolus journal when it was read, else [days]. */
        fun picture(boluses: JournalRead<ApexBolusRecord>?, bolusGate: BolusGate.Decision, daysAfter: JournalRead<ApexDailyRecord>, start: Boolean) =
            PumpPicture(heartbeatAtMs, status, boluses, bolusGate, daysAfter, tbr, tbrGate, System.currentTimeMillis(), start)
    }

    /** How today's day of [days] moved since the day kept, see `TbrGate`. */
    private fun movedSinceKept(serial: String, days: JournalRead<ApexDailyRecord>, atMs: Long): StatisticsDays.Moved? =
        days.answer.entries.firstOrNull()?.record?.let { StatisticsDays(store, serial).movedSinceKept(it, atMs) }

    private fun readBasalPart(heartbeatAtMs: Long, serial: String, status: ApexPumpState.StatusCard, readAll: ReadAll?): BasalPart? {
        val days = readJournal("daily statistics", manager::readDailyStats, daysBroken(serial, readAll)) ?: return null
        // A pump whose version is not known yet is not asked: the battery comes from Status V1 then.
        val hasStatusV2 = pumpState.version?.hasStatusV2 == true
        // Status V2 goes to the pump state as it is read; the picture does not hold it.
        if (hasStatusV2 && (pumpState.statusV2 == null || HeartbeatGrid.onHalfHour(HeartbeatGrid.minuteOf(heartbeatAtMs)))) manager.readStatusV2() ?: return silent("Status V2")

        val tbrGate = when {
            serial.isEmpty()   -> TbrGate.Decision(false, "no pump known yet")
            readAll != null    -> TbrGate.Decision(true, readAll.reason)
            basalPeriod.closesAt(serial, heartbeatAtMs, Delivered(days.answer.entries.map { it.record }, days.repeatedBroken)) ->
                TbrGate.Decision(false, "the exact basal record closes its period at this heartbeat: the statistics are the row")
            else               -> TbrGate.decide(TbrReconciliation.lastOf(store, serial)?.status, status.status, movedSinceKept(serial, days, status.readAtMs))
        }
        aapsLogger.info(LTag.PUMP, "Apex: last finished temporary basal ${if (tbrGate.read) "read" else "not read"}, ${tbrGate.reason}")
        trace.event(ApexTraceCat.STATE, "tbr_gate", "read" to tbrGate.read, "reason" to tbrGate.reason)
        val tbr = if (tbrGate.read) tbrReader.read() ?: return null else null
        return BasalPart(heartbeatAtMs, status, days, tbr, tbrGate)
    }

    /** The hints of a flicker in a statistics answer, held against the copy of [serial]; the closed days too at the start, see [BrokenRecord.brokenDay]. */
    private fun daysBroken(serial: String, readAll: ReadAll?): (ApexJournalAnswer<ApexDailyRecord>) -> List<ApexRead<ApexDailyRecord>> = { answer ->
        BrokenRecord.brokenDay(answer, StatisticsDays(store, serial).keptFrames(), closedToo = readAll == ReadAll.START)
    }

    /** One journal, [what], read by the one rule of reading again, see [ReadAgain.journal]. */
    fun <T> readJournal(
        what: String,
        read: () -> ApexJournalAnswer<T>?,
        brokenIn: (ApexJournalAnswer<T>) -> List<ApexRead<T>>
    ): JournalRead<T>? = ReadAgain.journal(aapsLogger, what, read, manager::pumpNotWorking, brokenIn)

    /** The bolus journal of [serial], [what], read by [read]: a flickering record new above the cells is read again, see [BrokenRecord.brokenBolus]. */
    fun readBolusJournal(what: String, read: () -> ApexJournalAnswer<ApexBolusRecord>?, serial: String): JournalRead<ApexBolusRecord>? =
        readJournal(what, read, { BrokenRecord.brokenBolus(it, BolusCells(store, serial).keptFrames()) })

    private fun <T> silent(what: String): T? {
        aapsLogger.warn(LTag.PUMP, "Apex: no answer to the read of the $what, the picture of this heartbeat is dropped")
        return null
    }

    /** Why every object is read, whatever the objects before it show; [reason] goes to the log. */
    enum class ReadAll(val reason: String) {

        /** The first reconciliation since AAPS started: the bolus journal is settled whatever the statistics show, the closed days are checked. */
        START("the first reconciliation since AAPS started"),

        /** The clock is written after this heartbeat: a write closes a period, everything before it is read. */
        CLOCK("the clock is written after this heartbeat, everything before it is read"),

        /** The last reconciliation broke on an error of the code: everything is read again. */
        AGAIN("the last reconciliation broke on an error of the code, everything is read again")
    }
}
