package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.account.time.ApexClock
import kotlin.math.abs

/** Point 1: the temporary basal rows of the pass with every object against the pass with none, matched by time. */
object ReplayCompareTbr {

    private const val MATCH_MS = 10 * ApexClock.MINUTE_MS
    private const val DETAIL_LIMIT = 80

    private class Match(val all: Episode, val none: Episode)

    fun run(all: ReplayRun, none: ReplayRun, file: ReplayAllFile, allNotes: List<AskNote>, report: ReplayReport) {
        report.section("1. Temporary basal rows: all objects against none")
        val a = Episode.of(all)
        val n = Episode.of(none)
        val orders = TbrOrder.all(file)
        val free = n.toMutableList()
        val pairs = ArrayList<Match>()
        val onlyAll = ArrayList<Episode>()
        for (e in a) {
            val best = free.filter { abs(it.start - e.start) <= MATCH_MS }.minByOrNull { abs(it.start - e.start) + if (it.rate == e.rate) 0 else MATCH_MS }
            if (best == null) onlyAll.add(e) else {
                free.remove(best)
                pairs.add(Match(e, best))
            }
        }
        report.line("  rows: all ${a.size}, none ${n.size}; matched ${pairs.size}, only in all ${onlyAll.size}, only in none ${free.size}")
        report.line("  to the second (start, end and rate equal): ${pairs.count { same(it) }}")
        report.line("  to the minute (same start minute, same end minute, same rate): ${pairs.count { sameMinute(it) }}")
        report.line("  differ: ${pairs.count { !same(it) }} matched rows; start differences (all less none): ${histogram(pairs.map { (it.all.start - it.none.start) / 1000 })}")
        report.line("  end differences (all less none): ${histogram(pairs.map { (it.all.end - it.none.end) / 1000 })}")
        report.line("  rate differences: ${pairs.count { abs(it.all.rate - it.none.rate) > 1e-6 }}; unit differences: ${pairs.count { it.all.absolute != it.none.absolute }}")
        val staleBeats = allNotes.filter { it.stale }.map { it.beat }.toSet()
        fun touchedByStale(p: Match) = listOfNotNull(p.all.beat, p.all.stopBeat).any { it in staleBeats }
        val differing = pairs.filter { !same(it) }
        report.line("  of the differing matched rows, ${differing.count { touchedByStale(it) }} were settled by the pass all at a heartbeat where the record gave it a stale answer of object A or B (a replay effect), ${differing.count { !touchedByStale(it) }} were not")
        report.line("  of those not touched by a stale answer: start differences ${histogram(differing.filter { !touchedByStale(it) }.map { (it.all.start - it.none.start) / 1000 })}")
        report.line("  of those not touched by a stale answer: end differences ${histogram(differing.filter { !touchedByStale(it) }.map { (it.all.end - it.none.end) / 1000 })}")
        for (p in differing.take(DETAIL_LIMIT)) {
            report.line("  (${if (touchedByStale(p)) "stale answer at its heartbeat" else "no stale answer"})")
            detail(report, all, none, p.all, p.none, orders)
        }
        if (pairs.count { !same(it) } > DETAIL_LIMIT) report.line("  ... more differing rows not listed")
        for (e in onlyAll) detail(report, all, none, e, null, orders)
        for (e in free) detail(report, all, none, null, e, orders)
        report.line("  summary: ${pairs.count { !same(it) }} matched rows differ, ${pairs.count { !sameMinute(it) }} by more than a minute; ${onlyAll.size} only in all, ${free.size} only in none")
    }

    /** Rows that stand where no command of the record puts them: a stop before the start, a start or a stop far from every command. */
    fun placement(run: ReplayRun, file: ReplayAllFile, name: String, report: ReplayReport) {
        val orders = TbrOrder.all(file)
        val sets = orders.filter { it.isSet }
        val episodes = Episode.of(run)
        var bad = 0
        for (e in episodes) {
            val notes = ArrayList<String>()
            if (e.stopMs != null && e.stopMs!! < e.start) notes.add("stopped ${(e.start - e.stopMs!!) / 60_000} min before it started")
            val startOff = sets.minOfOrNull { abs(it.ms - e.start) } ?: Long.MAX_VALUE
            val ahead = e.start - run.results[e.beat].beat.heartbeatMs
            if (ahead > 2 * ApexClock.MINUTE_MS) notes.add("starts ${ahead / 60_000} min after the heartbeat that wrote it")
            if (startOff > 3 * ApexClock.MINUTE_MS) notes.add("start ${startOff / 60_000} min from the nearest set command")
            e.stopMs?.let { stop ->
                val off = orders.minOfOrNull { abs(it.ms - stop) } ?: Long.MAX_VALUE
                if (off > 3 * ApexClock.MINUTE_MS) notes.add("stop ${off / 60_000} min from the nearest set or cancel command")
            }
            if (notes.isEmpty()) continue
            bad++
            val moves = file.clockWrites.filter { it.changed && abs(it.atMs - run.results[e.beat].beat.heartbeatMs) < 20 * ApexClock.MINUTE_MS }
            report.line("  ${run.at(e.beat)}: ${e.text()}: ${notes.joinToString("; ")}${if (moves.isEmpty()) "" else "; clock moved at " + moves.joinToString { ReplayTimeline.clock(it.atMs) }}")
        }
        report.line("  pass $name: ${episodes.size} rows, ${bad} placed where no command puts them")
        if (bad > 0) report.problem("pass $name: $bad temporary basal rows stand away from every command of the record")
    }

