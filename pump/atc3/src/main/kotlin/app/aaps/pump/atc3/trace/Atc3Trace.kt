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
 * A machine readable account of everything the driver and AAPS do, in one line per event.
 *
 * The ordinary log already says a great deal, but it says it in prose written for whoever is
 * reading that one line. Questions about *cadence* — how often the pump is woken, how much of that
 * is useful, how long each connection holds the radio, what order the loop and the driver actually
 * run in — need a stream that a script can add up, and that is what this is.
 *
 * Every line looks the same:
 *
 * ```
 * APXT|1|1756100000123|42|r8a3cs3|EXCH|done|what=read_0x12 ok=1 ms=214
 * ```
 *
 * marker, format version, phone clock in milliseconds, a sequence number, the run of the app and
 * the connection this belongs to, category, event, then space separated `key=value` pairs. Pipes and whitespace are
 * stripped from values so the shape never varies, whatever gets logged.
 *
 * Three deliberate choices:
 *
 * - **Its own timestamp.** The log file writes `HH:mm:ss.SSS` and no date, so a session that runs
 *   past midnight cannot be ordered from the log's own stamps. Every line carries epoch
 *   milliseconds of its own.
 * - **Its own sequence number.** The log rolls over at 25 MB and Android's logcat drops lines under
 *   load. A gap in the sequence is how the reader knows it is looking at a hole rather than at a
 *   quiet stretch.
 * - **[System.currentTimeMillis], not `DateUtil`.** This measures the real world, including the
 *   parts of it a test would rather pretend about.
 */
@Singleton
class Atc3Trace @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences
) {

    private val seq = AtomicLong(0)
    private val session = AtomicLong(0)

    /**
     * Which run of the app this line belongs to.
     *
     * The sequence and the connection number both start again when the process does, and AAPS is
     * restarted often enough while a driver is being worked on that a short log routinely holds
     * several runs. Without this, the third connection of one run and the third of the next are
     * both `s3`, and a report would add together two connections that never coexisted. Four hex
     * digits of the start time tell runs apart and carry nothing about the phone or the pump.
     */
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

    /**
     * How many of each kind of event has been written since the trace was switched on.
     *
     * Counted here because every event already passes through one place, so nothing has to be
     * instrumented twice and nothing can be counted that was not also written down. It follows
     * that this counts only while the trace is on: the early return above is the switch, and a
     * tally that kept running with the trace off would describe a period nobody can go and read.
     *
     * It is a summary, not a record. The lines themselves are in the log; this exists so that the
     * shape of a long run - how many connections, how much went unanswered, whether anything was
     * refused - can be seen without going to fetch them.
     */
    private val tally = ConcurrentHashMap<String, Int>()

    /** A snapshot of the tally, safe to read while events are still being written. */
    fun tally(): Map<String, Int> = tally.toMap()

    /** Forget what has been counted, which is what switching the trace on again means. */
    fun resetTally() = tally.clear()

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

    /**
     * Begin a new connection.
     *
     * Counting starts from zero here rather than at the moment the link comes up, because the time
     * spent failing to connect is part of what a connection costs.
     */
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

    /**
     * End the connection and report what it cost.
     *
     * This is the line the report adds up: a connection that carried no exchanges, or whose
     * exchanges taught the driver nothing, is one the pump was woken for nothing.
     */
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

    /**
     * An answer arrived that nothing was waiting for.
     *
     * On a link with only this driver on it that number is zero. It is not zero when a second
     * client is talking to the same pump, and that is the only way to know: notifications on a
     * shared connection reach every app subscribed to them, and a response frame carries no
     * identity - no request id, no sequence, no serial - so an answer of theirs is indistinguishable
     * from an answer of ours.
     */
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
