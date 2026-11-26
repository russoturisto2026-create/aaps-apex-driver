package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.aaps.AapsWrite
import java.util.Calendar
import java.util.TimeZone

/** Plain-text views of what the driver did, one block per heartbeat. */
object ReplayTimeline {

    /** The moment as HH:MM:SS of the record's day (UTC) and the milliseconds. */
    fun clock(ms: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        return "%02d:%02d:%02d %d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), ms)
    }

    fun write(w: AapsWrite): String = when (w) {
        is AapsWrite.EarlyBolus -> "BOLUS ORDER temp=${w.tempId} at=${clock(w.timestampMs)} ${w.amountUnits} U ${w.type}"
        is AapsWrite.Bolus   -> "BOLUS id=${w.pumpId} temp=${w.tempId} at=${clock(w.timestampMs)} ${w.amountUnits} U ${w.type}"
        is AapsWrite.Tbr     -> "TBR id=${w.pumpId} start=${clock(w.startMs)} ${w.rate}${if (w.isAbsolute) " U/h" else " %"} for ${w.durationMs / 60_000.0} min ${w.type}"
        is AapsWrite.StopTbr -> "STOP end=${clock(w.endMs)} id=${w.endMs}"
        is AapsWrite.Notice  -> "NOTICE ${w.kind} units=${w.units} at=${clock(w.atMs)}"
        is AapsWrite.Refill  -> "REFILL at=${clock(w.atMs)} ${w.fromUnits} U to ${w.toUnits} U"
        AapsWrite.OpenLoop   -> "LOOP OPENED"
    }

    /** The whole run, every heartbeat with its writes, logs of warn level and above, and traced events. */
    fun full(results: List<BeatResult>): String = buildString {
        for (r in results) {
            val s = r.status
            append("#${r.index} H ${clock(r.beat.heartbeatMs)} ran=${r.ran} reads(status=${r.statusReads}, journal=${r.journalReads})")
            if (s != null) append(" count=${s.deliveredTodayUnits} sched=${s.scheduledBasalRate} tbr=${if (s.tbrActive) "${s.tbrRate}/m${s.tbrMode}/${s.tbrDurationMinutes}min/gone${s.tbrElapsedMinutes}" else "none"} clk=${clock(s.clockSeconds * 1000)}")
            appendLine()
            r.error?.let { appendLine("    THREW $it ${it.stackTrace.take(4).joinToString(" < ")}") }
            r.writes.forEach { appendLine("    WRITE ${write(it)}") }
            r.logs.filter { it.level != "INFO" }.forEach { appendLine("    ${it.level} ${it.message}") }
            r.trace.forEach { appendLine("    TRACE $it") }
            r.periodCalls.forEach { appendLine("    GRID period $it") }
            r.syncCalls.forEach { appendLine("    SYNC $it") }
        }
    }
}
