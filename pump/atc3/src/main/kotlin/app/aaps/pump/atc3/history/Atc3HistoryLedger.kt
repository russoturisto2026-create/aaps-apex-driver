package app.aaps.pump.atc3.history

import app.aaps.core.data.model.BS
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.comm.Atc3BolusFingerprint
import app.aaps.pump.atc3.comm.Atc3BolusRecord
import app.aaps.pump.atc3.comm.Atc3StatusV1
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** A bolus AAPS has started counting but the pump has not yet written a record for. */
data class PendingBolus(
    /** Identifies the database row created when the pump accepted the command. */
    val temporaryId: Long,
    /** Phone clock at the moment the pump accepted the command. */
    val startedAtMs: Long,
    val requestedUnits: Double,
    val bolusType: BS.Type,
    /** The most the pump reported as delivered while it was running. */
    val reportedDeliveredUnits: Double = 0.0,
    /**
     * How many times the pump's history has been read without this bolus appearing in it.
     *
     * For the log only. The bolus waits for its record however many reads it takes; it is given up
     * on only when the history holds a record from a later minute, see [Atc3BolusReconciler].
     */
    val confirmAttempts: Int = 0,
    /**
     * The start, [startedAtMs], on the scale of a record's key: the pump's wall-clock digits read
     * through a UTC calendar ([app.aaps.pump.atc3.comm.Atc3StatusV1.wallClockUtcSeconds]).
     *
     * The pump's record of this bolus is stamped with the minute the bolus started, at second 59,
     * so this minute is what the record is matched on. The pump's clock is written from the phone's,
     * seconds included, so the phone's wall clock at the answer `A1/55` is the pump's.
     *
     * Taken when the bolus starts and kept, rather than worked out later: the conversion goes
     * through the timezone in force, and a record keeps the digits it was stamped with.
     */
    val startUtcSeconds: Long = Atc3StatusV1.wallClockUtcSeconds(startedAtMs)
)

/**
 * A bolus this driver gave up waiting for and closed at what it watched being delivered.
 *
 * Closing it is not the end of the story. The pump withholds the record of a bolus an alarm cut
 * short until that alarm is cleared, which can be hours. By then the pending entry is long gone, and
 * without this the record would come in as somebody else's bolus, counting the same insulin a
 * second time.
 *
 * So a settled bolus is remembered instead of forgotten, and the record that finally turns up is
 * recognised as the one already counted.
 *
 * A bolus the pump closed with its completion frame `A1/AA` is settled the same way, at the amount
 * that frame carried and without reading the history; its record is recognised when the history is
 * next read.
 */
data class SettledBolus(
    val startedAtMs: Long,
    val requestedUnits: Double,
    val units: Double,
    val settledAtMs: Long,
    /**
     * The id AAPS holds this bolus under, for one closed on its completion frame; zero for one
     * given up on, whose record still gets an id of its own when it turns up.
     */
    val pumpId: Long = 0L,
    /** The start on the scale of a record's key, see [PendingBolus.startUtcSeconds]. */
    val startUtcSeconds: Long = Atc3StatusV1.wallClockUtcSeconds(startedAtMs)
)

/** A record the driver has already accounted for, with the id it was given. */
data class SeenBolus(
    val pumpId: Long,
    val pumpClockUtcSeconds: Long,
    val fingerprint: Atc3BolusFingerprint
)

/** A temporary basal the driver believes is running. */
data class ActiveTbr(
    val pumpId: Long,
    val startedAtMs: Long,
    val rate: Double,
    /** True when AAPS asked for it, in which case its duration is AAPS's business, not ours. */
    val ours: Boolean,
    val ownDurationMs: Long,
    /**
     * When the pump says this temporary basal began, whole seconds on a UTC calendar, or null when
     * the pump was not asked or did not say.
     *
     * An identity rather than a time: it is what tells a renewed temporary basal from the one that
     * was already running, which no rate comparison can do when both run at the same rate.
     */
    val pumpStartUtcSeconds: Long? = null,
    /**
     * True when this is the pump being stopped, recorded as a temporary basal of zero, rather than a
     * temporary basal the pump runs. A stop has no start or end in the pump's temporary basal
     * objects, so none is asked for it.
     */
    val suspension: Boolean = false,
    /**
     * The pump's own start of it, [pumpStartUtcSeconds] on the phone's time scale, or null when
     * not known.
     *
     * Not where the row begins. The pump stamps a temporary basal with the time of its last status
     * snapshot, the whole minute before the command, so its start sits up to a minute before the
     * temporary basal really began. Our own row begins at
     * the moment the pump acknowledged the command, [startedAtMs], and this is kept beside it for
     * the one question the stamp answers -- whether a start the pump names later is another
     * temporary basal.
     */
    val pumpStartMs: Long? = null
) {

    /** Where the pump says it began when it said, else where the row begins. */
    val anchorMs: Long get() = pumpStartMs ?: startedAtMs
}

