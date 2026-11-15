package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.account.basal.Delivered
import app.aaps.pump.apex.account.bolus.BolusAccount
import app.aaps.pump.apex.account.bolus.BolusCountMark
import app.aaps.pump.apex.account.bolus.BolusSpanEnd
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.deep.BrokenRecordDeep
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.state.ApexAbilities
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The position the bolus spans are counted from, taken before any work with a pump that has none
 * kept: a new pump, or reference points dropped because the pump's objects changed. Without it a
 * bolus given before the first heartbeat has nothing to be counted from.
 *
 * The status is read, and it is the position of a pump without the daily statistics: its count of
 * the day and the reservoir, see [BolusCountMark]. A pump with the daily statistics gives them too,
 * and today's record is the position, see [BolusGate.keep].
 *
 * It is taken at the link setup and before every command of ours, and only when none is kept. Not
 * while a bolus of ours waits for its record: the count holds that bolus already and its row is not
 * written, so the position is taken after the bolus is settled, by the heartbeat that settles it.
 */
@SingleIn(AppScope::class)
@Inject
class StartPosition(
    private val aapsLogger: AAPSLogger,
    private val manager: ApexManager,
    private val store: ApexStore,
    private val pumpState: ApexPumpState,
    private val trace: ApexTrace
) {

    /** False when a position is needed and the pump did not give it: no command of ours goes before it is taken. */
    fun takeIfAbsent(serial: String): Boolean {
        if (serial.isEmpty()) return true
        val dayRecord = ApexAbilities.of(store, serial, pumpState.version?.firmwareText).dayRecord
        val gate = BolusGate(store, serial)
        val kept = if (dayRecord) gate.hasDayKept() else BolusCountMark(store, serial).get() != null
        if (kept) return true
        if (BolusAccount(store, serial).openActions().any()) {
            aapsLogger.info(LTag.PUMP, "Apex: no start position is taken while a bolus of ours waits for its record, the heartbeat that settles it keeps one")
            return true
        }
        val card = manager.readStatus() ?: return notTaken("the status")
        val atMs = card.readAtMs
        if (!dayRecord) {
            val count = Delivered.Count.of(card.status.deliveredTodayUnits, card.status.clockSeconds)
            BolusCountMark(store, serial).move(BolusSpanEnd.Count(atMs, count, card.status.reservoirUnits))
            aapsLogger.info(LTag.PUMP, "Apex: start position taken at $atMs: the pump's count ${count.units} U, reservoir ${card.status.reservoirUnits} U")
            trace.event(ApexTraceCat.STATE, "start_position", "at" to atMs, "count" to count.units, "reservoir" to card.status.reservoirUnits)
            return true
        }
        repeat(BrokenRecordDeep.READS_PER_JOURNAL) {
            val days = manager.readDailyStats()
            val today = days?.entries?.firstOrNull()?.record
            if (days != null && today != null) {
                val readAtMs = System.currentTimeMillis()
                gate.keep(JournalRead(days, repeatedBroken = false), readAtMs)
                aapsLogger.info(LTag.PUMP, "Apex: start position taken at $readAtMs: day bolus ${today.bolusUnits} basal ${today.basalUnits} temporary ${today.tbrUnits} U")
                trace.event(ApexTraceCat.STATE, "start_position", "at" to readAtMs, "bolus" to today.bolusUnits, "basal" to today.basalUnits, "tbr" to today.tbrUnits)
                return true
            }
        }
        return notTaken("the daily statistics")
    }

    private fun notTaken(what: String): Boolean {
        aapsLogger.warn(LTag.PUMP, "Apex: the start position is not taken, the pump did not give $what; it is asked again before the next command")
        trace.event(ApexTraceCat.STATE, "start_position_not_taken", "what" to what)
        return false
    }
}
