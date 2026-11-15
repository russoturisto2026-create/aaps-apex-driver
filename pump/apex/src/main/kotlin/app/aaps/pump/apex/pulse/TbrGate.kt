package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.protocol.ApexStatusV1

/**
 * Whether the last finished temporary basal (0x0B) has to be read at this reconciliation. The
 * status of the last reconciliation is held against the status read now:
 * - Level 1: both show the same temporary basal running (rate, mode, length, start minute). Nothing
 *   can have passed: another's would have replaced it for good. Nothing is read.
 * - Level 2a: neither shows one. Another's may have begun and ended between them; 0x0B tells.
 * - Level 2b: they differ. The one that ran ended, or another began; 0x0B tells how.
 * - The first reconciliation has nothing to hold against: nothing is read.
 */
object TbrGate {

    class Decision(val read: Boolean, val reason: String)

    fun decide(last: ApexStatusV1?, now: ApexStatusV1): Decision {
        if (last == null) return Decision(false, "first reconciliation")
        val before = TbrFacts.runningOf(last)
        val running = TbrFacts.runningOf(now)
        return when {
            before == null && running == null -> Decision(true, "no temporary basal before and now: one may have passed between")
            before != null && running != null && before.sameAs(running) -> Decision(false, "the same temporary basal runs")
            else -> Decision(true, "the temporary basal changed")
        }
    }
}
