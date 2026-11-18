package app.aaps.pump.apex.command

import android.os.SystemClock

/**
 * Waiting for the result of our own command. The pump's word on a command is only the beginning:
 * what counts is its state read after. The state follows the word by a moment, so it is read a few
 * times, until it shows that the command took.
 *
 * This is not a fault, and not the rule of reading an object again (`ReadAgainDeep`): every read here
 * may be sound, the pump is only not done yet. So each command has its own way of waiting: how many
 * reads, how far apart, and whether a read the pump does not answer ends the wait or not.
 */
object AwaitResult {

    /** The last state read, [last], null when none was read; [took] true when it showed the command took. */
    class Awaited<T>(val last: T?, val took: Boolean)

    /**
     * Read with [read] up to [tries] times, [pauseMs] before each read, until [took] says the command
     * took. A read that brings nothing ends the wait when [silenceEnds], else the next read is made.
     */
    fun <T : Any> until(tries: Int, pauseMs: Long, silenceEnds: Boolean, read: () -> T?, took: (T) -> Boolean): Awaited<T> {
        var last: T? = null
        repeat(tries) {
            if (pauseMs > 0) SystemClock.sleep(pauseMs)
            val got = read()
            if (got == null) {
                if (silenceEnds) return Awaited(last, took = false)
                return@repeat
            }
            last = got
            if (took(got)) return Awaited(got, took = true)
        }
        return Awaited(last, took = false)
    }
}
