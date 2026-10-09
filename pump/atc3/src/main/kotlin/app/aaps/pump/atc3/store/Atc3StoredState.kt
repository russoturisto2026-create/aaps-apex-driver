package app.aaps.pump.atc3.store

import app.aaps.pump.atc3.basal.Atc3BasalPeriod
import app.aaps.pump.atc3.history.Atc3HistoryLedger
import app.aaps.pump.atc3.history.LearnedBolus
import app.aaps.pump.atc3.link.Atc3LinkWatch
import kotlinx.serialization.Serializable

/** What the driver keeps across a restart, see [Atc3Store]: everything in it belongs to one pump. */
@Serializable
data class Atc3StoredState(
    /** The form of the document, raised when a later version changes what a part means. */
    val version: Int = VERSION,
    /** What the pump's history stands at, see [Atc3HistoryLedger]. */
    val ledger: Atc3HistoryLedger = Atc3HistoryLedger(),
    /** The boluses the comparison still counts, see [app.aaps.pump.atc3.history.Atc3HistorySync.bolusesLearnedAfter]. */
    val learned: List<LearnedBolus> = emptyList(),
    /** The half hour under way and the one closed last, the windows of the basal account. */
    val basalPeriod: Atc3BasalPeriod.State = Atc3BasalPeriod.State(),
    /** The pump's last answer, a silence is counted from. */
    val lastAnswer: Atc3LinkWatch.Stop? = null,
    /** The stop the pump is held in for want of an answer, null when none. */
    val linkStop: Atc3LinkWatch.Stop? = null
) {

    companion object {

        const val VERSION = 1
    }
}
