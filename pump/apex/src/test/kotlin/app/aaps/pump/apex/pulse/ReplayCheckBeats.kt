package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.clock.ApexClock

/** Checks 5 and 6: heartbeats with no answer, and the other things that look wrong. */
object ReplayCheckBeats {

    fun run(run: ReplayRun, report: ReplayReport) {
        report.section("5. Heartbeats with no status or no journal")
        val noStatus = run.results.filter { it.beat.statusHex == null }
        val noJournal = run.results.filter { it.beat.journalHex == null }
        val notRun = run.results.filter { it.ran != true }
        report.line("  heartbeats ${run.results.size}; no status ${noStatus.size}; no journal ${noJournal.size}; the driver gave up ${notRun.size}; threw ${run.results.count { it.error != null }}")
        val withWrites = notRun.filter { it.writes.isNotEmpty() }
        report.line("  heartbeats that gave up and wrote something: ${withWrites.size}")
        val late = ArrayList<String>()
        for (r in notRun) {
            val s = r.status ?: continue
            val p = run.results.lastOrNull { it.index < r.index && it.status != null }?.status
            val changed = p != null && (p.tbrActive != s.tbrActive || (s.tbrActive && (p.tbrRate != s.tbrRate || p.tbrDurationMinutes != s.tbrDurationMinutes)))
            if (changed) late.add(run.at(r.index))
        }
        report.line("  gave up at a heartbeat where the status showed a temporary basal start, stop or change: ${late.size} ${late.joinToString()}")
        var longest = 0
        var current = 0
        for (r in run.results) {
            current = if (r.ran == true) 0 else current + 1
            longest = maxOf(longest, current)
        }
        report.line("  longest run of heartbeats that gave up: $longest")
        if (longest > 1) report.problem("$longest heartbeats in a row where nothing was settled")
        for (r in run.results.filter { it.error != null }) report.failure("the driver threw at ${run.at(r.index)}: ${r.error}")

        report.section("6. Other")
        val periods = run.results.filter { it.periodCalls.isNotEmpty() }
        report.line("  heartbeat period set by the grid at ${periods.size} heartbeats (the recorded pump beats each minute, the grid wants five): first ${periods.take(3).joinToString { run.at(it.index) + " -> " + it.periodCalls }}")
        val ahead = run.results.filter { r -> r.beat.journalHex != null && ReplayCheckBolus.records(run, r.index).any { it.clockSeconds * 1000 - run.offsetAt(run.readMs(r.index)) > run.readMs(r.index) + 2 * ApexClock.MINUTE_MS } }
        report.line("  journal answers holding a record stamped more than two minutes after the read: ${ahead.size}")
        ahead.forEach { report.problem("a record is stamped in the future at ${run.at(it.index)}") }
        val counts = run.results.mapNotNull { it.status?.deliveredTodayUnits }
        val back = counts.zipWithNext().count { it.second < it.first }
        report.line("  count of the day went back $back times (a new pump day is one of them)")
        val lags = run.results.indices.mapNotNull { run.lagMs(it) }
        report.line("  the pump clock behind the phone heartbeat moment: from ${lags.minOrNull()} ms to ${lags.maxOrNull()} ms")
        val readTimes = run.results.mapNotNull { r -> r.trace.firstOrNull { it.name == "picture" }?.fields?.get("ms") as? Number }
        report.line("  the picture read moment less the heartbeat moment, as the driver measures it: ${readTimes.firstOrNull()} ms (the driver own clock, not the recorded one)")
        report.line("  places where the driver uses the phone clock while the record is in the past (a replay over the real clock):")
        report.line("    PictureReader.BasalPart.picture: readAtMs is the real now; HeartbeatTick passes it as nowMs to BolusReconciliation.settle, the cells, the record bounds, and the bolus row ids (id base is now): the ids above are the real now plus a number")
        report.line("    The record bounds of BolusReconciliation: a stamp in the future is told from the real now; with the real now far after the record, no record can be future here")
        report.line("    ApexStamp.read: of two readings of a stamp it takes the one nearer the real now and drops a reading after it")
        report.line("    PictureReader.offsetMs: the phone zone at the real now, used only when no clock write is stored; the replay stores a clock offset of zero")
        report.line("    HeartbeatTick.writeClockIfDue: passes the real now to the clock writer (the writer is a mock here)")
        report.line("    HeartbeatGrid.minuteOf: minute of the hour in the machine zone")
    }
}
