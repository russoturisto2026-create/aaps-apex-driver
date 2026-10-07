package app.aaps.pump.atc3.history

import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.keys.Atc3LongNonKey
import app.aaps.pump.atc3.protocol.Atc3Alarm
import app.aaps.pump.atc3.protocol.Atc3AlarmRecord
import app.aaps.pump.atc3.protocol.Atc3DailyStats
import app.aaps.pump.atc3.protocol.Atc3RefillRecord
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pump's alarms, refills and daily totals in the AAPS history: each written once, from the
 * newest the driver has not written yet.
 */
@Singleton
class Atc3HistoryEvents @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val pumpSync: PumpSync,
    private val dateUtil: DateUtil,
    private val pumpState: Atc3PumpState,
    private val trace: Atc3Trace,
    private val registration: Atc3PumpRegistration
) {

    private val serial: String get() = pumpState.serialNumber

    /**
     * Write the alarms newer than the last written into the AAPS history: an alarm reaches AAPS no other
     * way. A record of an event, not of delivery, see [Atc3Alarm]. The first read only sets where
     * writing starts, kept in [Atc3LongNonKey.LastAlarmSeconds]: weeks of old alarms are not imported.
     *
     * @param records an alarm history answer, as the pump gave it
     */
    suspend fun recordAlarms(records: List<Atc3AlarmRecord>) {
        if (records.isEmpty()) return
        val newest = records.maxOf { it.pumpClockUtcSeconds }
        val watermark = preferences.get(Atc3LongNonKey.LastAlarmSeconds)
        if (watermark == 0L) {
            preferences.put(Atc3LongNonKey.LastAlarmSeconds, newest)
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: ${records.size} alarm(s) already on the pump, none imported; " +
                    "AAPS starts recording alarms from here"
            )
            return
        }
        if (newest <= watermark) return
        if (!registration.ensureRegistered()) return

        val fresh = records.filter { it.pumpClockUtcSeconds > watermark }.sortedBy { it.pumpClockUtcSeconds }
        for (record in fresh) {
            val stored = pumpSync.insertTherapyEventIfNewWithTimestamp(
                timestamp = record.timestamp,
                type = therapyEventType(record.alarm),
                note = alarmNote(record),
                pumpId = null,
                pumpType = PumpType.ATC3,
                pumpSerial = serial
            )
            aapsLogger.debug(
                LTag.PUMP,
                "ATC3: alarm code ${record.code} at ${dateUtil.dateAndTimeString(record.timestamp)}" +
                    if (stored) " recorded" else " already in the history"
            )
        }
        preferences.put(Atc3LongNonKey.LastAlarmSeconds, newest)
        trace.event(Atc3TraceCat.HIST, "alarms", "new" to fresh.size, "newest" to newest)
    }

    /** The AAPS therapy event an alarm becomes: its own where AAPS has one, a note otherwise. */
    private fun therapyEventType(alarm: Atc3Alarm?): TE.Type = when (alarm) {
        Atc3Alarm.NO_DELIVERY     -> TE.Type.OCCLUSION
        Atc3Alarm.RESERVOIR_EMPTY -> TE.Type.RESERVOIR_EMPTY
        Atc3Alarm.LOW_BATTERY     -> TE.Type.BATTERY_EMPTY
        Atc3Alarm.DAILY_LIMIT     -> TE.Type.PUMP_STOPPED
        else                      -> TE.Type.NOTE
    }

    /**
     * Write the refills newer than the last written as insulin changes; the first read only sets where
     * writing starts.
     *
     * @return how many refills were new
     */
    suspend fun recordRefills(records: List<Atc3RefillRecord>): Int {
        if (records.isEmpty()) return 0
        val newest = records.maxOf { it.utcSeconds }
        val watermark = preferences.get(Atc3LongNonKey.LastRefillSeconds)
        if (watermark == 0L) {
            preferences.put(Atc3LongNonKey.LastRefillSeconds, newest)
            aapsLogger.debug(LTag.PUMP, "ATC3: ${records.size} refill(s) already on the pump, none imported; AAPS starts recording refills from here")
            return 0
        }
        if (newest <= watermark) return 0
        if (!registration.ensureRegistered()) return 0
        val fresh = records.filter { it.utcSeconds > watermark }.sortedBy { it.utcSeconds }
        for (record in fresh) {
            val stored = pumpSync.insertTherapyEventIfNewWithTimestamp(
                timestamp = record.timestamp,
                type = TE.Type.INSULIN_CHANGE,
                note = rh.gs(R.string.atc3_refill_note, record.amountUnits),
                pumpId = null,
                pumpType = PumpType.ATC3,
                pumpSerial = serial
            )
            aapsLogger.debug(LTag.PUMP, "ATC3: refill of ${record.amountUnits} U at ${dateUtil.dateAndTimeString(record.timestamp)}" + if (stored) " recorded" else " already in the history")
        }
        preferences.put(Atc3LongNonKey.LastRefillSeconds, newest)
        trace.event(Atc3TraceCat.HIST, "refills", "new" to fresh.size, "newest" to newest)
        return fresh.size
    }

    private fun alarmNote(record: Atc3AlarmRecord): String =
        "ATC3: " + (record.alarm?.name ?: "alarm code ${record.code}")

    /** @return how many of the days AAPS did not have */
    suspend fun recordDailyTotals(days: List<Atc3DailyStats>): Int {
        if (!registration.ensureRegistered()) return 0
        var written = 0
        for (day in days) {
            val stored = pumpSync.createOrUpdateTotalDailyDose(
                timestamp = day.startOfDayMillis(),
                bolusAmount = day.bolusUnits,
                // AAPS wants all the basal; the pump counts temporary basal apart.
                basalAmount = day.basalWithTbrUnits,
                // Zero leaves AAPS to add it up.
                totalAmount = 0.0,
                pumpId = null,
                pumpType = PumpType.ATC3,
                pumpSerial = serial
            )
            if (stored) written++
        }
        return written
    }
}
