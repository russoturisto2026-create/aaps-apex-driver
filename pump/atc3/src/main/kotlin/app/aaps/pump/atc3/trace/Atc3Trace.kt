package app.aaps.pump.atc3.trace

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Which part of the driver, or of AAPS around it, a trace line came from. */
enum class Atc3TraceCat {

    /** The Bluetooth link itself: connecting, ready, dropped, bytes each way. */
    BLE,

    /** One request and its answer, the driver's unit of work with the pump. */
    EXCH,

    /** A connection, from the moment something asked for one until the link closed. */
    SESS,

    /** A method AAPS called on the driver, and what the driver answered. */
    DRV,

    /** What the pump said about itself, once per status read. */
    STATE,

    /** What reached the AAPS database, and what the driver decided not to send. */
    HIST,

    /** The temporary basal record the driver keeps in step with the pump. */
    TBR,

    /** Events AAPS itself raises: glucose, recalculation, loop, queue, phone battery. */
    AAPS
}

/**
 * A machine readable account of what the driver and AAPS do, one line per event, for questions of
 * cadence a script has to add up. Every line has one shape:
 *
 * ```
 * APXT|1|1756100000123|42|r8a3cs3|EXCH|done|what=read_0x12 ok=1 ms=214
 * ```
 *
 * marker, format version, epoch milliseconds, sequence number, run and connection, category, event,
 * then `key=value` pairs. Its own time, since the log's has no date; its own sequence, so that a gap
 * shows a hole in the log rather than a quiet stretch; and the real clock, not `DateUtil`.
 */
@Singleton
class Atc3Trace @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences
) {

    private val seq = AtomicLong(0)
    private val session = AtomicLong(0)

    /** Which run of the app a line belongs to: sequence and connection number start again with the process. */
    private val run = "r%04x".format(System.currentTimeMillis() / 1000L and 0xFFFF)

    private var sessionStartedAt = 0L
    private val exchanges = AtomicInteger(0)
    private val exchangeFailures = AtomicInteger(0)
    private val bytesOut = AtomicInteger(0)
    private val bytesIn = AtomicInteger(0)
    private val foreignFrames = AtomicInteger(0)

    /** The connection every line is currently attributed to, zero before the first one. */
    val sessionId: Long get() = session.get()

    /** False when the user has turned the trace off; nothing is written and nothing is counted. */
    val enabled: Boolean get() = preferences.get(Atc3BooleanKey.Trace)

    /** How many of each event was written while the trace was on: the shape of a long run without fetching the lines. */
    private val tally = ConcurrentHashMap<String, Int>()

    /** A snapshot of the tally, safe to read while events are still being written. */
    fun tally(): Map<String, Int> = tally.toMap()

    fun now(): Long = System.currentTimeMillis()

    fun since(startMs: Long): Long = System.currentTimeMillis() - startMs

    fun event(cat: Atc3TraceCat, event: String, vararg fields: Pair<String, Any?>) {
        if (!enabled) return
        tally.merge(event, 1, Int::plus)
        val body = StringBuilder(96)
        body.append(MARKER).append('|').append(VERSION).append('|')
            .append(System.currentTimeMillis()).append('|')
            .append(seq.incrementAndGet()).append('|')
            .append(run).append('s').append(session.get()).append('|')
            .append(cat.name).append('|').append(clean(event))
        if (fields.isNotEmpty()) {
            body.append('|')
            for ((index, field) in fields.withIndex()) {
                if (index > 0) body.append(' ')
                body.append(clean(field.first)).append('=').append(clean(field.second))
            }
        }
        aapsLogger.info(LTag.PUMP, body.toString())
    }

    /** Begin a connection, counted from the asking: failing to connect is part of what it costs. */
    fun sessionOpen(reason: String) {
        session.incrementAndGet()
        sessionStartedAt = System.currentTimeMillis()
        exchanges.set(0)
        exchangeFailures.set(0)
        bytesOut.set(0)
        bytesIn.set(0)
        foreignFrames.set(0)
        event(Atc3TraceCat.SESS, "open", "reason" to reason)
    }

    /** End the connection and report what it cost: the line the report adds up. */
    fun sessionClose(reason: String) {
        event(
            Atc3TraceCat.SESS, "close",
            "reason" to reason,
            "ms" to if (sessionStartedAt == 0L) -1 else System.currentTimeMillis() - sessionStartedAt,
            "exch" to exchanges.get(),
            "failed" to exchangeFailures.get(),
            "out" to bytesOut.get(),
            "in" to bytesIn.get(),
            "foreign" to foreignFrames.get()
        )
        sessionStartedAt = 0L
    }

    /** An answer nothing was waiting for: zero unless another client talks to the same pump. */
    fun countForeign() {
        if (enabled) foreignFrames.incrementAndGet()
    }

    fun countExchange(ok: Boolean) {
        if (!enabled) return
        exchanges.incrementAndGet()
        if (!ok) exchangeFailures.incrementAndGet()
    }

    fun countOut(bytes: Int) {
        if (enabled) bytesOut.addAndGet(bytes)
    }

    fun countIn(bytes: Int) {
        if (enabled) bytesIn.addAndGet(bytes)
    }

    private fun clean(value: Any?): String {
        val text = when (value) {
            null       -> "-"
            is Boolean -> if (value) "1" else "0"
            is Double  -> String.format(Locale.ROOT, ROUNDED, value)
            else       -> value.toString()
        }
        if (text.isEmpty()) return "-"
        val out = StringBuilder(text.length)
        for (ch in text) out.append(if (ch == '|' || ch == '=' || ch.isWhitespace()) '_' else ch)
        return out.toString()
    }

    companion object {

        /** What a reader greps for. Deliberately unlike any word the ordinary log uses. */
        const val MARKER = "APXT"

        /** Bumped when the shape of a line changes, so an old report refuses a new log loudly. */
        const val VERSION = 1

        /** Doses and rates to three decimals, always with a dot so the report needs no locale. */
        private const val ROUNDED = "%.3f"
    }
}
