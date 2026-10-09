package app.aaps.pump.atc3.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

enum class Atc3StringNonKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = false
) : StringNonPreferenceKey {

    /**
     * What the driver keeps across a restart, see [app.aaps.pump.atc3.store.Atc3Store]. Not exportable:
     * one pump's position in its history.
     */
    State("atc3_state", ""),

    /** A document of [State] that did not read in full, kept for a look by hand; nothing reads it. */
    StateSetAside("atc3_state_set_aside", ""),

    // The keys below held the state before it was one document; they are read once and removed, see
    // [app.aaps.pump.atc3.store.Atc3LegacyState].
    HistoryLedger("atc3_history_ledger", ""),
    CheckLearned("atc3_check_learned", ""),
    BasalPeriod("atc3_basal_period", ""),
    LastAnswer("atc3_last_answer", ""),
    LinkStop("atc3_link_stop", ""),
}
