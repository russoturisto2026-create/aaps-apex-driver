package app.aaps.pump.atc3.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

enum class Atc3StringNonKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = false
) : StringNonPreferenceKey {

    /**
     * The driver's history ledger, see [app.aaps.pump.atc3.history.Atc3HistoryLedger].
     *
     * It has to outlive the process: a bolus interrupted by the app being killed is only
     * recoverable if the temporary id that stands for it is still on disk when the driver comes
     * back. Not exportable, because the ledger describes one pump's history position and restoring
     * it onto another phone or another pump would suppress or duplicate treatments.
     */
    HistoryLedger("atc3_history_ledger", ""),

    /**
     * The anchor of the delivery check, see [app.aaps.pump.atc3.history.Atc3StateCheck.encode]. Kept
     * across a restart, so that what the journal owes since the anchor is not lost with the process.
     */
    CheckAnchor("atc3_check_anchor", ""),

    /**
     * The boluses learned since the anchor, see [app.aaps.pump.atc3.history.Atc3HistorySync]. Kept with
     * the anchor: the one is counted from the other.
     */
    CheckLearned("atc3_check_learned", ""),

    /**
     * The half hour under way and the one closed last, in the exact basal mode, see
     * [app.aaps.pump.atc3.history.Atc3BasalPeriod.State]. Kept across a restart: the period is
     * counted from a read of the pump's count, and that read cannot be had again.
     */
    BasalPeriod("atc3_basal_period", ""),
}
