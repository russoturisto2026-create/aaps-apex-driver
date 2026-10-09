package app.aaps.pump.atc3.link

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.pump.atc3.trace.Atc3TraceCat
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The setup steps of one link, put to the Bluetooth stack one at a time; each link has its own.
 * The stack drops a step issued while another is outstanding, without a callback, so each is
 * issued only when the one before has been answered; each is answered, timed out or refused, and
 * traced. A step that fails takes the link with it, through [onFailed].
 *
 * @param onFailed called with the step's name and why it failed, after the queue has been emptied
 */
internal class Atc3GattOps(
    private val aapsLogger: AAPSLogger,
    private val trace: Atc3Trace,
    private val timer: ScheduledExecutorService,
    private val onFailed: (kind: String, why: String) -> Unit
) {

    /**
     * One request to the Bluetooth stack.
     *
     * @param kind      the name it is traced and completed under
     * @param timeoutMs how long its callback may take
     * @param issue     makes the call, returning whether the stack started it
     */
    private class GattOp(val kind: String, val timeoutMs: Long, val issue: () -> Boolean)

    private val lock = Any()
    private val queue = ArrayDeque<GattOp>()

    /** True once the link the queue served has ended: no step may be issued on a closing link. Under [lock]. */
    private var closed = false
    private var inFlight: GattOp? = null
    private var issuedAt = 0L
    private var opTimer: ScheduledFuture<*>? = null

    fun enqueue(kind: String, timeoutMs: Long, issue: () -> Boolean) {
        val taken = synchronized(lock) {
            if (closed) return@synchronized false
            queue.addLast(GattOp(kind, timeoutMs, issue))
            trace.event(
                Atc3TraceCat.BLE, "op_queued",
                "kind" to kind,
                "waiting" to queue.size,
                "inflight" to (inFlight?.kind ?: "-")
            )
            true
        }
        if (!taken) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: not asking for $kind, the link it belonged to has ended")
            trace.event(Atc3TraceCat.BLE, "op_rejected", "kind" to kind)
            return
        }
        issueNext()
    }

    private fun issueNext() {
        val op = synchronized(lock) {
            if (closed || inFlight != null) return
            val next = queue.removeFirstOrNull() ?: return
            inFlight = next
            issuedAt = trace.now()
            next
        }
        val accepted = try {
            op.issue()
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: missing Bluetooth permission issuing ${op.kind}", e)
            false
        }
        trace.event(Atc3TraceCat.BLE, "op_sent", "kind" to op.kind, "ok" to accepted)
        if (!accepted) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: the stack would not start ${op.kind}")
            fail(op, "refused")
            return
        }
        armTimer(op)
    }

    /** @return true when [kind] was the step outstanding, false for a callback nobody was waiting for */
    fun complete(kind: String, status: Int): Boolean {
        val op = synchronized(lock) {
            val current = inFlight
            if (current == null || current.kind != kind) {
                trace.event(
                    Atc3TraceCat.BLE, "op_stray",
                    "kind" to kind,
                    "expected" to (current?.kind ?: "-"),
                    "status" to status
                )
                return false
            }
            opTimer?.cancel(false)
            opTimer = null
            inFlight = null
            current
        }
        trace.event(
            Atc3TraceCat.BLE, "op_done",
            "kind" to op.kind,
            "status" to status,
            "ms" to trace.since(issuedAt)
        )
        issueNext()
        return true
    }

    /** Give up on a step, and on the link with it: a setup that lost a step is ended, named. */
    private fun fail(op: GattOp, why: String) {
        val waitedMs = trace.since(issuedAt)
        synchronized(lock) {
            // Nothing more goes out on a link that lost a step, whatever ending it takes.
            closed = true
            opTimer?.cancel(false)
            opTimer = null
            inFlight = null
            queue.clear()
        }
        trace.event(Atc3TraceCat.BLE, "op_failed", "kind" to op.kind, "why" to why, "ms" to waitedMs)
        onFailed(op.kind, why)
    }

    private fun armTimer(op: GattOp) {
        val armed = timer.schedule({
            val outstanding = synchronized(lock) { inFlight === op }
            if (outstanding) {
                aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: ${op.kind} was never answered in ${op.timeoutMs}ms")
                trace.event(Atc3TraceCat.BLE, "op_timeout", "kind" to op.kind, "ms" to op.timeoutMs)
                fail(op, "timeout")
            }
        }, op.timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(lock) {
            if (inFlight === op) opTimer = armed else armed.cancel(false)
        }
    }

    /** Drop everything outstanding and take no more: the link is gone. */
    fun close() {
        synchronized(lock) {
            closed = true
            opTimer?.cancel(false)
            opTimer = null
            val dropped = queue.size + if (inFlight != null) 1 else 0
            if (dropped > 0) {
                trace.event(
                    Atc3TraceCat.BLE, "op_cancelled",
                    "n" to dropped,
                    "inflight" to (inFlight?.kind ?: "-")
                )
            }
            queue.clear()
            inFlight = null
        }
    }

    /** What the stack is working on, for tests. */
    fun inFlightKind(): String? = synchronized(lock) { inFlight?.kind }

    /** How many steps wait behind it, for tests. */
    fun queueDepth(): Int = synchronized(lock) { queue.size }
}
