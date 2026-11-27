package app.aaps.pump.apex.bolus

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.statistics.StatisticsDays
import app.aaps.pump.apex.store.Action
import app.aaps.pump.apex.clock.ApexClock
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import kotlin.math.abs

/**
 * One record of the journal read whole: what it gives and when, our bolus it is the record of. [faulty]: the pump cannot
 * have written it, or its date is faulty, see `WholeJournal`. [candidate]: stamped before a sound record below it,
 * its dose faulty only when its day does not agree with the statistics, see [WholeJournalDays.checkDays].
 */
internal class JournalLine(val record: JournalEntry, val bolus: ApexBolusRecord, val action: Action?, var faulty: Boolean, val candidate: Boolean, var units: Double, var atMs: Long) {

    /** The window between the sound records around it: where it starts, and its middle, where a faulty record stands. */
    var windowFromMs: Long = atMs
    var middleMs: Long = atMs

    /** A window across midnight: the part before the first midnight, start and middle; [middleMs] is then in the part after the last one. */
    var earlyFromMs: Long? = null
    var earlyMiddleMs: Long? = null

    /** True: AAPS holds the row of its key, the line stands at the time of that row, see [WholeJournal]. */
    var held: Boolean = false

    /** Where a sound line stands for the window of its neighbours: the time of its row in AAPS, else its stamp. */
    val soundMs: Long get() = if (held) atMs else record.atMs

    /**
     * The moment that gives its day: a record that may be faulty belongs to the day of its place in the journal. A held
     * one belongs to the day of its stamp, as the pump counts it, even when its row stands in the day before.
     */
    val placeMs: Long get() = if (faulty || candidate) middleMs else if (held) record.atMs else atMs
}

/**
 * The days of the journal read whole, held against the bolus field of the statistics. Our bolus with no record counts
 * at what we saw, or the order, until its day is checked: then the field sets it, see [checkDays].
 */
