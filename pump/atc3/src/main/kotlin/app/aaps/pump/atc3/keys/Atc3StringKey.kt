package app.aaps.pump.atc3.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey

enum class Atc3StringKey(
    override val key: String,
    override val defaultValue: String,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val exportable: Boolean = true
) : StringPreferenceKey {

    /** Bluetooth address of the pump, for example 11:22:33:AA:BB:CC. */
    Atc3Address("atc3_address", ""),

    /**
     * Eight digit serial number printed on the pump.
     *
     * Every request carries an identity block of the identity prefix followed by this serial, so the
     * driver cannot talk to the pump without it.
     */
    Atc3SerialNumber("atc3_serial_number", ""),

    /**
     * The pump's Bluetooth password, the six digits shown on its status screen as `B/P`.
     *
     * Empty means the pump is not asking for one, and the driver then brings the link up without
     * presenting anything.
     *
     * Deliberately not marked as a PIN. That flag hides the value behind asterisks in the settings
     * list and replaces the summary with "PIN not set", and both are wrong here: this value has to
     * be compared by eye with the `B/P` line on the pump's screen, and the summary is what says
     * where to find it. It is not a secret from anyone holding the pump, which shows it unprompted.
     */
    Atc3BtPassword("atc3_bt_password", ""),

    /**
     * The other password the pump might be holding after a change, empty when there is no doubt.
     *
     * A change that the pump acknowledges does not always leave it holding the value that was asked
     * for; see [app.aaps.pump.atc3.comm.Atc3BtPassword.candidatesFor]. Rather than leave the link
     * down until somebody reads the number off the pump, the driver writes the second candidate
     * here, presents it once if the first is refused, and clears it as soon as either of them is
     * accepted.
     *
     * Not exportable: it is a guess with a lifetime of one connection, and restoring a stale one
     * into a working setup would present a wrong password to a pump that is perfectly happy.
     */
    Atc3BtPasswordAlternate("atc3_bt_password_alternate", "", exportable = false),
}
