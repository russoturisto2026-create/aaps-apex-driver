package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexStatusV1
import kotlin.math.abs

/** Checks 1 and 2: the temporary basal rows against the commands the record holds, and the start worked out from the status. */
object ReplayCheckTbr {

    private class Command(val ms: Long, val mode: String, val quarters: Int, val value: Int) {

        val isRate get() = mode == "01"
        val rate get() = value * ApexProtocol.DOSE_SCALE
        val lengthMs get() = quarters * 15 * ApexClock.MINUTE_MS
        val text get() = if (isRate) "$rate U/h" else "$value %"
    }

    private fun parse(c: ReplayCommand): Command {
        val p = c.payload
        return Command(c.ms, p.substring(0, 2), p.substring(2, 4).toInt(16), p.substring(6, 8).toInt(16) * 256 + p.substring(4, 6).toInt(16))
    }

    fun run(run: ReplayRun, report: ReplayReport) {
        report.section("1. Temporary basals against the commands")
        val ops = run.file.commands.filter { it.op == "02" || it.op == "05" }.sortedBy { it.ms }
        ops.forEachIndexed { k, c -> if (c.op == "02") set(run, report, parse(c), ops.getOrNull(k + 1)) else cancel(run, report, c) }
        doubled(run, report)
        replaced(run, report, ops)
        stopsAfterRunOut(run, report)
        openRows(run, report)
        report.section("2. Start minute worked out from the status")
        startMinute(run, report)
    }

    /** The rate in U/h the pump shows for an order in percent: the schedule rate times the percentage, cut to a step; one step either way is let pass. */
    private fun fits(c: Command, status: ApexStatusV1): Boolean {
        if (!status.tbrActive || status.tbrDurationMinutes != c.quarters * 15) return false
        val wanted = if (c.isRate) c.rate else status.scheduledBasalRate * c.value / 100.0
        return abs(status.tbrRate - wanted) <= (if (c.isRate) 1e-6 else ApexProtocol.DOSE_SCALE)
    }

    /** A temporary basal command: was there one row, in its minute, at the status's rate and the right length. */
    private fun set(run: ReplayRun, report: ReplayReport, c: Command, next: ReplayCommand?) {
        val lag = run.lagNear(c.ms)
        val pumpMs = c.ms - lag
        val minutes = setOf(ApexClock.minuteStart(c.ms), ApexClock.minuteStart(pumpMs))
        val head = "02 ${ReplayTimeline.clock(c.ms)} ${c.text} ${c.quarters * 15} min (pump clock about $lag ms behind: pump moment ${ReplayTimeline.clock(pumpMs)})"
        val first = run.beatAfter(c.ms) ?: return report.line("  $head: no heartbeat after it")
        val beat = run.results.firstOrNull { it.index >= first && it.ran == true }?.index ?: return report.line("  $head: no heartbeat that ran after it")
        val status = run.results[beat].status!!
        if (beat != first && fits(c, run.results[first].status!!)) report.line("  $head: seen at ${run.at(first)}, a heartbeat the driver gave up (nothing settled)")
        if (next != null && next.ms < run.readMs(beat) && !fits(c, status)) return report.line("  $head: another command (${next.op}) came before the next heartbeat that ran; no row expected")
        if (!fits(c, status)) return report.line("  $head: the status at ${run.at(beat)} shows ${if (status.tbrActive) "${status.tbrRate} U/h ${status.tbrDurationMinutes} min" else "none"}, not this one; no row expected")
        val rows = run.tbrRows.filter { it.beat in beat..beat + 2 && abs(it.write.startMs - ApexClock.minuteStart(c.ms)) <= 2 * ApexClock.MINUTE_MS && abs(it.write.rate - status.tbrRate) < 1e-6 }
        report.line("  $head: status at ${run.at(beat)} shows ${status.tbrRate} U/h mode ${status.tbrMode} ${status.tbrDurationMinutes} min gone ${status.tbrElapsedMinutes}; rows ${rows.size}")
        if (rows.size != 1) report.problem("02 ${ReplayTimeline.clock(c.ms)}: ${rows.size} rows, expected exactly one")
        for (r in rows) {
            val w = r.write
            val problems = ArrayList<String>()
            if (w.startMs !in minutes) problems.add("starts ${ReplayTimeline.clock(w.startMs)}, the minute of the command is ${minutes.joinToString { ReplayTimeline.clock(it) }}")
            if (!w.isAbsolute) problems.add("written in percent, expected U/h")
            val end = w.startMs + w.durationMs
            if (minutes.none { abs(end - (it + c.lengthMs)) <= ApexClock.MINUTE_MS }) problems.add("ends ${ReplayTimeline.clock(end)}, expected ${minutes.joinToString { ReplayTimeline.clock(it + c.lengthMs) }}")
            val edge = if (minutes.size > 1) " (command within the clock lag of a minute edge: either minute fits; the row took the ${if (w.startMs == ApexClock.minuteStart(c.ms)) "phone" else "pump"} minute)" else ""
            report.line("    row at ${run.at(r.beat)}: id ${w.pumpId} start ${ReplayTimeline.clock(w.startMs)} ${w.rate} ${if (w.isAbsolute) "U/h" else "%"} ${w.durationMs / 60_000} min" + if (problems.isEmpty()) " ok$edge" else "")
            if (problems.isNotEmpty()) report.problem("02 ${ReplayTimeline.clock(c.ms)} row ${w.pumpId}: ${problems.joinToString("; ")}")
        }
        if (!c.isRate && rows.isNotEmpty() && c.value != 0) {
            report.line("    percentage order: the row is written in U/h at the status rate; the percentage is not kept, a change of the schedule slot during the order would not move the row")
        }
    }

