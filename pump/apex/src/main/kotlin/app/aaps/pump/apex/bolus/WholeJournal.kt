package app.aaps.pump.apex.bolus

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.aaps.AapsCarrier
import app.aaps.pump.apex.aaps.AapsToday
import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.bolus.FaultyCell.Suspicion
import app.aaps.pump.apex.statistics.StatisticsDays
import app.aaps.pump.apex.store.Action
import app.aaps.pump.apex.store.ApexStore
import app.aaps.pump.apex.store.ClockLogEntry
import app.aaps.pump.apex.clock.ApexClock
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.pulse.PictureReader
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

/**
 * The bolus journal read whole: of a pump whose cells are empty, that started its journal anew, or
 * whose records are all newer than the cells, see [check]. Its records become cells above the ones kept, each at its own stamp. A faulty record (one the pump
 * cannot have written, or stamped before a record below it with no zone move between) stands in the middle of
 * the window between the sound records around it, so that the insulin stays where it went in.
 *
 * The days are held against the bolus field of the statistics, see [WholeJournalDays]: today, and yesterday when
 * it holds a record AAPS gets, each when the journal holds it whole. What the sound records of the day leave
 * goes to our boluses with no record, then to its faulty records; what none takes is an error of the driver.
 *
 * AAPS gets the records of the time the insulin acts that came more than a minute after its newest bolus,
 * from whatever source it came but a row that waits for its record, see [AapsToday.newestBolusMs], and none from
 * before the pump was registered: the later of the three bounds
 * counts. What is older is taken as in AAPS already and stays in the cells only; so does a faulty record
 * whose window starts before the bound: AAPS may hold its bolus. A record whose key AAPS holds, see `BolusKey`,
 * is sound at the time of that row and nothing is written for it: the row was made when the driver knew the
 * zone of the stamp, which a lost clock log no longer tells. Each cell keeps when this ran and the bolus field
 * of its day, see [BolusCells.Filled].
 */
