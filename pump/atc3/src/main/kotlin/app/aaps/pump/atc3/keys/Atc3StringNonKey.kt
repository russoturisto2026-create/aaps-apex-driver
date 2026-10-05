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
     * The boluses learned of late, see [app.aaps.pump.atc3.history.Atc3HistorySync]. Kept across a restart:
     * a half hour of the exact basal mode and a stop for want of an answer are both counted from a
     * read before it.
     */
    CheckLearned("atc3_check_learned", ""),

    /**
     * The half hour under way and the one closed last, in the exact basal mode, see
     * [app.aaps.pump.atc3.history.Atc3BasalPeriod.State]. Kept across a restart: the period is
     * counted from a read of the pump's count, and that read cannot be had again.
     */
    BasalPeriod("atc3_basal_period", ""),

    /**
     * The pump's last answer: the moment of the status read and the pump's count in it, see
     * [app.aaps.pump.atc3.history.Atc3LinkWatch.Stop]. Kept across a restart, so that a pump found gone
     * when AAPS comes back still has a read its silence is counted from.
     */
    LastAnswer("atc3_last_answer", ""),

    /**
     * The stop the pump is held in for want of an answer, see [app.aaps.pump.atc3.history.Atc3LinkWatch];
     * empty when there is none. Kept across a restart: the loop stays stopped and the stretch is
     * still to be written when the pump answers.
     */
    LinkStop("atc3_link_stop", ""),
}
