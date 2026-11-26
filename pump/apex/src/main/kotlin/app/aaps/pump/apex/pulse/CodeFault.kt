package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.apex.R
import app.aaps.pump.apex.protocol.ApexFrame
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.coroutines.cancellation.CancellationException

/**
 * A reconciliation that broke on an error of the driver's own code. The pump's answers are read again until two
 * reads are the same, so what broke it is what the pump holds: the next heartbeat breaks the same way, until the journal
 * moves on or the code is mended. The reconciliation writes nothing to the driver's base, see [Reconciliation];
 * no command of ours goes while the fault stands, see [HeartbeatTick.startIfNeeded]; the user is told until a
 * reconciliation succeeds, and the log keeps the error and the raw answers it broke on.
 */
@SingleIn(AppScope::class)
@Inject
class CodeFault(
    private val aapsLogger: AAPSLogger,
    private val notificationManager: NotificationManager,
    private val rh: ResourceHelper
) {

    /** True from a reconciliation that broke until one succeeds. */
    @Volatile var standing = false
        private set

    /** [block], or [broken] when it breaks on an error of the code; [picture] gives what was read, for the log. */
    fun <T> guard(broken: T, picture: () -> PumpPicture?, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tell(e, picture())
            broken
        }

    /** True until a reconciliation succeeded since AAPS started: a word of an AAPS that ran before may still be shown. */
    @Volatile private var wordMayStand = true

    /** A reconciliation succeeded: the fault is over. */
    fun clear() {
        if (!standing && !wordMayStand) return
        if (standing) aapsLogger.info(LTag.PUMP, "Apex: a reconciliation succeeded again, the error of the driver is over")
        standing = false
        wordMayStand = false
        notificationManager.dismiss(NotificationId.PUMP_SYNC_ERROR)
    }

    private fun tell(e: Exception, picture: PumpPicture?) {
        aapsLogger.error(LTag.PUMP, "Apex: the reconciliation broke on an error of the driver, nothing of it is kept", e)
        picture?.boluses?.answer?.entries?.forEach { aapsLogger.error(LTag.PUMP, "Apex: bolus journal it broke on: ${ApexFrame.hex(it.frame.raw)}") }
        picture?.days?.answer?.entries?.forEach { aapsLogger.error(LTag.PUMP, "Apex: daily statistics it broke on: ${ApexFrame.hex(it.frame.raw)}") }
        if (standing) return
        standing = true
        notificationManager.post(NotificationId.PUMP_SYNC_ERROR, rh.gs(R.string.apex_code_fault), level = NotificationLevel.URGENT)
    }
}
