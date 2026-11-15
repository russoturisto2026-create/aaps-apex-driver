package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.protocol.ApexFinishedTbr
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.protocol.ApexTbrFields
import kotlin.math.abs

/**
 * What a reconciliation knows of the temporary basal beyond the status: the last finished one
 * (0x0B), when it was read, see [TbrGate]. Read by [TbrFactsReader], settled by `TbrJournal`.
 */
class TbrFacts(
    /** How and when the last finished temporary basal ended; null when it was not read, or none finished since a reset. */
    val end: End?,
    /** The last finished temporary basal was read. */
    val endAsked: Boolean
) {

    /** The temporary basal a status shows running: its start is the minute worked out from the status. */
    class Running(
        /** The start of its minute, the pump's wall clock as a key, see `ApexStamp`. */
        val startClockSeconds: Long,
        /** U/h, or the percentage, by [mode]. */
        val rate: Double,
        val mode: Int,
        val durationMinutes: Int
    ) {

        /**
         * The same temporary basal as [other]: the same rate, mode, length and start minute. The pump's
         * count of minutes gone and its clock change at slightly different moments, so the start worked
         * out from two statuses can be one minute apart.
         */
        fun sameAs(other: Running): Boolean =
            ApexProtocol.sameDose(rate, other.rate) && mode == other.mode && durationMinutes == other.durationMinutes &&
                abs(startClockSeconds - other.startClockSeconds) <= MINUTE_SECONDS

        /** What runs, with its unit, for logs. */
        val text: String get() = "$rate U/h for $durationMinutes min since key $startClockSeconds"
    }

    /** The last finished temporary basal. */
    class End(
        val startClockSeconds: Long,
        val rate: Double?,
        val percent: Int?,
        val durationMinutes: Int,
        val endClockSeconds: Long,
        /** It ran for its whole length. */
        val completed: Boolean,
        /** How it ended, for logs. */
        val resultText: String
    ) {

        /** What it was started at, with its unit, for logs. */
        val amountAsked: String get() = ApexTbrFields.amountText(rate, percent)

        /**
         * This record is the temporary basal [running] a status showed: the same rate or percentage, mode,
         * length and start minute. Its start is the clock of a status snapshot, which after a clock write
         * can hold seconds; the start worked out from a status can be a minute off. Any field apart, it is
         * another one: one that came after [running] and took its place.
         */
        fun sameAs(running: Running): Boolean {
            val absolute = running.mode == ApexProtocol.TbrPayload.MODE_ABSOLUTE.toInt()
            val amount = if (absolute) rate?.let { ApexProtocol.sameDose(it, running.rate) } else percent?.let { it.toDouble() == running.rate }
            return amount == true && durationMinutes == running.durationMinutes &&
                abs(Math.floorDiv(startClockSeconds, MINUTE_SECONDS) - Math.floorDiv(running.startClockSeconds, MINUTE_SECONDS)) <= 1
        }
    }

    companion object {

        const val MINUTE_SECONDS = 60L

        /** The start of the minute the temporary basal began in, worked out from the status: the minute of its clock less the minutes gone. */
        fun startFromStatus(status: ApexStatusV1): Long =
            Math.floorDiv(status.clockSeconds, MINUTE_SECONDS) * MINUTE_SECONDS - status.tbrElapsedMinutes * MINUTE_SECONDS

        /** The temporary basal [status] shows running; null when none runs. */
        fun runningOf(status: ApexStatusV1): Running? =
            if (!status.tbrActive) null
            else Running(startFromStatus(status), status.tbrRate, status.tbrMode, status.tbrDurationMinutes)

        fun endOf(finished: ApexFinishedTbr): End =
            End(finished.startClockSeconds, finished.rate, finished.percent, finished.durationMinutes, finished.endClockSeconds, finished.completed, finished.resultText)
    }
}