    /** A cancel: the row of a running temporary basal must be stopped at the next heartbeat. */
    private fun cancel(run: ReplayRun, report: ReplayReport, c: ReplayCommand) {
        val before = run.beatBefore(c.ms)
        val after = run.ranAfter(c.ms)
        val head = "05 ${ReplayTimeline.clock(c.ms)}"
        if (before == null || after == null) return report.line("  $head: no heartbeat around it")
        val wasRunning = run.results[before].status!!.tbrActive
        val nowRunning = run.results[after].status?.tbrActive == true
        val stops = run.stops.filter { it.beat == after }
        report.line("  $head: before ${run.at(before)} running=$wasRunning; at ${run.at(after)} running=$nowRunning; stops ${stops.joinToString { ReplayTimeline.clock(it.write.endMs) }}")
        if (wasRunning && !nowRunning) {
            if (stops.size != 1) report.problem("05 ${ReplayTimeline.clock(c.ms)}: ${stops.size} stops at the next heartbeat, expected one")
            else if (stops[0].write.endMs < c.ms - 5 * ApexClock.MINUTE_MS || stops[0].write.endMs > run.results[after].beat.heartbeatMs) {
                report.problem("05 ${ReplayTimeline.clock(c.ms)}: stop at ${ReplayTimeline.clock(stops[0].write.endMs)} is out of place")
            }
        }
    }

    /** Rows written twice: the same id again, or two ids starting in one minute. */
    private fun doubled(run: ReplayRun, report: ReplayReport) {
        val byId = run.tbrRows.groupBy { it.write.pumpId }.filter { it.value.size > 1 }
        val byMinute = run.tbrRows.groupBy { ApexClock.minuteStart(it.write.startMs) }.filter { e -> e.value.map { it.write.pumpId }.distinct().size > 1 }
        report.line("  rows written ${run.tbrRows.size}, ids written more than once ${byId.size}, minutes with two ids ${byMinute.size}")
        byId.forEach { report.problem("row ${it.key} written at ${it.value.joinToString { run.at(it.beat) }}") }
        byMinute.forEach { report.problem("two rows start in ${ReplayTimeline.clock(it.key)}") }
    }

    /** The driver's own words for another's command that replaced a row: is there a command in the record for it. */
    private fun replaced(run: ReplayRun, report: ReplayReport, ops: List<ReplayCommand>) {
        val events = run.events("tbr_replaced")
        report.line("  replaced events: ${events.size}")
        for ((i, e) in events) {
            val prev = (i - 1 downTo 0).firstOrNull { run.results[it].ran == true }
            val from = prev?.let { run.readMs(it) } ?: Long.MIN_VALUE
            val inBetween = ops.filter { it.ms > from && it.ms <= run.readMs(i) }
            val text = "${run.at(i)} $e; commands since the last run heartbeat: ${inBetween.joinToString { "${it.op}@${ReplayTimeline.clock(it.ms)}" }.ifEmpty { "none" }}"
            report.line("    $text")
            if (inBetween.none { it.op == "02" }) report.problem("replaced with no 02 command in the record: $text")
            if (e.fields["ours"] == true) report.problem("a row 'ours' was replaced, the driver sent nothing: $text")
        }
    }

