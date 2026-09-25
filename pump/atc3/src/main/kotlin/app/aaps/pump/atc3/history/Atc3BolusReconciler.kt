package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.comm.Atc3BolusHistory
import app.aaps.pump.atc3.comm.Atc3BolusRecord
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** What is to be done about one bolus record. */
sealed interface Atc3BolusAction {

    /**
     * The record is the bolus AAPS is already counting; the temporary row becomes the real one.
     *
     * [timestamp] is the start of the bolus, the moment the pump accepted it, not the record's
     * stamp: the record carries only the minute.
     */
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

    /** A record already counted whose delivered amount the pump has since corrected upwards. */
    data class Rewrite(
        val timestamp: Long,
        val units: Double,
        val pumpId: Long
    ) : Atc3BolusAction

    /**
     * A bolus the pump accepted and never wrote a record for: the history already holds a record
     * from a later minute, and the pump writes nothing else while a record of ours is still owed.
     * The row is given up on.
     *
     * Not closed at some figure watched off progress frames: those numbers are for the screen, and
     * a bolus closed at one of them is a guess written into the record as though it were a fact.
     * Either the pump says what it delivered or nobody does, and when nobody does the honest thing
     * is to say so to the user and stop counting insulin that was never confirmed.
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
 * Decides, for every record the pump returned, whether AAPS already knows about it.
 *
 * This is the whole policy of both halves of the work in one pure function: which record is the
 * bolus AAPS started and is waiting to confirm, which is somebody else's that has to be imported,
 * and which has already been counted and must never be counted again. Being pure is what lets
 * every case be pinned as a unit test.
 *
 * Times: identity always uses [Atc3BolusRecord.pumpClockUtcSeconds], which no timezone can move.
 * A bolus of ours keeps the moment the pump accepted it. A bolus somebody else gave is handed to
 * AAPS at the time its record carries, as the pump wrote it. The pump's clock is written from the
 * phone's, so the two agree; nothing read from the pump's status is a source of time.
 *
 * **Which record is ours.** The pump stamps a bolus record with the minute the bolus started, at
 * second 59. A record can be a bolus of ours when it asked for exactly the dose ours asked for,
 * delivered no more than that, and sits in the minute ours started in or the one either side. The
 * minute either side is the pump's clock: a few seconds behind the phone's, a bolus started in the
 * first seconds of a minute is written into the minute before, while one started later in the same
 * minute is not.
 *
 * Records are paired first in, first out: our boluses oldest first, each taking the oldest record
 * still free that it can be. The pump writes our boluses in the order it delivered them, so the order
 * decides where the minute cannot. Taking the record in the exact minute first would not: two of our
 * boluses in neighbouring minutes, on a clock a minute behind, would each take the other's record and
 * leave one to be imported as a stranger's.
 */
object Atc3BolusReconciler {

    /**
     * @param clockShiftMinutes how the records of our own boluses sat against the minutes those
     *   boluses started: 0 when every one sat exactly there, -1 or +1 when one sat a minute early or
     *   late, null when no bolus of ours was matched in this read
     */
    data class Outcome(
        val actions: List<Atc3BolusAction>,
        val ledger: Atc3HistoryLedger,
        val clockShiftMinutes: Int? = null
    )

