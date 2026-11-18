package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.pump.apex.ApexLimits
import app.aaps.pump.apex.ApexPumpClock
import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.command.TbrCommand
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * A clock write closes a period: the heartbeat that writes it reads every object first, and writes it
 * only when its reconciliation succeeded.
 */
class HeartbeatClockTest {

    private val serial = "12345678"
    private val reader: PictureReader = mock()
    private val reconciliation: Reconciliation = mock()
    private val pumpClock: ApexPumpClock = mock()
    private val pumpState = ApexPumpState().apply { serialNumber = serial }
    private val card: ApexPumpState.StatusCard = Mockito.mock(ApexPumpState.StatusCard::class.java, Answers.RETURNS_DEEP_STUBS)
    private val picture: PumpPicture = Mockito.mock(PumpPicture::class.java, Answers.RETURNS_DEEP_STUBS)
    private val tick = HeartbeatTick(
        AAPSLoggerTest(), mock<RxBus>(), reader, mock<HeartbeatGrid>(), mock<ApexManager>(), FakeStore(), pumpState, pumpClock,
        reconciliation, mock<TbrCommand>(), mock<ApexLimits>(), mock<ApexTrace>()
    )

    private fun heartbeat(clockDue: String?, succeeded: Boolean, readAll: Boolean) {
        whenever(reader.readStatus()).thenReturn(card)
        whenever(reconciliation.quietStatus(eq(serial), eq(card), any())).thenReturn(card)
        whenever(pumpClock.dueReason(eq(serial), any())).thenReturn(clockDue)
        whenever(reader.read(any(), eq(serial), eq(card), eq(readAll))).thenReturn(picture)
        whenever(picture.days).thenReturn(null)
        whenever(reconciliation.run(eq(serial), eq(picture), any(), any())).thenReturn(Reconciliation.Result(picture, succeeded))
        tick.run(1_000L)
        verify(reader).read(any(), eq(serial), eq(card), eq(readAll))
    }

    @Test
    fun `the clock is due, the reconciliation succeeded - everything was read, the clock is written`() {
        heartbeat("the zone or summer time moved", succeeded = true, readAll = true)
        verify(pumpClock).writeIfDue(eq(serial), any(), any(), any(), any())
    }

    @Test
    fun `the clock is due, the reconciliation did not succeed - the clock waits`() {
        heartbeat("the zone or summer time moved", succeeded = false, readAll = true)
        verify(pumpClock, never()).writeIfDue(any(), any(), any(), any(), any())
    }

    @Test
    fun `the clock is not due - the objects are read as the gates say, no write`() {
        heartbeat(null, succeeded = true, readAll = false)
        verify(pumpClock, never()).writeIfDue(any(), any(), any(), any(), any())
        assertEquals(serial, pumpState.serialNumber)
    }
}
