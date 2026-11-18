package app.aaps.pump.apex.account

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.pump.apex.R
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.runBlocking

/**
 * Puts the account's writes into AAPS. The account hands out [AapsWrite]s and knows nothing of
 * `PumpSync`; this is the one place that carries them, so that a row is written the same way
 * whoever found it. A write carried twice does no harm: the core knows the rows by their numbers.
 */
@SingleIn(AppScope::class)
@Inject
class AapsCarrier(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val pumpSync: PumpSync,
    private val notificationManager: NotificationManager,
    private val dateUtil: DateUtil,
    private val trace: ApexTrace
) {

    fun carry(write: AapsWrite, serial: String) {
        when (write) {
            is AapsWrite.Bolus   -> {
                val inserted = runBlocking { pumpSync.syncBolusWithPumpId(write.timestampMs, PumpInsulin(write.amountUnits), BS.Type.fromString(write.type), write.pumpId, PumpType.APEX, serial) }
                aapsLogger.info(LTag.PUMP, "Apex: bolus row ${write.pumpId} ${write.amountUnits} U ${write.type} at ${write.timestampMs}, inserted $inserted")
                trace.event(ApexTraceCat.STATE, "row_bolus", "id" to write.pumpId, "at" to write.timestampMs, "units" to write.amountUnits, "type" to write.type, "inserted" to inserted)
            }

            is AapsWrite.Tbr     -> {
                val done = runBlocking {
                    pumpSync.syncTemporaryBasalWithPumpId(
                        write.startMs, PumpRate(write.rate), write.durationMs, write.isAbsolute,
                        PumpSync.TemporaryBasalType.valueOf(write.type), write.pumpId, PumpType.APEX, serial
                    )
                }
                aapsLogger.info(LTag.PUMP, "Apex: temporary basal row ${write.pumpId} ${write.rate} for ${write.durationMs / 60_000} min ${write.type}, inserted $done")
            }

            is AapsWrite.StopTbr -> {
                val done = runBlocking { pumpSync.syncStopTemporaryBasalWithPumpId(write.endMs, write.endMs, PumpType.APEX, serial) }
                aapsLogger.info(LTag.PUMP, "Apex: temporary basal ends at ${write.endMs}, done $done")
            }

            is AapsWrite.Notice  -> {
                val text = when (write.kind) {
                    NoticeKind.JOURNAL_FAULT         -> rh.gs(R.string.apex_notice_journal_fault, dateUtil.timeString(write.atMs), write.units)
                    NoticeKind.JOURNAL_FAULT_UNKNOWN -> rh.gs(R.string.apex_notice_journal_fault_unknown, dateUtil.timeString(write.atMs))
                    NoticeKind.JOURNAL_CLEAN_START   -> rh.gs(R.string.apex_notice_journal_clean_start, dateUtil.timeString(write.atMs))
                    NoticeKind.JOURNAL_UNLAID        -> rh.gs(R.string.apex_notice_journal_unlaid, dateUtil.timeString(write.atMs))
                    NoticeKind.IMPLAUSIBLE_ROW       -> rh.gs(R.string.apex_notice_implausible_row, dateUtil.timeString(write.atMs), write.units)
                    NoticeKind.BASAL_ABOVE_MAX       -> rh.gs(R.string.apex_notice_basal_above_max, dateUtil.timeString(write.atMs), write.units)
                }
                aapsLogger.error(LTag.PUMP, "Apex: $text")
                notificationManager.post(NotificationId.PUMP_ERROR, text, level = NotificationLevel.URGENT)
                // The notice stays in the treatment history too, next to the bolus it is about.
                runBlocking { pumpSync.insertAnnouncement(text, null, PumpType.APEX, serial) }
            }
        }
    }
}
