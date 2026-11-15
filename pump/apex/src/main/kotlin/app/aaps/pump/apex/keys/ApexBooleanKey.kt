package app.aaps.pump.apex.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.apex.R
import app.aaps.pump.apex.trace.ApexTrace

enum class ApexBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    private val titleResId: Int,
    private val summaryResId: Int? = null,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true
) : BooleanPreferenceKey {

    /** Write the machine readable trace, see [app.aaps.pump.apex.trace.ApexTrace]; a diagnostic, off by default. */
    Trace("apex_driver_trace", false, R.string.apex_trace_title, R.string.apex_trace_summary),

    /**
     * Keep the link open between exchanges: setting a link up is the fragile part, and a held link is
     * watched by the pump's heartbeat. On by default; off for a phone that handles a long link badly.
     */
    HoldLink("apex_hold_link", true, R.string.apex_hold_link_title, R.string.apex_hold_link_summary),
    ;

    override val title: TextRef = TextRef.AndroidRes(titleResId)
    override val summary: TextRef? = summaryResId?.let { TextRef.AndroidRes(it) }
}
