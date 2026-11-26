package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.apex.R
import app.aaps.pump.apex.link.ApexConnection
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.state.ApexPumpState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** What a new link needs once, in its turn on the AAPS queue, see [ApexLinkSetup]. */
@SingleIn(AppScope::class)
@Inject
class LinkSetup(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val notificationManager: NotificationManager,
    private val connection: ApexConnection,
    private val manager: ApexManager,
    private val pumpState: ApexPumpState,
    private val heartbeatTick: HeartbeatTick
) {

    /**
     * What a new link needs: the heartbeat period, the version once, and the first reconciliation since AAPS started, see
     * [HeartbeatTick.startIfNeeded]. True when the pump answered. A pump whose protocol is older than the
     * driver works with gives no daily statistics: nothing is reconciled, it takes no command, and the user
     * is told, see [ApexPumpState.unsupported].
     */
    fun run(): Boolean {
        if (!connection.isConnected) return false
        manager.ensureHeartbeatPeriod()
        if (pumpState.version == null) versionRead(manager.readVersion() != null)
        val version = pumpState.version
        if (version != null && !version.supported) {
            aapsLogger.error(LTag.PUMP, "Apex: the pump's protocol ${version.protocolText} is older than the driver works with: it gives no daily statistics, nothing is reconciled and no command is sent")
            notificationManager.post(NotificationId.UNSUPPORTED_FIRMWARE, rh.gs(R.string.apex_pump_unsupported, version.protocolText), level = NotificationLevel.URGENT)
            return false
        }
        heartbeatTick.startIfNeeded()
        return true
    }

    /** The user was told the firmware version cannot be read, and it was not read since. */
    @Volatile private var versionUnreadTold = false

    /**
     * The firmware version, [read] or not. Without it the protocol is not known, and with it the form of
     * the stamps, see `ApexStamp`: nothing is reconciled and no command of ours is sent until it is read.
     * It is asked again at the next link. The user is told once, and the notice goes when it is read.
     */
    private fun versionRead(read: Boolean) {
        if (read) {
            if (versionUnreadTold) notificationManager.dismiss(NotificationId.UNSUPPORTED_FIRMWARE)
            versionUnreadTold = false
            return
        }
        aapsLogger.warn(LTag.PUMP, "Apex: the firmware version could not be read: nothing is reconciled and no command is sent until it is")
        if (versionUnreadTold) return
        versionUnreadTold = true
        notificationManager.post(NotificationId.UNSUPPORTED_FIRMWARE, rh.gs(R.string.apex_version_unread), level = NotificationLevel.NORMAL)
    }
}
