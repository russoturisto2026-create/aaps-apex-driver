package app.aaps.pump.apex.account

/** What a notice to the user is about. Every notice is posted and kept in the treatment history. */
enum class NoticeKind {

    /** The pump did not write one bolus record; the amount was taken from the bolus field of the daily statistics. */
    JOURNAL_FAULT,

    /** The pump did not write one bolus record, and the daily statistics do not tell its amount: nothing was written. */
    JOURNAL_FAULT_UNKNOWN,

    /** The boluses of the pump were last held against AAPS too long ago: the account of boluses started clean, and the insulin on board may be wrong. */
    JOURNAL_CLEAN_START,

    /** The whole bolus journal of the pump shows nothing of the records kept: the account of boluses started anew from what the pump shows now. */
    JOURNAL_UNLAID,

    /** The pump reported a bolus of more than its reservoir held: nothing was written, see `PlausibleRecordDeep`. */
    IMPLAUSIBLE_ROW,

    /** The pump's count moved by more than its maximum basal can give over a basal period: the rest was written as a bolus, see `BasalPeriodDeep`. */
    BASAL_ABOVE_MAX
}