internal class WholeJournalDays(
    private val aapsLogger: AAPSLogger,
    private val trace: ApexTrace,
    /** What our open boluses with no record count at, by the id of their cell. */
    private val ours: MutableMap<Long, Double>
) {

    /** What the check of the days left: the notices, and the rest no record could take, U, of the data from [leftFromMs]. */
    class Checked(val notices: List<AapsWrite>, val leftUnits: Double, val leftFromMs: Long?)

    /** The line drawn last, see [MismatchLine]: in its day only what came after it counts, the days before it are closed. */
    private var mark: MismatchLine.Mark? = null
    private var offsetMs = 0L

    private fun counted(atMs: Long): Boolean = mark?.let { StatisticsDays.dayOf(atMs, offsetMs) != it.day || atMs >= it.atMs } ?: true

    /** The bolus field of [day], counted from the line when the line is in that day. */
    private fun fieldOf(newestFirst: List<ApexDailyRecord>, day: Long): Double? =
        StatisticsDays.bolusOfDay(newestFirst, day)?.let { field -> mark?.takeIf { it.day == day }?.let { ApexProtocol.onStep(field - it.fieldUnits) } ?: field }

    /**
     * A suspect record whose window crosses midnight stands in the day the statistics give it. Each day's field
     * tells whether the day still misses a dose:
     * - the later day misses one: the record is there;
     * - else the earlier day misses one: the record is there;
     * - else the later day is whole: the record cannot be in it, so it stands in the earlier one;
     * - the statistics tell nothing of the later day: the later one, so that its insulin stays in IOB.
     */
    fun pickDays(lines: List<JournalLine>, open: List<Action>, newestFirst: List<ApexDailyRecord>, dayOf: (Long) -> Long) {
        fun rest(day: Long): Double? = fieldOf(newestFirst, day)?.let { ApexProtocol.onStep(it - soundOfDay(day, lines, open, dayOf)) }
        for (line in lines) {
            val earlyMs = line.earlyMiddleMs ?: continue
            val late = rest(dayOf(line.middleMs))
            val early = rest(dayOf(earlyMs))
            val pick = when {
                late != null && late >= ApexProtocol.HALF_STEP   -> false
                early != null && early >= ApexProtocol.HALF_STEP -> true
                late != null                   -> true
                else                           -> false
            }
            if (pick) {
                line.windowFromMs = line.earlyFromMs!!
                line.middleMs = earlyMs
            }
            if (line.faulty) line.atMs = line.middleMs
            aapsLogger.info(LTag.PUMP, "Apex: whole journal, record ${line.record.seq}: its window crosses midnight, the day after is short by ${late ?: "?"} U, the day before by ${early ?: "?"} U: it stands at ${line.middleMs}")
        }
    }

    /** What the sound records and [open] boluses placed in [day] give, U. */
    private fun soundOfDay(day: Long, lines: List<JournalLine>, open: List<Action>, dayOf: (Long) -> Long): Double =
        lines.filter { !it.faulty && !it.candidate && dayOf(it.placeMs) == day && counted(it.placeMs) }.sumOf { it.units } +
            open.filter { dayOf(it.acceptedAtMs) == day && counted(it.acceptedAtMs) }.sumOf { ours.getValue(it.id) }

    /**
     * Hold today, and yesterday when it has a record from [fromMs] on, against the bolus field of [newestFirst],
     * each when the journal holds it whole: its oldest record is of an earlier day. The candidates of the day stay
     * as they are when the field holds them; else they are faulty. What the sound records of the day leave goes
     * first to our [open] boluses of the day, each up to its order, then to its faulty records, the most to the
     * first; more than one faulty record is a notice. What none of them can take is left, for a line, see
     * [MismatchLine]. A candidate of a day not checked stays as it is. With a line drawn, [line], the days
     * before it are closed and its day counts from it.
     */
    fun checkDays(lines: List<JournalLine>, open: List<Action>, newestFirst: List<ApexDailyRecord>, offsetMs: Long, fromMs: Long, line: MismatchLine.Mark?): Checked {
        mark = line
        this.offsetMs = offsetMs
        val today = StatisticsDays.datesOf(newestFirst).firstOrNull() ?: return Checked(emptyList(), 0.0, null)
        val yesterday = today - ApexClock.DAY_SECONDS
        val dayOf = { atMs: Long -> StatisticsDays.dayOf(atMs, offsetMs) }
        val oldestDay = lines.filter { !it.faulty && !it.candidate }.minOfOrNull { dayOf(it.record.atMs) } ?: return Checked(emptyList(), 0.0, null)
        pickDays(lines, open, newestFirst, dayOf)
        val checked = listOf(today) + listOf(yesterday).filter { day -> lines.any { dayOf(it.placeMs) == day && it.placeMs >= fromMs } }
        val notices = ArrayList<AapsWrite>()
        var left = 0.0
        var leftFromMs: Long? = null
        for (day in checked.filter { it > oldestDay && (line == null || it >= line.day) }) {
            val field = fieldOf(newestFirst, day) ?: continue
            val ofDay = lines.filter { dayOf(it.placeMs) == day && counted(it.placeMs) }
            val oursOfDay = open.filter { dayOf(it.acceptedAtMs) == day && counted(it.acceptedAtMs) }
            val sound = soundOfDay(day, lines, open, dayOf)
            val candidates = ofDay.filter { it.candidate }
            var rest = ApexProtocol.onStep(field - sound)
            val held = candidates.isNotEmpty() && abs(rest - candidates.sumOf { it.units }) < ApexProtocol.HALF_STEP
            if (held) rest = ApexProtocol.onStep(rest - candidates.sumOf { it.units })
            else candidates.forEach { it.faulty = true; it.units = 0.0; it.atMs = it.middleMs }
            // A candidate the field holds keeps its dose; the others that are faulty take what is left.
            val faulty = ofDay.filter { it.faulty && !(held && it.candidate) }
            aapsLogger.info(
                LTag.PUMP, "Apex: whole journal, day $day: the bolus field $field U, the sound records and ours $sound U, ${candidates.size} candidate(s) " +
                    "${if (held) "the field holds" else "faulty"}, ${oursOfDay.size} of ours with no record, ${faulty.size} faulty record(s), $rest U to give"
            )
            trace.event(ApexTraceCat.STATE, "bolus_start_day", "day" to day, "field" to field, "sound" to sound, "candidates" to candidates.size, "held" to held, "faulty" to faulty.size, "rest" to rest)
            // What the sound records leave goes first to our boluses with no record, each up to its order, then
            // to the faulty records, see FaultyCell.give. Ours stand at what we saw: the rest is counted from it.
            val units = (oursOfDay.map { ours.getValue(it.id) } + faulty.map { it.units }).toMutableList()
            rest = FaultyCell.give(units, oursOfDay.map { it.amount } + faulty.map { Double.MAX_VALUE }, rest)
            oursOfDay.forEachIndexed { i, it -> ours[it.id] = units[i] }
            faulty.forEachIndexed { i, it -> it.units = units[oursOfDay.size + i] }
            faulty.firstOrNull()?.let { first -> FaultyCell.sharedNotice(faulty.size, first.units, first.atMs)?.let { notices += it } }
            if (rest == 0.0) continue
            aapsLogger.error(LTag.PUMP, "Apex: whole journal, day $day: the bolus records do not sum to the bolus field $field U, $rest U is left with no faulty record to take it")
            left += abs(rest)
            val dayStartMs = ApexClock.momentOf(day, offsetMs)
            leftFromMs = minOf(leftFromMs ?: Long.MAX_VALUE, maxOf(dayStartMs, line?.atMs ?: dayStartMs))
        }
        return Checked(notices, left, leftFromMs)
    }
}
