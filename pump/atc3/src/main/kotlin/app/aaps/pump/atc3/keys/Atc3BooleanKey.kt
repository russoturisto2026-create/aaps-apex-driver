package app.aaps.pump.atc3.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.pump.atc3.trace.Atc3Trace

enum class Atc3BooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
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

    /** Write the machine readable trace, see [app.aaps.pump.atc3.trace.Atc3Trace]; a diagnostic, off by default. */
    Trace("atc3_driver_trace", false),

    /**
     * Keep the link open between exchanges: setting a link up is the fragile part, and a held link is
     * watched by the pump's heartbeat. On by default; off for a phone that handles a long link badly.
     */
    HoldLink("atc3_hold_link", true),
}
