package app.aaps.pump.atc3.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.pump.atc3.Atc3Const

enum class Atc3IntNonKey(
    override val key: String,
    override val defaultValue: Int,
    override val exportable: Boolean = true
) : IntNonPreferenceKey {

    /**
     * The alarm signal duration the driver last wrote, see [app.aaps.pump.atc3.comm.Atc3Settings].
     *
     * This is the one settings field Status V1 does not mirror, and `35/A1/32` replaces the whole
     * block, so every settings write has to carry a value for it. Remembering the last one is the
     * only way to stop a change to some other setting from also moving this one. Until the driver
     * has written it once the pump's own value is unknown, and the first settings write sets the
     * normal duration whatever the pump was on.
     */
    AlarmDuration("atc3_alarm_duration", defaultValue = Atc3Const.Settings.ALARM_DURATION_NORMAL),
}
