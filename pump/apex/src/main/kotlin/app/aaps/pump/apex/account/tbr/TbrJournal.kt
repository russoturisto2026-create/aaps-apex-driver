package app.aaps.pump.apex.account.tbr

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.pump.apex.account.AapsCarrier
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.Snapshot
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.deep.ForeignTbrDeep
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.pulse.TbrFacts
import app.aaps.pump.apex.pulse.TbrGate
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The temporary basal rows, held against the pump. A reconciliation is any read of Status V1: the
 * heartbeat, the read before and after our command. The status of the last reconciliation and the
 * moment it stands for are kept; nothing else.
 *
 * The plain path: our command writes its row at the moment of the order, see `TbrCommand`, and the
 * status read after it is the last reconciliation, see [ordered]. The next status that shows the same
 * temporary basal tells that nothing happened, see [TbrGate]. Anything else the status shows was not
 * done by our commands: another's temporary basal, one started or stopped on the pump, the end of ours
 * by itself. That is found and written in [ForeignTbrDeep].
 *
 * The moment of the last reconciliation never goes back: the heartbeat stands for the start of its
 * minute, but a status read after our command in that minute stands for its own read.
 *
 * What a temporary basal delivered is not taken from here: the rows stand as ordered.
 */
@SingleIn(AppScope::class)
@Inject
class TbrJournal(
    aapsLogger: AAPSLogger,
    private val store: ApexStore,
    carrier: AapsCarrier,
    trace: ApexTrace
) {

    private val foreign = ForeignTbrDeep(aapsLogger, store, carrier, trace)

    /** The status of the last reconciliation and the moment it stands for. */
    class Reconciled(val status: ApexStatusV1, val atMs: Long)

    /** The last reconciliation, or null before the first. */
    fun last(serial: String): Reconciled? = lastOf(store, serial)

    /**
     * Settle the status [card] read now against the last reconciliation, with [facts] read as the
     * gate said, see [TbrGate]; null when nothing was read. The read knows the pump up to [boundMs]:
     * the start of the heartbeat's minute, or the moment of a read outside the heartbeat.
     */
    fun settle(serial: String, card: ApexPumpState.StatusCard, facts: TbrFacts?, offsetMs: Long, boundMs: Long) {
        val last = last(serial)
        if (last == null) {
            foreign.firstReconciliation(serial, card, offsetMs, boundMs)
            keep(serial, card, boundMs)
            return
        }
        val decision = TbrGate.decide(last.status, card.status)
        if (!decision.read) {
            keep(serial, card, maxOf(last.atMs, boundMs))
            return
        }
        val lastPointMs = foreign.settleChanged(serial, last, card, facts, decision.reason, offsetMs, boundMs)
        keep(serial, card, maxOf(last.atMs, boundMs, lastPointMs))
    }

    /** Our command was done and the status [card] read after it shows so: it is the last reconciliation, at its own read. */
    fun ordered(serial: String, card: ApexPumpState.StatusCard) {
        keep(serial, card, maxOf(last(serial)?.atMs ?: Long.MIN_VALUE, card.readAtMs))
    }

    private fun keep(serial: String, card: ApexPumpState.StatusCard, atMs: Long) =
        store.saveSnapshot(serial, SnapshotKind.STATUS, Snapshot(listOf(card.raw), atMs))

    companion object {

        /** The last reconciliation kept in [store], or null before the first. */
        fun lastOf(store: ApexStore, serial: String): Reconciled? {
            val kept = store.snapshot(serial, SnapshotKind.STATUS) ?: return null
            val status = kept.frames.firstOrNull()?.let { ApexStatusV1.decode(ApexResponseFrame(it)) } ?: return null
            return Reconciled(status, kept.readAtMs)
        }
    }
}
