package app.aaps.pump.apex.basal

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.aaps.AapsCarrier
import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.store.ApexStore
import app.aaps.pump.apex.store.Snapshot
import app.aaps.pump.apex.store.SnapshotKind
import app.aaps.pump.apex.clock.ApexClock
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
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
 * by itself. That is found and written in [ForeignTbr].
 *
 * The moment of the last reconciliation never goes back: the heartbeat stands for the start of its
 * minute, but a status read after our command in that minute stands for its own read.
 *
 * What a temporary basal delivered is not taken from here: the rows stand as ordered.
 */
@SingleIn(AppScope::class)
@Inject
class TbrReconciliation(
    aapsLogger: AAPSLogger,
    private val store: ApexStore,
    carrier: AapsCarrier,
    trace: ApexTrace
) {

    private val foreign = ForeignTbr(aapsLogger, store, carrier, trace)

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

/**
 * Temporary basals the driver did not order, found by the reconciliation.
 *
 * The problem. The driver writes a row for each temporary basal it orders, and after each command
 * it keeps the status it read, see [TbrReconciliation]. A temporary basal can also be started, cancelled or
 * replaced on the pump's keys or by another client, or stopped by the pump itself. Nothing tells
 * the driver about it: the pump has no journal of temporary basals the driver reads. Left alone,
 * AAPS would count insulin the pump did not give, or miss insulin it gave.
 *
 * When it applies. When the status read now differs from the status of the last reconciliation, or
 * neither shows a temporary basal (another's may have begun and ended between them), see [TbrGate];
 * and at the very first reconciliation, when the status shows one running that no row stands for.
 * A status that shows the same temporary basal as last time never comes here. The end of our own
 * temporary basal that ran its length comes here too: only the last finished one (0x0B) tells it
 * apart from another's that took its place.
 *
 * The way out. The status read now is held against the last one:
 * - None before and none now: the last finished one (0x0B), when it ended in a minute after the
 *   last reconciliation, is another's that began and ended between them: its row.
 * - They differ: the one that ran ended. 0x0B says where when it is that one, by all its fields
 *   (start minute, rate, mode, length); any field apart and ended after the last reconciliation, it
 *   is a later one that passed between, and the one that ran ended where it began; an older record
 *   says nothing. Only one runs at a time: each one ends where the next one begins. The one the
 *   status shows running now gets its row from its start. With no end told, the one that ran ended
 *   where the next began, or by itself when its length is over, or in the middle of the span.
 *
 * Every point of a row has a source or is placed. The pump gives its points only by their minute,
 * or only by the status: the point goes in the middle of the span nothing is known of, from the last
 * reconciliation, or the start of its minute when later, to the end of its minute, or the read when
 * earlier. With only the status, the span ends at the bound of the read. Several points in one span
 * share it evenly. An end and the next start in one minute are one point: the next one took its place.
 */
private class ForeignTbr(
    private val aapsLogger: AAPSLogger,
    private val store: ApexStore,
    private val carrier: AapsCarrier,
    private val trace: ApexTrace
) {

    /**
     * The first reconciliation of this pump: the temporary basal the status [card] shows running, when
     * one runs, gets its row from its start. Nothing before it is known. [boundMs] is the bound of the read.
     */
    fun firstReconciliation(serial: String, card: ApexPumpState.StatusCard, offsetMs: Long, boundMs: Long) {
        val readAtMs = card.readAtMs
        val running = TbrFacts.runningOf(card.status)
        val points = Points(Long.MIN_VALUE, readAtMs, boundMs)
        val start = running?.let { points.start(minuteOf(serial, it.startClockSeconds, offsetMs, readAtMs)) }
        points.place()
        if (running != null && start != null) writeRunning(serial, running, start.atMs, offsetMs, readAtMs, "first reconciliation")
    }

    /**
     * The status [card] read now differs from the status of the [last] reconciliation, or neither shows a
     * temporary basal, see the rules above. [facts] were read as the gate said, [why] the gate's reason.
     * The read knows the pump up to [boundMs]. Returns the moment of the last point placed, see [Points.lastPointMs].
     */
    fun settleChanged(
        serial: String, last: TbrReconciliation.Reconciled, card: ApexPumpState.StatusCard, facts: TbrFacts?, why: String, offsetMs: Long, boundMs: Long
    ): Long {
        val readAtMs = card.readAtMs
        val running = TbrFacts.runningOf(card.status)
        val before = TbrFacts.runningOf(last.status)
        val end = facts?.end
        val lastMinuteMs = ApexClock.minuteStart(last.atMs)
        val points = Points(last.atMs, readAtMs, maxOf(boundMs, last.atMs))
        // The last finished one is that one that ran when every field is the same; else it is a later one when it ended after the last reconciliation's minute.
        val own = end?.takeIf { before != null && it.sameAs(before) }
        val between = end?.takeIf { own == null && minuteOf(serial, it.endClockSeconds, offsetMs, readAtMs) > lastMinuteMs }

        var stop: Points.Point? = null
        var how = ""
        if (before != null) when {
            own != null && own.completed -> how = "it ran its length"

            own != null -> {
                stop = points.end(minuteOf(serial, own.endClockSeconds, offsetMs, readAtMs))
                how = own.resultText
            }

            between != null || running != null -> {
                points.nextStartEnds { stop = it }
                how = "another's took its place"
            }

            stampMs(serial, before.startClockSeconds, offsetMs, readAtMs) + before.durationMinutes * ApexClock.MINUTE_MS - ApexClock.MINUTE_MS <= readAtMs ->
                how = "no temporary basal runs and its length is over, it ran out"

            else -> {
                stop = points.end(null)
                how = "no temporary basal runs, and nothing tells when it ended"
            }
        }
        val betweenStart = between?.let { points.start(minuteOf(serial, it.startClockSeconds, offsetMs, readAtMs)) }
        val betweenEnd = between?.takeIf { !it.completed }?.let { points.end(minuteOf(serial, it.endClockSeconds, offsetMs, readAtMs)) }
        val runningStart = running?.let { points.start(minuteOf(serial, it.startClockSeconds, offsetMs, readAtMs)) }
        points.place()

        if (before != null) {
            stop?.let { carrier.carry(AapsWrite.StopTbr(it.atMs), serial) }
            aapsLogger.info(LTag.PUMP, "Apex: the temporary basal ${before.text} is over, $how${stop?.let { "; its row ends at ${it.atMs}" } ?: ""}")
            trace.event(ApexTraceCat.STATE, "tbr_closed", "at" to stop?.atMs, "how" to how)
        }
        if (between != null && betweenStart != null) {
            val endMs = betweenEnd?.atMs ?: (betweenStart.atMs + between.durationMinutes * ApexClock.MINUTE_MS)
            val rate = between.rate ?: (between.percent ?: 0).toDouble()
            val pumpId = stampMs(serial, between.startClockSeconds, offsetMs, readAtMs)
            if (endMs > betweenStart.atMs) carrier.carry(AapsWrite.Tbr(pumpId, betweenStart.atMs, rate, between.rate != null, endMs - betweenStart.atMs, AapsWrite.NORMAL), serial)
            aapsLogger.info(LTag.PUMP, "Apex: another's temporary basal began and ended between two reconciliations, ${between.amountAsked} for ${between.durationMinutes} min, ${between.resultText}: its row from ${betweenStart.atMs} to $endMs")
            trace.event(ApexTraceCat.STATE, "tbr_foreign_over", "from" to betweenStart.atMs, "to" to endMs, "amount" to between.amountAsked)
        }
        if (running != null && runningStart != null) writeRunning(serial, running, runningStart.atMs, offsetMs, readAtMs, why)
        return points.lastPointMs
    }

    /** A temporary basal the status shows running and no row of ours stands for: its row from [startMs], for its length. */
    private fun writeRunning(serial: String, running: TbrFacts.Running, startMs: Long, offsetMs: Long, readAtMs: Long, why: String) {
        val pumpId = stampMs(serial, running.startClockSeconds, offsetMs, readAtMs)
        carrier.carry(AapsWrite.Tbr(pumpId, startMs, running.rate, true, running.durationMinutes * ApexClock.MINUTE_MS, AapsWrite.NORMAL), serial)
        aapsLogger.info(LTag.PUMP, "Apex: a temporary basal runs that no row stands for, ${running.text}, $why: its row from $startMs")
        trace.event(ApexTraceCat.STATE, "tbr_foreign_running", "at" to startMs, "rate" to running.rate, "minutes" to running.durationMinutes)
    }

    private fun stampMs(serial: String, clockSeconds: Long, offsetMs: Long, readAtMs: Long): Long = ApexClock.momentOfStamp(store, serial, clockSeconds, offsetMs, readAtMs)

    /** The start of the minute of the pump's stamp. */
    private fun minuteOf(serial: String, clockSeconds: Long, offsetMs: Long, readAtMs: Long): Long = ApexClock.minuteStart(stampMs(serial, clockSeconds, offsetMs, readAtMs))

    /**
     * The points of one reconciliation that have no source of their own, in their order. A point with
     * a minute lies in that minute, after the last reconciliation and before the read. A point with no
     * minute, told only by the status, lies before the bound of the read. Points in one span share it evenly.
     */
    private class Points(private val fromMs: Long, private val readAtMs: Long, private val boundMs: Long) {

        class Point(val minuteMs: Long?) {

            var atMs = 0L
        }

        private val all = ArrayList<Point>()
        private var pendingEnd: Point? = null
        private var startWaiter: ((Point) -> Unit)? = null

        /** An end in [minuteMs], or told only by the status when null. */
        fun end(minuteMs: Long?): Point = Point(minuteMs).also { all.add(it); pendingEnd = it }

        /** A start in [minuteMs]. With an end just before it in the same minute, it is one point. */
        fun start(minuteMs: Long): Point {
            val ended = pendingEnd
            val point = if (ended != null && ended.minuteMs == minuteMs) ended else Point(minuteMs).also { all.add(it) }
            pendingEnd = null
            startWaiter?.invoke(point)
            startWaiter = null
            return point
        }

        /** [use] gets the next start: the one that ran ended where the next one began. */
        fun nextStartEnds(use: (Point) -> Unit) {
            startWaiter = use
        }

        fun place() {
            var previous = fromMs
            var i = 0
            while (i < all.size) {
                val minuteMs = all[i].minuteMs
                var j = i
                while (j + 1 < all.size && all[j + 1].minuteMs == minuteMs) j++
                val from = maxOf(previous, minuteMs ?: fromMs)
                val to = maxOf(from, if (minuteMs != null) minOf(minuteMs + ApexClock.MINUTE_MS, readAtMs) else boundMs)
                val count = j - i + 1
                for (k in 0 until count) all[i + k].atMs = from + (to - from) * (k + 1) / (count + 1)
                previous = all[j].atMs
                i = j + 1
            }
        }

        /** The moment of the last point, after [place]; none when there are no points. */
        val lastPointMs: Long get() = all.lastOrNull()?.atMs ?: Long.MIN_VALUE
    }
}
