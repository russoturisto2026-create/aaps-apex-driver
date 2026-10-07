package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.protocol.Atc3BolusHistory
import app.aaps.pump.atc3.protocol.Atc3BolusRecord
import app.aaps.pump.atc3.protocol.Atc3Protocol
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** What is to be done about one bolus record. */
sealed interface Atc3BolusAction {

    /** The record is a bolus AAPS is counting: its row becomes the real one, dated by the record's stamp. */
    data class ResolvePending(
        val pending: PendingBolus,
        val timestamp: Long,
        val units: Double,
        val pumpId: Long,
        val carriesExtendedPart: Boolean
    ) : Atc3BolusAction

    /** A bolus nobody told AAPS about: given on the pump itself or from another device. */
    data class Import(
        val timestamp: Long,
        val units: Double,
        val pumpId: Long,
        val carriesExtendedPart: Boolean
    ) : Atc3BolusAction

    /** A row brought to the pump's record: ours closed on its completion frame, or one whose amount the pump has since corrected. */
    data class Rewrite(
        val timestamp: Long,
        val units: Double,
        val pumpId: Long
    ) : Atc3BolusAction

    /**
     * A bolus the pump accepted and never wrote a record of: a record of a later minute is already there.
     * The row is given up and the user told, rather than closed at a figure watched off progress frames.
     */
    data class DropPending(
        val pending: PendingBolus,
        val pumpId: Long
    ) : Atc3BolusAction

    /** The pump was asked and still has no record for this bolus; count the attempt. */
    data class CountAttempt(val pending: PendingBolus) : Atc3BolusAction

    /** Nothing to record, but the record is now accounted for and will not be looked at again. */
    data class Consume(val pumpId: Long, val reason: String) : Atc3BolusAction
}

/**
 * Decides for every record the pump returned whether AAPS already has it: the bolus AAPS is waiting
 * to confirm, another's to import, or one counted before.
 *
 * A record is a bolus of ours when it asked for exactly our dose, delivered no more, and sits in the
 * minute ours started in or one either side, for the pump's clock. Records are paired first in,
 * first out: our boluses oldest first, each taking the oldest free record it can be, since the pump
 * writes them in the order it delivered them.
 */
object Atc3BolusReconciler {

    /**
     * @param clockShiftMinutes where our boluses' records sat against their starts: 0, -1 or +1, or null when none of ours was matched
     */
    data class Outcome(
        val actions: List<Atc3BolusAction>,
        val ledger: Atc3HistoryLedger,
        val clockShiftMinutes: Int? = null
    )

