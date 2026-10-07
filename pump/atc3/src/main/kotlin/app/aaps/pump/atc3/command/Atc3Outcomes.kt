package app.aaps.pump.atc3.command

/** What became of a bolus command. */
sealed interface Atc3BolusOutcome {

    /** The pump refused the command: nothing was delivered. */
    data object Refused : Atc3BolusOutcome

    /** The command never reached the pump, or it never answered: nothing is known to be delivered. */
    data object NotSent : Atc3BolusOutcome

    /**
     * The pump accepted the bolus and it was followed to its end.
     *
     * [reportedUnits] is from the completion frame when it came, else from the last progress frame.
     * [cancelled]: the user asked for it to stop. [completed]: the pump closed it with its completion
     * frame; with [sawProgress] that is a delivered bolus, without it the bolus was cut short and its
     * record says what went in. [acceptedAtMs]: the phone's clock at the pump's acceptance, the start of
     * the bolus and the minute its record carries.
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
 * What became of a temporary basal command: [acceptedAtMs], when the pump accepted it, is when it
 * began; [durationMinutes] is the duration sent, in whole quarter hours.
 */
data class Atc3TbrResult(
    val acceptedAtMs: Long,
    val rate: Double,
    val durationMinutes: Int,
    val failure: String?
)

/** Why a command did not take, and whether the pump said no rather than saying nothing. */
data class Atc3Failure(val text: String, val refused: Boolean = false) {

    override fun toString(): String = text
}

/** What became of cancelling a temporary basal: when the pump accepted it, 0 when it never did. */
data class Atc3TbrCancel(val acceptedAtMs: Long, val failure: String?)
