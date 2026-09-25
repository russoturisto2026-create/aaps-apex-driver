package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.comm.Atc3TbrRecord
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The book of temporary basals: what the AAPS journal must hold for what the pump's journal says,
 * worked out minute by minute.
 *
 * The pump stamps every temporary basal with the time of its status snapshot, which is the whole
 * minute, so every temporary basal set within one minute carries that minute as its start, and the
 * pump's journal (`0x27`) holds one record per temporary basal that finished, with what it
 * delivered. AAPS keeps one row per moment: of several rows begun at one moment it keeps the last
 * and cuts the others to nothing. So **the unit of account is the minute**: the insulin of a
 * minute's records has to land, once and in full, in the rows of that minute that have time.
 *
 * A row keeps the time it ran -- from the pump's start to the moment it was closed at -- and takes
 * the average rate that makes its insulin of that time. The time is where the profile basal is not
 * credited; the insulin is the pump's figure. Cutting the time to fit an ordered rate gives the
 * time cut away to the profile, which is wrong by more than the pulse it corrects.
 *
 * Nothing here writes anything and nothing here is remembered between reads: the rows are the
 * ledger's notes of what AAPS holds, the records are the pump's answer, and the outcome is what to
 * write. Records of a minute AAPS holds no row of are strangers' work, imported as rows of their
 * own; a first read imports nothing and only says where the book begins.
 */
object Atc3TbrBook {

    /** A temporary basal row AAPS holds, as the ledger noted it. */
    data class Row(
        val pumpId: Long,
        /** The pump's start, whole seconds on a UTC calendar: the minute's identity. */
        val startUtcSeconds: Long,
        /** Where the row begins in AAPS, phone milliseconds; the resume for a continuation. */
        val rowMs: Long,
        /** The rate it was set at, in the pump's raw steps. */
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

    /** A row AAPS has not got: a stranger's temporary basal, or one nobody saw begin. */
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
     * @param rows the rows AAPS holds, from the ledger; only rows with the pump's own start
     * @param bookStartUtcSeconds the newest record already accounted for; zero on the first read,
     *   which then imports nothing
     * @param phoneNow the phone clock, for discarding what cannot be believed
     * @param takenIds ids already in AAPS, so an import gets one of its own
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
        // Oldest first within a minute: the pump lists the newest first, so the higher index is
        // the older record.
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
                // Nobody's row: a stranger's, or one the ticks never saw. Not on the first read,
                // and not behind the book's start: those were accounted for before.
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
     * The insulin of one minute's records into that minute's rows.
     *
     * Records and rows are paired by the rate they were set at, in the order they began; a record
     * without a row of its rate -- its row was cut to nothing, or the ticks never saw it begin --
     * hands its insulin to the next row of the minute that has time, or the last. A row that has
     * time is shaped to what it was given, over the time it ran; a row still open takes its share
     * when it closes.
     */
    private fun assign(records: List<Atc3TbrRecord>, rows: List<Row>, nextStartMs: Long?, shapes: MutableList<Shape>, carries: MutableList<Carry>) {
        val timed = rows.filter { it.hasTime }.sortedBy { it.rowMs }
        if (timed.isEmpty()) return
        val given = giveOut(records, rows)
        for (row in timed) {
            val units = given[row.pumpId] ?: continue
            if (row.endMs == null) {
                // Open: what a cut row of the minute handed over is kept for the close; its own
                // record, if any, is read again at the close.
                val handed = units - ownShareOf(row, records)
                if (handed > 0.0 && abs((row.carriedUnits ?: 0.0) - handed) >= Atc3Const.DOSE_SCALE / 2) carries.add(Carry(row.pumpId, handed))
                continue
            }
            // Closed: a row already holding this much is left alone; one holding more keeps it --
            // records fall off the journal's end, and the pump's account only ever grows.
            if (row.shapedUnits != null && units + Atc3Const.DOSE_SCALE / 2 < row.shapedUnits) continue
            if (row.shapedUnits != null && abs(row.shapedUnits - units) < Atc3Const.DOSE_SCALE / 2) continue
            // A stop, or a rate of nothing, that delivered nothing already holds what its record
            // says: writing it again would be a correction of nothing.
            if (row.rawRate == 0 && units <= 0.0) continue
            val span = (nextStartMs?.let { minOf(it, row.endMs) } ?: row.endMs) - row.rowMs
            if (span < MIN_SPAN_MS) continue
            shapes.add(Shape(row.pumpId, row.rowMs, span, units / (span / HOUR_MS), units))
        }
    }

    /**
     * The insulin of one minute's records, row by row: what each row of the minute that has time is
     * given, by id. The one rule of the minute, see [assign], and the same rule whether the row is
     * being shaped later from the count or closed now.
     *
     * @param records the minute's records, oldest first
     * @param rows the minute's rows, as AAPS holds them
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

    /**
     * What the minute's records give the row under [pumpId], or null when none of them reaches it.
     *
     * Asked when that row is being closed: the row closes at the insulin its minute gives it, by
     * the rule the book applies later, so that the book then finds the row already right. Closing a
     * row at the one newest record of its rate would miss the record of the part before a stop,
     * which shares the minute.
     */
    fun unitsGivenTo(records: List<Atc3TbrRecord>, rows: List<Row>, pumpId: Long): Double? =
        giveOut(records, rows)[pumpId]

    /** The insulin of the record of this row's own rate, when there is one. */
    private fun ownShareOf(row: Row, records: List<Atc3TbrRecord>): Double =
        records.firstOrNull { rawRateOf(it) == row.rawRate }?.deliveredUnits ?: 0.0

    /**
     * A row for a record nobody's row answers for: from the record's start, for as long as the
     * journal allows -- to the next record's start, or what it was started for -- at the average
     * rate that makes its insulin of that. A zero rate runs its stated time.
     */
    private fun importOf(record: Atc3TbrRecord, nextStartMs: Long?, phoneNow: Long, taken: MutableSet<Long>): Import {
        val stated = record.durationMinutes * 60_000L
        val bound = listOfNotNull(stated, nextStartMs?.let { it - record.startTimestamp }, phoneNow - record.startTimestamp).min()
        val span = maxOf(MIN_SPAN_MS, bound)
        val rate = if (record.deliveredUnits <= 0.0) 0.0 else record.deliveredUnits / (span / HOUR_MS)
        var id = Atc3PumpId.tbrStartAfter(record.startTimestamp, null)
        while (id in taken && id % 10L < Atc3PumpId.MAX_BUMP) id = Atc3PumpId.tbrStartAfter(record.startTimestamp, id)
        taken.add(id)
        return Import(record.startTimestamp, span, rate, id, record.deliveredUnits)
    }

    /** The rate as the pump counts it; a percentage record has none and is given -1. */
    fun rawRateOf(record: Atc3TbrRecord): Int = record.rate?.let { (it / Atc3Const.DOSE_SCALE).roundToInt() } ?: -1

    /** A row shorter than this cannot hold insulin: its rate would be absurd. */
    const val MIN_SPAN_MS = 1_000L

    private const val HOUR_MS = 3_600_000.0
}
