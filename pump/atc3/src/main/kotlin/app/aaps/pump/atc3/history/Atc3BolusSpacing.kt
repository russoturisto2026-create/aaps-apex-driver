package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.Atc3Const

/**
 * How long a bolus of ours is held back so that its record stands apart from the previous one's: a
 * record is matched by its dose and start minute, and two equal boluses of one minute have records
 * nothing separates. Held, not refused; counted from the previous bolus's start, which is what the
 * record carries. The arithmetic, [waitFor], needs no clock.
 */
class Atc3BolusSpacing {

    /** Phone clock at the start of the last bolus the driver gave, or 0 when there has been none. */
    private var lastStartedAtMs: Long = 0L

    /** Remember that the pump accepted a bolus of ours at [startedAtMs] on the phone clock. */
    fun started(startedAtMs: Long) {
        lastStartedAtMs = startedAtMs
    }

    /** How long a bolus asked for at [nowMs] must wait, milliseconds; zero when it may go now. */
    fun waitMs(nowMs: Long): Long = waitFor(lastStartedAtMs, nowMs)

    companion object {

        /**
         * The wait, never negative nor longer than [Atc3Const.BOLUS_SPACING_MS], a phone clock moved back included.
         *
         * @param lastStartedAtMs when the previous bolus started, 0 when there was none
         * @param nowMs the phone's clock now
         */
        fun waitFor(lastStartedAtMs: Long, nowMs: Long): Long {
            if (lastStartedAtMs <= 0L) return 0L
            val since = nowMs - lastStartedAtMs
            if (since >= Atc3Const.BOLUS_SPACING_MS) return 0L
            return (Atc3Const.BOLUS_SPACING_MS - since).coerceIn(0L, Atc3Const.BOLUS_SPACING_MS)
        }
    }
}
