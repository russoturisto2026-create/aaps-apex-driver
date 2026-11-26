package app.aaps.pump.apex.pulse

import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.pump.apex.aaps.ApexLimits
import app.aaps.pump.apex.clock.ApexPumpClock
import app.aaps.pump.apex.bolus.JournalBench
import app.aaps.pump.apex.store.FakeStore
import app.aaps.pump.apex.basal.TbrCommand
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexVersion
import app.aaps.pump.apex.state.ApexPumpState
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * A clock write closes a period: the heartbeat that writes it reads every object first, and writes it
 * only when its reconciliation succeeded. The first reconciliation since AAPS started reads every object too.
 */
class HeartbeatClockTest {

    private val serial = "12345678"
    private val reader: PictureReader = mock()
    private val reconciliation: Reconciliation = mock()
    private val pumpClock: ApexPumpClock = mock()
    // A pump of protocol 4.11: the version is read, its stamps are plain.
    private val pumpState = ApexPumpState().apply { serialNumber = serial; version = ApexVersion(listOf(6, 28), 4, 11, listOf(0, 0)) }
    private val card: ApexPumpState.StatusCard = Mockito.mock(ApexPumpState.StatusCard::class.java, Answers.RETURNS_DEEP_STUBS)
    private val picture: PumpPicture = Mockito.mock(PumpPicture::class.java, Answers.RETURNS_DEEP_STUBS)
    private val notifications: NotificationManager = mock()
    private val codeFault = CodeFault(AAPSLoggerTest(), notifications, mock())
    private val tick = HeartbeatTick(
        AAPSLoggerTest(), reader, mock<HeartbeatGrid>(), mock<ApexManager>(), pumpState, pumpClock,
        reconciliation, mock<TbrCommand>(), mock<ApexLimits>(), mock<PictureReport>(), codeFault, StartTries(AAPSLoggerTest(), notifications, mock())
    )

    private fun answers(clockDue: String?, succeeded: Boolean) {
        whenever(reader.readStatus()).thenReturn(card)
        whenever(reconciliation.quietStatus(eq(serial), eq(card))).thenReturn(card)
        whenever(pumpClock.dueReason(eq(serial), any())).thenReturn(clockDue)
        whenever(reader.read(any(), eq(serial), eq(card), anyOrNull())).thenReturn(picture)
        whenever(picture.days).thenReturn(JournalRead(JournalBench.days(200), repeatedBroken = false))
        whenever(reconciliation.run(eq(serial), eq(picture), any(), any())).thenReturn(Reconciliation.Result(picture, succeeded))
    }

    /** A heartbeat; [readAll]: why it read every object, null when it read as the gates say. */
    private fun heartbeat(clockDue: String?, succeeded: Boolean, readAll: PictureReader.ReadAll?) {
        answers(clockDue, succeeded)
        tick.run(1_000L)
        verify(reader).read(any(), eq(serial), eq(card), eq(readAll))
    }

    /** The first reconciliation since AAPS started, done at the link setup. */
    private fun started() {
        answers(null, succeeded = true)
        whenever(reconciliation.hasStatusKept(serial)).thenReturn(true)
        assertTrue(tick.startIfNeeded())
        verify(reader).read(any(), eq(serial), eq(card), eq(PictureReader.ReadAll.START))
        clearInvocations(reader)
    }

    @Test
    fun `the clock is due, the reconciliation succeeded - everything was read, the clock is written`() {
        started()
        heartbeat("the zone or summer time moved", succeeded = true, readAll = PictureReader.ReadAll.CLOCK)
        verify(pumpClock).writeIfDue(eq(serial), any(), any(), any())
    }

    @Test
    fun `the clock is due, the reconciliation did not succeed - the clock waits`() {
        started()
        heartbeat("the zone or summer time moved", succeeded = false, readAll = PictureReader.ReadAll.CLOCK)
        verify(pumpClock, never()).writeIfDue(any(), any(), any(), any())
    }

    @Test
    fun `the clock is not due - the objects are read as the gates say, no write`() {
        started()
        heartbeat(null, succeeded = true, readAll = null)
        verify(pumpClock, never()).writeIfDue(any(), any(), any(), any())
        assertEquals(serial, pumpState.serialNumber)
    }

    @Test
    fun `the first reconciliation since AAPS started with the clock due - read as the start, the clock waits for the heartbeat after it`() {
        whenever(reconciliation.hasStatusKept(serial)).thenReturn(true)
        heartbeat("the zone or summer time moved", succeeded = true, readAll = PictureReader.ReadAll.START)
        verify(pumpClock, never()).writeIfDue(any(), any(), any(), any())
        clearInvocations(reader)
        heartbeat("the zone or summer time moved", succeeded = true, readAll = PictureReader.ReadAll.CLOCK)
        verify(pumpClock).writeIfDue(eq(serial), any(), any(), any())
    }

    @Test
    fun `a status kept, AAPS just started - the link setup reads every object, the bolus journal too`() {
        started()
    }

    @Test
    fun `the first reconciliation since AAPS started did not succeed - the next heartbeat reads every object, then the gates decide`() {
        answers(null, succeeded = false)
        whenever(reconciliation.hasStatusKept(serial)).thenReturn(true)
        assertFalse(tick.startIfNeeded())
        clearInvocations(reader)
        heartbeat(null, succeeded = true, readAll = PictureReader.ReadAll.START)
        clearInvocations(reader)
        heartbeat(null, succeeded = true, readAll = null)
    }

    @Test
    fun `the reconciliation breaks on an error of the code - no command goes, the user is told until one succeeds`() {
        started()
        clearInvocations(notifications)
        answers(null, succeeded = true)
        whenever(reconciliation.run(eq(serial), eq(picture), any(), any()))
            .thenThrow(IllegalStateException("a record the code did not foresee")).thenReturn(Reconciliation.Result(picture, true))
        assertTrue(tick.run(1_000L))
        assertTrue(codeFault.standing)
        assertFalse(tick.startIfNeeded())
        verify(notifications).post(eq(NotificationId.PUMP_SYNC_ERROR), anyOrNull<String>(), eq(NotificationLevel.URGENT), any<Int>(), anyOrNull(), any(), anyOrNull())
        // The next heartbeat reads every object again; this time it succeeds.
        clearInvocations(reader)
        tick.run(2_000L)
        verify(reader).read(any(), eq(serial), eq(card), eq(PictureReader.ReadAll.AGAIN))
        assertFalse(codeFault.standing)
        verify(notifications).dismiss(NotificationId.PUMP_SYNC_ERROR)
        assertTrue(tick.startIfNeeded())
    }

    @Test
    fun `the start does not pass three times in a row - the user is told once, and the word goes when it passes`() {
        answers(null, succeeded = false)
        whenever(reconciliation.hasStatusKept(serial)).thenReturn(true)
        repeat(4) { assertFalse(tick.startIfNeeded()) }
        verify(notifications).post(eq(NotificationId.PUMP_WARNING), anyOrNull<String>(), eq(NotificationLevel.URGENT), any<Int>(), anyOrNull(), any(), anyOrNull())
        answers(null, succeeded = true)
        assertTrue(tick.startIfNeeded())
        verify(notifications).dismiss(NotificationId.PUMP_WARNING)
    }

    @Test
    fun `the first reconciliation since AAPS started takes down a word of an error left from before`() {
        started()
        verify(notifications).dismiss(NotificationId.PUMP_SYNC_ERROR)
    }
}
