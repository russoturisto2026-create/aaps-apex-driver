package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import org.mockito.Mockito.mock

/** One line the driver logged. */
class LogLine(val level: String, val message: String)

/** One event the driver traced: its name and fields, as the trace call gave them. */
class TraceLine(val name: String, val fields: Map<String, Any?>) {

    override fun toString() = "$name ${fields.entries.joinToString(" ") { "${it.key}=${it.value}" }}"
}

/** A logger that keeps what the driver says at info level and above. Debug lines are dropped. */
class ReplayLog(inner: AAPSLogger = mock(AAPSLogger::class.java)) : AAPSLogger by inner {

    private val lines = ArrayList<LogLine>()

    /** Everything kept so far; the caller reads the part since its last look by index. */
    val all: List<LogLine> get() = lines

    override fun info(tag: LTag, message: String) {
        lines.add(LogLine("INFO", message))
    }

    override fun warn(tag: LTag, message: String) {
        lines.add(LogLine("WARN", message))
    }

    override fun error(tag: LTag, message: String) {
        lines.add(LogLine("ERROR", message))
    }

    override fun error(message: String) {
        lines.add(LogLine("ERROR", message))
    }
}