    private fun same(p: Match) =
        p.all.start == p.none.start && p.all.end == p.none.end && abs(p.all.rate - p.none.rate) < 1e-6 && p.all.absolute == p.none.absolute

    private fun sameMinute(p: Match) =
        ApexClock.minuteStart(p.all.start) == ApexClock.minuteStart(p.none.start) && ApexClock.minuteStart(p.all.end) == ApexClock.minuteStart(p.none.end) && abs(p.all.rate - p.none.rate) < 1e-6

    /** Buckets of the absolute difference: 0, up to 1 min, up to 5 min, up to 30 min, more. */
    private fun histogram(secs: List<Long>): String {
        val bounds = listOf(0L, 60L, 300L, 1800L)
        val counts = IntArray(5)
        for (s in secs) counts[bounds.indexOfFirst { abs(s) <= it }.let { if (it < 0) 4 else it }]++
        return "0 s ${counts[0]}, <=1 min ${counts[1]}, <=5 min ${counts[2]}, <=30 min ${counts[3]}, more ${counts[4]}; largest ${secs.maxOfOrNull { abs(it) } ?: 0} s"
    }

    private fun detail(report: ReplayReport, all: ReplayRun, none: ReplayRun, ea: Episode?, en: Episode?, orders: List<TbrOrder>) {
        val low = minOf(ea?.start ?: Long.MAX_VALUE, en?.start ?: Long.MAX_VALUE) - 5 * ApexClock.MINUTE_MS
        val high = maxOf(ea?.end ?: 0L, en?.end ?: 0L) + 5 * ApexClock.MINUTE_MS
        report.line("  difference near ${ReplayTimeline.clock(low + 5 * ApexClock.MINUTE_MS)}")
        report.line("    all : ${ea?.let { "at #${it.beat} " + it.text() } ?: "no row"}")
        report.line("    none: ${en?.let { "at #${it.beat} " + it.text() } ?: "no row"}")
        report.line("    commands around: ${orders.filter { it.ms in low..high }.joinToString("; ") { it.text() }.ifEmpty { "none" }}")
        ea?.let { report.line("    status at all #${it.beat}: ${all.statusHex(it.beat)}") }
        en?.let { if (it.beat != ea?.beat) report.line("    status at none #${it.beat}: ${none.statusHex(it.beat)}") }
        report.line("    by the record: ${verdict(ea, en, orders)}")
    }

    /** Which pass is right: the start is the minute of a set command; the end the first cancel, next set or run-out after it. */
    private fun verdict(ea: Episode?, en: Episode?, orders: List<TbrOrder>): String {
        if (ea == null || en == null) return if (ea == null) "a row only in none" else "a row only in all"
        val set = orders.filter { it.isSet }.minByOrNull { abs(it.ms - ea.start) } ?: return "no set command near"
        val minute = ApexClock.minuteStart(set.ms)
        val startA = abs(ea.start - minute)
        val startN = abs(en.start - minute)
        val next = orders.firstOrNull { it.ms > set.ms }
        val expected = listOfNotNull(set.ms + set.lengthMs, next?.ms).min()
        val endA = abs(ea.end - expected)
        val endN = abs(en.end - expected)
        val why = if (next != null && next.ms == expected) "next ${if (next.isSet) "set" else "cancel"} command" else "ran out"
        return "start: command ${ReplayTimeline.clock(set.ms)}, all ${startA / 1000} s off its minute, none ${startN / 1000} s off; ${judge(startA, startN)}" +
            " | end: expected ${ReplayTimeline.clock(expected)} ($why), all ${endA / 1000} s off, none ${endN / 1000} s off; ${judge(endA, endN)}"
    }

    private fun judge(a: Long, n: Long): String = when {
        a <= ApexClock.MINUTE_MS && n <= ApexClock.MINUTE_MS -> "both within a minute"
        a < n                                                -> "all is right"
        n < a                                                -> "none is right"
        else                                                 -> "neither"
    }
}
