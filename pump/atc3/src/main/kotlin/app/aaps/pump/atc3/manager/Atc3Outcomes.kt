package app.aaps.pump.atc3.manager

/** What became of a bolus command. */
sealed interface Atc3BolusOutcome {

    /** The pump answered that it will not carry the command out. Nothing was delivered. */
    data object Refused : Atc3BolusOutcome

    /** The command never reached the pump, or it never answered. Nothing is known to be delivered. */
    data object NotSent : Atc3BolusOutcome

    /**
     * The pump accepted the command and delivery was followed to its end.
     *
     * [reportedUnits] is the total from the completion frame `A1/AA` when it came, otherwise the
     * last progress frame `A1/A0`.
     *
     * [cancelled] is set when the user asked for the bolus to stop. A bolus that delivered less
     * than was asked for is a failure, except when that is exactly what was wanted.
     *
     * [completed] is true when the pump closed the bolus with its completion frame `A1/AA`, and
     * [sawProgress] when at least one progress frame `A1/A0` of this bolus arrived. Both together
     * mean the bolus was delivered; without the completion frame it was cut short, by a cancel, an
     * alarm or the link, and what went in has to come from the pump's bolus history.
     *
     * [acceptedAtMs] is the phone clock at the pump's answer `A1/55` to the command, which is the
     * start of the bolus and the minute its history record will carry.
     */
    data class Delivered(
        val reportedUnits: Double,
        val cancelled: Boolean = false,
        val completed: Boolean = false,
        val sawProgress: Boolean = false,
        val acceptedAtMs: Long = 0L
    ) : Atc3BolusOutcome
}

/**
 * What became of a temporary basal command.
 *
 * [acceptedAtMs] is when the pump accepted it, which is when the temporary basal really began, and
 * [durationMinutes] is the duration actually sent, in whole quarter hours.
 */
data class Atc3TbrResult(
    val acceptedAtMs: Long,
    val rate: Double,
    val durationMinutes: Int,
    val failure: String?
)
