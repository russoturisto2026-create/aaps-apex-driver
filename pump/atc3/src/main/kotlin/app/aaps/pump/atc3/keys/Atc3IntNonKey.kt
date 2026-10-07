package app.aaps.pump.atc3.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.pump.atc3.protocol.Atc3Settings

enum class Atc3IntNonKey(
    override val key: String,
    override val defaultValue: Int,
    override val exportable: Boolean = true
) : IntNonPreferenceKey {

    /**
     * The alarm signal duration last written, see [app.aaps.pump.atc3.protocol.Atc3Settings]: the pump
     * does not report it, and every settings write carries it.
     */
    AlarmDuration("atc3_alarm_duration", defaultValue = Atc3Settings.ALARM_DURATION_NORMAL),
}
