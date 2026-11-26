package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.clock.ApexClock

/** One temporary basal row as the pass left it: its last written form and the stop that ended it, if any. */
class Episode(val pumpId: Long, var start: Long, var rate: Double, var absolute: Boolean, var lengthMs: Long, val beat: Int) {

    var stopMs: Long? = null
    var stopBeat: Int? = null

    val end: Long get() = stopMs ?: (start + lengthMs)

    fun text(): String =
        "id $pumpId ${ReplayTimeline.clock(start)} $rate${if (absolute) " U/h" else " %"} ${lengthMs / 60_000} min, " +
            (stopMs?.let { "stopped ${ReplayTimeline.clock(it)} at #$stopBeat" } ?: "not stopped, ends ${ReplayTimeline.clock(end)}")

    companion object {

        /** The rows of a run, in the order they were written; a stop ends the newest row that has none. */
        fun of(run: ReplayRun): List<Episode> {
            val list = ArrayList<Episode>()
            for (r in run.results) for (w in r.writes) when (w) {
                is AapsWrite.Tbr     -> {
                    val same = list.lastOrNull { it.pumpId == w.pumpId }
                    if (same != null) {
                        same.start = w.startMs; same.rate = w.rate; same.absolute = w.isAbsolute; same.lengthMs = w.durationMs
                    } else list.add(Episode(w.pumpId, w.startMs, w.rate, w.isAbsolute, w.durationMs, r.index))
                }

                is AapsWrite.StopTbr -> list.lastOrNull { it.stopMs == null }?.let { it.stopMs = w.endMs; it.stopBeat = r.index }
                else                 -> Unit
            }
            return list
        }
    }
}

/** A temporary basal command of the record in words. */
class TbrOrder(val ms: Long, val op: String, val payload: String) {

    val isSet get() = op == "02"
    val quarters get() = if (isSet) payload.substring(2, 4).toInt(16) else 0
    val lengthMs get() = quarters * 15 * ApexClock.MINUTE_MS

    fun text(): String {
        if (!isSet) return "cancel at ${ReplayTimeline.clock(ms)}"
        val value = payload.substring(6, 8).toInt(16) * 256 + payload.substring(4, 6).toInt(16)
        return "set at ${ReplayTimeline.clock(ms)} ${if (payload.startsWith("01")) "${value * 0.025} U/h" else "$value %"} ${quarters * 15} min"
    }

    companion object {

        fun all(file: ReplayAllFile): List<TbrOrder> =
            file.commands.filter { it.group == "35" && (it.op == "02" || it.op == "05") }.map { TbrOrder(it.ms, it.op, it.payload) }.sortedBy { it.ms }
    }
}
