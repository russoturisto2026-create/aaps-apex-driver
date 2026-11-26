package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.basal.TbrFacts
import app.aaps.pump.apex.basal.TbrGate
import app.aaps.pump.apex.bolus.BolusGate
import app.aaps.pump.apex.statistics.Delivered
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.state.ApexPumpState

/**
 * One journal as read at a heartbeat: the answer, and whether it came with the same broken-looking
 * record at every read, see `ReadAgain`. The statistics use it: the basal waits, the screen tells it.
 */
class JournalRead<T>(val answer: ApexJournalAnswer<T>, val repeatedBroken: Boolean)

/**
 * The pump as seen at one heartbeat: the objects read in answer to it. This is what the
 * reconciliation compares with the copy kept from the last heartbeat.
 */
class PumpPicture(
    /** Phone clock when the heartbeat came, milliseconds. */
    val heartbeatAtMs: Long,
    val status: ApexPumpState.StatusCard,
    /** The bolus journal; null when the daily statistics showed no bolus since the last heartbeat and it was not read, see [bolusGate]. */
    val boluses: JournalRead<ApexBolusRecord>?,
    /** Why the bolus journal was read, or was not. */
    val bolusGate: BolusGate.Decision,
    /** The daily statistics: read after the bolus journal when that was read, see [PictureReader]. */
    val days: JournalRead<ApexDailyRecord>,
    /** The temporary basal facts; null when the status showed what was expected and they were not read, see [tbrGate]. */
    val tbr: TbrFacts?,
    /** Why the temporary basal part was read, or was not. */
    val tbrGate: TbrGate.Decision,
    /** Phone clock when the last answer came, milliseconds. */
    val readAtMs: Long,
    /** The first reconciliation since AAPS started: the closed days of the statistics are checked and kept too, see `StatisticsDays.keep`. */
    val start: Boolean = false
) {

    /** What the pump had delivered up to this heartbeat: its daily statistics. */
    fun delivered(): Delivered = Delivered(days.answer.entries.map { it.record }, days.repeatedBroken)
}