    /**
     * @param records what the pump returned, in arrival order
     * @param recordCount the count the frames declared, for diagnostics
     * @param phoneNow the phone's clock now
     * @param earliestAcceptedMs the moment AAPS adopted this pump: anything older it refuses
     */
    fun reconcile(
        records: List<Atc3BolusRecord>,
        recordCount: Int,
        ledger: Atc3HistoryLedger,
        phoneNow: Long,
        earliestAcceptedMs: Long = 0L
    ): Outcome {
        var current = ledger.withRecordCount(recordCount)
        val actions = ArrayList<Atc3BolusAction>()

        // Oldest first: AAPS needs the events in order.
        val ordered = records.sortedBy { it.pumpClockUtcSeconds }
        val firstPass = !current.firstPassDone
        // Decided for the whole read first: which were counted, and which are ours.
        val seenPairs = current.pairWithSeen(ordered)
        val pairs = pairUp(ordered.filter { !seenPairs.containsKey(it) }, ownDoses(current))

        for (record in ordered) {
            val timestamp = record.timestamp
            val alreadySeen = seenPairs[record]

            if (alreadySeen != null) {
                if (alreadySeen.fingerprint != record.fingerprint) {
                    actions.add(Atc3BolusAction.Rewrite(timestamp, record.aapsUnits, alreadySeen.pumpId))
                    current = current.withSeen(
                        alreadySeen.copy(fingerprint = record.fingerprint, pumpClockUtcSeconds = record.pumpClockUtcSeconds)
                    )
                }
                continue
            }

            val dose = pairs[record]
            val pending = dose?.pending
            val settled = dose?.settled
            // Our row takes the record's stamp, whatever minute: the record is the pump's past. The minute it
            // sits off is the clock's miss, measured apart.

            if (pending != null) {
                val pumpId = current.assignPumpId(record)
                actions.add(
                    Atc3BolusAction.ResolvePending(
                        pending, record.timestamp, record.aapsUnits, pumpId, record.carriesExtendedPart
                    )
                )
                current = current.withoutPending(pending.temporaryId)
                    .withSeen(SeenBolus(pumpId, record.pumpClockUtcSeconds, record.fingerprint))
                continue
            }

            if (settled != null) {
                // Ours closed on its completion frame: already in AAPS under the record's id, now dated by it.
                actions.add(Atc3BolusAction.Rewrite(record.timestamp, record.aapsUnits, settled.pumpId))
                current = current.withoutSettled(settled.startedAtMs)
                    .withSeen(SeenBolus(settled.pumpId, record.pumpClockUtcSeconds, record.fingerprint))
                continue
            }

            val pumpId = current.assignPumpId(record)
            when {
                record.isEmptyRecord      ->
                    actions.add(Atc3BolusAction.Consume(pumpId, "record carries no amount"))

                firstPass                 ->
                    actions.add(Atc3BolusAction.Consume(pumpId, "history the pump already held"))

                timestamp < earliestAcceptedMs ->
                    // AAPS refuses anything from before it adopted this pump.
                    actions.add(Atc3BolusAction.Consume(pumpId, "older than the moment AAPS adopted this pump"))

                isTooOld(record, timestamp, current, phoneNow) ->
                    actions.add(Atc3BolusAction.Consume(pumpId, "older than the watermark"))

                else                      ->
                    actions.add(
                        Atc3BolusAction.Import(timestamp, record.aapsUnits, pumpId, record.carriesExtendedPart)
                    )
            }
            current = current.withSeen(SeenBolus(pumpId, record.pumpClockUtcSeconds, record.fingerprint))
        }

        if (ordered.isNotEmpty()) {
            val newestRecord = ordered.last()
            val newest = newestRecord.pumpClockUtcSeconds
            if (firstPass || newest > current.importFromUtcSeconds) {
                current = current.withWatermark(newest, newestRecord.timestamp)
            }
        } else if (firstPass) {
            current = current.withWatermark(current.importFromUtcSeconds, phoneNow)
        }

        // A bolus of ours waits for its record however long: an alarm can hold it back, and the pump writes
        // nothing else meanwhile. A record of a later minute, past the clock's slack, means ours will never come.
        val newestMinute = (ordered.map { it.pumpClockUtcSeconds } + current.seen.map { it.pumpClockUtcSeconds })
            .maxOrNull()?.let { minuteOf(it) }
        for (pending in current.pending) {
            val provenAbsent = newestMinute != null && newestMinute > minuteOf(pending.startUtcSeconds) + 1
            if (!provenAbsent) {
                actions.add(Atc3BolusAction.CountAttempt(pending))
                current = current.withPendingAttempt(pending.temporaryId, pending.confirmAttempts + 1)
                continue
            }
            actions.add(
                Atc3BolusAction.DropPending(
                    pending,
                    Atc3PumpId.of(pending.startUtcSeconds * 1000L, Atc3PumpId.KIND_RETRACTION)
                )
            )
            // Nothing is kept of it: the pump holds no record of this bolus and will write none.
            current = current.withoutPending(pending.temporaryId)
        }

        // Kept only as long as its record could still turn up.
        current = current.copy(
            settled = current.settled.filter { phoneNow - it.settledAtMs < Atc3Const.RECONCILE_MAX_AGE_MS }
        )

        val shifts = pairs.entries.map { (record, dose) -> (minuteOf(record.pumpClockUtcSeconds) - dose.startMinute).toInt() }
        val clockShift = if (shifts.isEmpty()) null else shifts.firstOrNull { it != 0 } ?: 0
        return Outcome(actions, current, clockShift)
    }

