package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.Atc3Const

/**
 * How long a bolus has to be held back so that the pump's record of it stands on its own.
 *
 * A bolus is confirmed by matching the pump's own record to the bolus the driver started — the dose
 * asked for and the minute the bolus started, which is the minute the pump stamps the record with.
 * Two boluses of ours that start in the same minute have records that nothing at all separates when
 * their doses are equal.
 *
 * So the two records are kept apart instead, at the only place where that is still possible — before
 * the second bolus is sent. **The bolus is held, not refused.** The delivery window simply blocks,
 * as it already does while the driver waits for a connection; the command goes out when it may. If
 * the user gives up while it waits, that is a cancel and it costs nothing, because at that point
 * nothing has been sent and no row exists.
 *
 * **Measured from the start of the previous bolus**, the moment the pump accepted it (`A1/55`),
 * because the start is what the pump stamps into the record. The next bolus is seldom asked for that
 * soon, so the hold rarely comes into play at all.
 *
 * **This covers the driver's own commands and nothing else.** A bolus given on the pump's keypad or
 * by another client is not spaced by anything the driver does.
 *
 * The instance holds one number and decides nothing else; the arithmetic is [waitFor] and is pure,
 * so it can be pinned without a pump, a clock or Android.
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
         * The wait itself, given when the previous bolus started and what time it is now.
         *
         * Never negative and never longer than [Atc3Const.BOLUS_SPACING_MS]. The ceiling is not
         * decoration: the phone's clock can move backwards, which puts the previous start in the
         * future, and an uncapped subtraction would then hold a bolus for as long as the jump was.
         *
         * @param lastStartedAtMs when the previous bolus started, 0 when there was none
         * @param nowMs the phone clock now
         */
        fun waitFor(lastStartedAtMs: Long, nowMs: Long): Long {
            if (lastStartedAtMs <= 0L) return 0L
            val since = nowMs - lastStartedAtMs
            if (since >= Atc3Const.BOLUS_SPACING_MS) return 0L
            return (Atc3Const.BOLUS_SPACING_MS - since).coerceIn(0L, Atc3Const.BOLUS_SPACING_MS)
        }
    }
}
