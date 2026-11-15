package app.aaps.pump.apex.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.pump.apex.protocol.ApexSettings

enum class ApexIntNonKey(
    override val key: String,
    override val defaultValue: Int,
    override val exportable: Boolean = true
) : IntNonPreferenceKey {

    /**
     * The alarm signal duration last written, see [app.aaps.pump.apex.protocol.ApexSettings]: the pump
     * does not report it, and every settings write carries it.
     */
    AlarmDuration("apex_alarm_duration", defaultValue = ApexSettings.ALARM_DURATION_NORMAL),
}
