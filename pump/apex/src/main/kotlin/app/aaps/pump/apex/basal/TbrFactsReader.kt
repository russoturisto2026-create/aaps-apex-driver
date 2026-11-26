package app.aaps.pump.apex.basal

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.manager.ApexManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Reads the last finished temporary basal (0x0B) when the gate says so, see [TbrGate]. Every pump the
 * driver works with has it, see `ApexVersion.supported`.
 */
@SingleIn(AppScope::class)
@Inject
class TbrFactsReader(
    private val aapsLogger: AAPSLogger,
    private val manager: ApexManager
) {

    /** The facts, or null when the read was sent and got no sound answer, see `ReadAgain`. */
    fun read(): TbrFacts? {
        val answer = manager.readFinishedTbr() ?: run {
            aapsLogger.warn(LTag.PUMP, "Apex: no sound answer to the read of the last finished temporary basal")
            return null
        }
        return TbrFacts(answer.finished?.let { TbrFacts.endOf(it) }, endAsked = true)
    }
}
