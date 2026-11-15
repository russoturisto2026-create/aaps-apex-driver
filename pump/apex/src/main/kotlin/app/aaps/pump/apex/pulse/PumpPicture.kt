package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.account.basal.Delivered
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexStatusV2
import app.aaps.pump.apex.state.ApexPumpState

/**
 * One journal as read at a heartbeat: the answer, and whether it came with a broken-looking
 * record every time it was read. See `BrokenRecordDeep`.
 */
class JournalRead<T>(val answer: ApexJournalAnswer<T>, val repeatedBroken: Boolean)

/** What the screen shows of the last picture: a few numbers, taken when the picture was read. */
class PictureSummary(
    val heartbeatAtMs: Long,
    val readAtMs: Long,
    /** The pump's count of insulin delivered today, U. */
    val counterUnits: Double,
    /** Today's day of the daily statistics, by kind; null when the answer had no day. */
    val dayBolusUnits: Double?,
    val dayBasalUnits: Double?,
    val dayTbrUnits: Double?,
    /** The newest bolus record: when, as a moment, and how much was delivered; null when the journal is empty. */
    val topBolusAtMs: Long?,
    val topBolusUnits: Double?,
    /** How far behind the phone the pump's clock stood by this heartbeat, milliseconds; null when not measured. */
    val clockErrorMs: Long?,
    /** The journals that came with a broken-looking record every time they were read. */
    val brokenJournals: List<String>,
    /** Read between the progress frames of our bolus: the pump counts part of the bolus, AAPS has no row for it yet. */
    val duringBolus: Boolean,
    /** Where the comparison of the day starts, see [DayWindow]; null during our bolus or with no day in the answer. */
    val dayFrom: DayWindow.Count? = null
) {

    /** The same picture with another newest bolus record, [atMs] and [units]. */
    fun withTopBolus(atMs: Long, units: Double) = PictureSummary(
        heartbeatAtMs, readAtMs, counterUnits, dayBolusUnits, dayBasalUnits, dayTbrUnits, atMs, units, clockErrorMs, brokenJournals, duringBolus, dayFrom
    )
}

/**
 * The pump as seen at one heartbeat: the objects read in answer to it. This is what the
 * reconciliation compares with the copy kept from the last heartbeat.
 */
class PumpPicture(
    /** Phone clock when the heartbeat came, milliseconds. */
    val heartbeatAtMs: Long,
    val status: ApexPumpState.StatusCard,
    /** Status V2, the battery; null when it was not read at this heartbeat, see [PictureReader]. */
    val statusV2: ApexStatusV2?,
    /** The bolus journal; null when the daily statistics showed no bolus since the last heartbeat and it was not read, see [bolusGate]. */
    val boluses: JournalRead<ApexBolusRecord>?,
    /** Why the bolus journal was read, or was not. */
    val bolusGate: BolusGate.Decision,
    /** The daily statistics: read after the bolus journal when that was read, see [PictureReader]; null when the pump gives none. */
    val days: JournalRead<ApexDailyRecord>?,
    /** The temporary basal facts; null when the status showed what was expected and they were not read, see [tbrGate]. */
    val tbr: TbrFacts?,
    /** Why the temporary basal part was read, or was not. */
    val tbrGate: TbrGate.Decision,
    /** Phone clock when the last answer came, milliseconds. */
    val readAtMs: Long
) {

    /** What the pump had delivered up to this heartbeat: the daily statistics when it gives them, else the count of Status V1. */
    fun delivered(): Delivered =
        days?.let { Delivered.DayRecord(it.answer.entries.map { entry -> entry.record }, it.repeatedBroken) }
            ?: Delivered.Count.of(status.deliveredTodayUnits, status.status.clockSeconds)
}
