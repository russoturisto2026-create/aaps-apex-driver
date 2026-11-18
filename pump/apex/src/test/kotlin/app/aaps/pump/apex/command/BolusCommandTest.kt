package app.aaps.pump.apex.command

import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.apex.ApexPumpClock
import app.aaps.pump.apex.R
import app.aaps.pump.apex.account.bolus.BolusAccount
import app.aaps.pump.apex.account.bolus.BolusJournal
import app.aaps.pump.apex.account.bolus.BolusRecordFrames
import app.aaps.pump.apex.account.bolus.JournalBench.Companion.TWO
import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.pulse.HeartbeatTick
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.state.FirmwareDiagnosis
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * The reads around our bolus. The read just before it brings nothing sound: the bolus is not sent, no
 * more commands go to the pump. The reads after it show no new record: the top is read three times, the
 * note of the bolus stays, and the heartbeat finds its record.
 */
class BolusCommandTest {

    private val manager: ApexManager = mock()
    private val delivery: ApexBolusDelivery = mock()
    private val heartbeatTick: HeartbeatTick = mock()
    private val rh: ResourceHelper = mock { on { gs(R.string.apex_bolus_not_sent) } doReturn "not sent" }
    private val diagnosis: FirmwareDiagnosis = mock { on { lastBolusRecord } doReturn true }
    private val pumpState: ApexPumpState = mock { on { this.diagnosis } doReturn diagnosis }
    private val store = FakeStore()
    private val journal: BolusJournal = mock()
    private val command = BolusCommand(
        AAPSLoggerTest(), mock<BolusProgressData>(), rh, manager, delivery, store, mock<ApexPumpClock>(),
        pumpState, journal, heartbeatTick, mock<ApexTrace>()
    )

    @Test
    fun `no answer to the read before - the bolus is not sent`() {
        whenever(manager.readLastBolusRecord()).thenReturn(null)
        val result = runBlocking { command.deliver(DetailedBolusInfo().apply { insulin = 0.6 }, "serial") }

        assertFalse(result.success)
        assertFalse(result.enacted)
        assertEquals("not sent", result.comment)
        verify(manager, times(3)).readLastBolusRecord()
        verify(manager, never()).readBolusHistory()
        verifyNoInteractions(delivery)
        verify(heartbeatTick, never()).run(any())
    }

    @Test
    fun `no new record after the bolus - the top is read three times, the note stays, nothing is settled`() {
        val top = BolusRecordFrames.record(TWO, 20, 20)
        val answer = ApexJournalAnswer(listOf(ApexRead(top.frame, ApexBolusRecord.decode(top.frame)!!)), 1)
        whenever(manager.readLastBolusRecord()).thenReturn(answer)
        runBlocking {
            whenever(delivery.bolus(any(), any(), any(), any(), any())).doSuspendableAnswer {
                it.getArgument<suspend (Long) -> Unit>(2).invoke(TWO * 1000L + 5_000L)
                ApexBolusOutcome.Delivered(0.5, completed = true)
            }
        }
        val result = runBlocking { command.deliver(DetailedBolusInfo().apply { insulin = 0.5 }, "serial") }

        assertTrue(result.success)
        verify(manager, times(4)).readLastBolusRecord()
        verify(manager, never()).readBolusHistory()
        verify(journal, never()).settleOurLast(any(), any(), any(), any())
        verify(journal, never()).settle(any(), any(), anyOrNull(), any(), any(), any(), any(), any(), any())
        assertEquals(1, BolusAccount(store, "serial").openActions().size)
    }
}