class WholeJournal(
    private val aapsLogger: AAPSLogger,
    private val store: ApexStore,
    private val manager: ApexManager,
    private val reader: PictureReader,
    private val carrier: AapsCarrier,
    private val aapsToday: AapsToday,
    private val trace: ApexTrace
) {

    /** Read the whole bolus journal, newest first. Null when it was not read: nothing is settled, the next heartbeat tries again. */
    fun read(serial: String): ApexJournalAnswer<ApexBolusRecord>? = reader.readBolusJournal("whole bolus journal", manager::readWholeBolusHistory, serial)?.answer

    /**
     * The pump says its whole journal is empty while the cells hold records laid over less than
     * [EMPTY_HOLD_MS] ago: not a memory wiped but an answer to wait out. Nothing is settled or kept, the
     * cells stay, and the records that come back lay over them. Past that time the journal starts anew.
     */
    fun emptyTooSoon(journal: BolusCells, whole: ApexJournalAnswer<ApexBolusRecord>, nowMs: Long): Boolean {
        if (!whole.empty || journal.keptFrames().isNullOrEmpty()) return false
        val laidAtMs = journal.readAtMs() ?: return false
        if (nowMs - laidAtMs >= EMPTY_HOLD_MS) return false
        aapsLogger.warn(LTag.PUMP, "Apex: the pump says its bolus journal is empty, the cells were laid over at $laidAtMs: nothing is settled, the next heartbeat reads again")
        trace.event(ApexTraceCat.STATE, "bolus_journal_empty_held", "laid" to laidAtMs)
        return true
    }

    /**
     * A journal that does not lay over the cells is taken whole as new when the cells are empty (the first
     * answer of this pump), when the pump holds fewer records than it did (it started its journal anew), or
     * when every dated record is newer than the cells under the clock offset [offsetMs]: the pump wrote more
     * boluses than its journal holds since the last read, and the older ones are gone from it. Records do
     * not change and a flicker is read again, so any other answer is an error of the driver: it breaks the
     * reconciliation, nothing is kept and the user is told, see `CodeFault`. Returns when [whole] is new.
     */
    fun check(journal: BolusCells, whole: ApexJournalAnswer<ApexBolusRecord>, offsetMs: Long) {
        if (journal.readAtMs() == null) return
        val before = journal.recordCount()
        if (before != null && whole.recordCount < before) {
            aapsLogger.warn(LTag.PUMP, "Apex: the pump holds ${whole.recordCount} bolus records, $before before: its journal started anew")
            return
        }
        val keptMs = journal.newestDatedAtMs()
        val oldestMs = whole.entries.filter { !it.record.dateBroken }.minOfOrNull { ApexClock.momentOf(it.record.clockSeconds, offsetMs) }
        check(keptMs != null && oldestMs != null && oldestMs > keptMs) {
            "the whole bolus journal, ${whole.recordCount} records, does not lay over the cells and the pump held ${before ?: "an unknown count of"} records before"
        }
        aapsLogger.warn(LTag.PUMP, "Apex: every bolus record is newer than the cells: more boluses than the journal holds came since the last read")
    }

    /**
     * Make the cells anew from [whole], the whole journal, freshen the copy of the statistics with [days]
     * and write to AAPS what it has not, see the class. [comparedAtMs]: when this reconciliation ran. Run
     * inside a transaction of the store.
     */
    fun settle(
        serial: String, journal: BolusCells, whole: ApexJournalAnswer<ApexBolusRecord>, days: ApexJournalAnswer<ApexDailyRecord>,
        offsetMs: Long, nowMs: Long, notBeforeMs: Long, reservoir: Double?, comparedAtMs: Long = nowMs, allDays: Boolean = false,
        bound: (AapsWrite) -> AapsWrite = { it }
    ): BolusReconciliation.Settled {
        val account = BolusAccount(store, serial)
        val records = journal.restart(BolusCells.boluses(whole), offsetMs, nowMs).records
        val actingFromMs = nowMs - (runBlocking { aapsToday.insulinActsMs() } ?: ACTS_MS)
        val newestMs = runBlocking { aapsToday.newestBolusMs(actingFromMs - ApexClock.MINUTE_MS) }
        // The pump stamps the minute a bolus started and 59 s: a record within a minute of the newest bolus in AAPS is that bolus.
        // Nothing before the line drawn last goes to AAPS, see MismatchLine.
        val mark = MismatchLine(store, serial).mark()
        val fromMs = maxOf(notBeforeMs, actingFromMs, newestMs?.let { it + ApexClock.MINUTE_MS } ?: actingFromMs, mark?.atMs ?: Long.MIN_VALUE)
        aapsLogger.warn(LTag.PUMP, "Apex: a new bolus journal, ${records.size} bolus records, the newest bolus in AAPS at $newestMs: the records from $fromMs go to AAPS")
        trace.event(ApexTraceCat.STATE, "bolus_start", "records" to records.size, "newest" to newestMs, "from" to fromMs)
        // A record pairs only with our bolus of its time, see BolusAccount.pair.
        val open = account.openActions().toMutableList()
        val paired = records.zip(account.pair(records, open)).toMap()
        val keys = journal.keys()
        // A stamp is off by its zone at most by a day: the rows from a day before the oldest record hold every key of the journal.
        val rows = records.minOfOrNull { it.atMs }?.let { runBlocking { aapsToday.pumpRowsMs(serial, it - ApexClock.DAY_MS) } }.orEmpty()
        val held = keys.mapNotNull { (seq, key) -> rows[key]?.let { seq to it } }.toMap()
        val lines = lines(records, paired, account, reservoir, nowMs, notBeforeMs, offsetMs, store.clockLog(serial), held)
        val newestFirst = days.entries.map { it.record }
        val writes = ArrayList<AapsWrite>()
        val seen = open.associate { it.id to FaultyCell.startUnits(it, account) }
        val ours = seen.toMutableMap()
        val checked = WholeJournalDays(aapsLogger, trace, ours).checkDays(lines, open, newestFirst, offsetMs, fromMs, mark)
        writes += checked.notices
        val told = checked.leftFromMs?.let { MismatchLine(store, serial).draw(it, comparedAtMs, checked.leftUnits, newestFirst) }.orEmpty()
        val filled = ArrayList<BolusCells.Filled>()
        val sharedOn = lines.any { it.faulty && it.units >= ApexProtocol.HALF_STEP }
        for (line in lines) {
            val key = keys[line.record.seq]
            val dayUnits = StatisticsDays.bolusOfDay(newestFirst, StatisticsDays.dayOf(line.atMs, offsetMs))
            val inAaps = when {
                line.action != null                        -> {
                    writes += account.row(line.bolus, line.action, key, notBeforeMs)
                    true
                }

                line.held                                  -> true

                line.faulty && line.windowFromMs >= fromMs -> {
                    val some = line.units >= ApexProtocol.HALF_STEP
                    if (some) writes += account.foreignRow(line.record, line.bolus, key, notBeforeMs).copy(amountUnits = line.units, timestampMs = line.atMs)
                    FaultyCell.notice(faulty = true, ours = false, hasRecord = true, line.units, line.atMs, sharedOn)?.let { writes += it }
                    some
                }

                line.atMs < fromMs || line.faulty          -> line.atMs >= notBeforeMs

                else                                       -> {
                    writes += account.foreignRow(line.record, line.bolus, key, notBeforeMs)
                    true
                }
            }
            filled += BolusCells.Filled(line.record.seq, line.action?.id, line.units, line.atMs, line.faulty, inAaps, comparedAtMs, dayUnits)
        }
        // Our bolus with no record: the field of its day set it, when the day was checked; a change is told.
        open.forEach {
            val dayUnits = StatisticsDays.bolusOfDay(newestFirst, StatisticsDays.dayOf(it.acceptedAtMs, offsetMs))
            val units = ours.getValue(it.id)
            val changed = abs(units - seen.getValue(it.id)) >= ApexProtocol.HALF_STEP
            if (changed) writes += AapsWrite.Bolus(null, maxOf(it.acceptedAtMs, notBeforeMs), units, it.type, tempId = it.tempId)
            FaultyCell.notice(changed, ours = true, hasRecord = false, units, it.acceptedAtMs, sharedOn)?.let { n -> writes += n }
            filled += BolusCells.Filled(null, it.id, units, it.acceptedAtMs, faulty = changed, inAaps = true, comparedAtMs, dayUnits)
        }
        StatisticsDays(store, serial).keep(days, nowMs, closedToo = allDays)
        writes.map(bound).forEach { carrier.carry(it, serial) }
        journal.settled(filled)
        journal.prune()
        return BolusReconciliation.Settled(writes, told = told)
    }

    /**
     * [records], oldest first, as lines: ours at the row of our bolus, a record whose key AAPS holds at the time of
     * that row, [held] by its number, the others as the record says. A record
     * the pump cannot have written is faulty: at nothing, in the middle of its window. A record stamped before a
     * sound record below it is a candidate, its dose checked with its day, see [WholeJournalDays.checkDays]. When [zoneLog] holds no
     * zone move in its window, nothing natural puts it there: its date is faulty, it stands in the middle of the
     * window, the newest one's up to now. Else it stays at its stamp. A window across midnight, [offsetMs] the clock
     * offset, keeps one day only, see [WholeJournalDays.pickDays].
     */
    private fun lines(
        records: List<JournalEntry>, paired: Map<JournalEntry, Action?>, account: BolusAccount, reservoir: Double?, nowMs: Long, notBeforeMs: Long,
        offsetMs: Long, zoneLog: List<ClockLogEntry>, held: Map<Long, Long>
    ): List<JournalLine> {
        var latestBelowMs: Long? = null
        val lines = records.mapNotNull { record ->
            val bolus = ApexBolusRecord.decode(record.frame) ?: return@mapNotNull null
            val action = paired[record]
            val heldMs = if (action == null) held[record.seq] else null
            val suspicion = if (action != null || heldMs != null) Suspicion.SOUND else FaultyCell.suspicion(bolus, record.atMs, latestBelowMs, reservoir, nowMs)
            val faulty = suspicion == Suspicion.FAULTY
            val candidate = suspicion == Suspicion.OUT_OF_PLACE
            val soundMs = heldMs ?: record.atMs
            if (suspicion == Suspicion.SOUND) latestBelowMs = maxOf(latestBelowMs ?: soundMs, soundMs)
            if (faulty || candidate) {
                aapsLogger.warn(
                    LTag.PUMP,
                    "Apex: whole journal, record ${record.seq} is ${if (faulty) "a faulty cell" else "stamped before a record below it, a candidate"}: " +
                        "${bolus.requestedUnits} U asked, ${bolus.totalDeliveredUnits} U given, stamped at ${record.atMs}"
                )
                trace.event(ApexTraceCat.STATE, "bolus_start_suspect", "seq" to record.seq, "stamp" to record.atMs, "faulty" to faulty)
            }
            if (action != null) account.row(bolus, action, null, notBeforeMs).let { JournalLine(record, bolus, action, false, false, it.amountUnits, it.timestampMs) }
            else if (heldMs != null) JournalLine(record, bolus, null, false, false, bolus.totalDeliveredUnits, heldMs).apply { this.held = true }
            else JournalLine(record, bolus, null, faulty, candidate, if (faulty) 0.0 else bolus.totalDeliveredUnits, record.atMs)
        }
        // The window of a suspect record lies between the sound records around it, the newest one's up to now.
        lines.forEachIndexed { i, line ->
            if (!line.faulty && !line.candidate) return@forEachIndexed
            val sound = { it: JournalLine -> !it.faulty && !it.candidate }
            val fromMs = lines.take(i).lastOrNull(sound)?.soundMs ?: line.record.atMs
            val toMs = lines.drop(i + 1).firstOrNull(sound)?.soundMs ?: nowMs
            // Across midnight the record stands in one day only, the one the statistics give it, see checkDays.
            val firstMidnightMs = ApexClock.momentOf(StatisticsDays.dayOf(fromMs, offsetMs) + ApexClock.DAY_SECONDS, offsetMs)
            val lastMidnightMs = ApexClock.momentOf(StatisticsDays.dayOf(toMs, offsetMs), offsetMs)
            if (fromMs < firstMidnightMs && firstMidnightMs <= lastMidnightMs && lastMidnightMs < toMs) {
                line.earlyFromMs = fromMs
                line.earlyMiddleMs = FaultyCell.middle(fromMs, firstMidnightMs - 1)
                line.windowFromMs = lastMidnightMs
                line.middleMs = FaultyCell.middle(lastMidnightMs, toMs)
            } else {
                line.windowFromMs = fromMs
                line.middleMs = FaultyCell.middle(fromMs, toMs)
            }
            // No zone move in its window can put a record before the one below it: its date is faulty, it stands in the window.
            if (line.candidate && zoneLog.none { it.oldOffsetMs != null && it.oldOffsetMs != it.newOffsetMs && it.writtenAtMs in fromMs..toMs }) {
                line.faulty = true
                aapsLogger.warn(LTag.PUMP, "Apex: whole journal, record ${line.record.seq}: no zone move between the records around it, its date is faulty, it stands at ${line.middleMs}")
            }
            if (line.faulty) line.atMs = line.middleMs
        }
        return lines
    }

    companion object {

        /**
         * How long a whole journal the pump says is empty is waited out, see [emptyTooSoon]. A wiped journal needs
         * no hurry: once the insulin of its boluses no longer acts, nothing of it counts. An empty answer before
         * that may be a passing state of the pump, and taking it would lay the old records over again as new.
         */
        const val EMPTY_HOLD_MS = 5 * ApexClock.HOUR_MS

        /** How long insulin acts while AAPS has no profile to tell it: five hours. */
        private const val ACTS_MS = 5 * 60 * 60_000L
    }
}
