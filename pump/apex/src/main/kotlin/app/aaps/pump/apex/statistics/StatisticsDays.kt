package app.aaps.pump.apex.statistics

import app.aaps.pump.apex.bolus.BolusCells
import app.aaps.pump.apex.store.ApexStore
import app.aaps.pump.apex.store.Snapshot
import app.aaps.pump.apex.store.SnapshotKind
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexResponseFrame

/**
 * The driver's copy of the pump's daily statistics, newest day first. The top day is the active one, the
 * day the next heartbeat counts from. The days under it are closed: they are only for the start and for the
 * first heartbeat after midnight, so none older than the oldest bolus cell is kept. A heartbeat adds to the
 * copy only from the top, the new days and the day it kept last; the closed days of its answer are not
 * taken, so a closed day read broken never spoils the copy. The start takes them too, checked against the
 * copy, see `BrokenRecord.brokenDay`.
 *
 * Days are counted by the rule of [counted]: a date can stand more than once. A day with no date is
 * of the day after the first day with a date below it, see [datesOf].
 */
class StatisticsDays(private val store: ApexStore, private val serial: String) {

    /** The frames of the days kept, newest first; empty when none is. */
    fun keptFrames(): List<ByteArray> = frames()

    /** The days kept, newest first; empty when none is. */
    fun kept(): List<ApexDailyRecord> = frames().mapNotNull { ApexDailyRecord.decode(ApexResponseFrame(it)) }

    /** The day the next heartbeat counts from: the top of the copy. */
    fun active(): ApexDailyRecord? = frames().firstOrNull()?.let { ApexDailyRecord.decode(ApexResponseFrame(it)) }

    /** How the basal and the temporary basal fields moved since the day kept, U, over [ms]. */
    class Moved(val basalUnits: Double, val tbrUnits: Double, val ms: Long)

    /**
     * The move of [today], read at [atMs], since the day kept. Null when no day is kept, when today is another
     * date, or when today fell below the day kept (the pump started the day over): the move is not known then.
     */
    fun movedSinceKept(today: ApexDailyRecord, atMs: Long): Moved? {
        val kept = active() ?: return null
        if (today.dayClockSeconds != kept.dayClockSeconds || !ApexDailyRecord.holdsKept(today, kept)) return null
        val keptAtMs = store.snapshot(serial, SnapshotKind.DAILY)?.readAtMs ?: return null
        return Moved(today.basalUnits - kept.basalUnits, today.tbrUnits - kept.tbrUnits, atMs - keptAtMs)
    }

    /**
     * [keep] at the end of a reconciliation that succeeded: today's day becomes the one the next heartbeat
     * counts from. Not while a bolus of ours waits for its record: it is counted from the day kept already,
     * and would be lost if the field that holds it became the new start.
     */
    fun keepReconciled(answer: ApexJournalAnswer<ApexDailyRecord>, readAtMs: Long, closedToo: Boolean = false) {
        if (store.cells(serial).any { it.open && it.action != null }) return
        keep(answer, readAtMs, closedToo)
    }

    /**
     * Lay [answer] over the copy, read at [readAtMs], and drop the days older than the bolus cells. [closedToo]: the
     * start, the closed days of the answer are taken too. The answer alone when the copy is empty, or its top
     * day is not in the answer.
     */
    fun keep(answer: ApexJournalAnswer<ApexDailyRecord>, readAtMs: Long, closedToo: Boolean = false) {
        val fresh = answer.entries.map { it.frame.raw }
        if (fresh.isEmpty()) return
        val kept = frames()
        val at = kept.firstOrNull()?.let { ApexDailyRecord.decode(ApexResponseFrame(it)) }?.let { top -> ApexDailyRecord.indexOfKept(answer.entries.map { it.record }, top) }
        val days = when {
            at == null -> fresh
            closedToo  -> fresh + kept.drop(fresh.size - at)
            else       -> fresh.take(at + 1) + kept.drop(1)
        }
        store.saveSnapshot(serial, SnapshotKind.DAILY, Snapshot(prune(days), readAtMs))
    }

