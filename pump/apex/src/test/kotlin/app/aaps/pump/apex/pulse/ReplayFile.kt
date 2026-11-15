package app.aaps.pump.apex.pulse

import java.io.File

/** One heartbeat of a recorded pump with the answers read after it; a null answer means the pump gave no whole one. */
class ReplayBeat(val heartbeatMs: Long) {

    var statusMs: Long? = null
    var statusHex: String? = null
    var journalMs: Long? = null
    var journalHex: List<String>? = null
}

/** A command another client sent to the pump. The driver under test sends nothing; these are the truth to check against. */
class ReplayCommand(val ms: Long, val op: String, val payload: String, val group: String = "35")

/**
 * A recorded exchange: one item per line, in time order. `H ms` a heartbeat, `S ms hex` the status
 * read after it, `J ms hex...` the bolus journal read after it, `C ms op payload` another client's command.
 */
class ReplayFile(val beats: List<ReplayBeat>, val commands: List<ReplayCommand>) {

    companion object {

        /** The environment variable that names the file; the test is skipped when it is not set. */
        const val VARIABLE = "APEX_REPLAY_FILE"

        /** The recorded file named by the environment, or null when it is not set or not there. */
        fun fromEnvironment(): ReplayFile? {
            val path = System.getenv(VARIABLE)?.takeIf { it.isNotBlank() } ?: return null
            val file = File(path).takeIf { it.isFile } ?: return null
            return parse(file.readLines())
        }

        fun parse(lines: List<String>): ReplayFile {
            val beats = ArrayList<ReplayBeat>()
            val commands = ArrayList<ReplayCommand>()
            for (line in lines) {
                val parts = line.trim().split(' ').filter { it.isNotEmpty() }
                if (parts.size < 2) continue
                val ms = parts[1].toLongOrNull() ?: continue
                when (parts[0]) {
                    "H" -> beats.add(ReplayBeat(ms))
                    "S" -> beats.lastOrNull()?.let { it.statusMs = ms; it.statusHex = parts.getOrNull(2) }
                    "J" -> beats.lastOrNull()?.let { it.journalMs = ms; it.journalHex = parts.drop(2) }
                    "C" -> commands.add(ReplayCommand(ms, parts.getOrElse(2) { "" }, parts.getOrElse(3) { "" }))
                }
            }
            return ReplayFile(beats, commands)
        }
    }
}