    /** A temporary basal that ran its whole length is left to end by itself: no stop after it ended. */
    private fun stopsAfterRunOut(run: ReplayRun, report: ReplayReport) {
        for (s in run.stops) {
            val row = run.tbrRows.lastOrNull { it.beat <= s.beat && it.write.startMs < s.write.endMs } ?: continue
            val end = row.write.startMs + row.write.durationMs
            if (s.write.endMs >= end) {
                report.line("  stop ${ReplayTimeline.clock(s.write.endMs)} at ${run.at(s.beat)} comes after the row ${row.write.pumpId} ran out at ${ReplayTimeline.clock(end)}")
                report.problem("stop at ${run.at(s.beat)} for row ${row.write.pumpId} that had run out at ${ReplayTimeline.clock(end)} (record shows the next command, not an end)")
            }
        }
        val ranOut = run.events("tbr_closed").filter { (it.second.fields["how"] as? String)?.contains("ran out") == true }
        report.line("  rows left to end by themselves (closed with no stop): ${ranOut.size} at ${ranOut.joinToString { run.at(it.first) }}")
    }

    /** Rows held open while the status shows none, at a heartbeat that ran to the end; and at the end of the record. */
    private fun openRows(run: ReplayRun, report: ReplayReport) {
        val odd = run.results.filter { it.ran == true && it.status != null && !it.status.tbrActive && it.openAfter != null }
        val missing = run.results.filter { it.ran == true && it.status != null && it.status.tbrActive && it.openAfter == null }
        report.line("  heartbeats with a row open and no temporary basal in the status: ${odd.size}; a temporary basal in the status and no row open: ${missing.size}")
        odd.forEach { report.problem("row open though none runs at ${run.at(it.index)}: ${it.openAfter}") }
        missing.forEach { report.problem("temporary basal runs and no row is open at ${run.at(it.index)}") }
        val last = run.results.last { it.status != null }
        report.line("  at the end: status shows ${if (last.status!!.tbrActive) "a temporary basal" else "none"}, open row ${last.openAfter ?: "none"}")
        if (!last.status.tbrActive && last.openAfter != null) report.problem("row still open at the end though the last status shows none: ${last.openAfter}")
    }

    /** Check 2: while one temporary basal runs, the start worked out from the status must stay in one minute. */
    private fun startMinute(run: ReplayRun, report: ReplayReport) {
        var changes = 0
        var runs = 0
        var previous: Int? = null
        for (r in run.results) {
            val s = r.status ?: continue
            val before = previous
            previous = r.index
            val p = before?.let { run.results[it].status }
            if (!s.tbrActive) continue
            if (before == null || p == null || !p.tbrActive || p.tbrRate != s.tbrRate || p.tbrDurationMinutes != s.tbrDurationMinutes || p.tbrMode != s.tbrMode || s.tbrElapsedMinutes < p.tbrElapsedMinutes) {
                runs++
                continue
            }
            val a = TbrFacts.startFromStatus(p)
            val b = TbrFacts.startFromStatus(s)
            if (a != b) {
                changes++
                val text = "${run.at(r.index)}: start ${ReplayTimeline.clock(b * 1000)} but at the heartbeat before (${run.at(before)}) ${ReplayTimeline.clock(a * 1000)}; clock ${p.clockSeconds}->${s.clockSeconds}, gone ${p.tbrElapsedMinutes}->${s.tbrElapsedMinutes}"
                report.line("    $text")
                report.line("      before: ${run.statusHex(before)}")
                report.line("      now:    ${run.statusHex(r.index)}")
                report.problem("start from the status moved: $text")
            }
        }
        report.line("  temporary basals seen in the status: $runs; moves of the start minute while one ran: $changes")
    }
}
