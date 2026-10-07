package app.aaps.pump.atc3.basal

import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.history.Atc3PumpId
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3TbrRecord
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the AAPS rows must hold for what the pump's temporary basal journal says, minute by minute.
 *
 * The pump stamps a temporary basal with the whole minute, so all set within one minute share that
 * start, and its journal has one record each with what it delivered; AAPS keeps one row per moment.
 * So the unit is the minute: a minute's records land once and in full in its rows that have time.
 * A row keeps the rate it was set at; where its records say less went in, its start moves later, by
 * no more than [MAX_START_SHIFT_MS], the stamp's own error. Records of a minute AAPS has no row of
 * are imported; a first read imports nothing. Nothing here writes or remembers.
 */
object Atc3TbrBook {

    /** A temporary basal row AAPS holds, as the ledger noted it. */
    data class Row(
        val pumpId: Long,
        /** The pump's start as a UTC-calendar key: the minute's identity. */
        val startUtcSeconds: Long,
        /** Where the row begins in AAPS; the resume for a continuation. */
        val rowMs: Long,
        /** The rate it was set at, in raw steps. */
        val rawRate: Int,
        /** Where AAPS's row ends, or null while it is open. */
        val endMs: Long?,
        /** The insulin the row was last shaped to, or null when it never was. */
        val shapedUnits: Double?,
        /** Insulin handed to it from a row of its minute cut to nothing, or null. */
        val carriedUnits: Double?
    ) {

        /** True when the row can hold insulin: still open, or long enough. */
        val hasTime: Boolean get() = endMs == null || endMs - rowMs >= MIN_SPAN_MS
    }

    /** A row of AAPS brought to the insulin its minute's records give it. */
    data class Shape(val pumpId: Long, val rowMs: Long, val durationMs: Long, val rateUnitsPerHour: Double, val units: Double)

    /** A row AAPS has not got: another's temporary basal, or one nobody saw begin. */
    data class Import(val timestamp: Long, val durationMs: Long, val rateUnitsPerHour: Double, val pumpId: Long, val units: Double)

    /** Insulin handed to an open row of the minute, to be counted when it closes. */
    data class Carry(val toPumpId: Long, val units: Double)

    data class Outcome(
        val shapes: List<Shape>,
        val imports: List<Import>,
        val carries: List<Carry>,
        /** Where the book now begins: the newest record's start. */
        val newestUtcSeconds: Long,
        /** How many minutes the records covered, and how many of them AAPS held rows of. */
        val minutes: Int,
        val minutesWithRows: Int,
        /** Records too old or too far ahead to be believed. */
        val stale: Int
    )

    /**
     * Account for the pump's journal against the rows AAPS holds.
     *
     * @param records the journal as the pump sent it
     * @param rows the rows AAPS holds, from the ledger; only those on the pump's own start
     * @param bookStartUtcSeconds the newest record already accounted for; 0 on the first read, which imports nothing
     * @param phoneNow the phone's clock, for discarding what cannot be believed
     * @param takenIds ids already in AAPS, so an import gets its own
     */
    fun account(
        records: List<Atc3TbrRecord>,
        rows: List<Row>,
        bookStartUtcSeconds: Long,
        phoneNow: Long,
        takenIds: Set<Long>
    ): Outcome {
        val believed = records.filter { phoneNow - it.startTimestamp <= Atc3Const.RECONCILE_MAX_AGE_MS && it.startTimestamp <= phoneNow }
        val stale = records.size - believed.size
        val newest = believed.maxOfOrNull { it.startUtcSeconds } ?: bookStartUtcSeconds
        // Oldest first within a minute: the higher index is the older record.
        val minutes = believed.groupBy { it.startUtcSeconds }.toSortedMap()
        val minuteStarts = minutes.keys.toList()
        val rowsByMinute = rows.groupBy { it.startUtcSeconds }

        val shapes = ArrayList<Shape>()
        val imports = ArrayList<Import>()
        val carries = ArrayList<Carry>()
        val taken = HashSet(takenIds)
        var withRows = 0

        for ((index, start) in minuteStarts.withIndex()) {
            val minuteRecords = minutes.getValue(start).sortedByDescending { it.index }
            val nextStartMs = minuteStarts.getOrNull(index + 1)?.let { minutes.getValue(it).first().startTimestamp }
            val minuteRows = rowsByMinute[start]
            if (minuteRows.isNullOrEmpty()) {
                // Nobody's row: imported, but not on the first read nor behind the book's start.
                if (bookStartUtcSeconds == 0L || start <= bookStartUtcSeconds) continue
                for (record in minuteRecords) imports.add(importOf(record, nextStartMs, phoneNow, taken))
                continue
            }
            withRows++
            assign(minuteRecords, minuteRows, nextStartMs, shapes, carries)
        }

        return Outcome(shapes, imports, carries, newest, minutes.size, withRows, stale)
    }

