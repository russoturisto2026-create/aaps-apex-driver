package app.aaps.pump.atc3.basal

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.pump.atc3.history.ActiveTbr
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3TbrRecord
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat

/**
 * The temporary basal rows shaped by the pump's journal of them, the other way of laying the basal
 * out: a row that just finished closes at what its record delivered, our row's end is reckoned from
 * its record where the pump gives only a stamp, and the rows are brought to the journal minute by
 * minute, with what the pump ran unseen imported, see [Atc3TbrBook]. Nothing in a closed half hour
 * moves, see [Atc3HistorySync.closedBefore]. Writes through [Atc3HistorySync], the one door into AAPS.
 */
internal class Atc3JournalShaping(
    private val sync: Atc3HistorySync,
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val trace: Atc3Trace
) {

    /**
     * Where the row of a temporary basal that just finished closes: at what its minute's records say it
     * delivered, its start moved later until its rate over its time is that, or at [proposedEndMs] when
     * the journal cannot say. The record is written as the temporary basal ends, so the row is right at
     * once; a failed read costs nothing, the book shapes it later.
     */
    suspend fun shapedClose(row: ActiveTbr, proposedEndMs: Long, journal: (() -> List<Atc3TbrRecord>?)?): Long {
        if (journal == null || row.ours || row.suspension || row.rate < Atc3Protocol.DOSE_SCALE / 2) return proposedEndMs
        // In the exact basal mode a row's insulin is the count over its half hour: nothing moves for a record.
        if (sync.closedBefore() > 0L) return proposedEndMs
        val records = journal() ?: return proposedEndMs
        // Only a row on the pump's own start belongs to a minute; one without it is shaped later.
        val raw = Atc3HistorySync.rawOf(row.rate)
        val own = row.pumpStartUtcSeconds ?: return proposedEndMs
        // A row of under a second holds no insulin of its minute, see Atc3TbrBook.Row.hasTime.
        val span = proposedEndMs - row.startedAtMs
        if (span < Atc3HistorySync.MIN_SHAPED_SPAN_MS) {
            trace.event(Atc3TraceCat.TBR, "close", "id" to row.pumpId, "record" to false, "s" to span / 1000)
            return proposedEndMs
        }
        // What the minute gives this row, by the book's one rule, so the book later finds it right.
        val minuteRecords = records.filter { it.startUtcSeconds == own }.sortedByDescending { it.index }
        val delivered = Atc3TbrBook.unitsGivenTo(minuteRecords, minuteRowsOf(own, row, proposedEndMs), row.pumpId)
        if (delivered == null) {
            trace.event(Atc3TraceCat.TBR, "close", "id" to row.pumpId, "record" to false)
            return proposedEndMs
        }
        // The row keeps its rate; its start moves later until rate times time is what was delivered.
        val ran = (delivered / row.rate * 3_600_000.0).toLong()
        val start = if (ran < span) (proposedEndMs - maxOf(ran, Atc3HistorySync.MIN_SHAPED_SPAN_MS)).coerceAtMost(row.startedAtMs + Atc3TbrBook.MAX_START_SHIFT_MS)
        else row.startedAtMs
        trace.event(
            Atc3TraceCat.TBR, "close",
            "id" to row.pumpId, "record" to true, "units" to delivered, "records" to minuteRecords.size,
            "s" to span / 1000, "rate" to row.rate, "raw" to raw, "from" to row.startedAtMs, "to" to start
        )
        if (start != row.startedAtMs) {
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: temporary basal id ${row.pumpId} delivered $delivered U at ${row.rate} U/h, " +
                    "its start moved from ${row.startedAtMs} to $start"
            )
            sync.syncTbrRow(start, row.rate, proposedEndMs - start, PumpSync.TemporaryBasalType.NORMAL, row.pumpId, update = true)
        }
        // Remembered whether or not the start moved: the row is the record's now.
        sync.storeLedger(sync.ledgerNow().withOurTbrShaped(row.pumpId, delivered, proposedEndMs, rowMs = start))
        return proposedEndMs
    }

    /**
     * Where our temporary basal ended when the pump gives only a stamp of it, a minute's precision: its
     * start plus delivered over rate is a second reckoning, and the later of the two is nearer. For
     * another's row, whose start is a stamp too, nothing moves.
     */
    suspend fun endByRecord(row: ActiveTbr, stampMs: Long, phoneNow: Long, journal: (() -> List<Atc3TbrRecord>?)?): Long {
        if (journal == null || !row.ours || row.suspension || row.rate < Atc3Protocol.DOSE_SCALE / 2) return stampMs
        // The row may begin at a half hour, later than the temporary basal: its record is no measure then.
        if (sync.closedBefore() > 0L) return stampMs
        val own = row.pumpStartUtcSeconds ?: return stampMs
        val records = journal() ?: return stampMs
        val minuteRecords = records.filter { it.startUtcSeconds == own }.sortedByDescending { it.index }
        val delivered = Atc3TbrBook.unitsGivenTo(minuteRecords, minuteRowsOf(own, row, stampMs), row.pumpId) ?: return stampMs
        val byRecord = row.startedAtMs + (delivered / row.rate * 3_600_000.0).toLong()
        val end = maxOf(stampMs, byRecord).coerceAtMost(phoneNow)
        trace.event(
            Atc3TraceCat.TBR, "end_by_record",
            "id" to row.pumpId, "stamp" to stampMs, "byRecord" to byRecord, "end" to end, "units" to delivered
        )
        return end
    }

    /** The rows of one minute as the book sees them, with the row being closed closed at [closingEndMs]. */
    private fun minuteRowsOf(startUtcSeconds: Long, closing: ActiveTbr, closingEndMs: Long): List<Atc3TbrBook.Row> {
        val noted = sync.ledgerNow().ourTbrs.mapNotNull { note ->
            if (note.startUtcSeconds != startUtcSeconds || !note.pumpStart) return@mapNotNull null
            val pumpId = note.pumpId ?: return@mapNotNull null
            val rowMs = note.rowMs ?: return@mapNotNull null
            val rawRate = note.rawRate ?: return@mapNotNull null
            val endMs = if (pumpId == closing.pumpId) closingEndMs else note.endMs
            Atc3TbrBook.Row(pumpId, note.startUtcSeconds, rowMs, rawRate, endMs, note.shapedUnits, note.carriedUnits)
        }
        if (noted.any { it.pumpId == closing.pumpId }) return noted
        return noted + Atc3TbrBook.Row(closing.pumpId, startUtcSeconds, closing.startedAtMs, Atc3HistorySync.rawOf(closing.rate), closingEndMs, null, null)
    }

    /**
     * Bring the temporary basal rows to the pump's journal, minute by minute, and import what ran
     * unseen, see [Atc3TbrBook]; asked when the count and the journal disagree.
     *
     * @return how many rows were written or brought to the journal
     */
    suspend fun reconcile(records: List<Atc3TbrRecord>): Int {
        val current = sync.ledgerNow()
        // Only rows on the pump's own start take part.
        val rows = current.ourTbrs.mapNotNull { note ->
            val pumpId = note.pumpId ?: return@mapNotNull null
            val rowMs = note.rowMs ?: return@mapNotNull null
            val rawRate = note.rawRate ?: return@mapNotNull null
            if (!note.pumpStart) return@mapNotNull null
            Atc3TbrBook.Row(pumpId, note.startUtcSeconds, rowMs, rawRate, note.endMs, note.shapedUnits, note.carriedUnits)
        }
        val outcome = Atc3TbrBook.account(
            records = records,
            rows = rows,
            bookStartUtcSeconds = current.tbrImportUtcSeconds,
            phoneNow = dateUtil.now(),
            takenIds = rows.mapTo(HashSet()) { it.pumpId }
        )
        for (carry in outcome.carries) {
            aapsLogger.debug(LTag.PUMP, "ATC3: ${carry.units} U of a row of its minute cut to nothing, kept for id ${carry.toPumpId} to take when it closes")
            trace.event(Atc3TraceCat.TBR, "tbr_minute", "into" to carry.toPumpId, "units" to carry.units)
            sync.storeLedger(sync.ledgerNow().withOurTbrCarried(carry.toPumpId, carry.units))
        }
        // A row AAPS asked for stays as ordered: its record only keeps it from being taken for another's.
        val asOrdered = current.ourTbrs.filter { it.asOrdered }.mapNotNullTo(HashSet()) { it.pumpId }
        // Nor does any row move for its record in the exact basal mode.
        val shapes = if (sync.closedBefore() > 0L) emptyList() else outcome.shapes.filterNot { it.pumpId in asOrdered }
        for (shape in shapes) {
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: temporary basal id ${shape.pumpId} from ${shape.rowMs} for ${shape.durationMs / 1000} s holds " +
                    "${shape.units} U by the journal, ${shape.rateUnitsPerHour} U/h"
            )
            trace.event(Atc3TraceCat.TBR, "reshape", "id" to shape.pumpId, "rate" to shape.rateUnitsPerHour, "at" to shape.rowMs, "s" to shape.durationMs / 1000, "units" to shape.units)
            // Its own rate, its start moved, see Atc3TbrBook.
            sync.syncTbrRow(shape.rowMs, shape.rateUnitsPerHour, shape.durationMs, PumpSync.TemporaryBasalType.NORMAL, shape.pumpId, update = true)
            sync.storeLedger(sync.ledgerNow().withOurTbrShaped(shape.pumpId, shape.units, shape.rowMs + shape.durationMs, rowMs = shape.rowMs))
        }
        for (import in outcome.imports) {
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: the pump ran a temporary basal from ${import.timestamp} for ${import.durationMs / 1000} s, " +
                    "${import.units} U, that AAPS did not know; recorded at ${import.rateUnitsPerHour} U/h, id ${import.pumpId}"
            )
            trace.event(Atc3TraceCat.TBR, "import", "at" to import.timestamp, "s" to import.durationMs / 1000, "units" to import.units, "id" to import.pumpId)
            sync.syncTbrRow(
                import.timestamp, import.rateUnitsPerHour, import.durationMs,
                if (import.units <= 0.0 && import.rateUnitsPerHour <= 0.0) PumpSync.TemporaryBasalType.PUMP_SUSPEND else PumpSync.TemporaryBasalType.NORMAL,
                import.pumpId
            )
        }
        sync.storeLedger(sync.ledgerNow().withTbrWatermark(outcome.newestUtcSeconds))
        trace.event(
            Atc3TraceCat.HIST, "tbr_history",
            "held" to records.size,
            "minutes" to outcome.minutes,
            "with_rows" to outcome.minutesWithRows,
            "stale" to outcome.stale,
            "shaped" to shapes.size,
            "carried" to outcome.carries.size,
            "imported" to outcome.imports.size
        )
        return shapes.size + outcome.imports.size + outcome.carries.size
    }
}