    /**
     * @param records what the pump returned, in arrival order
     * @param recordCount the count byte those frames carried, kept only as a diagnostic
     * @param phoneNow the phone clock now
     * @param earliestAcceptedMs the oldest treatment AAPS will take for this pump, which is the
     *   moment it adopted it; anything before that is refused by the core whatever the driver does,
     *   so it is recognised here rather than offered and silently dropped
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

        // Oldest first, because AAPS needs start and stop events in order to cut overlaps correctly.
        val ordered = records.sortedBy { it.pumpClockUtcSeconds }
        val firstPass = !current.firstPassDone
        // Decided for the whole read before any record is acted on: which records were counted
        // before, and which of the rest are our boluses.
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
            // A record a minute off our start is where the pump keeps that bolus, and the pump's past
            // cannot be changed. So our row is moved onto the pump's minute, keeping the second it
            // started at, and the two histories agree before the pump's clock is put right.
            val shiftMs = dose?.let { (minuteOf(record.pumpClockUtcSeconds) - it.startMinute) * 60_000L } ?: 0L

            if (pending != null) {
                val pumpId = current.assignPumpId(record)
                actions.add(
                    Atc3BolusAction.ResolvePending(
                        pending, pending.startedAtMs + shiftMs, record.aapsUnits, pumpId, record.carriesExtendedPart
                    )
                )
                current = current.withoutPending(pending.temporaryId)
                    .withSeen(SeenBolus(pumpId, record.pumpClockUtcSeconds, record.fingerprint))
                continue
            }

            if (settled != null) {
                // A bolus closed on its completion frame is already in AAPS under the id its record
                // would have had, so the record is filed under that id. One given up on has no row
                // under any id and its record gets one of its own.
                val pumpId = if (settled.pumpId != 0L) settled.pumpId else current.assignPumpId(record)
                actions.add(
                    when {
                        // The pump has finally written down a bolus this driver stopped waiting for.
                        // Importing it now would count the same insulin twice.
                        settled.pumpId == 0L                        ->
                            Atc3BolusAction.Consume(pumpId, "already settled at ${settled.units} U")

                        record.rawDelivered == raw(settled.units) && shiftMs == 0L ->
                            Atc3BolusAction.Consume(pumpId, "our bolus, closed on its completion frame")

                        // The pump's record says otherwise than the completion frame did, or keeps
                        // the bolus a minute away from our start; the record is what the pump keeps.
                        else                                        ->
                            Atc3BolusAction.Rewrite(settled.startedAtMs + shiftMs, record.aapsUnits, pumpId)
                    }
                )
                current = current.withoutSettled(settled.startedAtMs)
                    .withSeen(SeenBolus(pumpId, record.pumpClockUtcSeconds, record.fingerprint))
                continue
            }

            val pumpId = current.assignPumpId(record)
            when {
                record.isEmptyRecord      ->
                    actions.add(Atc3BolusAction.Consume(pumpId, "record carries no amount"))

                firstPass                 ->
                    actions.add(Atc3BolusAction.Consume(pumpId, "history the pump already held"))

                timestamp < earliestAcceptedMs ->
                    // AAPS refuses anything from before it adopted this pump, so offering it would
                    // be a write that always fails and a record marked as counted that never was.
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

        // A bolus of ours with no record waits for it however long it takes. An alarm holds the
        // record back while it stands, and the pump does nothing else meanwhile, so no later bolus
        // can be written down before ours. That is also
        // what settles the question the other way: a record from a later minute than ours, past the
        // minute of clock slack, while ours is not there, means the pump never wrote ours and never
        // will. The reads are counted for the log only; the count decides nothing.
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
            current = current.withoutPending(pending.temporaryId)
                .withSettled(
                    SettledBolus(
                        startedAtMs = pending.startedAtMs,
                        requestedUnits = pending.requestedUnits,
                        units = 0.0,
                        settledAtMs = phoneNow,
                        startUtcSeconds = pending.startUtcSeconds
                    )
                )
        }

        // A settlement is only worth keeping for as long as a record for it could still turn up.
        // Past that the reconciler would not import the record anyway, so the entry has no work left.
        current = current.copy(
            settled = current.settled.filter { phoneNow - it.settledAtMs < Atc3Const.RECONCILE_MAX_AGE_MS }
        )

        val shifts = pairs.entries.map { (record, dose) -> (minuteOf(record.pumpClockUtcSeconds) - dose.startMinute).toInt() }
        val clockShift = if (shifts.isEmpty()) null else shifts.firstOrNull { it != 0 } ?: 0
        return Outcome(actions, current, clockShift)
    }

    /**
     * True when the pump is holding bolus records this answer did not carry.
     *
     * The periodic `0x21` search stops at ten frames while its count byte keeps counting every
     * record stored, so the two disagreeing is the pump saying "there is more than I sent". That
     * alone is not a problem: the records beyond the tenth are usually ones already accounted for.
     * It only matters when the oldest record in the answer is *newer* than the watermark, because
     * then the stretch between the watermark and that record is history nobody has looked at, and
     * it can hold insulin that never reached AAPS.
     *
     * Both conditions are needed. The count alone would ask for the full history on every read of
     * a pump with a long history; the watermark alone cannot tell a gap from an empty pump.
     *
     * Before the first pass there is nothing to miss: that pass takes stock of everything the pump
     * already held and imports none of it, so reading more of it would be wasted.
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

    /**
     * Whether this record belongs to the pump's past rather than to what AAPS should import.
     *
     * Behind the watermark counts only when it is behind on both clocks. The pump clock alone would
     * blacklist a whole hour of records after the pump clock is put back, and it is strictly older
     * rather than at the watermark second, because the pump writes a correction record into the
     * very second the record before it has just vacated.
     */
    private fun isTooOld(
        record: Atc3BolusRecord,
        timestamp: Long,
        ledger: Atc3HistoryLedger,
        phoneNow: Long
    ): Boolean =
        (record.pumpClockUtcSeconds < ledger.importFromUtcSeconds && timestamp <= ledger.importFromPhoneMs) ||
            timestamp < phoneNow - Atc3Const.RECONCILE_MAX_AGE_MS

