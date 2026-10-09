package app.aaps.pump.atc3.history

import app.aaps.core.data.model.BS
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3StatusV1
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * A bolus of ours the pump accepted and has not written down yet. Its record is known by the dose
 * asked and the start minute; the row made of that record takes the type from here. Kept on disk,
 * so a bolus AAPS died in the middle of still gets its type.
 */
@Serializable
data class ExpectedBolus(
    /** The phone's clock when the pump accepted the bolus. */
    val acceptedAtMs: Long,
    val units: Double,
    val type: BS.Type,
    /** The start as a record's key, taken at the start: the conversion goes through the timezone in force. */
    val startUtcSeconds: Long = Atc3StatusV1.wallClockUtcSeconds(acceptedAtMs),
    /** What the pump was last seen to deliver of it, U; zero until seen. Its row when the pump writes no record, see [Atc3JournalFault]. */
    val seenUnits: Double = 0.0
)

/** A temporary basal the driver believes is running. */
@Serializable
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
 * What the driver remembers of the pump's history between connections, surviving the process: how
 * far back imports reach, our boluses waiting for their record, and the temporary basal believed to
 * run. Which bolus records are in AAPS already is not remembered: AAPS's rows say, see
 * [Atc3BolusReconciler]. Kept on disk by [app.aaps.pump.atc3.store.Atc3Store].
 */
@Serializable
data class Atc3HistoryLedger(
    /** The pump this ledger belongs to; another pump's is discarded. */
    val serial: String = "",
    /** Records older than this, on the pump's clock, are its past, not to import. */
    val importFromUtcSeconds: Long = 0L,
    /** The same boundary on the phone's clock: a record is past only behind both, so a clock put back loses nothing. */
    val importFromPhoneMs: Long = 0L,
    /** False until the import boundary was set, the first time the pump's history was read. */
    val firstPassDone: Boolean = false,
    /** Our boluses the pump has not written down yet, see [ExpectedBolus]. */
    val expected: List<ExpectedBolus> = emptyList(),
    val activeTbr: ActiveTbr? = null,
    /** Slots of the pump's journal it failed to write, passed over at every read, see [Atc3JournalFault]. */
    val faults: List<JournalFault> = emptyList()
) {

    fun withExpected(entry: ExpectedBolus) = copy(expected = expected + entry)

    fun withFaults(entries: List<JournalFault>) = copy(faults = entries)

    fun withActiveTbr(entry: ActiveTbr?) = copy(activeTbr = entry)

    fun withWatermark(utcSeconds: Long, phoneMs: Long) =
        copy(importFromUtcSeconds = utcSeconds, importFromPhoneMs = phoneMs, firstPassDone = true)

    companion object {

        fun raw(units: Double): Int = (units / Atc3Protocol.DOSE_SCALE).roundToInt()
    }
}
