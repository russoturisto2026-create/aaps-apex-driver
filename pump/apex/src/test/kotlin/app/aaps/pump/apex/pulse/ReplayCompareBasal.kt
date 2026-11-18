package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.protocol.ApexProtocol
import java.util.Locale
import kotlin.math.abs

/** Point 4: the basal of each half hour, from the daily statistics (pass all) against the count less the boluses (pass none). */
object ReplayCompareBasal {

    private const val STEP = ApexProtocol.DOSE_SCALE

    /** A closed period: where it began and ended (heartbeat moments and indexes) and what it counted. */
    private class Closed(val from: Long, val to: Long, val toBeat: Int, val units: Double)

    private fun closed(run: ReplayRun): List<Closed> =
        run.results.flatMap { r ->
            r.trace.filter { it.name == "basal_period" && it.fields["state"] == "closed" }
                .map { Closed(it.fields["from"] as Long, it.fields["to"] as Long, r.index, (it.fields["units"] as Number).toDouble()) }
        }

    private fun fmt(v: Double) = "%.3f".format(Locale.ROOT, v)

    private fun pumpDay(run: ReplayRun, beat: Int): Long? = run.results[beat].status?.let { Math.floorDiv(it.clockSeconds, 86_400L) }

    fun run(all: ReplayRun, none: ReplayRun, file: ReplayAllFile, report: ReplayReport) {
        report.section("4. Basal of each half hour: daily statistics (all) against count less boluses (none)")
        val ca = closed(all).associateBy { it.to }
        val cn = closed(none).associateBy { it.to }
        val both = (ca.keys intersect cn.keys).sorted()
        report.line("  periods closed: all ${ca.size}, none ${cn.size}; closed in both at the same heartbeat ${both.size}; only in all ${(ca.keys - cn.keys).size}, only in none ${(cn.keys - ca.keys).size}")
        (ca.keys - cn.keys).sorted().forEach { report.line("    only in all: closes ${ReplayTimeline.clock(it)} from ${ReplayTimeline.clock(ca[it]!!.from)} ${fmt(ca[it]!!.units)} U") }
        (cn.keys - ca.keys).sorted().forEach { report.line("    only in none: closes ${ReplayTimeline.clock(it)} from ${ReplayTimeline.clock(cn[it]!!.from)} ${fmt(cn[it]!!.units)} U") }
        val boluses = file.commands.filter { it.group == "35" && it.op in setOf("12", "13", "14") }
        var flagged = 0
        for (t in both) {
            val a = ca[t]!!
            val n = cn[t]!!
            val diff = a.units - n.units
            val same = a.from == n.from
            val flag = abs(diff) > STEP + 1e-9 || !same
            report.line("  ${ReplayTimeline.clock(a.from)} .. ${ReplayTimeline.clock(t)}${if (same) "" else " (none began ${ReplayTimeline.clock(n.from)})"}: all ${fmt(a.units)} none ${fmt(n.units)} diff ${fmt(diff)}${if (flag) "  <-- ${why(all, none, file, a, n, boluses)}" else ""}")
            if (flag) flagged++
        }
        report.problem("$flagged of ${both.size} periods differ by more than one step (${STEP} U) or began differently")
        perDay(all, none, ca, cn, report)
        states(all, "all", report)
        states(none, "none", report)
    }

    /** What may explain a difference: a bolus near the close, the pump's midnight, a clock move. */
    private fun why(all: ReplayRun, none: ReplayRun, file: ReplayAllFile, a: Closed, n: Closed, boluses: List<ReplayCommand>): String {
        val reasons = ArrayList<String>()
        val from = minOf(a.from, n.from)
        val near = boluses.filter { it.ms in (a.to - 6 * ApexClock.MINUTE_MS)..(a.to + 2 * ApexClock.MINUTE_MS) }
        if (near.isNotEmpty()) reasons.add("bolus command ${near.joinToString { "${it.op}@${ReplayTimeline.clock(it.ms)}" }} near the close")
        val inside = boluses.count { it.ms in from..a.to }
        if (inside > 0) reasons.add("$inside bolus command(s) inside the period")
        val fromBeat = all.results.indexOfFirst { ApexClock.minuteStart(it.beat.heartbeatMs) == from }
        if (fromBeat >= 0 && pumpDay(all, fromBeat) != pumpDay(all, a.toBeat)) reasons.add("the pump day changes in the period (${pumpDay(all, fromBeat)} to ${pumpDay(all, a.toBeat)})")
        file.clockWrites.filter { it.atMs in (from - ApexClock.MINUTE_MS)..(a.to + ApexClock.MINUTE_MS) }.forEach { reasons.add("clock write at ${ReplayTimeline.clock(it.atMs)}${if (it.changed) " (moved)" else " (same offset)"}") }
        if (a.from != n.from) reasons.add("the periods began at different heartbeats")
        val pa = all.results[a.toBeat].periodBolusesBefore
        val pn = none.results[n.toBeat].periodBolusesBefore
        return (reasons.joinToString("; ").ifEmpty { "no reason found" }) + " [boluses held: all ${fmt(pa)}, none ${fmt(pn)} U]"
    }

    private fun perDay(all: ReplayRun, none: ReplayRun, ca: Map<Long, Closed>, cn: Map<Long, Closed>, report: ReplayReport) {
        report.line("  -- sums per pump day (day of the closing heartbeat)")
        fun sums(run: ReplayRun, c: Map<Long, Closed>) = c.values.groupBy { pumpDay(run, it.toBeat) }.mapValues { e -> e.value.sumOf { it.units } to e.value.size }
        val sa = sums(all, ca)
        val sn = sums(none, cn)
        for (d in (sa.keys + sn.keys).filterNotNull().sorted()) {
            val x = sa[d]
            val y = sn[d]
            report.line("  pump day $d: all ${x?.let { fmt(it.first) + " U in " + it.second } ?: "none"}; none ${y?.let { fmt(it.first) + " U in " + it.second } ?: "none"}; difference ${fmt((x?.first ?: 0.0) - (y?.first ?: 0.0))} U")
        }
    }

    /** Postponed and restarted periods by reason. */
    private fun states(run: ReplayRun, name: String, report: ReplayReport) {
        val events = run.results.flatMap { r -> r.trace.filter { it.name == "basal_period" }.map { r.index to it } }
        val postponed = events.filter { it.second.fields["state"] == "postponed" }.groupBy { it.second.fields["why"] }
        val begun = events.filter { it.second.fields["state"] == "begun" && it.second.fields["why"] != "closed" }.groupBy { it.second.fields["why"] }
        report.line("  pass $name postponed: ${postponed.entries.joinToString { "${it.key} x${it.value.size}" }.ifEmpty { "none" }}")
        report.line("  pass $name begun again, not by a close: ${begun.entries.joinToString { "${it.key} x${it.value.size}" }.ifEmpty { "none" }}")
        for ((i, t) in events.filter { it.second.fields["state"] == "begun" && it.second.fields["why"] != "closed" || it.second.fields["state"] == "postponed" }.take(60)) {
            report.line("    ${run.at(i)} ${t.fields["state"]} ${t.fields["why"]}")
        }
    }
}
