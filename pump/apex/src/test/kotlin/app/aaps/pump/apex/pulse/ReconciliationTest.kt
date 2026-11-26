package app.aaps.pump.apex.pulse

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.aaps.AapsCarrier
import app.aaps.pump.apex.aaps.AapsWrite
import app.aaps.pump.apex.basal.BasalPeriodKeeper
import app.aaps.pump.apex.bolus.BolusCells
import app.aaps.pump.apex.bolus.BolusReconciliation
import app.aaps.pump.apex.bolus.JournalBench
import app.aaps.pump.apex.keys.ApexBooleanKey
import app.aaps.pump.apex.store.ClockOffset
import app.aaps.pump.apex.store.FakeStore
import app.aaps.pump.apex.store.Snapshot
import app.aaps.pump.apex.store.SnapshotKind
import app.aaps.pump.apex.basal.TbrReconciliation
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexDailyRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.protocol.ApexStatusV1
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The bolus is asked about first, from Status V1 alone; the status, today's day of the daily statistics,
 * is kept only by a reconciliation that succeeded, and the start is one.
 */
class ReconciliationTest {

    private val serial = "12345678"
    private val store = FakeStore()
    private val journal: BolusReconciliation = mock()
    private val keeper: BasalPeriodKeeper = mock()
    private val runningBolus: RunningBolus = mock()
    private val answer: ApexJournalAnswer<ApexBolusRecord> = mock()
    private val carrier: AapsCarrier = mock()
    private val preferences: Preferences = mock()
    private val reconciliation = Reconciliation(AAPSLoggerTest(), store, journal, mock<TbrReconciliation>(), keeper, runningBolus, carrier, preferences, mock<ApexTrace>())

    /**
     * The picture of a heartbeat at [atMs], today's bolus field at [bolusRaw] steps, with the bolus journal read; [bolusRaw]
     * null: the statistics hold no day. [reservoir]: what Status V1 shows, U. [start]: the first since AAPS started.
     */
    private fun picture(atMs: Long, bolusRaw: Int?, reservoir: Double = 100.0, start: Boolean = false): PumpPicture {
        val read = ApexStatusV1.decode(ApexResponseFrame(JournalBench.statusFrame(reservoir)))!!
        val card: ApexPumpState.StatusCard = mock { on { status } doReturn read }
        val boluses = JournalRead(answer, repeatedBroken = false)
        val days = JournalRead(bolusRaw?.let { JournalBench.days(it) } ?: ApexJournalAnswer(emptyList(), 0), repeatedBroken = false)
        return mock {
            on { heartbeatAtMs } doReturn atMs
            on { readAtMs } doReturn atMs + 3_000L
            on { status } doReturn card
            on { this.days } doReturn days
            on { this.boluses } doReturn boluses
            on { this.start } doReturn start
        }
    }

    private fun settledGives(settled: BolusReconciliation.Settled?) =
        whenever(journal.settle(eq(serial), any(), anyOrNull(), any(), any(), any(), any())).thenReturn(settled)

    /** The bolus field of the day kept, steps; null when no day is kept. */
    private fun keptRaw(): Int? = store.snapshot(serial, SnapshotKind.DAILY)?.frames?.firstOrNull()
        ?.let { ApexDailyRecord.decode(ApexResponseFrame(it))!!.bolusUnits }
        ?.let { Math.round(it / 0.025).toInt() }

    @Test
    fun `a bolus that runs - the first step says so from Status V1 alone, none runs - the last status read goes on`() {
        val card = picture(1_000L, 200).status
        val last: ApexPumpState.StatusCard = mock()
        whenever(runningBolus.quietStatus(eq(card), any())).thenReturn(null)
        assertNull(reconciliation.quietStatus(serial, card))
        whenever(runningBolus.quietStatus(eq(card), any())).thenReturn(last)
        assertEquals(last, reconciliation.quietStatus(serial, card))
    }

    @Test
    fun `no bolus at the start - the journal is settled and the picture is the first status kept`() {
        val beat = picture(1_000L, 200)
        settledGives(BolusReconciliation.Settled(emptyList()))

        val result = reconciliation.run(serial, beat, offsetMs = 0L)

        assertTrue(result.succeeded)
        assertEquals(200, keptRaw())
        assertTrue(reconciliation.hasStatusKept(serial))
        verify(keeper).onHeartbeat(serial, beat)
    }

