package app.aaps.pump.atc3.basal

import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.atc3.check.Atc3AapsJournal
import app.aaps.pump.atc3.check.Atc3JournalArithmetic
import app.aaps.pump.atc3.check.Atc3Reconciliation
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.manager.Atc3Manager
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.state.editStatus
import app.aaps.pump.atc3.store.Atc3Store
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Calendar

/**
 * The window of the basal account: begun where there is nothing to close from, closed at the half
 * hour by the pump's count with its boluses taken out, handed on as figures and never written.
 */
class Atc3BasalPeriodKeeperTest : TestBaseWithProfile() {

    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var atc3Manager: Atc3Manager
    @Mock lateinit var atc3HistorySync: Atc3HistorySync
    @Mock lateinit var aapsJournal: Atc3AapsJournal
    @Mock lateinit var reconciliation: Atc3Reconciliation
    @Mock lateinit var spread: Atc3BasalSpread

    private lateinit var pumpState: Atc3PumpState
    private lateinit var store: Atc3Store
    private lateinit var keeper: Atc3BasalPeriodKeeper

    /** A local moment today, so the half hour boundaries fall where the code puts them. */
    private fun at(hour: Int, minute: Int, second: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, second)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val windowStart = at(10, 0, 3)
    private val windowEnd = at(10, 30, 3)

    @BeforeEach
    fun setup() {
        pumpState = Atc3PumpState()
        pumpState.serialNumber = "12345678"
        store = Atc3Store(aapsLogger, preferences, uiInteraction, rh)
        val trace = Atc3Trace(aapsLogger, preferences)
        keeper = Atc3BasalPeriodKeeper(
            aapsLogger, rh, store, dateUtil, commandQueue, pumpState, atc3Manager, atc3HistorySync, aapsJournal, reconciliation, trace, spread
        )
        whenever(atc3HistorySync.bolusesLearnedAfter(any())).thenReturn(0.0)
        whenever(atc3HistorySync.hasExpectedBolus()).thenReturn(false)
        whenever(atc3HistorySync.bolusStraddles(any(), any())).thenReturn(false)
        runBlocking {
            whenever(reconciliation.readBoluses()).thenReturn(Atc3HistorySync.ReconcileResult())
        }
    }

    /** The status read at [readMs] with the pump's count at [counter], and the keeper asked as a tick asks it. */
    private suspend fun read(readMs: Long, counter: Double, journalRead: Boolean = false) {
        whenever(dateUtil.now()).thenReturn(readMs)
        pumpState.editStatus(readAtMs = readMs) { it.copy(snapshotTime = readMs, deliveredTodayUnits = counter) }
        keeper.beginIfNeeded(pumpState.statusCard!!)
        keeper.closeIfDue(journalRead)
    }

    /** The rows order [units] of basal over any interval. */
    private fun rowsOrder(units: Double) = runBlocking {
        whenever(aapsJournal.insulinBetween(any(), any(), any())).thenReturn(Atc3JournalArithmetic.Breakdown(0.0, 0.0, units))
    }

    /** Every window handed on so far, in order. */
    private fun windowsSettled(): List<Atc3BasalPeriod.Window> {
        val windows = argumentCaptor<Atc3BasalPeriod.Window>()
        verify(spread, atLeast(0)).settle(windows.capture())
        return windows.allValues
    }

    @Test
    fun `the first read begins the window, and nothing is closed`() = runTest {
        read(windowStart, 10.0)

        assertThat(store.state.basalPeriod.start?.readMs).isEqualTo(windowStart)
        assertThat(store.state.basalPeriod.closed).isNull()
        verify(spread, never()).settle(any())
    }

    @Test
    fun `the first read of the next half hour closes the window by the count less its boluses, as figures only`() = runTest {
        rowsOrder(0.5)
        read(windowStart, 10.0)
        whenever(atc3HistorySync.bolusesLearnedAfter(windowStart)).thenReturn(1.0)

        read(windowEnd, 11.6)

        val window = windowsSettled().single()
        assertThat(window.fromMs).isEqualTo(windowStart)
        assertThat(window.toMs).isEqualTo(windowEnd)
        assertThat(window.pumpUnits).isWithin(1e-9).of(1.6)
        assertThat(window.bolusUnits).isWithin(1e-9).of(1.0)
        assertThat(window.basalUnits).isWithin(1e-9).of(0.6)
        assertThat(window.orderedUnits!!).isWithin(1e-9).of(0.5)
        assertThat(window.again).isFalse()
        // The next window begins at this read; the one closed is kept for a bolus that ran across its end.
        assertThat(store.state.basalPeriod.start?.readMs).isEqualTo(windowEnd)
        assertThat(store.state.basalPeriod.closed?.start?.readMs).isEqualTo(windowStart)
        assertThat(store.state.basalPeriod.closed?.endReadMs).isEqualTo(windowEnd)
        verify(atc3HistorySync).pruneLearned()
    }

