package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.clock.ApexClock
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import java.util.Locale
import kotlin.math.abs

/** Point 3: bolus rows of the two passes against each other, the commands and the journal. */
object ReplayCompareBolus {

    private class Seen(val record: ApexBolusRecord, val firstReadMs: Long)

    fun run(all: ReplayRun, none: ReplayRun, file: ReplayAllFile, report: ReplayReport) {
        report.section("3. Boluses")
        val ra = all.bolusRows.map { it.write }
        val rn = none.bolusRows.map { it.write }
        val key = { w: AapsWrite.Bolus -> Triple(w.timestampMs, w.amountUnits, w.type) }
        val onlyAll = ra.filter { a -> rn.none { key(it) == key(a) } }
        val onlyNone = rn.filter { n -> ra.none { key(it) == key(n) } }
        report.line("  bolus rows: all ${ra.size}, none ${rn.size}; only in all ${onlyAll.size}, only in none ${onlyNone.size}")
        onlyAll.forEach { report.line("    only in all:  ${ReplayTimeline.write(it)}") }
        onlyNone.forEach { report.line("    only in none: ${ReplayTimeline.write(it)}") }
        if (onlyAll.isNotEmpty() || onlyNone.isNotEmpty()) report.problem("bolus rows differ between the passes: ${onlyAll.size} only in all, ${onlyNone.size} only in none")
        val seen = journalRecords(file)
        report.line("  distinct journal records in the whole record: ${seen.size}")
        totals(file, report, seen, all, none)
        commands(file, report, seen, all, none)
        unexplained(file, report, seen, all, "all")
        unexplained(file, report, seen, none, "none")
        for ((name, run) in listOf("all" to all, "none" to none)) {
            report.line("  -- notices and deep path events, pass $name")
            val sub = ReplayReport()
            ReplayCheckBolus.events(run, sub)
            sub.lines.forEach { report.line(it) }
            sub.problems.forEach { report.problem("pass $name: $it") }
        }
    }

    /** The insulin the rows add up to, in each pass, against the journal and against the bolus field of the daily statistics. */
    private fun totals(file: ReplayAllFile, report: ReplayReport, seen: List<Seen>, all: ReplayRun, none: ReplayRun) {
        val firstAt = all.results.firstOrNull { it.beat.journalHex != null }?.beat?.journalMs ?: return
        val journal = seen.filter { it.firstReadMs > firstAt }.sumOf { it.record.totalDeliveredUnits }
        var field = 0.0
        var previous: ApexDailyRecord? = null
        for (a in file.answers['D'].orEmpty().filter { it.ms > firstAt }) {
            val top = a.hexes.firstOrNull()?.let { ReplayCheckBolus.frame(it) }?.let { ApexDailyRecord.decode(it) } ?: continue
            val before = previous
            if (before != null) field += if (before.dayClockSeconds == top.dayClockSeconds) maxOf(0.0, top.bolusUnits - before.bolusUnits) else top.bolusUnits
            previous = top
        }
        report.line("  insulin as boluses after the first journal answer: journal records ${"%.3f".format(Locale.ROOT, journal)} U; bolus field of the daily statistics ${"%.3f".format(Locale.ROOT, field)} U")
        for ((name, run) in listOf("all" to all, "none" to none)) {
            val rows = run.bolusRows.map { it.write }
            val loose = rows.filter { unplaced(run, it) }
            report.line("  pass $name: rows ${"%.3f".format(Locale.ROOT, rows.sumOf { it.amountUnits })} U in ${rows.size}, of which unplaced ${"%.3f".format(Locale.ROOT, loose.sumOf { it.amountUnits })} U in ${loose.size}")
        }
    }

    private fun journalRecords(file: ReplayAllFile): List<Seen> {
        val first = HashMap<Pair<Long, Int>, Seen>()
        for (a in file.answers['J'].orEmpty()) for (hex in a.hexes) {
            val frame = ReplayCheckBolus.frame(hex) ?: continue
            val r = ApexBolusRecord.decode(frame) ?: continue
            first.getOrPut(r.clockSeconds to (r.rawDelivered + r.rawExtendedDelivered)) { Seen(r, a.ms) }
        }
        return first.values.sortedBy { it.firstReadMs }
    }

    /** The moment of a record: its stamp under the offset in force at its first read, or when that puts it far ahead of the read, the offset that puts it just before. */
    private fun momentOf(s: Seen, file: ReplayAllFile): Long {
        val inForce = s.record.clockSeconds * 1000 - file.offsetAt(s.firstReadMs)
        if (inForce <= s.firstReadMs + 10 * ApexClock.MINUTE_MS) return inForce
        val offsets = (file.clockWrites.map { it.offsetMs } + 0L).distinct()
        return offsets.map { s.record.clockSeconds * 1000 - it }.filter { it <= s.firstReadMs + 2 * ApexClock.MINUTE_MS }.maxOrNull() ?: inForce
    }

    /** Rows stamped far from the record's time: the row for boluses nobody knows of is stamped by the read moment, the real clock here. */
    private fun unplaced(run: ReplayRun, w: AapsWrite.Bolus) = w.timestampMs > run.results.last().beat.heartbeatMs + ApexClock.HOUR_MS

