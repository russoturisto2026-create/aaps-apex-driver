package app.aaps.pump.apex

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.command.TbrCommand
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
 * A temporary basal that runs above the new maximum basal is cancelled first, and the user is told:
 * the pump would hold a maximum below what it delivers. When the cancel fails, the limits are not
 * written; the next heartbeat tries again.
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
    private val tbrCommand: TbrCommand,
    private val trace: ApexTrace
) {

    /** The maximum bolus AAPS holds, U. */
    fun maxBolusUnits(): Double = preferences.get(DoubleKey.SafetyMaxBolus)

    /** The maximum basal AAPS holds, U/h. */
    fun maxBasalUnits(): Double = preferences.get(DoubleKey.ApsMaxBasal)

    /** True when the pump's [settings] hold AAPS's maximum bolus and basal, to the pump's step. */
    fun matches(settings: ApexSettings): Boolean = settings.maxBolusRaw == steps(maxBolusUnits()) && settings.maxBasalRaw == steps(maxBasalUnits())

    /** At a heartbeat between boluses, with the status [card] it read: the pump made to hold AAPS's limits. */
    fun onHeartbeat(serial: String, card: ApexPumpState.StatusCard) {
        val settings = card.settings
        if (settings == null || matches(settings)) return
        if (pumpState.takesNoCommands) return
        val bolus = maxBolusUnits()
        val basal = maxBasalUnits()
        val tbrRate = card.status.tbrRate
        val tbrCancelled = card.status.tbrActive && tbrRate > basal && !ApexProtocol.sameDose(tbrRate, basal)
        if (tbrCancelled) {
            aapsLogger.info(LTag.PUMP, "Apex: the temporary basal of $tbrRate U/h runs above the new max basal $basal U/h: cancelled first")
            val failure = tbrCommand.cancel(serial).failure
            trace.event(ApexTraceCat.STATE, "limits_tbr_cancel", "rate" to tbrRate, "basal" to basal, "ok" to (failure == null))
            if (failure != null) {
                aapsLogger.error(LTag.PUMP, "Apex: the temporary basal above the new max basal was not cancelled, $failure; the limits are not written")
                notificationManager.post(NotificationId.PUMP_SETTINGS_FAILED, rh.gs(R.string.apex_limits_tbr_not_cancelled, tbrRate, basal, failure), level = NotificationLevel.URGENT)
                return
            }
        }
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
        val written = if (tbrCancelled) rh.gs(R.string.apex_limits_written_tbr_cancelled, bolus, basal, tbrRate) else rh.gs(R.string.apex_limits_written, bolus, basal)
        notificationManager.post(NotificationId.PUMP_SETTINGS_FAILED, written, level = NotificationLevel.INFO, validMinutes = 60)
    }

    /** A value in the pump's steps of 0.025. */
    private fun steps(units: Double): Int = (units / ApexProtocol.DOSE_SCALE).roundToInt()
}
