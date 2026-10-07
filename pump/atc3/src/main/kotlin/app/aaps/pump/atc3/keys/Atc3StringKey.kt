package app.aaps.pump.atc3.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.pump.atc3.link.Atc3BtPassword

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

    /** The eight digit serial printed on the pump, which every request carries. */
    Atc3SerialNumber("atc3_serial_number", ""),

    /**
     * The pump's Bluetooth password, as shown on its screen. Not marked a PIN: it is compared by eye with
     * the pump's screen, which shows it to anyone holding the pump.
     */
    Atc3BtPassword("atc3_bt_password", ""),

    /**
     * The other password the pump may hold after a change, empty when there is no doubt, see
     * [app.aaps.pump.atc3.link.Atc3BtPassword.candidatesFor]. Not exportable: it lives for one connection.
     */
    Atc3BtPasswordAlternate("atc3_bt_password_alternate", "", exportable = false),
}