    /** The rows in the minute of [ms], not counting the unplaced ones. */
    private fun rowsNear(run: ReplayRun, ms: Long): List<AapsWrite.Bolus> =
        run.bolusRows.map { it.write }.filter { w -> !unplaced(run, w) && ApexClock.minuteStart(w.timestampMs) == ApexClock.minuteStart(ms) }

    private fun commands(file: ReplayAllFile, report: ReplayReport, seen: List<Seen>, all: ReplayRun, none: ReplayRun) {
        for (c in file.commands.filter { it.group == "35" && it.op in setOf("12", "13", "14") }) {
            val asked = (c.payload.substring(2, 4).toInt(16) * 256 + c.payload.substring(0, 2).toInt(16)) * 0.025
            val rec = seen.filter { momentOf(it, file) in (c.ms - 30_000)..(c.ms + 400_000) }.minByOrNull { abs(momentOf(it, file) - c.ms) }
            val head = "${c.op} ${ReplayTimeline.clock(c.ms)} asked $asked U"
            if (rec == null) {
                report.line("  $head: no journal record within 6 minutes")
                if (c.op == "12") report.problem("$head: no journal record")
                continue
            }
            val m = momentOf(rec, file)
            val r = rec.record
            val rows = listOf("all" to all, "none" to none).joinToString("; ") { (name, run) ->
                "$name ${rowsNear(run, m).joinToString { "${it.amountUnits} U@${ReplayTimeline.clock(it.timestampMs)}" }.ifEmpty { "no row" }}"
            }
            report.line("  $head: record ${ReplayTimeline.clock(m)} requested ${r.requestedUnits} delivered ${r.deliveredUnits} extended ${r.extendedDeliveredUnits} total ${r.totalDeliveredUnits}; rows: $rows")
            if (c.op != "12") continue
            for ((name, run) in listOf("all" to all, "none" to none)) {
                val got = rowsNear(run, m)
                val records = seen.count { ApexClock.minuteStart(momentOf(it, file)) == ApexClock.minuteStart(m) }
                if (records == 1 && (got.size != 1 || abs(got[0].amountUnits - r.totalDeliveredUnits) > 1e-6)) report.problem("$head, pass $name: rows ${got.map { it.amountUnits }} but the record says ${r.totalDeliveredUnits} U")
            }
        }
    }

    /** Every record that is new after the first journal answer of the pass has rows in its minute that add up to what it delivered. */
    private fun unexplained(file: ReplayAllFile, report: ReplayReport, seen: List<Seen>, run: ReplayRun, name: String) {
        val firstAt = run.results.firstOrNull { it.beat.journalHex != null }?.beat?.journalMs ?: return
        val later = seen.filter { it.firstReadMs > firstAt }
        val rows = run.bolusRows.map { it.write }
        val unplacedRows = rows.filter { unplaced(run, it) }
        val byMinute = later.groupBy { ApexClock.minuteStart(momentOf(it, file)) }
        var wrongAmount = 0
        var wrongCount = 0
        for ((minute, records) in byMinute.toSortedMap()) {
            val got = rowsNear(run, minute)
            val wanted = records.map { Math.round(it.record.totalDeliveredUnits * 1000) }.sorted()
            val have = got.map { Math.round(it.amountUnits * 1000) }.sorted()
            if (wanted == have) continue
            if (got.size != records.size) wrongCount++ else wrongAmount++
            report.problem("pass $name: minute ${ReplayTimeline.clock(minute)}: records ${wanted.map { it / 1000.0 }}, rows ${have.map { it / 1000.0 }}")
        }
        val extra = rows.filter { w -> !unplaced(run, w) && later.none { ApexClock.minuteStart(momentOf(it, file)) == ApexClock.minuteStart(w.timestampMs) } }
        report.line("  pass $name: new records after its first answer ${later.size} in ${byMinute.size} minutes; minutes where the rows differ from the records in number $wrongCount, in amount $wrongAmount; rows with no new record ${extra.size}; unplaced rows (stamped by the real clock) ${unplacedRows.size}")
        extra.forEach { report.problem("pass $name: row ${ReplayTimeline.write(it)} has no record of the journal read whole") }
        run.bolusRows.filter { unplaced(run, it.write) }.forEach { report.line("    unplaced at ${run.at(it.beat)}: ${ReplayTimeline.write(it.write)}") }
        val placed = rows - unplacedRows.toSet()
        val dup = placed.groupBy { it.pumpId }.filter { it.value.size > 1 }.keys + placed.groupBy { it.timestampMs to it.amountUnits }.filter { it.value.size > 1 }.flatMap { e -> e.value.map { it.pumpId } }
        if (dup.isNotEmpty()) report.failure("pass $name: bolus rows twice: ${dup.toSet()}")
        val ids = unplacedRows.groupBy { it.pumpId }.filter { it.value.size > 1 }
        if (ids.isNotEmpty()) report.line("  pass $name: ${ids.size} row numbers of unplaced rows are used for more than one row (one per second of the read, here the real clock): ${ids.entries.joinToString { "${it.key} x${it.value.size}" }}")
    }
}
