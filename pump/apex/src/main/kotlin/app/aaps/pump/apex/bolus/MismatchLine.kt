package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.aaps.NoticeKind
import app.aaps.pump.apex.statistics.StatisticsDays
import app.aaps.pump.apex.store.ApexStore
import app.aaps.pump.apex.protocol.ApexDailyRecord
import kotlin.math.abs

/**
 * The line drawn when the bolus records do not sum to the bolus field of the statistics and no faulty record
 * can take the rest. Everything before the line is taken as it is: the rest is not written, so it is not in
 * IOB, and nothing before the line is checked again. The loop is opened and the user is told; a second line
 * in one day is a serious error of the driver, and the user is asked to tell the developer.
 *
 * Only the last line is kept: its moment and the bolus field of its day at that moment. The day of the line
 * is checked from that field, see [WholeJournalDays.checkDays], and no record stamped before it goes to AAPS,
 * see [WholeJournal]. A second line moves it on: nothing adds up, nothing is rolled back.
 */
internal class MismatchLine(private val store: ApexStore, private val serial: String) {

    /** The line: drawn at [atMs], in the pump's day [day] (its clock key), when the bolus field of that day was [fieldUnits] U. */
    class Mark(val atMs: Long, val day: Long, val fieldUnits: Double)

    /** The last line drawn; null when none was. */
    fun mark(): Mark? {
        val parts = store.state(serial, LINE_KEY)?.split(';') ?: return null
        return Mark(parts.getOrNull(0)?.toLongOrNull() ?: return null, parts.getOrNull(1)?.toLongOrNull() ?: return null, parts.getOrNull(2)?.toDoubleOrNull() ?: return null)
    }

    /**
     * Draw the line at [atMs]: the records from [fromMs] on did not sum to the statistics [newestFirst] by
     * [units] U. Returns what goes to AAPS: the notice, the second one of the day as a serious error, and the
     * loop opened. Nothing is drawn when the statistics hold no today.
     */
    fun draw(fromMs: Long, atMs: Long, units: Double, newestFirst: List<ApexDailyRecord>): List<AapsWrite> {
        val day = StatisticsDays.datesOf(newestFirst).firstOrNull() ?: return emptyList()
        val field = StatisticsDays.bolusOfDay(newestFirst, day) ?: return emptyList()
        val before = store.state(serial, COUNT_KEY)?.split(';')
        val count = if (before?.getOrNull(0)?.toLongOrNull() == day) (before.getOrNull(1)?.toIntOrNull() ?: 0) + 1 else 1
        store.putState(serial, LINE_KEY, "$atMs;$day;$field")
        store.putState(serial, COUNT_KEY, "$day;$count")
        val kind = if (count > 1) NoticeKind.JOURNAL_MISMATCH_AGAIN else NoticeKind.JOURNAL_MISMATCH
        return listOf(AapsWrite.Notice(kind, units = abs(units), atMs = atMs, fromMs = fromMs), AapsWrite.OpenLoop)
    }

    private companion object {

        const val LINE_KEY = "bolus_mismatch_line"
        const val COUNT_KEY = "bolus_mismatch_count"
    }
}
