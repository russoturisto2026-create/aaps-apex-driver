package app.aaps.pump.apex.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.apex.R
import app.aaps.pump.apex.link.ApexBtPassword

enum class ApexStringKey(
    override val key: String,
    override val defaultValue: String,
    private val titleResId: Int,
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
    ApexAddress("apex_address", "", R.string.apex_address_title),

    /** The eight digit serial printed on the pump, which every request carries. */
    ApexSerialNumber("apex_serial_number", "", R.string.apex_serial_title),

    /**
     * The pump's Bluetooth password, as shown on its screen. Not marked a PIN: it is compared by eye with
     * the pump's screen, which shows it to anyone holding the pump.
     */
    ApexBtPassword("apex_bt_password", "", R.string.apex_password_title),

    /** The other password the pump may hold after a change, tried once if the first is refused; empty otherwise. */
    ApexBtPasswordAlternate("apex_bt_password_alternate", "", R.string.apex_password_title, exportable = false),

    /** The exact basal record: off or by heartbeats, see `ExactBasalMode`. Off by default; a value no longer known reads as off. */
    ExactBasal("apex_exact_basal", "off", R.string.apex_exact_basal_title),
    ;

    override val title: TextRef = TextRef.AndroidRes(titleResId)
}
