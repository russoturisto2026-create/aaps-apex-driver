package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.state.ApexAbilities
import app.aaps.pump.apex.state.ApexPumpState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Reads the last finished temporary basal (0x0B) when the gate says so, see [TbrGate], and the pump
 * has it, see [ApexAbilities]. A pump without it gets facts with nothing read.
 */
@SingleIn(AppScope::class)
@Inject
class TbrFactsReader(
    private val aapsLogger: AAPSLogger,
    private val manager: ApexManager,
    private val store: ApexStore,
    private val pumpState: ApexPumpState
) {

    /** The facts, or null when the read was sent and got no sound answer, see `ReadAgainDeep`. */
    fun read(serial: String): TbrFacts? {
        if (!ApexAbilities.of(store, serial, pumpState.version?.firmwareText).finishedTbr) return TbrFacts(null, endAsked = false)
        val answer = manager.readFinishedTbr() ?: run {
            aapsLogger.warn(LTag.PUMP, "Apex: no sound answer to the read of the last finished temporary basal")
            return null
        }
        return TbrFacts(answer.finished?.let { TbrFacts.endOf(it) }, endAsked = true)
    }
}
