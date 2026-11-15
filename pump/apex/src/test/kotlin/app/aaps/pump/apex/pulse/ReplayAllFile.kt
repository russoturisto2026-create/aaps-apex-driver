package app.aaps.pump.apex.pulse

import java.io.File
import java.util.Calendar
import java.util.TimeZone

/** One answer of the record: the moment it was read and its frames as hex. */
class RecordedAnswer(val ms: Long, val hexes: List<String>)

/** The pump's clock was written at [atMs]; from then it stands [offsetMs] ahead of the record's time. */
class ClockWrite(val atMs: Long, val offsetMs: Long, val changed: Boolean)

/**
 * A recorded pump that has every object. One item per line, in time order: `H` a heartbeat, and the
 * answers `S` status, `V` second status, `A` last temporary basal command, `B` last finished one,
 * `D` daily statistics, `J` bolus journal; `C` a command another client sent.
 */
class ReplayAllFile(
    val beatTimes: List<Long>,
    val answers: Map<Char, List<RecordedAnswer>>,
    /** Every command, of every group. */
    val commands: List<ReplayCommand>,
    val clockWrites: List<ClockWrite>
) {

    /** Where the pump's clock stood against the record's time at [ms]. */
    fun offsetAt(ms: Long): Long = clockWrites.lastOrNull { it.atMs <= ms }?.offsetMs ?: 0L

    /** The end of the time that belongs to heartbeat [k]: the next heartbeat. */
    fun windowEnd(k: Int): Long = beatTimes.getOrElse(k + 1) { Long.MAX_VALUE }

    /** The answers of [kind] read after heartbeat [k], within [ANSWER_SECONDS] seconds of it and before the next one: a real driver reads all its objects of a heartbeat within seconds. */
    fun inWindow(kind: Char, k: Int): List<RecordedAnswer> =
        answers[kind].orEmpty().filter { it.ms > beatTimes[k] && it.ms <= beatTimes[k] + ANSWER_SECONDS * 1000L && it.ms < windowEnd(k) }

    /** Commands of group 35 only, in the form the checks of the first replay read. */
    fun asReplayFile(): ReplayFile = ReplayFile(
        beatTimes.indices.map { k ->
            ReplayBeat(beatTimes[k]).also { b ->
                inWindow('S', k).firstOrNull()?.let { b.statusMs = it.ms; b.statusHex = it.hexes.firstOrNull() }
                inWindow('J', k).firstOrNull()?.let { b.journalMs = it.ms; b.journalHex = it.hexes }
            }
        },
        commands.filter { it.group == "35" }
    )

    companion object {

        const val VARIABLE = "APEX_REPLAY_FULL_FILE"

        /** An answer belongs to a heartbeat only when it was read this many seconds after it came at most. */
        const val ANSWER_SECONDS = 20L
        private const val QUARTER_MS = 900_000.0

        fun fromEnvironment(): ReplayAllFile? {
            val path = System.getenv(VARIABLE)?.takeIf { it.isNotBlank() } ?: return null
            val file = File(path).takeIf { it.isFile } ?: return null
            return parse(file.readLines())
        }

        fun parse(lines: List<String>): ReplayAllFile {
            val beats = ArrayList<Long>()
            val answers = HashMap<Char, ArrayList<RecordedAnswer>>()
            val commands = ArrayList<ReplayCommand>()
            for (line in lines) {
                val parts = line.trim().split(' ').filter { it.isNotEmpty() }
                if (parts.size < 2) continue
                val ms = parts[1].toLongOrNull() ?: continue
                val kind = parts[0][0]
                when (kind) {
                    'H'                       -> beats.add(ms)
                    'S', 'V', 'A', 'B', 'D', 'J' -> answers.getOrPut(kind) { ArrayList() }.add(RecordedAnswer(ms, parts.drop(2)))
                    'C'                       -> commands.add(ReplayCommand(ms, parts.getOrElse(3) { "" }, parts.getOrElse(4) { "" }, parts.getOrElse(2) { "" }))
                }
            }
            return ReplayAllFile(beats, answers, commands, clockWrites(commands))
        }

        /** Each clock write: the new offset is the written clock less the command's moment, rounded to a quarter hour. */
        private fun clockWrites(commands: List<ReplayCommand>): List<ClockWrite> {
            val writes = ArrayList<ClockWrite>()
            var offset = 0L
            for (c in commands.filter { it.group == "35" && it.op == "31" && it.payload.length >= 12 }) {
                val b = IntArray(6) { c.payload.substring(it * 2, it * 2 + 2).toInt(16) }
                val clock = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    clear()
                    set(2000 + b[0], b[1] - 1, b[2], b[3], b[4], b[5])
                }.timeInMillis
                val next = Math.round((clock - c.ms) / QUARTER_MS) * 900_000L
                writes.add(ClockWrite(c.ms, next, next != offset))
                offset = next
            }
            return writes
        }
    }
}
