package app.aaps.pump.apex.command

/** What became of a bolus command. */
sealed interface ApexBolusOutcome {

    /** The pump refused the command: nothing was delivered. */
    data object Refused : ApexBolusOutcome

    /**
     * The command never reached the pump, or it never answered and sent no progress frames: nothing
     * is known to be delivered, and no row is made.
     */
    data object NotSent : ApexBolusOutcome

    /**
     * The pump accepted the bolus and it was followed to its end.
     *
     * [reportedUnits] is from the completion frame when it came, else from the last progress frame.
     * [cancelled]: the pump accepted the user's cancel. [completed]: the pump closed it with its completion
     * frame.
     */
    data class Delivered(
        val reportedUnits: Double,
        val cancelled: Boolean = false,
        val completed: Boolean = false
    ) : ApexBolusOutcome
}

/** A clock write: the moment written, UTC milliseconds, and what went wrong, when something did. */
data class ApexClockWrite(val atMs: Long, val failure: String?)

/** Why a command did not take. */
data class ApexFailure(val text: String) {

    override fun toString(): String = text
}
