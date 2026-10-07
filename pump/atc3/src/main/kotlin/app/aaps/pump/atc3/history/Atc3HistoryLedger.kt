package app.aaps.pump.atc3.history

import app.aaps.core.data.model.BS
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.basal.Atc3TbrBook
import app.aaps.pump.atc3.protocol.Atc3BolusFingerprint
import app.aaps.pump.atc3.protocol.Atc3BolusRecord
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3StatusV1
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** A bolus AAPS has started counting but the pump has not yet written a record for. */
data class PendingBolus(
    /** The id of the row created when the pump accepted the bolus. */
    val temporaryId: Long,
    /** The phone's clock when the pump accepted the bolus. */
    val startedAtMs: Long,
    val requestedUnits: Double,
    val bolusType: BS.Type,
    /** The most the pump reported delivered while it ran. */
    val reportedDeliveredUnits: Double = 0.0,
    /** History reads that did not hold its record yet, for the log; see [Atc3BolusReconciler] for when it is given up. */
    val confirmAttempts: Int = 0,
    /** The start as a record's key, what its record is matched on, taken at the start: the conversion goes through the timezone in force. */
    val startUtcSeconds: Long = Atc3StatusV1.wallClockUtcSeconds(startedAtMs)
)

/** A bolus of ours closed on its completion frame, kept until its record is read so the record is known for it. */
data class SettledBolus(
    val startedAtMs: Long,
    val requestedUnits: Double,
    val units: Double,
    val settledAtMs: Long,
    /** The id AAPS holds it under, which its record is filed under. */
    val pumpId: Long,
    /** The start as a record's key, see [PendingBolus.startUtcSeconds]. */
    val startUtcSeconds: Long = Atc3StatusV1.wallClockUtcSeconds(startedAtMs)
)

/** A record already accounted for, and its id. */
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
    /** True when AAPS asked for it: then its duration is AAPS's. */
    val ours: Boolean,
    val ownDurationMs: Long,
    /** The pump's start of it as a UTC-calendar key, or null: what tells a renewed temporary basal from the running one. */
    val pumpStartUtcSeconds: Long? = null,
    /** True for the pump stopped, recorded as a temporary basal of zero. */
    val suspension: Boolean = false,
    /**
     * The pump's start of it on the phone's time, or null. Not where the row begins: the pump stamps the
     * minute before the command, and our row begins at the acknowledgement, [startedAtMs]. Kept to tell
     * whether a start the pump names later is another temporary basal.
     */
    val pumpStartMs: Long? = null
) {

    /** Where the pump says it began when it said, else where the row begins. */
    val anchorMs: Long get() = pumpStartMs ?: startedAtMs
}

/**
 * A temporary basal AAPS asked for, noted so that the journal, which does not say who set one, is
 * not read back as another's. A start alone cannot tell it, the journal's being whole minutes, so
 * the note carries the rate and the duration too. See [Atc3TbrBook].
 */
data class OurTbr(
    /** Where it began, as a journal record's key, see [app.aaps.pump.atc3.protocol.Atc3StatusV1.wallClockUtcSeconds]. */
    val startUtcSeconds: Long,
    /** The rate the pump confirmed, in raw steps, or null in a note from an older ledger. */
    val rawRate: Int? = null,
    /** The minutes the pump took, which its record will carry, or null in a note from an older ledger. */
    val durationMinutes: Int? = null,
    /** The id it was written into AAPS under, or null in a note from an older ledger. */
    val pumpId: Long? = null,
    /** True when [startUtcSeconds] is the pump's own start, false when it is the acknowledgement. */
    val pumpStart: Boolean = false,
    /** Where AAPS's row of it was closed, or null while open; the journal's end once it shaped the row. */
    val endMs: Long? = null,
    /** Where AAPS's row of it begins, or null in a note from an older ledger; after a stop, the resume. */
    val rowMs: Long? = null,
    /** The insulin the row was last shaped to from the journal, U, or null: a row the journal agrees with is not written again. */
    val shapedUnits: Double? = null,
    /** Insulin handed to this row from a row of its minute AAPS cut to nothing, U, or null. */
    val carriedUnits: Double? = null,
    /** True for a temporary basal AAPS asked for: its row stays as ordered, the journal shapes only another's. */
    val asOrdered: Boolean = false
)

/**
 * What the driver remembers of the pump's history between connections, surviving the process:
 * which records were counted and under which id, our boluses waiting for their record, how far back
 * imports reach, and the temporary basal believed to run. Plain Kotlin, stored as lines with
 * amounts in raw steps, so that no locale can change them.
 */
