package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.aaps.AapsWrite

/**
 * The basal the rows order, as AAPS would sum it for the replay: the schedule the pump's status
 * showed, and the temporary basal rows the driver wrote at the heartbeats before. A row written at
 * a heartbeat is known from the next one on, as in the real settling order. Only absolute rates
 * are held; a later row wins where two overlap.
 */
class ReplayOrderedBasal {

    private class Row(val startMs: Long, val rate: Double, var endMs: Long)

    private val rows = ArrayList<Row>()
    private val ids = HashMap<Long, Row>()
    private val schedule = ArrayList<Pair<Long, Double>>()

    /** The schedule's rate, U/h, as the status shows it at [atMs]. */
    fun noteSchedule(atMs: Long, rate: Double) {
        schedule.add(atMs to rate)
    }

    /** The rows the driver wrote at a heartbeat. */
    fun note(writes: List<AapsWrite>) {
        for (write in writes) when (write) {
            is AapsWrite.Tbr     -> if (write.isAbsolute) {
                ids.remove(write.pumpId)?.let { rows.remove(it) }
                val row = Row(write.startMs, write.rate, write.startMs + write.durationMs)
                rows.add(row)
                ids[write.pumpId] = row
            }

            is AapsWrite.StopTbr -> rows.filter { it.startMs < write.endMs && it.endMs > write.endMs }.forEach { it.endMs = write.endMs }
            else                 -> Unit
        }
    }

    /** The basal from [startMs] to [endMs], U. */
    fun between(startMs: Long, endMs: Long): Double {
        val cuts = (rows.flatMap { listOf(it.startMs, it.endMs) } + schedule.map { it.first } + listOf(startMs, endMs))
            .filter { it in startMs..endMs }.distinct().sorted()
        var units = 0.0
        for (i in 0 until cuts.size - 1) {
            val from = cuts[i]
            val to = cuts[i + 1]
            val row = rows.lastOrNull { it.startMs <= from && it.endMs >= to }
            val rate = row?.rate ?: schedule.lastOrNull { it.first <= from }?.second ?: schedule.firstOrNull()?.second ?: 0.0
            units += rate * (to - from) / HOUR_MS
        }
        return units
    }

    private companion object {

        const val HOUR_MS = 3_600_000.0
    }
}
