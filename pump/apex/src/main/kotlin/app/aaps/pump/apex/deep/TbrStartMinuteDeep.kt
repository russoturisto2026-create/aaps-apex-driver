package app.aaps.pump.apex.deep

import kotlin.math.abs
import app.aaps.pump.apex.account.time.ApexClock

/**
 * The start minute of a temporary basal, compared with one minute of slack.
 *
 * The problem. The status does not carry the start of the running temporary basal: it is worked out
 * as the minute of the status clock less the minutes gone. The pump moves its clock and its count of
 * minutes gone at slightly different moments, so two statuses of the same temporary basal can give
 * starts one minute apart. The last finished record (0x0B) carries the start as a stamp, which after
 * a clock write can hold seconds, so it too can stand one minute off the start worked out from a
 * status. Compared exactly, the same temporary basal would read as another's: a false row and a
 * false end.
 *
 * When it applies. Every time two starts of a temporary basal are compared: the gate between two
 * statuses, see `TbrGate`, and the last finished record against the one a status showed, see
 * [ForeignTbrDeep].
 *
 * The way out. Starts up to one minute apart are the same start. Two real temporary basals with the
 * same rate, mode and length, begun one minute apart, cannot be told apart this way; the rate, mode
 * and length are compared too, so only such a pair is taken as one.
 */
object TbrStartMinuteDeep {

    /** Two starts worked out from statuses, pump wall clock in seconds: the same when at most one minute apart. */
    fun sameStatusStart(firstSeconds: Long, secondSeconds: Long): Boolean = abs(firstSeconds - secondSeconds) <= ApexClock.MINUTE_SECONDS

    /** The start of a record, [recordSeconds], and a start worked out from a status, [statusSeconds]: the same when their minutes are at most one apart. */
    fun sameRecordStart(recordSeconds: Long, statusSeconds: Long): Boolean =
        abs(Math.floorDiv(recordSeconds, ApexClock.MINUTE_SECONDS) - Math.floorDiv(statusSeconds, ApexClock.MINUTE_SECONDS)) <= 1
}
