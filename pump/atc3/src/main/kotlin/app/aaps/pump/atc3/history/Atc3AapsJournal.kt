package app.aaps.pump.atc3.history

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.pump.atc3.Atc3Pump
import java.util.Calendar
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
     */
    suspend fun insulinBetween(
        fromMs: Long,
        toMs: Long,
        bolusFromMs: Long = fromMs,
        bolusToMs: Long = toMs
    ): Atc3JournalArithmetic.Breakdown? {
        profileFunction.getProfile(toMs) ?: return null
        val serial = atc3Pump.serialNumber

        // The boluses on their own window, see Atc3StateCheck.Baseline.bolusUntilMs.
        val boluses = persistenceLayer.getBolusesFromTimeToTime(bolusFromMs, bolusToMs, true)
            .filter { it.isValid && it.ids.pumpSerial == serial }
            .map { it.timestamp to it.amount }

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
            cursor = halfHourOf(cursor + HALF_HOUR_MS)
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
            fromMs, toMs, boluses, rows, bolusFromMs, bolusToMs, changesAt
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

    private fun halfHourOf(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.MILLISECOND, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MINUTE, if (get(Calendar.MINUTE) < 30) 0 else 30)
    }.timeInMillis

    private companion object {

        const val HALF_HOUR_MS = 30 * 60_000L
    }
}