    private fun frames(): List<ByteArray> = store.snapshot(serial, SnapshotKind.DAILY)?.frames.orEmpty()

    /**
     * [newestFirst] down to the last day of a date not older than the oldest record of the bolus
     * cells with a date (a faulty cell may have none), and the days with no date right under it: one may be the
     * start of that day, lost with the power, see [datesOf]. All of it when no record is kept.
     */
    private fun prune(newestFirst: List<ByteArray>): List<ByteArray> {
        val oldest = BolusCells(store, serial).keptFrames()?.mapNotNull { ApexBolusRecord.decode(ApexResponseFrame(it)) }?.lastOrNull { !it.dateBroken } ?: return newestFirst
        val oldestDay = Math.floorDiv(oldest.clockSeconds, SECONDS_PER_DAY) * SECONDS_PER_DAY
        val days = newestFirst.map { ApexDailyRecord.decode(ApexResponseFrame(it)) }
        var last = days.indexOfLast { it != null && it.hasDate && it.dayClockSeconds >= oldestDay }
        if (last < 0) return newestFirst.take(1)
        while (days.getOrNull(last + 1)?.hasDate == false) last++
        return newestFirst.take(last + 1)
    }

    companion object {

        private const val SECONDS_PER_DAY = 86_400L

        /**
         * [newestFirst] without the copies. A day is a copy when the day right above it has the same
         * date and holds it in every field. Every other day is a part of the day and counts: a day with
         * no date, parts of one date that do not stand together, a day the one above does not hold.
         */
        fun counted(newestFirst: List<ApexDailyRecord>): List<ApexDailyRecord> = newestFirst.filterIndexed { at, _ -> !isCopy(newestFirst, at) }

        /** True when the day at [at] of [newestFirst] is a copy of the day right above it, see [counted]. */
        fun isCopy(newestFirst: List<ApexDailyRecord>, at: Int): Boolean {
            val above = newestFirst.getOrNull(at - 1) ?: return false
            val day = newestFirst[at]
            return day.hasDate && above.hasDate && above.dayClockSeconds == day.dayClockSeconds && ApexDailyRecord.holdsKept(above, day)
        }

        /** The bolus field of the day [dayClockSeconds] in [newestFirst], U, its parts added and its copies left out; null when no part has that date. */
        fun bolusOfDay(newestFirst: List<ApexDailyRecord>, dayClockSeconds: Long): Double? {
            val dates = datesOf(newestFirst)
            val parts = newestFirst.indices.filter { dates[it] == dayClockSeconds && !isCopy(newestFirst, it) }
            return if (parts.isEmpty()) null else ApexProtocol.onStep(parts.sumOf { newestFirst[it].bolusUnits })
        }

        /** The pump's day, its start in pump clock seconds, of the moment [atMs] under the clock offset [offsetMs]. */
        fun dayOf(atMs: Long, offsetMs: Long): Long = Math.floorDiv(Math.floorDiv(atMs + offsetMs, 1000L), SECONDS_PER_DAY) * SECONDS_PER_DAY

        /**
         * The date of each day of [newestFirst]. A day with no date is a day the pump started at midnight and lost
         * with its power: the day after the first day with a date below it. The day above it is when the power came
         * back, a day or more later. With no day with a date below, it has the date of the first one above; null
         * when there is none either.
         */
        fun datesOf(newestFirst: List<ApexDailyRecord>): List<Long?> {
            val dated = newestFirst.map { it.dayClockSeconds.takeIf { _ -> it.hasDate } }
            return newestFirst.indices.map { at ->
                dated[at] ?: dated.drop(at + 1).firstOrNull { it != null }?.let { it + SECONDS_PER_DAY } ?: dated.take(at).lastOrNull { it != null }
            }
        }
    }
}
