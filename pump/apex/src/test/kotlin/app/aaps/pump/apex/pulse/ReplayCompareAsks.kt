package app.aaps.pump.apex.pulse

/** Points 5 and 6 of the two-ways replay: what the driver asked of the record, who gave up and why. */
object ReplayCompareAsks {

    fun run(all: ReplayRun, allSource: ReplaySource, none: ReplayRun, noneSource: ReplaySource, report: ReplayReport) {
        report.section("5. Asks, answers from earlier reads, heartbeats that gave up")
        for ((name, run, source) in listOf(Triple("all", all, allSource), Triple("none", none, noneSource))) {
            report.line("  pass $name: heartbeats fed ${run.results.size}, ran to the end ${run.results.count { it.ran == true }}, gave up ${run.results.count { it.ran != true }}, threw ${run.results.count { it.error != null }}")
            for (kind in "SVDJAB") {
                val notes = source.notes.filter { it.kind == kind }
                if (notes.isEmpty()) continue
                val by = notes.groupingBy { it.how }.eachCount()
                report.line("    object $kind: asked ${notes.size} times; from the window ${by["window"] ?: 0}, repeated read ${by["repeat"] ?: 0}, from an earlier read ${by["earlier"] ?: 0}, no answer ${by["none"] ?: 0}, refused ${by["refused"] ?: 0}")
                if (kind == 'A' || kind == 'B') {
                    val stale = notes.filter { it.stale }
                    report.line("      answered from an earlier read: ${notes.count { it.how == "earlier" }}; stale by the record (a set or cancel lies between that read and the heartbeat): ${stale.size}${stale.take(25).joinToString(prefix = " at ") { run.at(it.beat) }}")
                }
                val late = notes.filter { it.how == "window" && it.readMs!! - run.results[it.beat].beat.heartbeatMs > 60_000 }
                if (kind != 'S') report.line("      answered from the window but read more than a minute after the heartbeat: ${late.size} (the recorded client read it for its own reasons; the driver under test would not have seen it yet)")
                val none = notes.filter { it.how == "none" }
                if (none.isNotEmpty()) report.line("      no answer at ${none.take(25).joinToString { run.at(it.beat) }}${if (none.size > 25) " ..." else ""}")
                val ages = notes.filter { it.how == "earlier" }.mapNotNull { n -> n.readMs?.let { run.results[n.beat].beat.heartbeatMs - it } }
                if (ages.isNotEmpty()) report.line("      age of the earlier answers: smallest ${ages.min() / 1000} s, largest ${ages.max() / 1000} s")
            }
            val gaveUp = run.results.filter { it.ran != true }
            gaveUp.take(60).forEach { r ->
                val why = r.logs.filter { it.level != "INFO" }.joinToString(" | ") { it.message.take(140) }.ifEmpty { "no warning logged" }
                val none = source.notes.filter { it.beat == r.index && it.how == "none" }.joinToString { it.kind.toString() }
                report.line("    gave up ${run.at(r.index)}: record had no answer for [$none]; $why")
            }
            if (gaveUp.isNotEmpty()) report.problem("pass $name: ${gaveUp.size} heartbeats gave up")
        }
    }
}
