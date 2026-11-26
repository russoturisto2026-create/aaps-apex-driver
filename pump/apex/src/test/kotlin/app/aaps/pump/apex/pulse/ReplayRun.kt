package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.aaps.NoticeKind

/** A finished replay with the views the checks share. */
class ReplayRun(
    val file: ReplayFile,
    val results: List<BeatResult>,
    val serial: String,
    /** How far the pump clock stood ahead of the record's time at a moment; zero when it was never moved. */
    val offsetAt: (Long) -> Long = { 0L }
) {

    /** A write and the heartbeat it was written at. */
    class Written<T : AapsWrite>(val beat: Int, val write: T)

    val tbrRows: List<Written<AapsWrite.Tbr>> = writesOf()
    val stops: List<Written<AapsWrite.StopTbr>> = writesOf()
    val bolusRows: List<Written<AapsWrite.Bolus>> = writesOf()
    val notices: List<Written<AapsWrite.Notice>> = writesOf()

    private inline fun <reified T : AapsWrite> writesOf(): List<Written<T>> =
        results.flatMap { r -> r.writes.filterIsInstance<T>().map { Written(r.index, it) } }

    /** The moment the status of heartbeat [i] was read, else the heartbeat's own moment. */
    fun readMs(i: Int): Long = results[i].beat.statusMs ?: results[i].beat.heartbeatMs

    /** About how far the pump's clock stands behind the phone's at heartbeat [i]: the heartbeat's moment less the clock its status shows. */
    fun lagMs(i: Int): Long? = results[i].status?.let { results[i].beat.heartbeatMs - (it.clockSeconds * 1000 - offsetAt(readMs(i))) }

    /** The lag at the heartbeat with a status nearest to [ms]; 0 when there is none. */
    fun lagNear(ms: Long): Long {
        var best: Int? = null
        for (r in results) if (r.status != null && (best == null || Math.abs(r.beat.heartbeatMs - ms) < Math.abs(results[best].beat.heartbeatMs - ms))) best = r.index
        return best?.let { lagMs(it) } ?: 0L
    }

    /** The first heartbeat with a status read after [ms]. */
    fun beatAfter(ms: Long): Int? = results.firstOrNull { it.status != null && readMs(it.index) > ms }?.index

    /** The last heartbeat with a status read before [ms]. */
    fun beatBefore(ms: Long): Int? = results.lastOrNull { it.status != null && readMs(it.index) <= ms }?.index

    /** The first heartbeat after [ms] at which the driver ran to the end. */
    fun ranAfter(ms: Long): Int? = results.firstOrNull { it.ran == true && it.beat.heartbeatMs > ms }?.index

    fun at(i: Int): String = "#$i ${ReplayTimeline.clock(results[i].beat.heartbeatMs)}"

    fun statusHex(i: Int): String = results[i].beat.statusHex ?: "none"

    /** The trace events named [name] with their heartbeat. */
    fun events(name: String): List<Pair<Int, TraceLine>> = results.flatMap { r -> r.trace.filter { it.name == name }.map { r.index to it } }

    fun noticeText(n: Written<AapsWrite.Notice>): String = "${n.write.kind == NoticeKind.JOURNAL_FAULT} ${n.write.kind} ${n.write.units} U"
}

/** The plain-text report: lines for reading, and the problems found, each also marked in the lines. */
class ReplayReport {

    val lines = ArrayList<String>()
    val problems = ArrayList<String>()

    /** What is certain and failed: the test asserts there are none. Each is also a problem. */
    val failures = ArrayList<String>()

    fun section(title: String) {
        lines.add("")
        lines.add("== $title")
    }

    fun line(text: String) {
        lines.add(text)
    }

    /** A thing the driver did wrong, or that needs a look: counted and marked. */
    fun problem(text: String) {
        problems.add(text)
        lines.add("  !! $text")
    }

    fun failure(text: String) {
        failures.add(text)
        problem(text)
    }

    fun text(): String = lines.joinToString("\n")
}
