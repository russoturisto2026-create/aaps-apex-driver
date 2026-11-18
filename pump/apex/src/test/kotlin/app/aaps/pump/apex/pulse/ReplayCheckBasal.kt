package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.protocol.ApexProtocol
import java.util.Locale
import kotlin.math.abs

/** Check 4: the basal by the pump count, per half hour: what moved, what was taken off, the result, and the rate that bounds it. */
object ReplayCheckBasal {

    private const val STEP = ApexProtocol.DOSE_SCALE
    private const val FEW_STEPS = 3 * STEP

    private fun count(run: ReplayRun, i: Int): Double? = run.results[i].status?.deliveredTodayUnits
    private fun day(run: ReplayRun, i: Int): Long? = run.results[i].status?.let { Math.floorDiv(it.clockSeconds, 86_400L) }

    /** The most the status rates could give from heartbeat [from] to [to]: the higher of the two ends for each span. */
    private fun upper(run: ReplayRun, from: Int, to: Int): Double {
        val ids = (from..to).filter { run.results[it].status != null }
        fun rate(i: Int) = run.results[i].status!!.let { if (it.suspended) 0.0 else if (it.tbrActive) it.tbrRate else it.scheduledBasalRate }
        var sum = 0.0
        for (k in 0 until ids.size - 1) {
            val dt = run.results[ids[k + 1]].beat.heartbeatMs - run.results[ids[k]].beat.heartbeatMs
            sum += maxOf(rate(ids[k]), rate(ids[k + 1])) * dt / 3_600_000.0
        }
        return sum
    }

    fun run(run: ReplayRun, report: ReplayReport) {
        report.section("4. Basal by the pump count, per half hour")
        val states = run.results.flatMap { r -> r.trace.filter { it.name == "basal_period" }.map { r.index to it } }
        states.filter { it.second.fields["state"] == "postponed" || (it.second.fields["state"] == "begun" && it.second.fields["why"] != "closed") }.forEach { (i, t) ->
            report.line("  ${run.at(i)} ${t.fields["state"]} because ${t.fields["why"]}")
        }
        val closed = states.filter { it.second.fields["state"] == "closed" }
        val sums = HashMap<Long, Double>()
        for ((i, t) in closed) {
            // A period begins at the start of its heartbeat's minute, see BasalPeriodKeeper.
            val s = run.results.indexOfFirst { ApexClock.minuteStart(it.beat.heartbeatMs) == t.fields["from"] }
            val units = (t.fields["units"] as Number).toDouble()
            val moved = Math.round((count(run, i)!! - count(run, s)!!) / STEP)
            val taken = Math.round((run.results[i].periodBolusesBefore + run.bolusRows.filter { it.beat == i }.sumOf { it.write.amountUnits }) / STEP)
            val expected = maxOf(0L, moved - taken) * STEP
            val bound = upper(run, s, i)
            val text = "${run.at(s)} .. ${run.at(i)}: count ${count(run, s)} -> ${count(run, i)} moved ${moved * STEP} U, boluses off ${taken * STEP} U, result $units U (rate bound ${"%.3f".format(Locale.ROOT, bound)} U)"
            report.line("  $text")
            if (abs(units - expected) > 1e-6) report.problem("result differs from move less boluses ($expected U): $text")
            if (moved < taken) report.problem("negative before the clamp (${(moved - taken) * STEP} U): $text")
            if (units > bound + FEW_STEPS) report.problem("result above what the status rates could give by ${"%.3f".format(Locale.ROOT, units - bound)} U: $text")
            day(run, i)?.let { d -> sums[d] = (sums[d] ?: 0.0) + units }
        }
        perDay(run, report, states, closed, sums)
    }

    /** The sum of the results over a pump day against last count less first count less the boluses. */
    private fun perDay(run: ReplayRun, report: ReplayReport, states: List<Pair<Int, TraceLine>>, closed: List<Pair<Int, TraceLine>>, sums: Map<Long, Double>) {
        for ((d, sum) in sums.toSortedMap()) {
            val begun = states.filter { it.second.fields["state"] == "begun" && day(run, it.first) == d }.minByOrNull { it.first } ?: continue
            val lastClose = closed.filter { day(run, it.first) == d }.maxBy { it.first }.first
            val lastBeat = run.results.indices.last { day(run, it) == d }
            val boluses = run.bolusRows.filter { it.beat > begun.first && it.beat <= lastClose }.sumOf { it.write.amountUnits }
            val expected = count(run, lastClose)!! - count(run, begun.first)!! - boluses
            report.line("  pump day $d: first period began ${run.at(begun.first)} at count ${count(run, begun.first)}, last closed ${run.at(lastClose)} at ${count(run, lastClose)}, boluses ${"%.3f".format(Locale.ROOT, boluses)} U; sum of results ${"%.3f".format(Locale.ROOT, sum)} U, expected ${"%.3f".format(Locale.ROOT, expected)} U")
            report.line("    the last status of the day ${run.at(lastBeat)} has count ${count(run, lastBeat)}: ${"%.3f".format(Locale.ROOT, count(run, lastBeat)!! - count(run, lastClose)!!)} U delivered after the last close is in no period")
            if (abs(sum - expected) > FEW_STEPS) report.problem("pump day $d: sum of results ${"%.3f".format(Locale.ROOT, sum)} U, last count less first count less boluses ${"%.3f".format(Locale.ROOT, expected)} U")
        }
    }
}
