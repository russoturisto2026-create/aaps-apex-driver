package app.aaps.pump.apex

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.keys.ApexStringKey
import app.aaps.pump.apex.link.ApexBtPassword
import app.aaps.pump.apex.link.ApexConnection
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexSettings
import app.aaps.pump.apex.state.ApexPumpState
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * The pump's settings for AAPS, written on the user's word from the driver's screen: extended
 * boluses off, so that the account of insulin holds; brightness at the lowest and the screen off
 * after the shortest time, to spare the battery; alarms by vibration. The whole block goes as the
 * pump last reported it, with these four changed, and the pump's status proves the write. The user
 * sees the result as a notification.
 */
@SingleIn(AppScope::class)
@Inject
class ApexPumpSetup(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val manager: ApexManager,
    private val connection: ApexConnection,
    private val pumpState: ApexPumpState,
    private val notificationManager: NotificationManager,
    private val preferences: Preferences,
    private val pumpEnactResultProvider: () -> PumpEnactResult
) {

    fun setupForAaps(): PumpEnactResult {
        if (!connection.isConnected) return failed(rh.gs(R.string.apex_not_connected))
        if (pumpState.suspended || pumpState.activeAlarmCodes.isNotEmpty() || pumpState.locked) return failed(rh.gs(R.string.apex_pump_not_taking_commands))
        val current = manager.readStatus()?.settings ?: return failed(rh.gs(R.string.apex_not_connected))
        val wanted = current.copy(
            extendedBolusAllowed = false,
            brightnessLevel = 0,
            alarmSignalType = ApexSettings.ALARM_TYPE_VIBRATION,
            screenTimeoutRaw = ApexConst.SCREEN_TIMEOUT_FOR_AAPS_RAW
        )
        if (wanted.mirroredFieldsMatch(current)) {
            notificationManager.post(NotificationId.PUMP_SETTINGS_FAILED, rh.gs(R.string.apex_setup_already), level = NotificationLevel.INFO, validMinutes = 60)
            return pumpEnactResultProvider().success(true).enacted(false)
        }
        val failure = manager.writeSettings(wanted)
        if (failure != null) {
            aapsLogger.error(LTag.PUMP, "Apex: setup for AAPS failed, ${failure.text}")
            notificationManager.post(NotificationId.PUMP_SETTINGS_FAILED, rh.gs(R.string.apex_setup_failed, failure.text), level = NotificationLevel.URGENT)
            return failed(failure.text)
        }
        aapsLogger.info(LTag.PUMP, "Apex: the pump is set up for AAPS")
        notificationManager.post(NotificationId.PUMP_SETTINGS_FAILED, rh.gs(R.string.apex_setup_done), level = NotificationLevel.INFO, validMinutes = 60)
        return pumpEnactResultProvider().success(true).enacted(true)
    }

    /**
     * A new random Bluetooth password for the pump. Both readings the pump may take are stored before
     * the link comes back: the first is presented, the second once if the first is refused. The
     * password is not shown: it is on the pump's status screen, as B/P.
     */
    fun changeBtPassword(): PumpEnactResult {
        if (!connection.isConnected) return failed(rh.gs(R.string.apex_not_connected))
        if (pumpState.suspended || pumpState.activeAlarmCodes.isNotEmpty() || pumpState.locked) return failed(rh.gs(R.string.apex_pump_not_taking_commands))
        if (!pumpState.canChangePassword) {
            return failed(rh.gs(R.string.apex_password_unsupported))
        }
        val value = ApexBtPassword.random()
        val failure = manager.setBtPassword(value)
        if (failure != null) {
            aapsLogger.error(LTag.PUMP, "Apex: Bluetooth password change failed, ${failure.text}")
            notificationManager.post(NotificationId.PUMP_SETTINGS_FAILED, rh.gs(R.string.apex_password_change_failed, failure.text), level = NotificationLevel.URGENT)
            return failed(failure.text)
        }
        val candidates = ApexBtPassword.candidatesFor(value)
        preferences.put(ApexStringKey.ApexBtPassword, candidates[0])
        preferences.put(ApexStringKey.ApexBtPasswordAlternate, candidates[1])
        aapsLogger.info(LTag.PUMP, "Apex: the pump has a new Bluetooth password")
        notificationManager.post(NotificationId.PUMP_SETTINGS_FAILED, rh.gs(R.string.apex_password_changed), level = NotificationLevel.INFO, validMinutes = 60)
        return pumpEnactResultProvider().success(true).enacted(true)
    }

    private fun failed(comment: String): PumpEnactResult = pumpEnactResultProvider().success(false).enacted(false).comment(comment)
}
