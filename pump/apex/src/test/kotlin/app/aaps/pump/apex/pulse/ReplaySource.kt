package app.aaps.pump.apex.pulse

/** One ask of the driver under test and how the record answered it. [how]: window, repeat, earlier or none. */
class AskNote(val beat: Int, val kind: Char, val how: String, val readMs: Long?, val stale: Boolean)

/**
 * Hands the driver the answers of the record, heartbeat by heartbeat. The answers read in the
 * window of the heartbeat come first, in order; a further read of the same object gets the last
 * of them. An object with none in the window is answered with the latest one read before the
 * heartbeat came, except Status V1, which is then not answered. The window is the first seconds
 * after the heartbeat, see [ReplayAllFile.ANSWER_SECONDS]: an answer read later is first seen at the
 * next heartbeat. Only the [kinds] the pass has are served.
 */
class ReplaySource(private val file: ReplayAllFile, private val kinds: Set<Char>) {

    /** Called with the moment the record read an answer, just before the driver gets it: the clock writes up to then are made. */
    var beforeServe: (Long) -> Unit = {}

    val notes = ArrayList<AskNote>()
    private var beat = -1
    private val reads = HashMap<Char, Int>()

    fun begin(k: Int) {
        beat = k
        reads.clear()
    }

    fun next(kind: Char): RecordedAnswer? {
        val index = reads.merge(kind, 1, Int::plus)!! - 1
        val inside = file.inWindow(kind, beat)
        val at = file.beatTimes[beat]
        if (kind !in kinds) return note(kind, "refused", null, false)
        if (inside.isNotEmpty()) {
            val answer = if (kind == 'S') inside[0] else inside[minOf(index, inside.size - 1)]
            beforeServe(answer.ms)
            return answer.also { note(kind, if (index >= inside.size && kind != 'S') "repeat" else "window", it.ms, false) }
        }
        if (kind == 'S') return note(kind, "none", null, false)
        val earlier = file.answers[kind].orEmpty().lastOrNull { it.ms <= at } ?: return note(kind, "none", null, false)
        val stale = (kind == 'A' || kind == 'B') && file.commands.any { it.group == "35" && (it.op == "02" || it.op == "05") && it.ms > earlier.ms && it.ms <= at }
        note(kind, "earlier", earlier.ms, stale)
        return earlier
    }

    private fun note(kind: Char, how: String, readMs: Long?, stale: Boolean): RecordedAnswer? {
        notes.add(AskNote(beat, kind, how, readMs, stale))
        return null
    }
}