    @Test
    fun `the start drops the lines of the clock log that counted more bolus records than the driver, the heartbeats do not`() {
        listOf(128L, 949L, 338L, 380L).forEachIndexed { i, count -> store.saveClock(serial, ClockOffset(28_800_000L, 1_000L * i, "test"), count) }
        settledGives(BolusReconciliation.Settled(emptyList()))
        store.putState(serial, BolusCells.COUNTER_KEY, "300")
        reconciliation.run(serial, picture(1_000L, 200), offsetMs = 0L)
        assertEquals(listOf(128L, 949L, 338L, 380L), store.clockLog(serial).map { it.bolusCount })

        store.putState(serial, BolusCells.COUNTER_KEY, "380")
        reconciliation.run(serial, picture(2_000L, 200, start = true), offsetMs = 0L)
        assertEquals(listOf(128L, 338L, 380L), store.clockLog(serial).map { it.bolusCount })
    }

    @Test
    fun `the start before a command, off the heartbeats - the status is kept, the period waits for its heartbeat`() {
        val beat = picture(1_000L, 228)
        settledGives(BolusReconciliation.Settled(emptyList()))

        assertTrue(reconciliation.run(serial, beat, offsetMs = 0L, withPeriod = false).succeeded)
        assertEquals(228, keptRaw())
        verify(keeper, never()).onHeartbeat(any(), any())
    }

    @Test
    fun `a journal not settled - no reconciliation, the day kept stays`() {
        settledGives(BolusReconciliation.Settled(emptyList()))
        reconciliation.run(serial, picture(500L, 200), offsetMs = 0L)
        settledGives(null)

        assertFalse(reconciliation.run(serial, picture(1_000L, 228), offsetMs = 0L).succeeded)
        assertEquals(200, keptRaw())
    }

    @Test
    fun `the statistics came with no day - no reconciliation at all, nothing settled or kept, the next heartbeat reads again`() {
        settledGives(BolusReconciliation.Settled(emptyList()))
        reconciliation.run(serial, picture(500L, 200), offsetMs = 0L)
        clearInvocations(journal, keeper)

        assertFalse(reconciliation.run(serial, picture(1_000L, null), offsetMs = 0L).succeeded)
        verify(journal, never()).settle(any(), any(), any(), any(), any(), any(), any())
        verify(keeper, never()).onHeartbeat(any(), any())
        assertEquals(200, keptRaw())
    }

    @Test
    fun `a line is told and the loop opened only once the reconciliation is kept - one cut short tells nothing`() {
        val beat = picture(1_000L, 200)
        val told = listOf(AapsWrite.OpenLoop)
        settledGives(BolusReconciliation.Settled(emptyList(), told = told))
        whenever(keeper.onHeartbeat(serial, beat)).thenThrow(IllegalStateException("cut short"))
        assertThrows(IllegalStateException::class.java) { reconciliation.run(serial, beat, offsetMs = 0L) }
        verify(carrier, never()).carry(any(), any())

        val next = picture(2_000L, 200)
        assertTrue(reconciliation.run(serial, next, offsetMs = 0L).succeeded)
        verify(carrier).carry(AapsWrite.OpenLoop, serial)
    }

    @Test
    fun `the reservoir grew by 5 U or more since the status kept - an insulin change is told, also when the journal does not settle`() {
        whenever(preferences.get(ApexBooleanKey.RefillEvent)).thenReturn(true)
        store.saveSnapshot(serial, SnapshotKind.STATUS, Snapshot(listOf(JournalBench.statusFrame(20.0)), 500L))
        settledGives(BolusReconciliation.Settled(emptyList()))

        assertTrue(reconciliation.run(serial, picture(1_000L, 200, reservoir = 24.0), offsetMs = 0L).succeeded)
        verify(carrier, never()).carry(any<AapsWrite.Refill>(), any())

        assertTrue(reconciliation.run(serial, picture(2_000L, 200, reservoir = 26.0), offsetMs = 0L).succeeded)
        verify(carrier).carry(argThat { this is AapsWrite.Refill && atMs == 2_000L && abs(toUnits - fromUnits - 6.0) < 1e-6 }, eq(serial))

        settledGives(null)
        assertFalse(reconciliation.run(serial, picture(3_000L, 200, reservoir = 180.0), offsetMs = 0L).succeeded)
        verify(carrier).carry(argThat { this is AapsWrite.Refill && atMs == 3_000L }, eq(serial))
    }

    @Test
    fun `the switch is off - a refill is not told`() {
        store.saveSnapshot(serial, SnapshotKind.STATUS, Snapshot(listOf(JournalBench.statusFrame(20.0)), 500L))
        settledGives(BolusReconciliation.Settled(emptyList()))

        assertTrue(reconciliation.run(serial, picture(1_000L, 200, reservoir = 180.0), offsetMs = 0L).succeeded)
        verify(carrier, never()).carry(any<AapsWrite.Refill>(), any())
    }
}
