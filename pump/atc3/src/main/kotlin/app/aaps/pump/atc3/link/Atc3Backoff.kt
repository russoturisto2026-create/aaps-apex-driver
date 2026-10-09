package app.aaps.pump.atc3.link

/**
 * The wait before the next attempt at a link. [Atc3BLE] changes it under its own lock, together with
 * the link's end, so a connect that sees the link idle sees the wait too; it is read without one.
 *
 * @param now the clock, milliseconds
 */
internal class Atc3Backoff(private val now: () -> Long = System::currentTimeMillis) {

    /** Attempts in a row that never gave a usable link: what sets the wait before the next. */
    @Volatile var failures = 0
        private set

    @Volatile private var blockedUntil = 0L

    /** How long connecting is held off, milliseconds, 0 when it is allowed. */
    val remainingMs: Long get() = (blockedUntil - now()).coerceAtLeast(0L)

    /**
     * Set the wait after a link ended.
     *
     * @param wasReady the link had been usable: its end is no failure
     * @param stackAnswered the stack answered the attempt; false when it never did
     * @param released the driver let go of a link the stack had handed over
     * @return the wait set, milliseconds
     */
    fun afterEnd(wasReady: Boolean, stackAnswered: Boolean, released: Boolean): Long {
        // Only an attempt that never gave a usable link counts towards the wait.
        val failedWait = when {
            wasReady      -> 0L
            // The stack answered: it works, and the pump is out of reach. A flat wait.
            stackAnswered -> ANSWERED_MS
            // The stack said nothing: it is wedged, and only time helps, more of it each round.
            else          -> (BASE_MS shl failures.coerceAtMost(MAX_SHIFT)).coerceAtMost(CAP_MS)
        }
        // A link the driver let go of may still be up on the radio: asked for again at once, the stack
        // hands that one back half set up, and the attempt hangs until its step times out.
        val wait = maxOf(failedWait, if (released) RELEASE_SETTLE_MS else 0L)
        failures = if (wasReady || stackAnswered) 0 else failures + 1
        blockedUntil = if (wait == 0L) 0L else now() + wait
        return wait
    }

    /** Hold connecting off after the stack refused to start an attempt at all: it would refuse again at once. */
    fun afterRefusal(): Long {
        blockedUntil = maxOf(blockedUntil, now() + ANSWERED_MS)
        return ANSWERED_MS
    }

    companion object {

        /** The wait after the driver lets a link go, before it asks for a new one. */
        const val RELEASE_SETTLE_MS = 3_000L

        /** The wait after an attempt the stack never answered; it doubles from here. */
        private const val BASE_MS = 5_000L

        /** The wait after an attempt the stack refused: flat, the stack works. */
        const val ANSWERED_MS = 15_000L
        private const val CAP_MS = 60_000L

        /** Keeps the doubling from overflowing. */
        private const val MAX_SHIFT = 5
    }
}
