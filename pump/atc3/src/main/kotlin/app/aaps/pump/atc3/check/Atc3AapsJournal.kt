package app.aaps.pump.atc3.check

import app.aaps.core.data.model.TB
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.pump.atc3.basal.Atc3BasalFact
import app.aaps.pump.atc3.clock.Atc3DayClock
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.state.Atc3PumpState
import java.util.TreeMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the AAPS journal holds for this pump, handed to [Atc3JournalArithmetic]: only this pump's rows,
 * in the pump's own units, because that is what the pump's count is in.
 */
@Singleton
class Atc3AapsJournal @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val pumpState: Atc3PumpState
) {

    /**
     * What the journal accounts for over `[fromMs, toMs)`, in pump units, or null when no profile runs.
     *
     * @param bolusUnits the boluses of the interval, summed by the caller from when each was learned,
     *   see [Atc3HistorySync.bolusesLearnedAfter]: no window over the rows' minutes can say which of
     *   them the pump's count already holds
     */
    suspend fun insulinBetween(
        fromMs: Long,
        toMs: Long,
        bolusUnits: Double = 0.0
    ): Atc3JournalArithmetic.Breakdown? {
        profileFunction.getProfile(toMs) ?: return null
        val serial = pumpState.serialNumber

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

        // The scheduled rate at the interval's start and at every half hour in it. Read at the start rather
        // than at the half hour before it: a profile switched on in between would credit a rate that never ran.
        val scheduled = TreeMap<Long, Double>()
        var cursor = fromMs
        while (cursor < toMs) {
            scheduled[cursor] = profileFunction.getProfile(cursor)?.getBasal(cursor) ?: 0.0
            cursor = Atc3DayClock.halfHourOf(cursor + Atc3DayClock.HALF_HOUR_MS)
        }

        // A profile switched on inside the interval changes the rate there too.
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

    /** The scheduled rate at [atMs] in pump units, or null while a temporary basal row of this pump runs there. */
    suspend fun scheduledRateAt(atMs: Long): Double? {
        val serial = pumpState.serialNumber
        val running = persistenceLayer.getTemporaryBasalActiveAt(atMs)
        if (running != null && running.isValid && running.ids.pumpSerial == serial) return null
        return profileFunction.getProfile(atMs)?.getBasal(atMs)
    }

    /** What the journal accounts for since [fromMs], for the day's account on the screen; boluses by their rows, which over hours is right. */
    suspend fun insulinOfDay(fromMs: Long, toMs: Long): Atc3JournalArithmetic.Breakdown? =
        insulinBetween(fromMs, toMs, bolusesBetween(fromMs, toMs))

    /** The boluses of this pump AAPS holds rows of in `[fromMs, toMs)`, summed. */
    suspend fun bolusesBetween(fromMs: Long, toMs: Long): Double {
        val serial = pumpState.serialNumber
        return persistenceLayer.getBolusesFromTimeToTime(fromMs, toMs, true)
            .filter { it.isValid && it.ids.pumpSerial == serial }
            .sumOf { it.amount }
    }

    /** The temporary basal rows of this pump in `[fromMs, toMs)`, see [Atc3BasalFact.writeBasalFact]. */
    suspend fun rowsBetween(fromMs: Long, toMs: Long): List<Atc3BasalFact.JournalRow> {
        val serial = pumpState.serialNumber
        return persistenceLayer.getTemporaryBasalsActiveBetweenTimeAndTime(fromMs, toMs)
            .filter { it.isValid && it.ids.pumpSerial == serial && it.timestamp < toMs }
            .map {
                Atc3BasalFact.JournalRow(
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
