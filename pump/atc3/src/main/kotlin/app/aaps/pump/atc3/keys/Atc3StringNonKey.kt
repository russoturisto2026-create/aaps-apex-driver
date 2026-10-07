package app.aaps.pump.atc3.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey
import app.aaps.pump.atc3.basal.Atc3BasalPeriod
import app.aaps.pump.atc3.history.Atc3HistoryLedger
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.link.Atc3LinkWatch

enum class Atc3StringNonKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = false
) : StringNonPreferenceKey {

    /**
     * The history ledger, see [app.aaps.pump.atc3.history.Atc3HistoryLedger], on disk so that a bolus
     * interrupted by the app being killed is still known. Not exportable: one pump's history position.
     */
    HistoryLedger("atc3_history_ledger", ""),

    /** The boluses learned of late, see [app.aaps.pump.atc3.history.Atc3HistorySync], kept across a restart with what they are counted from. */
    CheckLearned("atc3_check_learned", ""),

    /** The half hour under way and the one closed last, see [app.aaps.pump.atc3.basal.Atc3BasalPeriod.State], kept across a restart. */
    BasalPeriod("atc3_basal_period", ""),

    /** The pump's last answer, see [app.aaps.pump.atc3.link.Atc3LinkWatch.Stop], kept so a silence is counted across a restart. */
    LastAnswer("atc3_last_answer", ""),

    /** The stop the pump is held in for want of an answer, empty when none, kept across a restart. */
    LinkStop("atc3_link_stop", ""),
}
