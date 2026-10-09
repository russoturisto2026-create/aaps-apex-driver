package app.aaps.pump.atc3

/**
 * The driver's own constants: its version, how long it waits for the pump, how often it reads
 * what, and the limits it keeps. What the pump defines is in [app.aaps.pump.atc3.protocol.Atc3Protocol].
 */
object Atc3Const {

    /** Minutes between two heartbeats; set on every link, since the pump takes it from whichever client set it last. */
    const val HEARTBEAT_PERIOD_MINUTES = 3

    /** Object byte of the heartbeat frame: the pump writes its heartbeat period, in minutes, there. */
    const val HEARTBEAT_OBJECT: Byte = HEARTBEAT_PERIOD_MINUTES.toByte()

    /** The driver's version, on its screen and in the log at start: the date of the change and its number that day, raised with every change. */
    const val DRIVER_VERSION = "2026.10.09.2"

    /** The firmware from which the pump has a Bluetooth password; older firmware is run under a standing warning. */
    val PASSWORD_FIRMWARE = listOf(1, 1, 1, 0)

    /** The voltage the battery shows as empty at; the pump reports volts only, the percentage is a straight line from here. */
    const val BATTERY_EMPTY_VOLTS = 1.2305

    /** How many percent the charge scale moves per volt. */
    const val BATTERY_PERCENT_PER_VOLT = 350.0

    /**
     * The pump profile slot that holds AAPS's basal schedule. AAPS owns the schedule; profiles stored in
     * the pump are not offered, since a fixed slot cannot carry an AAPS profile's percentage and shift.
     */
    const val DRIVER_PROFILE_INDEX = 0

    /** Refusals of the Bluetooth password before the driver stops asking: a refusal does not improve with tries. */
    const val AUTH_MAX_ATTEMPTS = 10

    /** How long the pump may take to answer a request: three times the slowest burst. */
    const val COMMAND_TIMEOUT_MS = 9_000L

    /** The wait between checks that a control command took: the status follows the acknowledgement by a couple of seconds. */
    const val EFFECT_POLL_INTERVAL_MS = 1_500L

    /** How long a command may go on trying before it gives the serial queue back, a tenth of the loop's cycle. */
    const val COMMAND_BUDGET_MS = 30_000L

    /** How many times a command may be sent before the driver gives up on it. */
    const val COMMAND_SEND_ATTEMPTS = 3

    /** Status reads after one send before sending again. */
    const val COMMAND_CHECK_ATTEMPTS = 3

    /** How many times to re-read the status while waiting for a control command to take effect. */
    const val EFFECT_POLL_ATTEMPTS = 8

    /** How old a Status V1 report may be: it is rebuilt once a minute. */
    const val STATUS_SNAPSHOT_AGE_MS = 60 * 1000L

    /** How far back a record may be and still be imported, milliseconds. */
    const val RECONCILE_MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** How far ahead a temporary basal the driver did not start is written while its length is not known: longer than AAPS's slowest poll. */
    const val TBR_HORIZON_MS = 20 * 60 * 1000L

    /** How far ahead a stop is written: it must not lapse by itself, and each tick pushes it on. */
    const val SUSPEND_HORIZON_MS = 24 * 60 * 60 * 1000L

    /** History reads in a row that may put our boluses a minute off before the pump's clock is written, see [app.aaps.pump.atc3.clock.Atc3ClockWatch]. */
    const val CLOCK_JOURNAL_MISSES_TO_SET = 2

    /**
     * The pump's clock against the phone's, measured on a report up to [STATUS_SNAPSHOT_AGE_MS] old: up to
     * this far apart it is put right quietly, at the start of AAPS, at a change of the loop's mode, every
     * [CLOCK_SYNC_EVERY_MS], and after [CLOCK_JOURNAL_MISSES_TO_SET]; never further without the user told.
     */
    const val CLOCK_QUIET_CORRECTION_MS = 5 * 60 * 1000L + STATUS_SNAPSHOT_AGE_MS

    /** How often the pump's clock is put on the phone's while the two are close, milliseconds. */
    const val CLOCK_SYNC_EVERY_MS = 8 * 60 * 60 * 1000L

    /** The least between two clock writes asked for by the loop's mode changing, milliseconds. */
    const val CLOCK_SYNC_MIN_GAP_MS = 5 * 60 * 1000L

    /** Up to this far apart the clock is put right at once and the user told; from here on nothing is written and the loop stops. */
    const val CLOCK_MAX_CORRECTION_MS = 55 * 60 * 1000L

    /** How old the knowledge of what the pump delivers may be before the loop's decision asks for a read. */
    const val STATUS_FRESH_MS = 4 * 60 * 1000L

    /** How old it may get before a glucose value alone asks for a read: the fallback for a cycle without a decision. */
    const val STATUS_STALE_MS = 5 * 60 * 1000L

    /** How often the battery is worth a read of its own: it moves over days. */
    const val BATTERY_READ_INTERVAL_MS = 60 * 60 * 1000L

    /** How often the alarm history is read when nothing is raised, for an alarm that came and went between two looks. */
    const val ALARM_READ_INTERVAL_MS = 30 * 60 * 1000L

    /** How long after the link comes up the first request waits: the pump ignores requests for a moment after connecting. */
    const val FIRST_REQUEST_SETTLE_MS = 3_000L

    /** How many times to re-read the history looking for the record of the bolus just delivered. */
    const val BOLUS_RECORD_POLL_ATTEMPTS = 4
}