/**
 * A temporary basal AAPS itself asked for, noted so the journal cannot read it back as a stranger's.
 *
 * The journal does not say who set a temporary basal, so the driver has to recognise its own work
 * in it. A start alone cannot do that, and the reason is a difference of resolution: the journal's
 * starts are whole minutes, while the note is written at the second the pump acknowledged the
 * command, so an honest gap between the two runs anywhere from nothing to a whole minute. Any
 * window wide enough to hold that is wide enough to hold a stranger's temporary basal set in the
 * same minute. So the note carries what the temporary basal was, not only when it began. See
 * [Atc3TbrBook].
 */
data class OurTbr(
    /**
     * Where it began, keyed the way a journal record is keyed.
     *
     * The scale matters and is not the obvious one: these are pump wall-clock digits read through a
     * UTC calendar, what [app.aaps.pump.atc3.comm.Atc3StatusV1.wallClockUtcSeconds] produces, not
     * plain epoch seconds. They are only ever compared with record keys, which are on that scale,
     * and a plain epoch second here is a whole timezone offset out and matches nothing.
     */
    val startUtcSeconds: Long,
    /**
     * The rate the pump confirmed, in the pump's own raw steps, or null in a note read back from a
     * ledger written before notes carried one.
     *
     * Raw steps rather than units, as every other amount here is stored, so that no rounding and no
     * decimal comma can come between a note and the record it has to be recognised in.
     */
    val rawRate: Int? = null,
    /**
     * How long the pump said it was started for, minutes, or null in a note from an older ledger.
     *
     * The pump's figure, read back out of its status, not the one the loop asked for: the pump
     * takes whole quarter hours and the journal writes down what it took, so this is the number the
     * record will carry.
     */
    val durationMinutes: Int? = null,
    /** The id the temporary basal was written into AAPS under, or null in a note from an older ledger. */
    val pumpId: Long? = null,
    /**
     * True when [startUtcSeconds] is the pump's own start, read back from object `0x0A` after the
     * command, and the AAPS record already carries it. False when it is only the moment the pump
     * acknowledged the command, on the phone's clock.
     */
    val pumpStart: Boolean = false,
    /**
     * Where AAPS's record of it was closed, phone epoch milliseconds, or null while it is open.
     * Moving the record onto the pump's start has to leave its end where it is. Once the journal
     * has shaped the record this is the end the journal gave it.
     */
    val endMs: Long? = null,
    /**
     * Where AAPS's record of it begins, phone epoch milliseconds, or null in a note from an older
     * ledger. Usually the pump's own start; for the continuation of a temporary basal a stop
     * interrupted it is the moment of resuming, while [startUtcSeconds] stays the pump's start.
     */
    val rowMs: Long? = null,
    /**
     * The insulin the row was last shaped to from the journal, units, or null when it never was:
     * the row's rate is the average this amount makes over the row's span. Compared with the
     * record's delivered amount so that a row the journal agrees with is not written again.
     */
    val shapedUnits: Double? = null,
    /**
     * Insulin handed to this row from a row of the same pump start that AAPS cut to nothing --
     * two temporary basals of one minute share the pump's start -- units, or null. Added to the
     * record's own delivered amount when the row is shaped, so the minute's insulin lands in the
     * one row of the minute that has time.
     */
    val carriedUnits: Double? = null,
    /**
     * True for a temporary basal AAPS asked for. Its row stays as it was ordered, the rate over
     * the time between two acknowledgements, and the journal shapes only what somebody else set.
     */
    val asOrdered: Boolean = false
)

