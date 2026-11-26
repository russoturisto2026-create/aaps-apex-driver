package app.aaps.pump.apex.basal

import app.aaps.pump.apex.aaps.AapsCarrier
import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.store.FakeStore
import app.aaps.pump.apex.clock.ApexClock
import app.aaps.pump.apex.protocol.ApexFinishedTbr
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.basal.TbrFrames.finished
import app.aaps.pump.apex.basal.TbrFrames.key
import app.aaps.pump.apex.basal.TbrFrames.ms
import app.aaps.pump.apex.basal.TbrFrames.status
import app.aaps.pump.apex.basal.TbrFrames.statusOf
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

/**
 * The status of the last reconciliation held against the status now. Our rows stand at the moments
 * of our orders; a point the pump gives only by its minute, or only by the status, goes in the middle
 * of the span nothing is known of, and several share it evenly.
 */
class TbrReconciliationTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private val written = ArrayList<AapsWrite>()
    private val carrier: AapsCarrier = mock(AapsCarrier::class.java)
    private val journal = TbrReconciliation(AAPSLoggerTest(), store, carrier, mock(ApexTrace::class.java))

    init {
        doAnswer { inv -> written.add(inv.getArgument(0)); null }.`when`(carrier).carry(anyArg(), ArgumentMatchers.anyString())
    }

    private fun running(rateRaw: Int, minutes: Int, elapsed: Int, clock: Long) = status(true, rateRaw, minutes, elapsed, clock = clock)
    private fun none(clock: Long) = status(false, clock = clock)
    private fun card(frame: ApexResponseFrame, atMs: Long) = ApexPumpState.StatusCard(statusOf(frame), frame.raw, atMs, null)

    /** A reconciliation: the status [frame] read at [atMs], the last finished one [end] read when the gate says so. */
    private fun reconcile(frame: ApexResponseFrame, atMs: Long, end: ApexResponseFrame?, boundMs: Long) {
        val gate = TbrGate.decide(journal.last(serial)?.status, statusOf(frame))
        val facts = if (gate.read) TbrFacts(end?.let { ApexFinishedTbr.decode(it) }?.let { TbrFacts.endOf(it) }, endAsked = true) else null
        journal.settle(serial, card(frame, atMs), facts, 0L, boundMs)
    }

    /** A heartbeat at [atMs]: it knows the pump up to the start of its minute. */
    private fun heartbeat(frame: ApexResponseFrame, atMs: Long, end: ApexResponseFrame? = null) = reconcile(frame, atMs, end, ApexClock.minuteStart(atMs))

    /** The read before our command at [atMs]: the minute is not over. */
    private fun beforeCommand(frame: ApexResponseFrame, atMs: Long, end: ApexResponseFrame? = null) = reconcile(frame, atMs, end, atMs)

    /** Our command done: the status [frame] read after it at [atMs]. The row itself is the command's, not the journal's. */
    private fun ordered(frame: ApexResponseFrame, atMs: Long) = journal.ordered(serial, card(frame, atMs))

    private fun stop(atMs: Long) = AapsWrite.StopTbr(atMs)
    private fun row(stampMs: Long, startMs: Long, rate: Double, lengthMs: Long) = AapsWrite.Tbr(stampMs, startMs, rate, true, lengthMs, AapsWrite.NORMAL)

    /** Ours: 1.5 U/h for 30 min ordered at 9:00:12, the status after it read at 9:00:14. */
    private fun ours() = ordered(running(60, 30, elapsed = 0, clock = key(9, 0, 14)), ms(9, 0, 14))

    @Test
    fun `the first reconciliation with a temporary basal running - its row from the middle of its start minute`() {
        heartbeat(running(80, 60, elapsed = 3, clock = key(9, 15, 0)), ms(9, 15, 0))
        assertEquals(listOf<AapsWrite>(row(ms(9, 12), ms(9, 12, 30), 2.0, 60 * 60_000L)), written)
        assertEquals(ms(9, 15), journal.last(serial)!!.atMs)
    }

    @Test
    fun `level 1 - the same one runs at the next heartbeat - nothing is written`() {
        ours()
        heartbeat(running(60, 30, elapsed = 5, clock = key(9, 5, 0)), ms(9, 5))
        assertTrue(written.isEmpty())
    }

    @Test
    fun `ours cancelled on the pump - its row ends in the middle of the minute the last finished one gives`() {
        ours()
        heartbeat(none(key(9, 20)), ms(9, 20), finished(9, 0, 9, 17, rateRaw = 60))
        assertEquals(listOf<AapsWrite>(stop(ms(9, 17, 30))), written)
    }

    @Test
    fun `ours ran its length - its row ends by itself, nothing is written`() {
        ours()
        heartbeat(none(key(9, 35)), ms(9, 35), finished(9, 0, 9, 30, result = ApexFinishedTbr.Offset.RESULT_COMPLETED, rateRaw = 60))
        assertTrue(written.isEmpty())
    }

    @Test
    fun `another's replaced ours - our end and its start are one point, in the middle of its minute`() {
        ours()
        val oursEnd = finished(9, 0, 9, 3, result = ApexFinishedTbr.Offset.RESULT_CANCELLED_BY_COMMAND, rateRaw = 60)
        heartbeat(running(80, 30, elapsed = 2, clock = key(9, 5, 0)), ms(9, 5), oursEnd)
        assertEquals(listOf(stop(ms(9, 3, 30)), row(ms(9, 3), ms(9, 3, 30), 2.0, 30 * 60_000L)), written)
    }

    @Test
    fun `another's in the minute of our order - one point in the middle from the status after our order to the end of the minute`() {
        ours()
        val oursEnd = finished(9, 0, 9, 0, result = ApexFinishedTbr.Offset.RESULT_CANCELLED_BY_COMMAND, rateRaw = 60)
        heartbeat(running(80, 30, elapsed = 5, clock = key(9, 5, 0)), ms(9, 5), oursEnd)
        assertEquals(listOf(stop(ms(9, 0, 37)), row(ms(9, 0), ms(9, 0, 37), 2.0, 30 * 60_000L)), written)
    }

    @Test
    fun `another's began and ended while ours ran, none runs now - ours ends where it began, its row`() {
        ours()
        heartbeat(none(key(9, 10)), ms(9, 10), finished(9, 7, 9, 8, rateRaw = 80, quarters = 1))
        assertEquals(listOf(stop(ms(9, 7, 30)), row(ms(9, 7), ms(9, 7, 30), 2.0, 60_000L)), written)
    }

    @Test
    fun `none before and none now - another's that passed between gets its row, once`() {
        heartbeat(none(key(9, 5)), ms(9, 5))
        val keypad = finished(9, 7, 9, 8, rateRaw = 80, quarters = 1)
        heartbeat(none(key(9, 10)), ms(9, 10), keypad)
        assertEquals(listOf<AapsWrite>(row(ms(9, 7), ms(9, 7, 30), 2.0, 60_000L)), written)
        // The next heartbeat finds the same record: it ended before the last reconciliation, nothing more.
        heartbeat(none(key(9, 15)), ms(9, 15), keypad)
        assertEquals(1, written.size)
    }

    @Test
    fun `none before and none now, an old record - nothing`() {
        heartbeat(none(key(9, 5)), ms(9, 5))
        heartbeat(none(key(9, 10)), ms(9, 10), finished(9, 0, 9, 2, rateRaw = 80, quarters = 1))
        assertTrue(written.isEmpty())
    }

    @Test
    fun `another's set and cancelled in one minute - two points share the minute, its row is a third of it`() {
        heartbeat(none(key(9, 5)), ms(9, 5))
        heartbeat(none(key(9, 10)), ms(9, 10), finished(9, 7, 9, 7, rateRaw = 80, quarters = 1))
        assertEquals(listOf<AapsWrite>(row(ms(9, 7), ms(9, 7, 20), 2.0, 20_000L)), written)
    }

    @Test
    fun `another's that ran its whole length between two reconciliations - its row has its length`() {
        heartbeat(none(key(9, 0)), ms(9, 0))
        heartbeat(none(key(9, 20)), ms(9, 20), finished(9, 2, 9, 17, result = ApexFinishedTbr.Offset.RESULT_COMPLETED, rateRaw = 80, quarters = 1))
        assertEquals(listOf<AapsWrite>(row(ms(9, 2), ms(9, 2, 30), 2.0, 15 * 60_000L)), written)
    }

    @Test
    fun `our cancel in the minute of our order - its record is not taken for another's`() {
        ordered(running(60, 30, elapsed = 0, clock = key(9, 18, 7)), ms(9, 18, 7))
        ordered(none(key(9, 18, 42)), ms(9, 18, 42))
        heartbeat(none(key(9, 21)), ms(9, 21), finished(9, 18, 9, 18, result = ApexFinishedTbr.Offset.RESULT_CANCELLED_BY_COMMAND, rateRaw = 60))
        assertTrue(written.isEmpty())
    }

    @Test
    fun `nothing tells how ours ended - the middle of the span from the last reconciliation to the start of the heartbeat's minute`() {
        ours()
        heartbeat(running(60, 30, elapsed = 15, clock = key(9, 15)), ms(9, 15))
        heartbeat(none(key(9, 20)), ms(9, 20), finished(8, 0, 8, 30, rateRaw = 60))
        assertEquals(listOf<AapsWrite>(stop(ms(9, 17, 30))), written)
    }

    @Test
    fun `nothing tells how ours ended, its length is over - it ran out`() {
        ours()
        heartbeat(none(key(9, 30)), ms(9, 30))
        assertTrue(written.isEmpty())
    }

    @Test
    fun `before our command - ours cancelled on the pump in this minute ends in the middle from the minute's start to the read`() {
        ours()
        beforeCommand(none(key(9, 3, 20)), ms(9, 3, 20), finished(9, 0, 9, 3, rateRaw = 60))
        assertEquals(listOf<AapsWrite>(stop(ms(9, 3, 10))), written)
    }

    @Test
    fun `before our command - another's begun and over since the heartbeat gets its row, its end before the read`() {
        heartbeat(none(key(9, 15)), ms(9, 15))
        val keypad = finished(9, 16, 9, 18, rateRaw = 80, quarters = 1)
        beforeCommand(none(key(9, 18, 20)), ms(9, 18, 20), keypad)
        assertEquals(listOf<AapsWrite>(row(ms(9, 16), ms(9, 16, 30), 2.0, 100_000L)), written)
        heartbeat(none(key(9, 20)), ms(9, 20), keypad)
        assertEquals(1, written.size)
    }

    @Test
    fun `without the last finished one - ours ends where the next one began`() {
        ours()
        journal.settle(serial, card(running(80, 30, elapsed = 2, clock = key(9, 5, 0)), ms(9, 5)), TbrFacts(null, endAsked = false), 0L, ms(9, 5))
        assertEquals(listOf(stop(ms(9, 3, 30)), row(ms(9, 3), ms(9, 3, 30), 2.0, 30 * 60_000L)), written)
    }

    @Test
    fun `before our cancel the last finished one is not read - another's that replaced ours gets its row by the status, once`() {
        ours()
        // Another's 2 U/h began at 9:03; the read before our cancel at 9:05 has no 0x0B.
        journal.settle(serial, card(running(80, 30, elapsed = 2, clock = key(9, 5, 0)), ms(9, 5)), TbrFacts(null, endAsked = false), 0L, ms(9, 5))
        // Our cancel cut it at 9:05:02 (the row of the command), the status after it is the last reconciliation.
        ordered(none(key(9, 5, 4)), ms(9, 5, 4))
        heartbeat(none(key(9, 10)), ms(9, 10), finished(9, 3, 9, 5, result = ApexFinishedTbr.Offset.RESULT_CANCELLED_BY_COMMAND, rateRaw = 80))
        assertEquals(listOf(stop(ms(9, 3, 30)), row(ms(9, 3), ms(9, 3, 30), 2.0, 30 * 60_000L)), written)
    }

    @Test
    fun `the moment of the last reconciliation never goes back - a heartbeat queued behind our command`() {
        ordered(running(60, 30, elapsed = 0, clock = key(9, 21, 2)), ms(9, 21, 2))
        heartbeat(running(60, 30, elapsed = 0, clock = key(9, 21, 4)), ms(9, 21, 4))
        assertEquals(ms(9, 21, 2), journal.last(serial)!!.atMs)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `another's began the minute after ours and was cancelled in it - ours ends where it began, it gets its row`() {
        // The record is not ours, its rate and length are another's.
        ordered(running(0, 30, elapsed = 0, clock = key(9, 20, 34)), ms(9, 20, 34))
        heartbeat(none(key(9, 25)), ms(9, 25), finished(9, 21, 9, 21, result = ApexFinishedTbr.Offset.RESULT_CANCELLED_BY_COMMAND, rateRaw = 80, quarters = 1))
        assertEquals(listOf(stop(ms(9, 21, 20)), row(ms(9, 21), ms(9, 21, 20), 2.0, 20_000L)), written)
    }

    @Test
    fun `another's began in the same minute as ours - ours ends where it began, it gets its row to its end`() {
        // The same start minute, another rate and length.
        ordered(running(0, 120, elapsed = 0, clock = key(9, 30, 10)), ms(9, 30, 10))
        heartbeat(none(key(9, 35)), ms(9, 35), finished(9, 30, 9, 32, result = ApexFinishedTbr.Offset.RESULT_CANCELLED_BY_COMMAND, rateRaw = 80, quarters = 1))
        assertEquals(listOf(stop(ms(9, 30, 35)), row(ms(9, 30), ms(9, 30, 35), 2.0, 115_000L)), written)
    }

    @Test
    fun `ours with its start some seconds into the minute after a clock write - the record is ours`() {
        ordered(running(0, 120, elapsed = 0, clock = key(9, 25, 12)), ms(9, 25, 21))
        heartbeat(none(key(9, 35)), ms(9, 35), finished(9, 25, 9, 31, result = ApexFinishedTbr.Offset.RESULT_CANCELLED_BY_COMMAND, rateRaw = 0, quarters = 8, startSecond = 12))
        assertEquals(listOf<AapsWrite>(stop(ms(9, 31, 30))), written)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T
}