data class Atc3HistoryLedger(
    /** The pump this ledger belongs to; another pump's is discarded. */
    val serial: String = "",
    /** Records older than this, on the pump's clock, are its past, not to import. */
    val importFromUtcSeconds: Long = 0L,
    /** The same boundary on the phone's clock: a record is past only behind both, so a clock put back loses nothing. */
    val importFromPhoneMs: Long = 0L,
    /** The newest temporary basal record taken in from the journal: apart from the bolus boundary, as the two journals are read apart. */
    val tbrImportUtcSeconds: Long = 0L,
    /** How many journal records on [tbrImportUtcSeconds] itself were taken in: several of one minute share that second. */
    val tbrTakenAtWatermark: Int = 0,
    /** The temporary basals AAPS asked for, see [OurTbr]. */
    val ourTbrs: List<OurTbr> = emptyList(),
    /** False until one pass has taken stock of what the pump already held. */
    val firstPassDone: Boolean = false,
    val lastRecordCount: Int = -1,
    val pending: List<PendingBolus> = emptyList(),
    /** Boluses closed on the driver's own account, still waiting for the pump to write them down. */
    val settled: List<SettledBolus> = emptyList(),
    val seen: List<SeenBolus> = emptyList(),
    val activeTbr: ActiveTbr? = null,
) {

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

    /** Remember a record in place of what was remembered of it: appending would correct the same row for ever. */
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

    /** Remember this temporary basal as ours, with the pump's rate and duration, and forget notes too old to matter. */
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
    fun withOurTbrShaped(pumpId: Long, units: Double, endMs: Long? = null, rowMs: Long? = null) =
        copy(ourTbrs = ourTbrs.map {
            if (it.pumpId == pumpId) it.copy(shapedUnits = units, endMs = endMs ?: it.endMs, rowMs = rowMs ?: it.rowMs) else it
        })

    /** The note written under [pumpId] now holds the pump's own start, and AAPS's record carries it. */
    fun withOurTbrOnPumpStart(pumpId: Long, utcSeconds: Long, rowMs: Long? = null) =
        copy(ourTbrs = ourTbrs.map {
            if (it.pumpId == pumpId) it.copy(startUtcSeconds = utcSeconds, pumpStart = true, rowMs = rowMs ?: it.rowMs) else it
        })

    /** AAPS's record written under [pumpId] now begins at [rowMs]: the part before it went into a closed half hour. */
    fun withOurTbrRow(pumpId: Long, rowMs: Long) =
        copy(ourTbrs = ourTbrs.map { if (it.pumpId == pumpId) it.copy(rowMs = rowMs) else it })

    /**
     * AAPS's row under [pumpId] was closed at [endMs].
     *
     * @param overwrite true for the end the pump's journal gives, which replaces any before
     */
    fun withOurTbrEnd(pumpId: Long, endMs: Long, overwrite: Boolean = false) =
        copy(ourTbrs = ourTbrs.map { if (it.pumpId == pumpId && (overwrite || it.endMs == null)) it.copy(endMs = endMs) else it })

    fun withRecordCount(count: Int) = copy(lastRecordCount = count)

    /**
     * Which records were counted before, and under which entry. A record is known only by its minute and
     * the dose asked for: in each minute, as many records of a dose as were counted are the counted ones,
     * and any beyond are new. The delivered amount is left out: the pump can correct it later.
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

    /** The id this record keeps for good: the next free second, since the pump can reuse a second a neighbour vacated. */
    fun assignPumpId(record: Atc3BolusRecord): Long = freePumpId(record.pumpClockUtcSeconds)

    /** The id of a bolus of ours closed on its completion frame: the one its record will get. */
    fun assignOwnPumpId(startUtcSeconds: Long): Long =
        freePumpId(Math.floorDiv(startUtcSeconds, 60L) * 60L + 59L)

    /** An id for this second that no counted record and no waiting bolus of ours holds. */
    private fun freePumpId(utcSeconds: Long): Long {
        val base = utcSeconds * 1000L
        val taken = seen.map { it.pumpId }.toSet() + settled.map { it.pumpId }
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
        // Each note is `start:rawRate:minutes:pumpId:pumpStart:endMs:rowMs:shapedRaw:carriedRaw`; a note without
        // a rate, from an older ledger, keeps its bare start.
        if (ourTbrs.isNotEmpty()) lines.add(
            "o|" + ourTbrs.joinToString(",") {
                if (it.rawRate == null || it.durationMinutes == null) "${it.startUtcSeconds}"
                else "${it.startUtcSeconds}:${it.rawRate}:${it.durationMinutes}:${it.pumpId ?: ""}:${if (it.pumpStart) 1 else 0}:${it.endMs ?: ""}:${it.rowMs ?: ""}:${it.shapedUnits?.let { u -> raw(u) } ?: ""}:${it.carriedUnits?.let { u -> raw(u) } ?: ""}:${if (it.asOrdered) 1 else 0}"
            }
        )
        // Fields 6 and 7 are unused and stay empty.
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
        return lines.joinToString("\n")
    }

    companion object {

        /** How many of our own temporary basal starts are kept; a day of them at worst. */
        const val OUR_TBR_KEPT = 300
        private const val VERSION = "atc3-ledger-v1"

        private fun raw(units: Double): Int = (units / Atc3Protocol.DOSE_SCALE).roundToInt()

        private fun units(raw: Int): Double = raw * Atc3Protocol.DOSE_SCALE

        /** The serial of the pump a stored ledger belongs to, or null when there is none to read. */
        fun serialOf(stored: String): String? {
            val lines = stored.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty() || lines[0].trim() != VERSION) return null
            return lines.firstOrNull { it.startsWith("w|") }?.split("|")?.getOrNull(4)?.takeIf { it.isNotBlank() }
        }

        /** Read a ledger back; anything unreadable, older or another pump's gives an empty one, which only means taking stock again. */
        fun decode(stored: String, serial: String, onBadLine: (String) -> Unit = {}): Atc3HistoryLedger {
            val lines = stored.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty() || lines[0].trim() != VERSION) return Atc3HistoryLedger(serial = serial)
            var ledger = Atc3HistoryLedger(serial = serial)
            for (line in lines.drop(1)) {
                val f = line.split("|")
                // A line that does not parse is dropped, and said: a lost pending bolus is insulin nothing will close.
                runCatching {
                    when (f[0]) {
                        "w"  -> {
                            if (f[4] != serial) return Atc3HistoryLedger(serial = serial)
                            ledger = ledger.copy(
                                importFromUtcSeconds = f[1].toLong(),
                                firstPassDone = f[2] == "1",
                                lastRecordCount = f[3].toInt(),
                                // Fields later versions added are read defensively.
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
                                // Fields 6 and 7 are not read; later fields defensively.
                                confirmAttempts = f.getOrNull(8)?.toIntOrNull() ?: 0,
                                startUtcSeconds = f.getOrNull(9)?.toLongOrNull()
                                    ?: Atc3StatusV1.wallClockUtcSeconds(f[2].toLong())
                            )
                        )

                        // A line without an id is a bolus an earlier version gave up on: passed over.
                        "x"  -> f.getOrNull(5)?.toLongOrNull()?.takeIf { it != 0L }?.let { pumpId ->
                            ledger = ledger.withSettled(
                                SettledBolus(
                                    startedAtMs = f[1].toLong(),
                                    requestedUnits = units(f[2].toInt()),
                                    units = units(f[3].toInt()),
                                    settledAtMs = f[4].toLong(),
                                    pumpId = pumpId,
                                    startUtcSeconds = f.getOrNull(6)?.toLongOrNull()
                                        ?: Atc3StatusV1.wallClockUtcSeconds(f[1].toLong())
                                )
                            )
                        }

                        "s"  -> ledger = ledger.copy(
                            seen = ledger.seen + SeenBolus(
                                pumpId = f[1].toLong(),
                                pumpClockUtcSeconds = f[2].toLong(),
                                fingerprint = Atc3BolusFingerprint(f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6].toInt())
                            )
                        )

                        // The version is not moved for this line: an older ledger still reads, its bare starts as notes
                        // that match nothing, rather than being discarded with the bolus bookkeeping.
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
                                // Read defensively, as later versions appended it.
                                pumpStartUtcSeconds = f.getOrNull(6)?.toLongOrNull(),
                                suspension = f.getOrNull(7) == "1",
                                pumpStartMs = f.getOrNull(8)?.toLongOrNull()
                            )
                        )

                        // A line an earlier version kept and this one does not.
                        else -> Unit
                    }
                }.onFailure { onBadLine(line) }
            }
            return ledger
        }
    }
}