/**
 * The loop's last word on the temporary basal: what it asked the pump to run, or that it asked for
 * none.
 *
 * This is what the pump is held to. Every tick compares what the pump runs with it, and whatever
 * somebody else set or cancelled on the pump is put back to it at once.
 */
data class LoopTbr(
    /** The rate in the pump's raw steps, or null when the loop's last word was a cancel. */
    val rawRate: Int?,
    /** How long it was set for, minutes; zero for a cancel. */
    val durationMinutes: Int,
    /** When the pump acknowledged the command, phone epoch milliseconds. */
    val atMs: Long
) {

    /** When the loop's temporary basal runs out by itself, or null for a cancel. */
    val endsAtMs: Long? get() = rawRate?.let { atMs + durationMinutes * 60_000L }
}

/**
 * What the driver remembers about the pump's history between connections.
 *
 * The pump numbers nothing and rewrites timestamps, so identity has to be the driver's own and has
 * to survive the process being killed. This holds it: which records have been counted and under
 * which id, which boluses AAPS is already counting but the pump has not yet written down, how far
 * back reconciliation may reach, and the temporary basal currently believed to be running.
 *
 * Everything here is pure Kotlin so the rules can be unit tested. Persistence is a hand written
 * line format rather than JSON: the module depends on no JSON library, `org.json` is a stub in
 * plain JVM tests, and amounts are stored as raw integers so a comma decimal locale cannot corrupt
 * them.
 */
