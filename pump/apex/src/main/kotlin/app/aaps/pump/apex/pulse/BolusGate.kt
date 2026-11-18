package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.account.bolus.BolusAccount
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.Snapshot
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexResponseFrame
import kotlin.math.abs

/**
 * Whether the bolus journal has to be read at this heartbeat. The daily statistics say what the
 * pump delivered today by kind, and a bolus that went in since the last heartbeat moves the bolus
 * field. Our own boluses move it too, and their records are settled by the reconciliation that runs
 * when our bolus ends; a bolus settled when no day could be kept is kept by the account until the
 * next day is kept. So the journal is read when the bolus
 * field moved by more than our boluses together, a bolus somebody else gave, or by less, which
 * the statistics should not do; and left alone when it moved by our boluses alone, or not at all.
 * The basal and temporary basal fields are only reported, for the log.
 *
 * The journal is read all the same when the statistics cannot answer the question: no day kept yet,
 * a new date (the fields started over), or the three fields not adding up to the count of Status
 * V1 beyond one step of the pump (the two are read a pause apart, and a step of basal may fall
 * between). An order of ours not settled yet reads the journal too: the read after our bolus did not
 * come, and the field already counts it. A day with no date that came the same at every read is
 * what the pump holds, see `BrokenRecordDeep`, and is taken as it is. Today below the day kept in
 * some field is a day the pump started over after a power loss, see [bolusMovedSinceKept].
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
        val sumGapUnits: Double? = null,
        /** Our boluses settled since the last heartbeat, U each; null when the fields were not compared. */
        val ourBolusUnits: List<Double>? = null
    )

    /** [days] null: the pump gives no daily statistics, and the journal is read at every heartbeat. */
    fun decide(counterUnits: Double, days: JournalRead<ApexDailyRecord>?): Decision {
        if (days == null) return Decision(true, "the pump gives no daily statistics")
        val today = days.answer.entries.firstOrNull()?.record ?: return Decision(true, "the statistics came with no day")
        val gap = today.totalUnits - counterUnits
        val kept = keptDay() ?: return Decision(true, "no day kept yet", sumGapUnits = gap)
        if (abs(gap) > ApexProtocol.DOSE_SCALE + ApexProtocol.ROUNDING) return Decision(true, "the day's fields do not add up to the count", sumGapUnits = gap)
        if (today.dayClockSeconds != kept.dayClockSeconds) return Decision(true, "a new day on the pump", sumGapUnits = gap)
        if (!ApexDailyRecord.holdsKept(today, kept)) return Decision(true, "today went back below the day kept: the pump lost its power and started the day over", sumGapUnits = gap)
        val bolus = today.bolusUnits - kept.bolusUnits
        val basal = today.basalUnits - kept.basalUnits
        val tbr = today.tbrUnits - kept.tbrUnits
        val account = BolusAccount(store, serial)
        val ours = account.settledSinceHeartbeat()
        val rest = bolus - ours.sum()
        fun verdict(read: Boolean, reason: String) = Decision(read, reason, bolus, basal, tbr, gap, ours)
        return when {
            ours.isEmpty() && abs(rest) > ApexProtocol.ROUNDING -> verdict(true, "the bolus field moved")
            rest > ApexProtocol.ROUNDING                        -> verdict(true, "the bolus field moved by more than our boluses")
            rest < -ApexProtocol.ROUNDING                       -> verdict(true, "the bolus field moved by less than our boluses")
            account.openActions().any()            -> verdict(true, "an order of ours waits for its record")
            ours.isNotEmpty()                      -> verdict(false, "the bolus field moved by our boluses alone")
            else                                   -> verdict(false, "no bolus since the last heartbeat")
        }
    }

    /**
     * Keep today's record of [days] as the day the next span is counted from: the status
     * kept by a reconciliation that succeeded, see `Reconciliation`. It counts every bolus settled so far, so the account's list of
     * them starts over. Not while an order of ours is not settled: it is counted from the day kept
     * already, and would be lost if the field that holds it became the new start.
     */
    fun keep(days: JournalRead<ApexDailyRecord>, readAtMs: Long) {
        if (BolusAccount(store, serial).openActions().any()) return
        val top = days.answer.entries.firstOrNull()?.frame?.raw ?: return
        store.saveSnapshot(serial, SnapshotKind.DAILY, Snapshot(listOf(top), readAtMs))
        BolusAccount(store, serial).clearSettledSinceHeartbeat()
    }

    /**
     * How far the bolus field moved since the day kept at the last heartbeat, U. Across the pump's
     * midnight the kept day is found again among the older days of [days], and each day after it
     * counts whole. Null when no day is kept, or the kept day is not in the answer.
     */
    fun bolusMovedSinceKept(days: ApexJournalAnswer<ApexDailyRecord>): Double? {
        val kept = keptDay() ?: return null
        val newestFirst = days.entries.map { it.record }
        val at = ApexDailyRecord.indexOfKept(newestFirst, kept) ?: return null
        return newestFirst.take(at).sumOf { it.bolusUnits } + newestFirst[at].bolusUnits - kept.bolusUnits
    }

    /** True when a day with no date stands at the kept day or after it in [days]: a day cut short by the loss of the battery. */
    fun undatedSinceKept(days: ApexJournalAnswer<ApexDailyRecord>): Boolean {
        val kept = keptDay() ?: return false
        val newestFirst = days.entries.map { it.record }
        val at = ApexDailyRecord.indexOfKept(newestFirst, kept) ?: return false
        return newestFirst.take(at + 1).any { !it.hasDate }
    }

    /** True when a day is kept for the next span to be counted from. */
    fun hasDayKept(): Boolean = keptDay() != null

    private fun keptDay(): ApexDailyRecord? =
        store.snapshot(serial, SnapshotKind.DAILY)?.frames?.firstOrNull()?.let { ApexDailyRecord.decode(ApexResponseFrame(it)) }

    private companion object {
    }
}
