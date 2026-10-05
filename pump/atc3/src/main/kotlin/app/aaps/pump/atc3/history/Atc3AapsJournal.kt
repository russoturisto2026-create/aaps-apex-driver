package app.aaps.pump.atc3.history

import app.aaps.core.data.model.TB
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.pump.atc3.Atc3Pump
import java.util.TreeMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads what the AAPS journal holds for this pump and hands it to [Atc3JournalArithmetic].
 *
 * Only this pump's rows count: a bolus somebody typed into AAPS as already given elsewhere is not
 * in the pump's count and must not be compared with it. Amounts and rates are in the pump's own
 * units, as AAPS stores them, because the pump's count is in those units.
 */
@Singleton
class Atc3AapsJournal @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val atc3Pump: Atc3Pump
) {

    /**
     * What the journal accounts for over `[fromMs, toMs)`, in pump units, or null when no profile
     * is running and there is no scheduled rate to fill the gaps with.
     *
     * @param bolusUnits the boluses of the interval, summed by the caller. The bolus rows are not
     *   read here: they are stamped on the pump's minutes -- a stranger's at second 59 of the minute
     *   it started in, ours moved onto the pump's minute when its record sits a minute off -- and
     *   the interval is cut at the seconds of two reads, so no time window over the rows can say
     *   which of them the pump's count already holds. What can is when the driver learned of each
     *   bolus, see [Atc3HistorySync.bolusesLearnedAfter].
     */
    suspend fun insulinBetween(
        fromMs: Long,
        toMs: Long,
        bolusUnits: Double = 0.0
    ): Atc3JournalArithmetic.Breakdown? {
        profileFunction.getProfile(toMs) ?: return null
        val serial = atc3Pump.serialNumber

        val rows = persistenceLayer.getTemporaryBasalsActiveBetweenTimeAndTime(fromMs, toMs)
            .filter { it.isValid && it.ids.pumpSerial == serial }
            .map {
                Atc3JournalArithmetic.Row(
                    startMs = it.timestamp,
                    endMs = it.end,
                    rateUnitsPerHour = if (it.isAbsolute) it.rate else null,
                    percent = if (it.isAbsolute) null else it.rate.toInt()
                )
            }

        // The scheduled rate at the interval's own start and at every half hour boundary inside it,
        // read once each: the arithmetic asks at those moments and at row edges, and the profile
        // does not change between them.
        //
        // The first reading is the start of the interval, not the half hour boundary before it. A
        // profile begins when it is switched on, which is no respecter of half hours, and the
        // boundary before the interval can lie on the far side of that switch -- where the profile
        // held a different rate, or, after a first setup, none at all. Read there, the whole
        // interval is credited with a rate that never ran in it; read where there was no profile,
        // with nothing, and then the pump's ordinary basal becomes an excess the journal cannot
        // account for and the user is told to go and check the pump.
        val scheduled = TreeMap<Long, Double>()
        var cursor = fromMs
        while (cursor < toMs) {
            scheduled[cursor] = profileFunction.getProfile(cursor)?.getBasal(cursor) ?: 0.0
            cursor = Atc3DayClock.halfHourOf(cursor + Atc3DayClock.HALF_HOUR_MS)
        }

        // A profile switched on inside the interval is the other way the rate changes, and the pump
        // is put on the new one the moment it is made. Read there too, and say where, so that the
        // arithmetic splits the interval at the switch instead of carrying the old rate past it.
        val changesAt = persistenceLayer
            .getEffectiveProfileSwitchesFromTimeToTime(fromMs, toMs, true)
            .map { it.timestamp }
            .filter { it > fromMs && it < toMs }
            .distinct()
        for (at in changesAt) scheduled[at] = profileFunction.getProfile(at)?.getBasal(at) ?: 0.0

        return Atc3JournalArithmetic.insulin(
            fromMs, toMs, bolusUnits, rows, changesAt
        ) { at ->
            scheduled.floorEntry(at)?.value ?: 0.0
        }
    }

    /**
     * The scheduled rate AAPS holds at [atMs] in pump units, or null when a temporary basal row of
     * this pump is active there: then the journal, not the profile, accounts for that time.
     */
    suspend fun scheduledRateAt(atMs: Long): Double? {
        val serial = atc3Pump.serialNumber
        val running = persistenceLayer.getTemporaryBasalActiveAt(atMs)
        if (running != null && running.isValid && running.ids.pumpSerial == serial) return null
        return profileFunction.getProfile(atMs)?.getBasal(atMs)
    }

    /**
     * What the journal accounts for since [fromMs] -- the pump's midnight, or the beginning the
     * day's account was last taken up at -- for setting beside the pump's count on the driver's
     * screen. Here the boluses are taken by their rows: over hours the minute a row is stamped on
     * does not matter, as it does between two reads.
     */
    suspend fun insulinOfDay(fromMs: Long, toMs: Long): Atc3JournalArithmetic.Breakdown? =
        insulinBetween(fromMs, toMs, bolusesBetween(fromMs, toMs))

    /** The boluses of this pump AAPS holds rows of in `[fromMs, toMs)`, summed. */
    suspend fun bolusesBetween(fromMs: Long, toMs: Long): Double {
        val serial = atc3Pump.serialNumber
        return persistenceLayer.getBolusesFromTimeToTime(fromMs, toMs, true)
            .filter { it.isValid && it.ids.pumpSerial == serial }
            .sumOf { it.amount }
    }

    /**
     * The temporary basal rows AAPS holds of this pump that run anywhere in `[fromMs, toMs)`, for
     * closing that stretch by the pump's count, see [Atc3HistorySync.writeBasalFact].
     */
    suspend fun rowsBetween(fromMs: Long, toMs: Long): List<Atc3HistorySync.JournalRow> {
        val serial = atc3Pump.serialNumber
        return persistenceLayer.getTemporaryBasalsActiveBetweenTimeAndTime(fromMs, toMs)
            .filter { it.isValid && it.ids.pumpSerial == serial && it.timestamp < toMs }
            .map {
                Atc3HistorySync.JournalRow(
                    pumpId = it.ids.pumpId,
                    timestamp = it.timestamp,
                    durationMs = it.duration,
                    rate = it.rate,
                    isAbsolute = it.isAbsolute,
                    stop = it.type == TB.Type.PUMP_SUSPEND
                )
            }
    }
}
