package app.aaps.pump.apex

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexSettings
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.roundToInt

/**
 * The pump's own maximum bolus and maximum basal kept the same as AAPS's: the maximum bolus of the
 * safety settings and the maximum basal of the loop. AAPS leads. At a heartbeat between boluses the
 * settings the status carries are held against AAPS's values; when they differ, the whole settings
 * block goes to the pump with the two changed, and the pump's status proves the write. The user is
 * told either way: the values now stand in the pump and in AAPS, or the write failed.
 *
 * Nothing is written while the pump is suspended, under an alarm or locked: it takes no commands then.
 */
@SingleIn(AppScope::class)
@Inject
class ApexLimits(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val manager: ApexManager,
    private val pumpState: ApexPumpState,
    private val notificationManager: NotificationManager,
    private val trace: ApexTrace
) {

    /** The maximum bolus AAPS holds, U. */
    fun maxBolusUnits(): Double = preferences.get(DoubleKey.SafetyMaxBolus)

    /** The maximum basal AAPS holds, U/h. */
    fun maxBasalUnits(): Double = preferences.get(DoubleKey.ApsMaxBasal)

    /** True when the pump's [settings] hold AAPS's maximum bolus and basal, to the pump's step. */
    fun matches(settings: ApexSettings): Boolean = settings.maxBolusRaw == steps(maxBolusUnits()) && settings.maxBasalRaw == steps(maxBasalUnits())

    /** At a heartbeat between boluses, with the [settings] its status carried: the pump made to hold AAPS's limits. */
    fun onHeartbeat(settings: ApexSettings?) {
        if (settings == null || matches(settings)) return
        if (pumpState.suspended || pumpState.activeAlarmCodes.isNotEmpty() || pumpState.locked) return
        val bolus = maxBolusUnits()
        val basal = maxBasalUnits()
        val wanted = settings.copy(maxBolusRaw = steps(bolus), maxBasalRaw = steps(basal))
        aapsLogger.info(
            LTag.PUMP,
            "Apex: the pump holds max bolus ${settings.maxBolusRaw * ApexProtocol.DOSE_SCALE} U and max basal ${settings.maxBasalRaw * ApexProtocol.DOSE_SCALE} U/h, AAPS $bolus U and $basal U/h: writing AAPS's"
        )
        val failure = manager.writeSettings(wanted)
        trace.event(ApexTraceCat.STATE, "limits_written", "bolus" to bolus, "basal" to basal, "ok" to (failure == null))
        if (failure != null) {
            aapsLogger.error(LTag.PUMP, "Apex: the max bolus and basal did not go to the pump, ${failure.text}")
            notificationManager.post(NotificationId.PUMP_SETTINGS_FAILED, rh.gs(R.string.apex_limits_failed, bolus, basal, failure.text), level = NotificationLevel.URGENT)
            return
        }
        notificationManager.post(NotificationId.PUMP_SETTINGS_FAILED, rh.gs(R.string.apex_limits_written, bolus, basal), level = NotificationLevel.INFO, validMinutes = 60)
    }

    /** A value in the pump's steps of 0.025. */
    private fun steps(units: Double): Int = (units / ApexProtocol.DOSE_SCALE).roundToInt()
}