    /**
     * One bolus of ours still waiting for its record: running or cut short ([pending]), or closed
     * already, on its completion frame or given up on ([settled]).
     */
    private class OwnDose(val startUtcSeconds: Long, val requestedRaw: Int, val pending: PendingBolus?, val settled: SettledBolus?) {
        val startMinute: Long get() = Math.floorDiv(startUtcSeconds, 60L)
    }

    private fun ownDoses(ledger: Atc3HistoryLedger): List<OwnDose> =
        ledger.pending.map { OwnDose(it.startUtcSeconds, raw(it.requestedUnits), it, null) } +
            ledger.settled.map { OwnDose(it.startUtcSeconds, raw(it.requestedUnits), null, it) }

    /**
     * Which record is which bolus of ours, first in, first out.
     *
     * Our boluses oldest first; each takes the oldest record still free that it can be. Keyed by the
     * record object itself, not by its value: two equal records in one minute are two boluses.
     */
    private fun pairUp(records: List<Atc3BolusRecord>, doses: List<OwnDose>): Map<Atc3BolusRecord, OwnDose> {
        val paired = IdentityHashMap<Atc3BolusRecord, OwnDose>()
        val oldestFirst = records.sortedBy { it.pumpClockUtcSeconds }
        for (dose in doses.sortedBy { it.startUtcSeconds }) {
            val record = oldestFirst.firstOrNull { !paired.containsKey(it) && fits(it, dose) } ?: continue
            paired[record] = dose
        }
        return paired
    }

    /**
     * Whether this record can be that bolus of ours.
     *
     * What was asked for has to agree exactly — that field never changes, and our boluses never
     * carry an extended part. What was delivered may be less, never more: a bolus cut short by a
     * cancel, an alarm or the link delivers less than it asked for. The minute is the one the bolus
     * started in or the one either side, for the pump's clock.
     */
    private fun fits(record: Atc3BolusRecord, dose: OwnDose): Boolean =
        minuteOf(record.pumpClockUtcSeconds) in (dose.startMinute - 1)..(dose.startMinute + 1) &&
            record.rawRequested == dose.requestedRaw &&
            record.rawExtendedRequested == 0 &&
            record.rawExtendedDelivered == 0 &&
            record.rawDelivered <= record.rawRequested

    private fun minuteOf(utcSeconds: Long): Long = Math.floorDiv(utcSeconds, 60L)

    private fun raw(units: Double): Int = (units / Atc3Const.DOSE_SCALE).roundToInt()
}