    @Test
    fun `boluses first, the journal is read at the boundary when the tick did not`() = runTest {
        rowsOrder(0.5)
        read(windowStart, 10.0)

        read(windowEnd, 10.5)

        verify(reconciliation, times(1)).readBoluses()
        verify(spread, times(1)).settle(any())
    }

    @Test
    fun `a journal that cannot be read puts the close off to the next read`() = runTest {
        rowsOrder(0.5)
        read(windowStart, 10.0)
        runBlocking { whenever(reconciliation.readBoluses()).thenReturn(null) }

        read(windowEnd, 10.5)

        verify(spread, never()).settle(any())
        assertThat(store.state.basalPeriod.start?.readMs).isEqualTo(windowStart)
    }

    @Test
    fun `with no profile running nothing can be ordered, and the window still closes`() = runTest {
        runBlocking { whenever(aapsJournal.insulinBetween(any(), any(), any())).thenReturn(null) }
        read(windowStart, 10.0)

        read(windowEnd, 10.6)

        val window = windowsSettled().single()
        assertThat(window.orderedUnits).isNull()
        assertThat(window.differenceUnits).isNull()
        assertThat(store.state.basalPeriod.start?.readMs).isEqualTo(windowEnd)
    }

    @Test
    fun `a bolus learned after the close that began by its end has the window closed again, up to that read`() = runTest {
        rowsOrder(0.5)
        read(windowStart, 10.0)
        read(windowEnd, 10.6)
        // The next tick reads the journal and learns a bolus of 2 U that began before the boundary read.
        val later = at(10, 35, 0)
        whenever(atc3HistorySync.bolusStraddles(windowEnd, windowEnd)).thenReturn(true)
        whenever(atc3HistorySync.bolusesLearnedAfter(windowStart)).thenReturn(2.0)

        read(later, 12.7, journalRead = true)

        val windows = windowsSettled()
        assertThat(windows).hasSize(2)
        val again = windows.last()
        assertThat(again.again).isTrue()
        assertThat(again.fromMs).isEqualTo(windowStart)
        assertThat(again.toMs).isEqualTo(later)
        assertThat(again.basalUnits).isWithin(1e-9).of(0.7)
        assertThat(store.state.basalPeriod.start?.readMs).isEqualTo(later)
    }

    @Test
    fun `after a close the bolus journal is read once more, on the next tick, and not again`() = runTest {
        rowsOrder(0.5)
        read(windowStart, 10.0)
        // One read at the boundary, one more on the tick after, for a bolus that ran across the end.
        read(windowEnd, 10.5)
        read(at(10, 35, 0), 10.6)
        read(at(10, 40, 0), 10.7)

        verify(reconciliation, times(2)).readBoluses()
    }

    @Test
    fun `a bolus learned after the window began that had begun by its first read begins the window anew`() = runTest {
        read(windowStart, 10.0)
        whenever(atc3HistorySync.bolusStraddles(windowStart, windowStart)).thenReturn(true)
        val later = at(10, 5, 0)

        read(later, 10.3, journalRead = true)

        assertThat(store.state.basalPeriod.start?.readMs).isEqualTo(later)
        verify(spread, never()).settle(any())
    }

    @Test
    fun `a count that began anew within the day begins the window anew, with nothing closed`() = runTest {
        rowsOrder(0.5)
        read(windowStart, 10.0)

        read(windowEnd, 0.3)

        verify(spread, never()).settle(any())
        assertThat(store.state.basalPeriod.start?.readMs).isEqualTo(windowEnd)
        assertThat(store.state.basalPeriod.start?.counterUnits).isEqualTo(0.3)
    }

    @Test
    fun `a refill begins the window anew at the next read`() = runTest {
        rowsOrder(0.5)
        read(windowStart, 10.0)
        keeper.noteRefill()
        val afterRefill = at(10, 12, 0)

        read(afterRefill, 10.2)

        assertThat(store.state.basalPeriod.start?.readMs).isEqualTo(afterRefill)
        // Once: the next read goes on from there.
        read(at(10, 17, 0), 10.3)
        assertThat(store.state.basalPeriod.start?.readMs).isEqualTo(afterRefill)
    }
}
