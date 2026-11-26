package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.apex.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The first reconciliation since AAPS started, tried until it succeeds: no command of ours goes before it, see
 * [HeartbeatTick.startIfNeeded]. A start that does not pass, the pump not answering or its answers not whole,
 * is tried again at every heartbeat and before every command. After [TOLD_AFTER] tries in a row the user is
 * told that the loop gives no bolus, until a start succeeds. A running bolus is not a try.
 */
@SingleIn(AppScope::class)
@Inject
class StartTries(
    private val aapsLogger: AAPSLogger,
    private val notificationManager: NotificationManager,
    private val rh: ResourceHelper
) {

    @Volatile private var failed = 0

    /** A start that did not pass. */
    fun failed() {
        failed++
        aapsLogger.warn(LTag.PUMP, "Apex: the first reconciliation since AAPS started did not pass, try $failed in a row")
        if (failed == TOLD_AFTER) notificationManager.post(NotificationId.PUMP_WARNING, rh.gs(R.string.apex_start_not_passed, failed), level = NotificationLevel.URGENT)
    }

    /** The start succeeded. */
    fun done() {
        if (failed >= TOLD_AFTER) notificationManager.dismiss(NotificationId.PUMP_WARNING)
        failed = 0
    }

    companion object {

        const val TOLD_AFTER = 3
    }
}
