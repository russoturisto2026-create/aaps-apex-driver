package app.aaps.pump.apex.state

/** What the screen shows of the last picture: a few numbers, taken when the picture was read. */
class PictureSummary(
    val heartbeatAtMs: Long,
    /** Today's day of the daily statistics, by kind; null when the answer had no day. */
    val dayBolusUnits: Double?,
    val dayBasalUnits: Double?,
    val dayTbrUnits: Double?,
    /** The newest bolus record: when, as a moment, and how much was delivered; null when the journal is empty. */
    val topBolusAtMs: Long?,
    val topBolusUnits: Double?,
    /**
     * The reconciliation of this picture succeeded, see `Reconciliation`. False while a bolus runs, or
     * when the journal waits for the next read: the pump counts what AAPS has no row for yet.
     */
    val reconciled: Boolean,
    /** Where the comparison of the day starts, see [DayWindow]; null when not [reconciled] or with no day in the answer. */
    val dayFrom: DayWindow.Count? = null
)