    /**
     * True when the pump holds records this answer did not carry, and the oldest it carried is newer
     * than the import boundary: then what lies between may be insulin AAPS never had. Not before the
     * first pass, which imports nothing.
     */
    fun recordsMissing(history: Atc3BolusHistory, ledger: Atc3HistoryLedger): Boolean {
        if (!ledger.firstPassDone) return false
        if (history.records.isEmpty()) return false
        if (history.recordCount <= history.records.size) return false
        val oldestSent = history.records.minOf { it.pumpClockUtcSeconds }
        return oldestSent > ledger.importFromUtcSeconds
    }

    /** True when these records would resolve that pending bolus, changing nothing. */
    fun wouldResolve(
        records: List<Atc3BolusRecord>,
        pending: PendingBolus,
        ledger: Atc3HistoryLedger
    ): Boolean {
        val ordered = records.sortedBy { it.pumpClockUtcSeconds }
        val seenPairs = ledger.pairWithSeen(ordered)
        val fresh = ordered.filter { !seenPairs.containsKey(it) }
        return pairUp(fresh, ownDoses(ledger)).values.any { it.pending?.temporaryId == pending.temporaryId }
    }

    /** Whether this record is the pump's past: behind the boundary on both clocks, and strictly older. */
    private fun isTooOld(
        record: Atc3BolusRecord,
        timestamp: Long,
        ledger: Atc3HistoryLedger,
        phoneNow: Long
    ): Boolean =
        (record.pumpClockUtcSeconds < ledger.importFromUtcSeconds && timestamp <= ledger.importFromPhoneMs) ||
            timestamp < phoneNow - Atc3Const.RECONCILE_MAX_AGE_MS

    /** One bolus of ours waiting for its record: running or cut short ([pending]), or closed on its completion frame ([settled]). */
    private class OwnDose(val startUtcSeconds: Long, val requestedRaw: Int, val pending: PendingBolus?, val settled: SettledBolus?) {

        val startMinute: Long get() = Math.floorDiv(startUtcSeconds, 60L)
    }

    private fun ownDoses(ledger: Atc3HistoryLedger): List<OwnDose> =
        ledger.pending.map { OwnDose(it.startUtcSeconds, raw(it.requestedUnits), it, null) } +
            ledger.settled.map { OwnDose(it.startUtcSeconds, raw(it.requestedUnits), null, it) }

    /** Which record is which bolus of ours, first in, first out; keyed by the record object, as two equal records are two boluses. */
    private fun pairUp(records: List<Atc3BolusRecord>, doses: List<OwnDose>): Map<Atc3BolusRecord, OwnDose> {
        val paired = IdentityHashMap<Atc3BolusRecord, OwnDose>()
        val oldestFirst = records.sortedBy { it.pumpClockUtcSeconds }
        for (dose in doses.sortedBy { it.startUtcSeconds }) {
            val record = oldestFirst.firstOrNull { !paired.containsKey(it) && fits(it, dose) } ?: continue
            paired[record] = dose
        }
        return paired
    }

    /** Whether this record can be that bolus of ours: the same dose asked, no more delivered, the same minute or one either side. */
    private fun fits(record: Atc3BolusRecord, dose: OwnDose): Boolean =
        minuteOf(record.pumpClockUtcSeconds) in (dose.startMinute - 1)..(dose.startMinute + 1) &&
            record.rawRequested == dose.requestedRaw &&
            record.rawExtendedRequested == 0 &&
            record.rawExtendedDelivered == 0 &&
            record.rawDelivered <= record.rawRequested

    /** Whether [records] hold what can be the record of our bolus started at [startUtcSeconds] for [requestedUnits]. */
    fun holdsRecordOf(records: List<Atc3BolusRecord>, startUtcSeconds: Long, requestedUnits: Double): Boolean {
        val minute = minuteOf(startUtcSeconds)
        val requestedRaw = raw(requestedUnits)
        return records.any {
            minuteOf(it.pumpClockUtcSeconds) in (minute - 1)..(minute + 1) &&
                it.rawRequested == requestedRaw && it.rawExtendedRequested == 0
        }
    }

    private fun minuteOf(utcSeconds: Long): Long = Math.floorDiv(utcSeconds, 60L)

    private fun raw(units: Double): Int = (units / Atc3Protocol.DOSE_SCALE).roundToInt()
}
