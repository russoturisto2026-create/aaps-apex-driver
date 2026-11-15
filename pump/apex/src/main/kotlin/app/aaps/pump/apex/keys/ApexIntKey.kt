package app.aaps.pump.apex.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.apex.ApexConst
import app.aaps.pump.apex.R

enum class ApexIntKey(
    override val key: String,
    override val defaultValue: Int,
    private val titleResId: Int,
    override val min: Int,
    override val max: Int,
    override val preferenceType: PreferenceType = PreferenceType.LIST
) : IntPreferenceKey {

    /** Minutes between two heartbeats of the pump, one of [ApexConst.HEARTBEAT_PERIODS]. */
    HeartbeatPeriod(
        "apex_heartbeat_period", ApexConst.HEARTBEAT_PERIOD_DEFAULT, R.string.apex_heartbeat_period_title,
        min = ApexConst.HEARTBEAT_PERIODS.min(), max = ApexConst.HEARTBEAT_PERIODS.max()
    ),
    ;

    override val title: TextRef = TextRef.AndroidRes(titleResId)
}
