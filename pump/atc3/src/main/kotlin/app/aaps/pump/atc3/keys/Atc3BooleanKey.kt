package app.aaps.pump.atc3.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey

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

    /**
     * Write the machine readable trace, see [app.aaps.pump.atc3.trace.Atc3Trace].
     *
     * Off by default: it is a diagnostic, and the ordinary user has no reason to spend a few
     * hundred log lines an hour on it. The switch lives on the device screen so it can be turned on
     * when it is needed and left off the rest of the time.
     */
    Trace("atc3_driver_trace", false),

    /**
     * Keep the Bluetooth link open between exchanges instead of closing it each time.
     *
     * On by default: closed each time, a link is set up hundreds of times a day, and setting one up
     * is the fragile part. Holding the link runs that setup once.
     *
     * The pump holds a link for hours, and its own heartbeat every 180 s is what keeps the held
     * link honest, so a link that dies is noticed rather than assumed good.
     *
     * The switch exists so closing the link after each exchange can be had without a new build if
     * a phone turns out to handle a long lived link badly.
     */
    HoldLink("atc3_hold_link", true),
}
