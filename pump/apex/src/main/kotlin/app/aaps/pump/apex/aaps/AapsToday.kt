package app.aaps.pump.apex.aaps

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.pump.apex.clock.ApexClock
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Today's insulin as the AAPS rows hold it, counted the way the IOB counts them: every bolus row
 * summed, every temporary basal row over the time it really ran, the schedule where no row ran.
 * This is the number to hold against the pump's own count of the day: a gap between the two is a
 * dose AAPS lost or invented, nothing else. The core's daily dose is not that number: it samples the
 * rows on a five minute grid, and a temporary basal that lived two minutes is counted whole or not
 * at all by where its start fell on the grid.
 */
@SingleIn(AppScope::class)
@Inject
class AapsToday(
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val dateUtil: DateUtil
) {

    class Units(val bolusUnits: Double, val basalUnits: Double)

    /**
     * From [from], the phone's midnight by default, up to [now] and not at it: a row at [now] belongs to
     * what comes after; null while AAPS has no profile for some of that time.
     */
    suspend fun count(now: Long = dateUtil.now(), from: Long = MidnightTime.calc(now)): Units? {
        val boluses = persistenceLayer.getBolusesFromTimeToTime(from, now - 1, true)
            .filter { it.type != BS.Type.PRIMING }
            .sumOf { it.amount }
        return Units(boluses, basalBetween(from, now) ?: return null)
    }

    /**
     * When the newest bolus in AAPS from [fromMs] on was given, from any source; null while there is none. A row
     * of a pump that still waits for its record, with a temporary key only, does not count: the record it waits
     * for may stand after an older bolus that AAPS does not have yet. Taken by time: the list comes in the order
     * the rows were made, not in the order of their time.
     */
    suspend fun newestBolusMs(fromMs: Long): Long? =
        persistenceLayer.getBolusesFromTime(fromMs, true).filter { it.ids.temporaryId == null || it.ids.pumpId != null }.maxOfOrNull { it.timestamp }

    /** The rows of the pump [serial] from [fromMs] on that came from its records: the time of each, by its key, see `BolusKey`. */
    suspend fun pumpRowsMs(serial: String, fromMs: Long): Map<Long, Long> =
        persistenceLayer.getBolusesFromTime(fromMs, true).filter { it.ids.pumpSerial == serial }.mapNotNull { row -> row.ids.pumpId?.let { it to row.timestamp } }.toMap()

    /** How long the insulin in use acts, ms, by the profile AAPS runs; null while it has none. */
    suspend fun insulinActsMs(): Long? = profileFunction.getRunningOrRequestedICfg()?.insulinEndTime

    /** The basal the rows order from [start] to [end], the way the IOB counts it; null while AAPS has no profile for some of that time. */
    suspend fun basalBetween(start: Long, end: Long): Double? {
        var basal = 0.0
        var at = start
        // The row running at the start and every row begun since: together, every row that touches the span.
        val rows = (persistenceLayer.getTemporaryBasalsActiveAt(start) + persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(start, end, true))
            .distinctBy { it.id }
            .sortedBy { it.timestamp }
        for (row in rows) {
            val from = maxOf(row.timestamp, start, at)
            val to = minOf(row.end, end)
            if (to <= from) continue
            basal += scheduled(at, from) ?: return null
            val profile = profileFunction.getProfile(from) ?: return null
            basal += row.convertedToAbsolute(from, profile) * (to - from) / ApexClock.HOUR_MS
            at = to
        }
        basal += scheduled(at, end) ?: return null
        return basal
    }

    /** The schedule's basal from one moment to another, minute by minute: the rate may change on the half hour. */
    private suspend fun scheduled(from: Long, to: Long): Double? {
        var sum = 0.0
        var t = from
        while (t < to) {
            val next = minOf(to, ApexClock.minuteStart(t) + ApexClock.MINUTE_MS)
            val profile = profileFunction.getProfile(t) ?: return null
            sum += profile.getBasal(t) * (next - t) / ApexClock.HOUR_MS
            t = next
        }
        return sum
    }
}
