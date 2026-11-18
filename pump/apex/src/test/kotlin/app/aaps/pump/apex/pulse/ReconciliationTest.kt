package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.account.basal.BasalPeriodKeeper
import app.aaps.pump.apex.account.basal.Delivered
import app.aaps.pump.apex.account.bolus.BolusCountMark
import app.aaps.pump.apex.account.bolus.BolusJournal
import app.aaps.pump.apex.account.bolus.BolusSpanEnd
import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.account.tbr.TbrJournal
import app.aaps.pump.apex.deep.BolusByDaysDeep
import app.aaps.pump.apex.deep.BolusReconcileDeep
import app.aaps.pump.apex.deep.RunningBolusDeep
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The bolus is asked about first, from Status V1 alone; the status is kept only by a reconciliation
 * that succeeded, and the start is one.
 * A pump without the daily statistics: the status kept is its count, see [BolusCountMark].
 */
class ReconciliationTest {

    private val serial = "12345678"
    private val pumpDay = 20_000L
    private val store = FakeStore()
    private val reader: PictureReader = mock()
    private val journal: BolusJournal = mock()
    private val keeper: BasalPeriodKeeper = mock()
    private val runningBolus: RunningBolusDeep = mock()
    private val answer: ApexJournalAnswer<ApexBolusRecord> = mock()
    private val reconciliation = Reconciliation(AAPSLoggerTest(), store, reader, journal, mock<TbrJournal>(), keeper, runningBolus, mock<ApexTrace>())

    /** The picture of a heartbeat at [atMs], the pump's count at [count], with the bolus journal read. */
    private fun picture(atMs: Long, count: Double): PumpPicture {
        val card: ApexPumpState.StatusCard = mock { on { deliveredTodayUnits } doReturn count }
        val boluses = JournalRead(answer, repeatedBroken = false)
        return mock {
            on { heartbeatAtMs } doReturn atMs
            on { readAtMs } doReturn atMs + 3_000L
            on { status } doReturn card
            on { days } doReturn null
            on { this.boluses } doReturn boluses
            on { delivered() } doReturn Delivered.Count(count, pumpDay)
        }
    }

    private fun checked(offMove: Boolean) = BolusReconcileDeep.Checked(BolusByDaysDeep.Result(emptyList(), change = null), offMove)

    private fun keepCount(atMs: Long, count: Double) = BolusCountMark(store, serial).move(BolusSpanEnd.Count(atMs, Delivered.Count(count, pumpDay), null))

    private fun settledGives(offMove: Boolean) =
        whenever(journal.settle(eq(serial), any(), anyOrNull(), any(), any(), any(), any(), any(), any())).thenReturn(checked(offMove))


    @Test
    fun `a bolus that runs - the first step says so from Status V1 alone, none runs - the last status read goes on`() {
        val card = picture(1_000L, 10.0).status
        val last = picture(1_000L, 10.05).status
        whenever(runningBolus.quietStatus(eq(card), any())).thenReturn(null)
        assertNull(reconciliation.quietStatus(serial, card, dayRecord = false))
        whenever(runningBolus.quietStatus(eq(card), any())).thenReturn(last)
        assertEquals(last, reconciliation.quietStatus(serial, card, dayRecord = false))
    }

    @Test
    fun `no bolus at the start - the journal is settled and the picture is the first status kept`() {
        val beat = picture(1_000L, 10.0)
        settledGives(offMove = false)

        val result = reconciliation.run(serial, beat, offsetMs = 0L)

        assertTrue(result.succeeded)
        assertEquals(10.0, BolusCountMark(store, serial).get()!!.count.units, 1e-9)
        verify(keeper).onHeartbeat(serial, beat)
    }

    @Test
    fun `records off the move with no bolus running - the journal and the numbers read again, the status kept by them`() {
        keepCount(500L, 9.0)
        val beat = picture(1_000L, 12.0)
        val fresh = picture(1_000L, 12.5)
        settledGives(offMove = true)
        whenever(journal.readJournalAgain(eq(serial), any())).thenReturn(answer)
        whenever(reader.refresh(beat)).thenReturn(fresh)

        val result = reconciliation.run(serial, beat, offsetMs = 0L)

        assertTrue(result.succeeded)
        assertEquals(12.5, BolusCountMark(store, serial).get()!!.count.units, 1e-9)
        verify(keeper).onHeartbeat(serial, fresh)
    }

    @Test
    fun `records that explain the move - the status kept moves, the period with it`() {
        keepCount(500L, 9.0)
        val beat = picture(1_000L, 9.2)
        settledGives(offMove = false)

        val result = reconciliation.run(serial, beat, offsetMs = 0L)

        assertTrue(result.succeeded)
        assertEquals(1_000L, BolusCountMark(store, serial).get()!!.atMs)
        verify(keeper).onHeartbeat(serial, beat)
        verify(journal, never()).readJournalAgain(any(), any())
    }

    @Test
    fun `the start before a command, off the heartbeats - the status is kept, the period waits for its heartbeat`() {
        keepCount(500L, 9.0)
        val beat = picture(1_000L, 9.7)
        settledGives(offMove = false)

        assertTrue(reconciliation.run(serial, beat, offsetMs = 0L, withPeriod = false).succeeded)
        assertEquals(1_000L, BolusCountMark(store, serial).get()!!.atMs)
        verify(keeper, never()).onHeartbeat(any(), any())
    }

    @Test
    fun `a journal not settled - no reconciliation`() {
        keepCount(500L, 9.0)
        val beat = picture(1_000L, 9.7)
        whenever(journal.settle(eq(serial), any(), anyOrNull(), any(), any(), any(), any(), any(), any())).thenReturn(null)

        assertFalse(reconciliation.run(serial, beat, offsetMs = 0L).succeeded)
        assertEquals(500L, BolusCountMark(store, serial).get()!!.atMs)
    }
}
