package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.pulse.JournalRead
import app.aaps.pump.apex.statistics.StatisticsDays
import app.aaps.pump.apex.store.ApexStore
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexProtocol
import kotlin.math.abs

/**
 * Whether the bolus journal has to be read at this heartbeat. The daily statistics say what the
 * pump delivered today by kind, and a bolus that went in since the last heartbeat moves the bolus
 * field. So the journal is read when the bolus field moved, and when a bolus of ours waits for the
 * reconciliation: its picture is held against the records, see `BolusReconciliation`. It is left alone when
 * the field did not move and no bolus of ours waits. The basal and temporary basal fields are only
 * reported, for the log.
 *
 * The journal is read all the same when the statistics cannot answer the question: no day kept yet,
 * a new date (the fields started over), or the three fields not adding up to the count of Status
 * V1 beyond one step of the pump (the two are read a pause apart, and a step of basal may fall
 * between). A day with no date that came the same at all three reads is what the pump holds, see
 * `BrokenRecord`, and is taken as it is. Today below the day kept in some field is a day the pump
 * started over after a power loss, see [bolusMovedSinceKept].
 */
class BolusGate(private val store: ApexStore, private val serial: String) {

    /** The verdict and what it was made from; the moves are null when there was no kept day of the same date to compare with. */
    class Decision(
        val read: Boolean,
        val reason: String,
        val bolusMovedUnits: Double? = null,
        val basalMovedUnits: Double? = null,
        val tbrMovedUnits: Double? = null,
        /** The day's three fields less the count of Status V1, U; null when the answer had no day. */
        val sumGapUnits: Double? = null
    )

    fun decide(counterUnits: Double, days: JournalRead<ApexDailyRecord>): Decision {
        val today = days.answer.entries.firstOrNull()?.record ?: return Decision(true, "the statistics came with no day")
        val gap = today.totalUnits - counterUnits
        val kept = keptDay() ?: return Decision(true, "no day kept yet", sumGapUnits = gap)
        if (abs(gap) > ApexProtocol.DOSE_SCALE + ApexProtocol.ROUNDING) return Decision(true, "the day's fields do not add up to the count", sumGapUnits = gap)
        if (today.dayClockSeconds != kept.dayClockSeconds) return Decision(true, "a new day on the pump", sumGapUnits = gap)
        if (!ApexDailyRecord.holdsKept(today, kept)) return Decision(true, "today went back below the day kept: the pump lost its power and started the day over", sumGapUnits = gap)
        val bolus = today.bolusUnits - kept.bolusUnits
        val basal = today.basalUnits - kept.basalUnits
        val tbr = today.tbrUnits - kept.tbrUnits
        fun verdict(read: Boolean, reason: String) = Decision(read, reason, bolus, basal, tbr, gap)
        return when {
            abs(bolus) > ApexProtocol.ROUNDING                    -> verdict(true, "the bolus field moved")
            BolusAccount(store, serial).openActions().any() -> verdict(true, "a bolus of ours waits for the reconciliation")
            else                                            -> verdict(false, "no bolus since the last heartbeat")
        }
    }

    /**
     * How far the bolus field moved since the day kept at the last heartbeat, U. Across the pump's midnight the
     * kept day is found again among the older days of [days], and each day after it counts whole, but for
     * the copies of a day, see [StatisticsDays.counted]. Null when no day is kept, or the kept day is not in the answer.
     */
    fun bolusMovedSinceKept(days: ApexJournalAnswer<ApexDailyRecord>): Double? {
        val kept = keptDay() ?: return null
        val newestFirst = days.entries.map { it.record }
        val at = ApexDailyRecord.indexOfKept(newestFirst, kept) ?: return null
        return StatisticsDays.counted(newestFirst.take(at)).sumOf { it.bolusUnits } + newestFirst[at].bolusUnits - kept.bolusUnits
    }

    private fun keptDay(): ApexDailyRecord? = StatisticsDays(store, serial).active()
}