data class Atc3HistoryLedger(
    /** Serial of the pump this ledger belongs to; another pump's ledger is discarded, not reused. */
    val serial: String = "",
    /** Records older than this are the pump's past, not something to import, on the pump clock. */
    val importFromUtcSeconds: Long = 0L,
    /**
     * The same boundary on the phone clock.
     *
     * A record has to be behind both to count as the pump's past. The pump clock alone cannot say:
     * put it back an hour, as AAPS asks the user to do at the daylight saving change, and every
     * record for the next hour would look older than the watermark and be silently dropped.
     */
    val importFromPhoneMs: Long = 0L,
    /**
     * The newest temporary basal record already taken in from the pump's journal, on the pump clock.
     *
     * Kept apart from [importFromUtcSeconds] because the two journals are read at different times
     * and for different reasons: the bolus history whenever the books do not balance, the temporary
     * basal journal only when the boluses did not explain it. One watermark for both would let a
     * bolus read move the boundary past temporary basals nobody had looked at.
     */
    val tbrImportUtcSeconds: Long = 0L,
    /**
     * How many journal records starting on [tbrImportUtcSeconds] itself were already taken in.
     *
     * The pump keeps starts to the whole minute, so several temporary basals of one minute share
     * the watermark's second, and the later ones finish -- and reach the journal -- after it was
     * set. They finish in the order they were set, so the ones taken are the oldest of them.
     */
    val tbrTakenAtWatermark: Int = 0,
    /**
     * The temporary basals AAPS itself asked for, as [OurTbr] notes.
     *
     * The identity the driver assigns from a journal record does not quite collide with the one the
     * live tracker gave the same temporary basal: the pump begins on its own clock second, a second
     * or two off the moment it accepted the command. Without these notes the driver reads its own
     * work back out of the journal as a stranger's, and AAPS truncates the record it already had to
     * make room for a near duplicate.
     */
    val ourTbrs: List<OurTbr> = emptyList(),
    /** False until one pass has taken stock of what the pump already held. */
    val firstPassDone: Boolean = false,
    val lastRecordCount: Int = -1,
    val pending: List<PendingBolus> = emptyList(),
    /** Boluses closed on the driver's own account, still waiting for the pump to write them down. */
    val settled: List<SettledBolus> = emptyList(),
    val seen: List<SeenBolus> = emptyList(),
    val activeTbr: ActiveTbr? = null,
    /** The loop's last word on the temporary basal, or null when the driver has not seen one yet. */
    val loopTbr: LoopTbr? = null
) {

    fun withLoopTbr(entry: LoopTbr?) = copy(loopTbr = entry)

    fun withPending(entry: PendingBolus) = copy(pending = pending + entry)

    /** Note that the pump was asked for this bolus's record once more and did not have it. */
    fun withPendingAttempt(temporaryId: Long, attempts: Int) =
        copy(pending = pending.map { if (it.temporaryId == temporaryId) it.copy(confirmAttempts = attempts) else it })

    fun withPendingUpdated(temporaryId: Long, reportedDeliveredUnits: Double) =
        copy(pending = pending.map {
            if (it.temporaryId == temporaryId) it.copy(reportedDeliveredUnits = reportedDeliveredUnits) else it
        })

    fun withoutPending(temporaryId: Long) = copy(pending = pending.filterNot { it.temporaryId == temporaryId })

    fun withSettled(entry: SettledBolus) = copy(settled = settled + entry)

    fun withoutSettled(startedAtMs: Long) = copy(settled = settled.filterNot { it.startedAtMs == startedAtMs })

    /**
     * Remember a record, replacing what was remembered about it before.
     *
     * Replacing matters: a record whose delivered amount the pump corrects is stored again under
     * the same id, and appending would leave the stale reading in front of the new one, so every
     * later pass would see a difference and correct the same treatment for ever.
     */
    fun withSeen(entry: SeenBolus): Atc3HistoryLedger {
        val kept = (seen.filterNot { it.pumpId == entry.pumpId } + entry)
            .sortedByDescending { it.pumpClockUtcSeconds }
            .take(Atc3Const.SEEN_CAPACITY)
        return copy(seen = kept)
    }

    fun withActiveTbr(entry: ActiveTbr?) = copy(activeTbr = entry)

    fun withWatermark(utcSeconds: Long, phoneMs: Long) =
        copy(importFromUtcSeconds = utcSeconds, importFromPhoneMs = phoneMs, firstPassDone = true)

    fun withTbrWatermark(utcSeconds: Long, takenAtWatermark: Int = 0) =
        copy(tbrImportUtcSeconds = utcSeconds, tbrTakenAtWatermark = takenAtWatermark)

    /**
     * Remember that this temporary basal was ours, and forget the ones too old to be offered.
     *
     * The rate and the duration are the pump's own, as it reported them back, because those are the
     * figures the journal record will carry; see [OurTbr].
     */
    fun withOurTbr(
        utcSeconds: Long,
        rate: Double,
        durationMinutes: Int,
        keepFromUtcSeconds: Long,
        pumpId: Long? = null,
        pumpStart: Boolean = false,
        rowMs: Long? = null,
        asOrdered: Boolean = false
    ) =
        copy(
            ourTbrs = (ourTbrs + OurTbr(utcSeconds, raw(rate), durationMinutes, pumpId, pumpStart, rowMs = rowMs, asOrdered = asOrdered))
                .filter { it.startUtcSeconds >= keepFromUtcSeconds }
                .takeLast(OUR_TBR_KEPT)
        )

    /** The row under [pumpId] carries [units] handed over from a row of its minute AAPS cut to nothing. */
    fun withOurTbrCarried(pumpId: Long, units: Double) =
        copy(ourTbrs = ourTbrs.map { if (it.pumpId == pumpId) it.copy(carriedUnits = units) else it })

    /** The row under [pumpId] has been shaped to [units] of insulin over its span. */
    fun withOurTbrShaped(pumpId: Long, units: Double, endMs: Long? = null) =
        copy(ourTbrs = ourTbrs.map {
            if (it.pumpId == pumpId) it.copy(shapedUnits = units, endMs = endMs ?: it.endMs) else it
        })

    /** The note written under [pumpId] now holds the pump's own start, and AAPS's record carries it. */
    fun withOurTbrOnPumpStart(pumpId: Long, utcSeconds: Long, rowMs: Long? = null) =
        copy(ourTbrs = ourTbrs.map {
            if (it.pumpId == pumpId) it.copy(startUtcSeconds = utcSeconds, pumpStart = true, rowMs = rowMs ?: it.rowMs) else it
        })

    /**
     * AAPS's record written under [pumpId] was closed at [endMs].
     *
     * @param overwrite true when the end is the pump's own account of the record, from its journal,
     *   which replaces whatever moment the record was closed at before
     */
    fun withOurTbrEnd(pumpId: Long, endMs: Long, overwrite: Boolean = false) =
        copy(ourTbrs = ourTbrs.map { if (it.pumpId == pumpId && (overwrite || it.endMs == null)) it.copy(endMs = endMs) else it })

    fun withRecordCount(count: Int) = copy(lastRecordCount = count)

    /**
     * Which of these records were already counted, and under which entry.
     *
     * A record carries no identity, only its minute and its amounts, and inside one minute the pump
     * gives its records seconds 59, 58 and so on, moving a stored one back to make room. So a record
     * is known by its minute and the dose it asked for, and nothing more: in each minute, as many
     * records of a dose as were counted before are the counted ones, and every one beyond that is a
     * new bolus. Two equal records in one minute are two boluses.
     *
     * The delivered amount is left out of what makes two records the same: the pump can later
     * account for a step it delivered while stopping, and a record corrected that way is the same
     * record, to be updated rather than imported again. Records that agree in full are paired first.
     */
    fun pairWithSeen(records: List<Atc3BolusRecord>): Map<Atc3BolusRecord, SeenBolus> {
        val paired = IdentityHashMap<Atc3BolusRecord, SeenBolus>()
        val left = seen.toMutableList()
        for (inFull in booleanArrayOf(true, false)) {
            for (record in records) {
                if (paired.containsKey(record)) continue
                val match = left.firstOrNull {
                    sameDoseSameMinute(it, record) && (!inFull || it.fingerprint == record.fingerprint)
                } ?: continue
                paired[record] = match
                left.remove(match)
            }
        }
        return paired
    }

    private fun sameDoseSameMinute(seen: SeenBolus, record: Atc3BolusRecord): Boolean =
        Math.floorDiv(seen.pumpClockUtcSeconds, 60L) == Math.floorDiv(record.pumpClockUtcSeconds, 60L) &&
            seen.fingerprint.rawRequested == record.rawRequested &&
            seen.fingerprint.rawExtendedRequested == record.rawExtendedRequested

    /**
     * Allocate the id this record will keep for good.
     *
     * The bump is not decoration: the pump can give a new record the very second a neighbouring
     * record has just vacated, and without an escape the two would share an id and AAPS would treat
     * the second as an edit of the first.
     */
    fun assignPumpId(record: Atc3BolusRecord): Long = freePumpId(record.pumpClockUtcSeconds)

    /**
     * The id for a bolus of ours closed on its completion frame, before its record has been read.
     *
     * The one its record would get: the minute the bolus started, at second 59, which is how the
     * pump stamps it. When the record is read it is filed under this id, so AAPS keeps one row.
     */
    fun assignOwnPumpId(startUtcSeconds: Long): Long =
        freePumpId(Math.floorDiv(startUtcSeconds, 60L) * 60L + 59L)

    /**
     * An id for this second that no record already counted and no bolus of ours still waiting for
     * its record holds.
     */
    private fun freePumpId(utcSeconds: Long): Long {
        val base = utcSeconds * 1000L
        val taken = seen.map { it.pumpId }.toSet() + settled.map { it.pumpId }.filter { it != 0L }
        for (bump in 0..Atc3PumpId.MAX_BUMP) {
            val candidate = Atc3PumpId.of(base, Atc3PumpId.KIND_BOLUS, bump)
            if (candidate !in taken) return candidate
        }
        return Atc3PumpId.of(base, Atc3PumpId.KIND_BOLUS, Atc3PumpId.MAX_BUMP)
    }

    fun encode(): String {
        val lines = ArrayList<String>()
        lines.add(VERSION)
        lines.add(
            "w|$importFromUtcSeconds|${if (firstPassDone) 1 else 0}|$lastRecordCount|$serial|" +
                "$importFromPhoneMs|$tbrImportUtcSeconds|$tbrTakenAtWatermark"
        )
        // Each note is `start:rawRate:minutes:pumpId:pumpStart:endMs:rowMs:shapedRaw:carriedRaw`, and a note that has no rate
        // -- one read back from a ledger written before notes carried one -- keeps the bare start it
        // was stored with, so
        // that writing the ledger out again does not invent figures the pump never gave.
        if (ourTbrs.isNotEmpty()) lines.add(
            "o|" + ourTbrs.joinToString(",") {
                if (it.rawRate == null || it.durationMinutes == null) "${it.startUtcSeconds}"
                else "${it.startUtcSeconds}:${it.rawRate}:${it.durationMinutes}:${it.pumpId ?: ""}:${if (it.pumpStart) 1 else 0}:${it.endMs ?: ""}:${it.rowMs ?: ""}:${it.shapedUnits?.let { u -> raw(u) } ?: ""}:${it.carriedUnits?.let { u -> raw(u) } ?: ""}:${if (it.asOrdered) 1 else 0}"
            }
        )
        // Fields 6 and 7 are unused; the slots stay empty so that every line reads the same way.
        for (p in pending) {
            lines.add(
                "p|${p.temporaryId}|${p.startedAtMs}|${raw(p.requestedUnits)}|${raw(p.reportedDeliveredUnits)}|" +
                    "${p.bolusType.name}|||${p.confirmAttempts}|" +
                    "${p.startUtcSeconds}"
            )
        }
        for (x in settled) {
            lines.add(
                "x|${x.startedAtMs}|${raw(x.requestedUnits)}|${raw(x.units)}|${x.settledAtMs}|${x.pumpId}|" +
                    "${x.startUtcSeconds}"
            )
        }
        for (s in seen) {
            lines.add(
                "s|${s.pumpId}|${s.pumpClockUtcSeconds}|${s.fingerprint.rawRequested}|${s.fingerprint.rawDelivered}|" +
                    "${s.fingerprint.rawExtendedRequested}|${s.fingerprint.rawExtendedDelivered}"
            )
        }
        activeTbr?.let {
            lines.add(
                "t|${it.pumpId}|${it.startedAtMs}|${raw(it.rate)}|${if (it.ours) 1 else 0}|${it.ownDurationMs}|" +
                    (it.pumpStartUtcSeconds?.toString() ?: "") + "|${if (it.suspension) 1 else 0}|" +
                    (it.pumpStartMs?.toString() ?: "")
            )
        }
        loopTbr?.let { lines.add("l|${it.rawRate ?: ""}|${it.durationMinutes}|${it.atMs}") }
        return lines.joinToString("\n")
    }

    companion object {

        /** How many of our own temporary basal starts are kept; a day of them at worst. */
        const val OUR_TBR_KEPT = 300

        private const val VERSION = "atc3-ledger-v1"

        private fun raw(units: Double): Int = (units / Atc3Const.DOSE_SCALE).roundToInt()

        private fun units(raw: Int): Double = raw * Atc3Const.DOSE_SCALE

        /**
         * Read a ledger back, or return an empty one.
         *
         * Anything unreadable, from a different version or belonging to a different pump gives an
         * empty ledger rather than an exception: a driver that cannot start because its bookkeeping
         * file is malformed is worse than one that takes stock of the pump's history again.
         */
        fun decode(stored: String, serial: String, onBadLine: (String) -> Unit = {}): Atc3HistoryLedger {
            val lines = stored.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty() || lines[0].trim() != VERSION) return Atc3HistoryLedger(serial = serial)
            var ledger = Atc3HistoryLedger(serial = serial)
            for (line in lines.drop(1)) {
                val f = line.split("|")
                // A line that will not parse is dropped rather than thrown, but not in silence:
                // a lost pending bolus means insulin AAPS is counting that nothing will ever close.
                runCatching {
                    when (f[0]) {
                        "w"  -> {
                            if (f[4] != serial) return Atc3HistoryLedger(serial = serial)
                            ledger = ledger.copy(
                                importFromUtcSeconds = f[1].toLong(),
                                firstPassDone = f[2] == "1",
                                lastRecordCount = f[3].toInt(),
                                // Written by a later version of the driver than the one that may
                                // have stored this line, so read it defensively.
                                importFromPhoneMs = f.getOrNull(5)?.toLongOrNull() ?: 0L,
                                tbrImportUtcSeconds = f.getOrNull(6)?.toLongOrNull() ?: 0L,
                                // A ledger from before this count took every record of the second as known.
                                tbrTakenAtWatermark = f.getOrNull(7)?.toIntOrNull() ?: Int.MAX_VALUE
                            )
                        }

                        "p"  -> ledger = ledger.withPending(
                            PendingBolus(
                                temporaryId = f[1].toLong(),
                                startedAtMs = f[2].toLong(),
                                requestedUnits = units(f[3].toInt()),
                                bolusType = BS.Type.valueOf(f[5]),
                                reportedDeliveredUnits = units(f[4].toInt()),
                                // Fields 6 and 7 are not read, see encode. The ones after were
                                // appended by later versions, so they are read defensively.
                                confirmAttempts = f.getOrNull(8)?.toIntOrNull() ?: 0,
                                startUtcSeconds = f.getOrNull(9)?.toLongOrNull()
                                    ?: Atc3StatusV1.wallClockUtcSeconds(f[2].toLong())
                            )
                        )

                        // Unknown to older versions of the driver, which drop the line and carry on
                        // with nothing worse than the double count this exists to prevent.
                        "x"  -> ledger = ledger.withSettled(
                            SettledBolus(
                                startedAtMs = f[1].toLong(),
                                requestedUnits = units(f[2].toInt()),
                                units = units(f[3].toInt()),
                                settledAtMs = f[4].toLong(),
                                pumpId = f.getOrNull(5)?.toLongOrNull() ?: 0L,
                                startUtcSeconds = f.getOrNull(6)?.toLongOrNull()
                                    ?: Atc3StatusV1.wallClockUtcSeconds(f[1].toLong())
                            )
                        )

                        "s"  -> ledger = ledger.copy(
                            seen = ledger.seen + SeenBolus(
                                pumpId = f[1].toLong(),
                                pumpClockUtcSeconds = f[2].toLong(),
                                fingerprint = Atc3BolusFingerprint(f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6].toInt())
                            )
                        )

                        // The version is deliberately not moved for this line. A ledger discarded
                        // wholesale takes the bolus bookkeeping with it -- pending boluses AAPS is
                        // already counting, the records already seen, both watermarks -- and that
                        // is far worse to lose than a day of temporary basal notes. So a bare start
                        // from an older ledger still reads, and reads as a note with no rate and no
                        // duration recorded, which matches nothing: see [OurTbr] and
                        // [Atc3TbrBook]. An older driver meeting a newer line reads no
                        // notes at all, which is where it stood before the notes existed.
                        "o"  -> ledger = ledger.copy(
                            ourTbrs = f[1].split(",").mapNotNull { note ->
                                val parts = note.split(":")
                                parts[0].toLongOrNull()?.let {
                                    OurTbr(
                                        it,
                                        parts.getOrNull(1)?.toIntOrNull(),
                                        parts.getOrNull(2)?.toIntOrNull(),
                                        parts.getOrNull(3)?.toLongOrNull(),
                                        parts.getOrNull(4) == "1",
                                        parts.getOrNull(5)?.toLongOrNull(),
                                        rowMs = parts.getOrNull(6)?.toLongOrNull(),
                                        shapedUnits = parts.getOrNull(7)?.toIntOrNull()?.let { r -> units(r) },
                                        carriedUnits = parts.getOrNull(8)?.toIntOrNull()?.let { r -> units(r) },
                                        asOrdered = parts.getOrNull(9) == "1"
                                    )
                                }
                            }
                        )

                        "t"  -> ledger = ledger.withActiveTbr(
                            ActiveTbr(
                                pumpId = f[1].toLong(),
                                startedAtMs = f[2].toLong(),
                                rate = units(f[3].toInt()),
                                ours = f[4] == "1",
                                ownDurationMs = f[5].toLong(),
                                // Appended by a later version than the one that may have written
                                // this line, so read it defensively, as the watermark line is.
                                pumpStartUtcSeconds = f.getOrNull(6)?.toLongOrNull(),
                                suspension = f.getOrNull(7) == "1",
                                pumpStartMs = f.getOrNull(8)?.toLongOrNull()
                            )
                        )

                        "l"  -> ledger = ledger.withLoopTbr(LoopTbr(f[1].toIntOrNull(), f[2].toInt(), f[3].toLong()))

                        else -> Unit
                    }
                }.onFailure { onBadLine(line) }
            }
            return ledger
        }
    }
}