    /**
     * One minute's records into its rows, paired by rate in the order they began; a record without a
     * row of its rate hands its insulin to the next row of the minute that has time. A closed row is
     * shaped to what it was given; an open one takes its share when it closes.
     */
    private fun assign(records: List<Atc3TbrRecord>, rows: List<Row>, nextStartMs: Long?, shapes: MutableList<Shape>, carries: MutableList<Carry>) {
        val timed = rows.filter { it.hasTime }.sortedBy { it.rowMs }
        if (timed.isEmpty()) return
        val given = giveOut(records, rows)
        for (row in timed) {
            val units = given[row.pumpId] ?: continue
            if (row.endMs == null) {
                // Open: what a cut row handed over is kept for the close.
                val handed = units - ownShareOf(row, records)
                if (handed > 0.0 && abs((row.carriedUnits ?: 0.0) - handed) >= Atc3Protocol.DOSE_SCALE / 2) carries.add(Carry(row.pumpId, handed))
                continue
            }
            // A closed row holding as much or more is left alone: records fall off the journal's end.
            if (row.shapedUnits != null && units + Atc3Protocol.DOSE_SCALE / 2 < row.shapedUnits) continue
            if (row.shapedUnits != null && abs(row.shapedUnits - units) < Atc3Protocol.DOSE_SCALE / 2) continue
            // A stop that delivered nothing already holds what its record says.
            if (row.rawRate == 0 && units <= 0.0) continue
            val span = (nextStartMs?.let { minOf(it, row.endMs) } ?: row.endMs) - row.rowMs
            if (span < MIN_SPAN_MS) continue
            val rate = row.rawRate * Atc3Protocol.DOSE_SCALE
            if (rate <= 0.0) continue
            val end = row.rowMs + span
            val ran = (units / rate * HOUR_MS).toLong()
            if (ran >= span) continue
            val start = (end - maxOf(ran, MIN_SPAN_MS)).coerceAtMost(row.rowMs + MAX_START_SHIFT_MS)
            if (start <= row.rowMs) continue
            shapes.add(Shape(row.pumpId, start, end - start, rate, units))
        }
    }

    /**
     * What each row of the minute that has time is given, by id: the rule of [assign], the same for a
     * row shaped later and one closed now.
     *
     * @param records the minute's records, oldest first
     * @param rows the minute's rows
     */
    fun giveOut(records: List<Atc3TbrRecord>, rows: List<Row>): Map<Long, Double> {
        val timed = rows.filter { it.hasTime }.sortedBy { it.rowMs }
        if (timed.isEmpty()) return emptyMap()
        val given = HashMap<Long, Double>()
        val usedRows = HashSet<Long>()
        for (record in records) {
            val raw = rawRateOf(record)
            val own = timed.firstOrNull { it.rawRate == raw && it.pumpId !in usedRows }
            val target = own ?: rows.filter { !it.hasTime && it.rawRate == raw }.minByOrNull { it.rowMs }
                ?.let { cut -> timed.firstOrNull { it.rowMs >= cut.rowMs } }
                ?: timed.last()
            if (own != null) usedRows.add(own.pumpId)
            given[target.pumpId] = (given[target.pumpId] ?: 0.0) + record.deliveredUnits
        }
        return given
    }

    /** What the minute's records give the row under [pumpId], or null when none reaches it, for closing that row by the book's rule. */
    fun unitsGivenTo(records: List<Atc3TbrRecord>, rows: List<Row>, pumpId: Long): Double? =
        giveOut(records, rows)[pumpId]

    /** The insulin of the record of this row's own rate, when there is one. */
    private fun ownShareOf(row: Row, records: List<Atc3TbrRecord>): Double =
        records.firstOrNull { rawRateOf(it) == row.rawRate }?.deliveredUnits ?: 0.0

    /**
     * A row for a record nobody's row answers for: from its start, at its rate, for the time that rate
     * takes to deliver what it says, within what the journal allows. A percentage record, with no rate,
     * takes the average.
     */
    private fun importOf(record: Atc3TbrRecord, nextStartMs: Long?, phoneNow: Long, taken: MutableSet<Long>): Import {
        val stated = record.durationMinutes * 60_000L
        val bound = listOfNotNull(stated, nextStartMs?.let { it - record.startTimestamp }, phoneNow - record.startTimestamp).min()
        val set = record.rate
        val ran = if (set != null && set > 0.0) (record.deliveredUnits / set * HOUR_MS).toLong() else bound
        val span = maxOf(MIN_SPAN_MS, minOf(bound, ran))
        val rate = when {
            set != null                  -> set
            record.deliveredUnits <= 0.0 -> 0.0
            else                         -> record.deliveredUnits / (span / HOUR_MS)
        }
        var id = Atc3PumpId.tbrStartAfter(record.startTimestamp, null)
        while (id in taken && id % 10L < Atc3PumpId.MAX_BUMP) id = Atc3PumpId.tbrStartAfter(record.startTimestamp, id)
        taken.add(id)
        return Import(record.startTimestamp, span, rate, id, record.deliveredUnits)
    }

    /** The rate as the pump counts it; a percentage record has none and is given -1. */
    fun rawRateOf(record: Atc3TbrRecord): Int = record.rate?.let { (it / Atc3Protocol.DOSE_SCALE).roundToInt() } ?: -1

    /** A row shorter than this cannot hold insulin: its rate would be absurd. */
    const val MIN_SPAN_MS = 1_000L

    /** How far a row's start may be moved past the pump's stamp: the stamp is the start of a minute. */
    const val MAX_START_SHIFT_MS = 60_000L
    private const val HOUR_MS = 3_600_000.0
}
