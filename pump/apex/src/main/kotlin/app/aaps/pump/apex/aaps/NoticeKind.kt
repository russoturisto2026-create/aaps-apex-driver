package app.aaps.pump.apex.aaps

/** What a notice to the user is about. Every notice is posted and kept in the treatment history. */
enum class NoticeKind {

    /** The pump did not write one bolus record; the amount was taken from the bolus field of the daily statistics. */
    JOURNAL_FAULT,

    /** The pump did not write one bolus record, and the daily statistics do not tell its amount: nothing was written. */
    JOURNAL_FAULT_UNKNOWN,

    /** When the journal is read whole, one day has more than one faulty record: what its bolus field leaves went to the first of them. */
    FAULTS_SHARED,

    /** Our bolus left no record: by the move of the statistics the pump delivered what is written, nothing as a rule. */
    OURS_NO_RECORD,

    /** The pump reported a bolus of more than its reservoir held: nothing was written, see `BolusReconciliation`. */
    IMPLAUSIBLE_ROW,

    /** The bolus records did not sum to the statistics and no record could take the rest: a line is drawn, the loop opened, see `MismatchLine`. */
    JOURNAL_MISMATCH,

    /** A second line in one day: a serious error of the driver, the user is asked to tell the developer. */
    JOURNAL_MISMATCH_AGAIN
}
