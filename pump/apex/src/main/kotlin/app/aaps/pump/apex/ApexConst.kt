package app.aaps.pump.apex

/**
 * The driver's own constants: its version, how long it waits for the pump and how many times it
 * tries. What the pump defines is in [app.aaps.pump.apex.protocol.ApexProtocol].
 */
object ApexConst {

    /** The driver's version, on its screen and in the log at start: the date of the change and its number that day. */
    const val DRIVER_VERSION = "v4 2026.11.15.1"

    /** The protocol from which the pump can be given a Bluetooth password. */
    const val PASSWORD_PROTOCOL_MAJOR = 4
    const val PASSWORD_PROTOCOL_MINOR = 12

    /**
     * The periods of the heartbeat the user can choose, minutes, see `ApexIntKey.HeartbeatPeriod`. Each
     * of them divides the half hour, so the grid of the heartbeat holds :00 and :30. The period is set on
     * every link, since the pump takes it from whichever client set it last.
     */
    val HEARTBEAT_PERIODS = listOf(1, 2, 3, 5)

    /** The period of the heartbeat until the user chooses another, minutes. */
    const val HEARTBEAT_PERIOD_DEFAULT = 5

    /**
     * The pump profile slot that holds AAPS's basal schedule. AAPS owns the schedule; profiles stored in
     * the pump are not offered.
     */
    const val DRIVER_PROFILE_INDEX = 0

    /** Refusals of the Bluetooth password before the driver stops asking: a refusal does not improve with tries. */
    const val AUTH_MAX_ATTEMPTS = 10

    /** How long the pump may take to answer a request: three times the slowest burst. */
    const val COMMAND_TIMEOUT_MS = 9_000L

    /**
     * The least time between the last frame the pump answered and the next request: the pump falls
     * silent for minutes when requests crowd it. Counted from the frame, not from the request.
     */
    const val REQUEST_GAP_MS = 2_100L

    /**
     * The window kept clear around the pump's next heartbeat, which comes on its beat to within half a
     * second: a request met by it lost its answer, or the heartbeat was lost. No request starts this
     * long before the heartbeat is due...
     */
    const val HEARTBEAT_CLEAR_BEFORE_MS = 1_500L

    /** ...until it came, or this long after it was due. */
    const val HEARTBEAT_CLEAR_AFTER_MS = 700L

    /** The pump's heartbeat comes on second 0 of its clock, and reaches the phone this much later. */
    const val HEARTBEAT_LATENCY_MS = 300L

    /** How far the pump's clock, as its heartbeat shows it, may be off before the clock is written again. */
    const val CLOCK_ERROR_LIMIT_MS = 500L

    /** How long after the link comes up the first request waits: the pump ignores requests for a moment after connecting. */
    const val FIRST_REQUEST_SETTLE_MS = 3_000L

    /** The wait between checks that a control command took: the status follows the acknowledgement by a couple of seconds. */
    const val EFFECT_POLL_INTERVAL_MS = 1_500L

    /** How many times to re-read the status while waiting for a control command to take effect. */
    const val EFFECT_POLL_ATTEMPTS = 8

    /** The screen-off time set up for AAPS, tenths of a second: 15 s. */
    const val SCREEN_TIMEOUT_FOR_AAPS_RAW = 150
}
