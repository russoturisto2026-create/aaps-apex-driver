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
}
