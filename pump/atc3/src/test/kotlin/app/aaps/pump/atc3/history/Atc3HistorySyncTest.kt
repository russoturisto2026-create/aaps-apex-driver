package app.aaps.pump.atc3.history

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.LongNonKey
import app.aaps.pump.atc3.basal.Atc3BasalPeriod
import app.aaps.pump.atc3.clock.Atc3ClockWatch
import app.aaps.pump.atc3.keys.Atc3LongNonKey
import app.aaps.pump.atc3.keys.Atc3StringNonKey
import app.aaps.pump.atc3.link.Atc3LinkWatch
import app.aaps.pump.atc3.protocol.Atc3BolusRecord
import app.aaps.pump.atc3.protocol.Atc3FinishedTbr
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3RefillRecord
import app.aaps.pump.atc3.protocol.Atc3StatusV1
import app.aaps.pump.atc3.protocol.Atc3TbrStatus
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.store.Atc3Store
import app.aaps.pump.atc3.store.Atc3StoredState
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * What the driver tells AAPS, and what it does when AAPS says no.
 *
 * The class under test is the only place in the driver that writes treatments, so the cases here
 * are about the answers coming back: a refusal must never end with the record filed as counted,
 * because then nothing would ever offer it again.
 */
class Atc3HistorySyncTest : TestBaseWithProfile() {

    @Mock lateinit var pumpSync: PumpSync
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var persistenceLayer: PersistenceLayer

    /** The bolus rows AAPS holds: every row written here lands in it, as the reconciler reads them back. */
    private val rows = ArrayList<BS>()

    private lateinit var pumpState: Atc3PumpState
    private lateinit var clockWatch: Atc3ClockWatch
    private lateinit var sync: Atc3HistorySync
    private lateinit var store: Atc3Store
    private lateinit var events: Atc3HistoryEvents

    private val serial = "12345678"

    @BeforeEach
    fun setup() {
        pumpState = Atc3PumpState()
        pumpState.serialNumber = serial
        whenever(rh.gs(anyInt())).thenReturn("mocked resource")
        whenever(preferences.get(LongNonKey.ActivePumpChangeTimestamp)).thenReturn(0L)
        // These say the uninteresting thing — the write went in — so each case can stub over it
        // when the answer is what it is about.
        whenever(pumpSync.syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())).thenReturn(true)
        whenever(pumpSync.syncStopTemporaryBasalWithPumpId(any(), any(), any(), any(), any())).thenReturn(true)
        whenever(pumpSync.syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())).thenAnswer {
            rows.add(BS(timestamp = it.getArgument(0), amount = it.getArgument(1), type = it.getArgument(2), ids = IDs(pumpId = it.getArgument(3), pumpSerial = it.getArgument(5))))
            true
        }
        whenever(persistenceLayer.getBolusesFromTimeIncludingInvalid(any(), any())).thenAnswer { Single.just(rows.toList()) }
        whenever(pumpSync.createOrUpdateTotalDailyDose(any(), any(), any(), any(), anyOrNull(), any(), any())).thenReturn(true)
        clockWatch = Atc3ClockWatch()
        val trace = Atc3Trace(aapsLogger, preferences)
        val registration = Atc3PumpRegistration(aapsLogger, rh, pumpSync, pumpState)
        store = Atc3Store(aapsLogger, preferences, uiInteraction, rh)
        sync = Atc3HistorySync(
            aapsLogger, rh, uiInteraction, preferences, store, pumpSync, persistenceLayer, dateUtil, pumpState,
            clockWatch, trace, registration
        )
        events = Atc3HistoryEvents(aapsLogger, rh, preferences, pumpSync, dateUtil, pumpState, trace, registration)
    }

    /**
     * The record the pump writes for a bolus of ours accepted at [startedAtMs]: the start minute on
     * the pump's wall clock, second 59, moved by [shiftMinutes] for a pump clock that is off.
     */
    private fun ownRecord(startedAtMs: Long, raw: Int, delivered: Int = raw, shiftMinutes: Int = 0): Atc3BolusRecord {
        val stamp = Math.floorDiv(Atc3StatusV1.wallClockUtcSeconds(startedAtMs), 60L) * 60L + 59L + shiftMinutes * 60L
        return Atc3BolusRecord(
            index = 0,
            // The pump's stamp: the start minute at second 59, as the driver decodes it.
            timestamp = Math.floorDiv(startedAtMs, 60_000L) * 60_000L + 59_000L + shiftMinutes * 60_000L,
            pumpClockUtcSeconds = stamp,
            rawRequested = raw,
            rawDelivered = delivered
        )
    }

    /** A stranger's record stamped [secondsAgo] ago; [delivered] defaults to [raw]. */
    private fun record(secondsAgo: Long, raw: Int, delivered: Int = raw) = Atc3BolusRecord(
        index = 0,
        timestamp = now - secondsAgo * 1000L,
        pumpClockUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(now - secondsAgo * 1000L),
        rawRequested = raw,
        rawDelivered = delivered,
        rawExtendedRequested = 0,
        rawExtendedDelivered = 0
    )

    /**
     * A start clock the pump did not fill in: six zero bytes, which the clock decoder turns into
     * 1999-11-30.
     */
    private val emptyStart = Atc3TbrStatus(
        startTimestamp = 943909200000L,
        startUtcSeconds = 943909200L,
        rate = 0.45,
        percent = null,
        durationMinutes = 45,
        deliveredUnits = 0.0
    )

    /** Take the first pass: AAPS adopted the pump ten minutes ago, and what the pump held before that is its past. */
    private suspend fun afterFirstPass() {
        whenever(pumpSync.verifyPumpIdentification(any(), any())).thenReturn(true)
        whenever(preferences.get(LongNonKey.ActivePumpChangeTimestamp)).thenReturn(now - 600_000L)
        sync.reconcileBoluses(listOf(record(3600, 40)), 1)
    }

    /** The start of the minute [timestamp] lies in: where a row made of a record is dated. */
    private fun minuteOf(timestamp: Long) = timestamp - Math.floorMod(timestamp, 60_000L)

    @Test
    fun `forgetting the pump forgets everything kept of it`() = runTest {
        afterFirstPass()
        store.update {
            it.copy(
                learned = listOf(LearnedBolus(now, 1.0, now - 60_000L)),
                basalPeriod = Atc3BasalPeriod.State(Atc3BasalPeriod.Mark(now, 20.0, now)),
                lastAnswer = Atc3LinkWatch.Stop(now, 20.0),
                linkStop = Atc3LinkWatch.Stop(now - 3_600_000L, 18.0)
            )
        }

        sync.forgetPump("")

        assertThat(store.state).isEqualTo(Atc3StoredState(ledger = Atc3HistoryLedger(serial = "")))
    }

    @Test
    fun `an unadopted pump has nothing written and nothing filed away`() = runTest {
        // AAPS is holding somebody else's pump, so every write would be refused. Filing the records
        // as counted here would lose them for good: nothing offers a record twice.
        whenever(pumpSync.verifyPumpIdentification(any(), any())).thenReturn(false)

        val result = sync.reconcileBoluses(listOf(record(60, 40)), 1)

        assertThat(result.confirmed).isEmpty()
        assertThat(result.newestImportedAtMs).isNull()
        verify(pumpSync, never()).syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())
        verify(preferences, never()).put(eq(Atc3StringNonKey.State), any<String>())
    }

    @Test
    fun `a bolus given on the pump reaches AAPS once the pump is adopted`() = runTest {
        afterFirstPass()

        val result = sync.reconcileBoluses(listOf(record(3600, 40), record(60, 80)), 2)

        // Dated by the minute: the record's seconds are its place in the minute, not a time.
        verify(pumpSync, times(1)).syncBolusWithPumpId(
            eq(minuteOf(now - 60_000L)), eq(2.0), eq(BS.Type.NORMAL), any(), eq(PumpType.ATC3), eq(serial)
        )
        assertThat(result.newestImportedAtMs).isEqualTo(minuteOf(now - 60_000L))
    }

    @Test
    fun `a bolus of the minute the pump was adopted in is dated at the adoption moment, so AAPS takes it`() = runTest {
        // Adopted one second into the minute, the bolus 21 seconds later: a row at the minute start is older than the adoption.
        val adopted = minuteOf(now - 60_000L) + 1_000L
        whenever(pumpSync.verifyPumpIdentification(any(), any())).thenReturn(true)
        whenever(preferences.get(LongNonKey.ActivePumpChangeTimestamp)).thenReturn(adopted)

        sync.reconcileBoluses(listOf(record(60, 80)), 1)
        sync.reconcileBoluses(listOf(record(60, 80)), 1)

        verify(pumpSync, times(1)).syncBolusWithPumpId(eq(adopted), eq(2.0), eq(BS.Type.NORMAL), eq(minuteOf(now - 60_000L)), eq(PumpType.ATC3), eq(serial))
        verify(uiInteraction, never()).addNotification(anyInt(), anyOrNull(), anyInt())
    }

    @Test
    fun `a bolus row AAPS refuses is said on the screen`() = runTest {
        afterFirstPass()
        whenever(pumpSync.syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())).thenReturn(false)

        sync.reconcileBoluses(listOf(record(60, 80)), 1)

        verify(uiInteraction, times(1)).addNotification(eq(Notification.PUMP_SYNC_ERROR), anyOrNull(), eq(Notification.URGENT))
    }

    @Test
    fun `a bolus AAPS already knows about is not imported a second time`() = runTest {
        afterFirstPass()
        sync.reconcileBoluses(listOf(record(60, 80)), 1)

        sync.reconcileBoluses(listOf(record(60, 80)), 1)

        verify(pumpSync, times(1)).syncBolusWithPumpId(
            any(), eq(2.0), anyOrNull(), any(), any(), any()
        )
    }

    @Test
    fun `a record from before AAPS adopted the pump is never offered to it`() = runTest {
        whenever(preferences.get(LongNonKey.ActivePumpChangeTimestamp)).thenReturn(now - 600_000L)
        afterFirstPass()

        sync.reconcileBoluses(listOf(record(3600, 40), record(1200, 80)), 2)

        // Twenty minutes old, and AAPS only took the pump ten minutes ago.
        verify(pumpSync, never()).syncBolusWithPumpId(
            any(), eq(2.0), anyOrNull(), any(), any(), any()
        )
    }

    // Our boluses: expected from the pump's acceptance, written from the pump's record

    @Test
    fun `our bolus is written from its record with the type expected, and the expectation closes`() = runTest {
        afterFirstPass()
        val startedAt = now - 30_000L
        sync.expect(startedAt, 2.0, BS.Type.SMB)
        assertThat(sync.hasExpectedBolus()).isTrue()

        val record = ownRecord(startedAt, 80)
        val result = sync.reconcileBoluses(listOf(record), 1)

        assertThat(result.confirmed).containsExactly(startedAt, 2.0)
        verify(pumpSync, times(1)).syncBolusWithPumpId(
            eq(minuteOf(record.timestamp)), eq(2.0), eq(BS.Type.SMB), any(), eq(PumpType.ATC3), eq(serial)
        )
        assertThat(sync.hasExpectedBolus()).isFalse()
        assertThat(pumpState.lastBolus?.atMs).isEqualTo(minuteOf(record.timestamp))
        // Read again, the row is there and nothing is written twice.
        sync.reconcileBoluses(listOf(record), 1)
        verify(pumpSync, times(1)).syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())
    }

    @Test
    fun `the expectation is on disk before anything is delivered`() = runTest {
        afterFirstPass()
        clearInvocations(preferences)

        sync.expect(now, 2.0, BS.Type.SMB)

        verify(preferences).put(eq(Atc3StringNonKey.State), any<String>())
    }

    @Test
    fun `a record of another dose is not ours, and ours stays expected`() = runTest {
        afterFirstPass()
        val startedAt = now - 30_000L
        sync.expect(startedAt, 2.0, BS.Type.SMB)

        val result = sync.reconcileBoluses(listOf(ownRecord(startedAt, 40)), 1)

        assertThat(result.confirmed).isEmpty()
        verify(pumpSync, times(1)).syncBolusWithPumpId(any(), eq(1.0), eq(BS.Type.NORMAL), any(), any(), any())
        assertThat(sync.hasExpectedBolus()).isTrue()
    }

    @Test
    fun `a bolus cut short is written at what the record says went in`() = runTest {
        afterFirstPass()
        val startedAt = now - 30_000L
        sync.expect(startedAt, 2.0, BS.Type.NORMAL)

        // 2.0 asked, 0.6 delivered, in raw steps of 0.025.
        val result = sync.reconcileBoluses(listOf(ownRecord(startedAt, 80, delivered = 24)), 1)

        // Written as the arithmetic rather than as 0.6: raw 24 times the dose scale is not exactly that in binary floating point.
        assertThat(result.confirmed[startedAt]!!).isWithin(1e-9).of(0.6)
        verify(pumpSync, times(1)).syncBolusWithPumpId(any(), eq(24 * Atc3Protocol.DOSE_SCALE), eq(BS.Type.NORMAL), any(), any(), any())
    }

    @Test
    fun `an expectation a day old goes by itself`() = runTest {
        afterFirstPass()
        sync.expect(now - 25 * 3_600_000L, 2.0, BS.Type.NORMAL)

        sync.reconcileBoluses(emptyList(), 0)

        assertThat(sync.hasExpectedBolus()).isFalse()
    }

    @Test
    fun `a record of a later minute closes the expectation of a bolus that never came`() = runTest {
        afterFirstPass()
        sync.expect(now - 5 * 60_000L, 2.0, BS.Type.NORMAL)

        sync.reconcileBoluses(listOf(record(60, 40)), 1)

        assertThat(sync.hasExpectedBolus()).isFalse()
    }

    @Test
    fun `a row the user deleted is not written again`() = runTest {
        afterFirstPass()
        val record = ownRecord(now - 30_000L, 80)
        rows.add(BS(timestamp = minuteOf(record.timestamp), amount = 2.0, type = BS.Type.NORMAL, isValid = false, ids = IDs(pumpId = 1L, pumpSerial = serial)))

        sync.reconcileBoluses(listOf(record), 1)

        verify(pumpSync, never()).syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())
    }

    @Test
    fun `a row of another pump, or one entered by hand, does not stand for the record`() = runTest {
        afterFirstPass()
        val record = ownRecord(now - 30_000L, 80)
        rows.add(BS(timestamp = minuteOf(record.timestamp), amount = 2.0, type = BS.Type.NORMAL, ids = IDs(pumpId = 1L, pumpSerial = "other")))
        rows.add(BS(timestamp = minuteOf(record.timestamp), amount = 2.0, type = BS.Type.NORMAL, ids = IDs(pumpId = minuteOf(record.timestamp))))

        sync.reconcileBoluses(listOf(record), 1)

        verify(pumpSync, times(1)).syncBolusWithPumpId(any(), eq(2.0), anyOrNull(), any(), any(), any())
    }

    @Test
    fun `an extended bolus becomes a note and a warning, not a row, and only once`() = runTest {
        afterFirstPass()
        whenever(pumpSync.insertTherapyEventIfNewWithTimestamp(any(), any(), anyOrNull(), anyOrNull(), any(), any())).thenReturn(true, false)
        val record = Atc3BolusRecord(0, now - 60_000L, (now - 60_000L) / 1000L, 40, 40, 80, 80)

        sync.reconcileBoluses(listOf(record), 1)
        sync.reconcileBoluses(listOf(record), 1)

        verify(pumpSync, never()).syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())
        verify(pumpSync, times(2)).insertTherapyEventIfNewWithTimestamp(
            eq(minuteOf(record.timestamp)), eq(TE.Type.NOTE), anyOrNull(), eq(minuteOf(record.timestamp)), eq(PumpType.ATC3), eq(serial)
        )
        verify(uiInteraction, times(1)).addNotification(eq(Notification.PUMP_ERROR), anyOrNull(), eq(Notification.URGENT))
        // Not learned either: the count stays above the journal until the user enters it or the cycle starts over.
        assertThat(sync.bolusesLearnedAfter(0L)).isWithin(1e-9).of(0.0)
    }

    // The boluses the delivery check counts: learned when the record is read

    @Test
    fun `a bolus counts for the check from the moment its record is read, and began when the pump accepted it`() = runTest {
        // The check anchored at 09:53:58, our bolus started at 09:54:02 and its record sat in the
        // pump's minute 09:53, dating the row before the anchor. For the check it began at the
        // acceptance, after the anchor, whatever the row says.
        afterFirstPass()
        val anchoredAt = now - 40_000L
        val startedAt = now - 30_000L
        sync.expect(startedAt, 0.5, BS.Type.SMB)
        assertThat(sync.bolusesLearnedAfter(anchoredAt)).isWithin(1e-9).of(0.0)

        sync.reconcileBoluses(listOf(ownRecord(startedAt, 20, shiftMinutes = -1)), 1)
        assertThat(sync.bolusesLearnedAfter(anchoredAt)).isWithin(1e-9).of(0.5)
        assertThat(sync.bolusStraddles(anchoredAt, anchoredAt)).isFalse()

        // Kept while a window it lies in may still be closed by the pump's count, and gone once it
        // is older than any window may wait.
        sync.pruneLearned()
        assertThat(sync.bolusesLearnedAfter(anchoredAt)).isWithin(1e-9).of(0.5)
        whenever(dateUtil.now()).thenReturn(now + 4 * 60 * 60_000L)
        sync.pruneLearned()
        assertThat(sync.bolusesLearnedAfter(anchoredAt)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `a bolus that began before the anchor and was learned after it spoils the anchor`() = runTest {
        // AAPS restarted while our bolus ran and anchored with part of it already in the pump's
        // count; the record came after the anchor.
        afterFirstPass()
        val startedAt = now - 30_000L
        val anchoredAt = now - 20_000L
        sync.expect(startedAt, 2.0, BS.Type.NORMAL)
        sync.reconcileBoluses(listOf(ownRecord(startedAt, 80)), 1)

        assertThat(sync.bolusStraddles(anchoredAt, anchoredAt)).isTrue()
        // An anchor read before the bolus began is a good one.
        assertThat(sync.bolusStraddles(startedAt - 5_000L, startedAt - 5_000L)).isFalse()
    }

    @Test
    fun `a stranger's bolus and ours are each learned once, when their records are read`() = runTest {
        afterFirstPass()
        sync.expect(now - 30_000L, 2.0, BS.Type.SMB)

        sync.reconcileBoluses(listOf(record(300, 40), ownRecord(now - 30_000L, 80)), 2)

        assertThat(sync.bolusesLearnedAfter(0L)).isWithin(1e-9).of(3.0)
        // Read again, the rows are there and nothing is learned twice.
        sync.reconcileBoluses(listOf(record(300, 40), ownRecord(now - 30_000L, 80)), 2)
        assertThat(sync.bolusesLearnedAfter(0L)).isWithin(1e-9).of(3.0)
    }

    // What the history says about the pump's clock

    @Test
    fun `two reads that find our boluses a minute early ask for the clock to be written`() = runTest {
        afterFirstPass()
        val first = now - 8 * 60_000L
        val second = now - 4 * 60_000L

        sync.expect(first, 1.0, BS.Type.SMB)
        sync.reconcileBoluses(listOf(ownRecord(first, 40, shiftMinutes = -1)), 1)
        assertThat(clockWatch.needsSetting(now)).isFalse()

        sync.expect(second, 1.0, BS.Type.SMB)
        sync.reconcileBoluses(listOf(ownRecord(second, 40, shiftMinutes = -1)), 1)
        assertThat(clockWatch.needsSetting(now)).isTrue()
        // Recognised both times, as ours.
        verify(pumpSync, times(2)).syncBolusWithPumpId(any(), eq(1.0), eq(BS.Type.SMB), any(), any(), any())
    }

    @Test
    fun `a read that finds our bolus in its own minute clears the count`() = runTest {
        afterFirstPass()
        val first = now - 8 * 60_000L
        val second = now - 4 * 60_000L

        sync.expect(first, 1.0, BS.Type.SMB)
        sync.reconcileBoluses(listOf(ownRecord(first, 40, shiftMinutes = -1)), 1)
        sync.expect(second, 1.0, BS.Type.SMB)
        sync.reconcileBoluses(listOf(ownRecord(second, 40)), 1)

        assertThat(clockWatch.needsSetting(now)).isFalse()
    }

    @Test
    fun `what the ledger learned is written down`() = runTest {
        afterFirstPass()
        verify(preferences, atLeastOnce()).put(eq(Atc3StringNonKey.State), any<String>())
    }

    // The pump's delivery state, which reaches AAPS as a temporary basal

    @Test
    fun `a temporary basal the pump is running is recorded`() = runTest {
        sync.onStatus(suspended = false, tbrRunning = true, rate = 1.5, durationMs = null, pumpTbrStart = null)

        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            any(), eq(1.5), any(), eq(true), eq(PumpSync.TemporaryBasalType.NORMAL), any(), any(), any()
        )
    }

    @Test
    fun `a stopped pump is recorded as a temporary basal of zero`() = runTest {
        sync.onStatus(suspended = true, tbrRunning = false, rate = 0.0, durationMs = null, pumpTbrStart = null)

        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            any(), eq(0.0), any(), eq(true), eq(PumpSync.TemporaryBasalType.PUMP_SUSPEND), any(), any(), any()
        )
    }

    @Test
    fun `the record is closed when the pump goes back to its schedule`() = runTest {
        sync.onStatus(suspended = true, tbrRunning = false, rate = 0.0, durationMs = null, pumpTbrStart = null)

        sync.onStatus(suspended = false, tbrRunning = false, rate = 0.0, durationMs = null, pumpTbrStart = null)

        verify(pumpSync, times(1)).syncStopTemporaryBasalWithPumpId(any(), any(), any(), any(), any())
    }

    @Test
    fun `a start clock the pump left empty does not become the timestamp AAPS is given`() = runTest {
        // The pump can answer object 0x0A with six zero clock bytes for a temporary basal started on
        // its keypad. Decoded that is 1999-11-30, and AAPS would refuse the whole record as older
        // than the moment it adopted the pump, so the temporary basal would never reach the insulin
        // on board.
        sync.onStatus(suspended = false, tbrRunning = true, rate = 0.45, durationMs = null, pumpTbrStart = emptyStart)

        val timestamp = argumentCaptor<Long>()
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            timestamp.capture(), eq(0.45), any(), eq(true), any(), any(), any(), any()
        )
        assertThat(timestamp.firstValue).isEqualTo(now)
    }

    @Test
    fun `a duration the pump did give survives a start clock it did not`() = runTest {
        // Only the start is disbelieved. The duration came through the same answer, and without it
        // the record is pushed out on every poll past the end the pump will actually stop at.
        sync.onStatus(suspended = false, tbrRunning = true, rate = 0.45, durationMs = null, pumpTbrStart = emptyStart)

        val duration = argumentCaptor<Long>()
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            any(), eq(0.45), duration.capture(), eq(true), any(), any(), any(), any()
        )
        assertThat(duration.firstValue).isEqualTo(45 * 60_000L)
    }

    @Test
    fun `a start clock the pump did fill in is used as it stands`() = runTest {
        val startedAt = now - 10 * 60_000L
        val real = Atc3TbrStatus(
            startTimestamp = startedAt,
            startUtcSeconds = startedAt / 1000L,
            rate = 0.45,
            percent = null,
            durationMinutes = 45,
            deliveredUnits = 0.0
        )

        sync.onStatus(suspended = false, tbrRunning = true, rate = 0.45, durationMs = null, pumpTbrStart = real)

        val timestamp = argumentCaptor<Long>()
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            timestamp.capture(), eq(0.45), any(), eq(true), any(), any(), any(), any()
        )
        assertThat(timestamp.firstValue).isEqualTo(startedAt)
    }

    // Object 0x0B: believed for the rate AAPS has open, whoever set it

    private suspend fun ourTwoUnitsAt(startedAt: Long) = sync.tbrStartedByAaps(
        ackAtMs = startedAt + 20_000L, rate = 2.0, durationMinutes = 30,
        pumpStart = Atc3TbrStatus(startedAt, startedAt / 1000L, 2.0, null, 30, 0.0)
    )

    private fun finished(start: Long, end: Long, rate: Double) =
        Atc3FinishedTbr(3, start, start / 1000L, end, end / 1000L, rate, null, 30, 0.125)

    @Test
    fun `the end of the same rate set later over ours is where the rate AAPS has open stopped`() = runTest {
        // The loop's 2.0 U/h of 18:04 replaced unseen by a stranger's 2.0 U/h at 18:05, cancelled
        // at 18:09.
        val ours = now - 10 * 60_000L
        ourTwoUnitsAt(ours)
        val end = ours + 5 * 60_000L
        assertThat(sync.realEndOf(finished(ours + 60_000L, end, 2.0))).isEqualTo(end)
    }

    @Test
    fun `the end of another rate is not the end of the one open`() = runTest {
        val ours = now - 10 * 60_000L
        ourTwoUnitsAt(ours)
        assertThat(sync.realEndOf(finished(ours + 60_000L, ours + 5 * 60_000L, 1.5))).isNull()
    }

    @Test
    fun `an end record older than the one open is a leftover and not believed`() = runTest {
        // 0x0B is not updated when one temporary basal replaces another, and can be hours old.
        val ours = now - 10 * 60_000L
        ourTwoUnitsAt(ours)
        assertThat(sync.realEndOf(finished(ours - 6 * 3_600_000L, ours - 5 * 3_600_000L, 2.0))).isNull()
    }

    @Test
    fun `our temporary basal in the same minute as the one before closes that one where this one was acknowledged`() = runTest {
        // AAPS cuts the running record only at a strictly later start, so the driver's 0.0 U/h and
        // the loop's 2.0 U/h of one minute would both be left open, one on top of the other. Each
        // row begins at its own acknowledgement, the pump's stamp being the minute for both.
        val minute = now - now % 60_000L - 60_000L
        sync.tbrStartedByAaps(minute + 10_000L, 0.0, 60, Atc3TbrStatus(minute, minute / 1000L, 0.0, null, 60, 0.0))
        sync.tbrStartedByAaps(minute + 20_000L, 2.0, 30, Atc3TbrStatus(minute, minute / 1000L, 2.0, null, 30, 0.0))

        val firstEnd = Atc3PumpId.tbrEndOf(Atc3PumpId.of(minute + 10_000L, Atc3PumpId.KIND_TBR_START))
        verify(pumpSync, times(1)).syncStopTemporaryBasalWithPumpId(eq(minute + 20_000L), eq(firstEnd), any(), any(), any())
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            eq(minute + 20_000L), eq(2.0), any(), eq(true), any(), any(), any(), any()
        )
    }

    @Test
    fun `a stop is recorded from the moment the pump gives and closed at the moment it resumed`() = runTest {
        val stoppedAt = now - 5 * 60_000L
        sync.onStatus(suspended = true, tbrRunning = false, rate = 0.0, durationMs = null, pumpTbrStart = null, pausedAtMs = stoppedAt)
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            eq(stoppedAt), eq(0.0), any(), eq(true), eq(PumpSync.TemporaryBasalType.PUMP_SUSPEND), any(), any(), any()
        )

        val resumedAt = now - 2 * 60_000L
        sync.onStatus(suspended = false, tbrRunning = false, rate = 0.0, durationMs = null, pumpTbrStart = null, resumedAtMs = resumedAt)
        verify(pumpSync, times(1)).syncStopTemporaryBasalWithPumpId(eq(resumedAt), any(), any(), any(), any())
    }

    // A row of ours closes where the next command begins, as ordered

    @Test
    fun `a temporary basal of ours replaced by the loop closes where the next begins and stays as ordered`() = runTest {
        afterFirstPass()
        val pumpStart = now - 10 * 60_000L
        val started = Atc3TbrStatus(
            startTimestamp = pumpStart, startUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(pumpStart),
            rate = 3.0, percent = null, durationMinutes = 30, deliveredUnits = 0.0
        )
        sync.tbrStartedByAaps(ackAtMs = pumpStart + 2_000L, rate = 3.0, durationMinutes = 30, pumpStart = started)
        val nextStart = pumpStart + 4 * 60_000L + 40_000L
        val next = Atc3TbrStatus(
            startTimestamp = nextStart, startUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(nextStart),
            rate = 1.0, percent = null, durationMinutes = 30, deliveredUnits = 0.0
        )
        sync.tbrStartedByAaps(ackAtMs = nextStart + 2_000L, rate = 1.0, durationMinutes = 30, pumpStart = next)

        verify(pumpSync, times(1)).syncStopTemporaryBasalWithPumpId(eq(nextStart + 2_000L), any(), any(), any(), any())
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(eq(pumpStart + 2_000L), eq(3.0), eq(30 * 60_000L), eq(true), eq(PumpSync.TemporaryBasalType.NORMAL), any(), any(), any())
    }

    @Test
    fun `the user's disconnect keeps its type in the row`() = runTest {
        // AAPS's "disconnect pump" is a zero temporary basal typed EMULATED_PUMP_SUSPEND, and the
        // type is what AAPS shows the disconnection by.
        afterFirstPass()
        sync.tbrStartedByAaps(ackAtMs = now, rate = 0.0, durationMinutes = 15, type = PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND)
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            eq(now), eq(0.0), eq(15 * 60_000L), eq(true), eq(PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND), any(), any(), any()
        )
    }

    @Test
    fun `a temporary basal of ours cancelled closes at the acknowledgement and stays as ordered`() = runTest {
        afterFirstPass()
        val pumpStart = now - 10 * 60_000L
        val started = Atc3TbrStatus(
            startTimestamp = pumpStart, startUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(pumpStart),
            rate = 3.0, percent = null, durationMinutes = 30, deliveredUnits = 0.0
        )
        sync.tbrStartedByAaps(ackAtMs = pumpStart + 2_000L, rate = 3.0, durationMinutes = 30, pumpStart = started)
        sync.tbrStopped(pumpStart + 170_000L)

        verify(pumpSync, times(1)).syncStopTemporaryBasalWithPumpId(eq(pumpStart + 170_000L), any(), any(), any(), any())
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(eq(pumpStart + 2_000L), eq(3.0), eq(30 * 60_000L), eq(true), eq(PumpSync.TemporaryBasalType.NORMAL), any(), any(), any())
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())
    }

    private fun refill(atMs: Long, units: Double) = Atc3RefillRecord(
        index = 0, timestamp = atMs, utcSeconds = Atc3StatusV1.wallClockUtcSeconds(atMs), amountUnits = units, type = 1
    )

    @Test
    fun `the first read of the refills imports nothing, the next one records a new refill as an insulin change`() = runTest {
        afterFirstPass()
        whenever(preferences.get(Atc3LongNonKey.LastRefillSeconds)).thenReturn(0L)
        whenever(pumpSync.insertTherapyEventIfNewWithTimestamp(any(), any(), anyOrNull(), anyOrNull(), any(), any())).thenReturn(true)
        val old = refill(now - 3 * 24 * 3_600_000L, 9.2)

        events.recordRefills(listOf(old))
        verify(pumpSync, never()).insertTherapyEventIfNewWithTimestamp(any(), any(), anyOrNull(), anyOrNull(), any(), any())
        verify(preferences).put(Atc3LongNonKey.LastRefillSeconds, old.utcSeconds)

        whenever(preferences.get(Atc3LongNonKey.LastRefillSeconds)).thenReturn(old.utcSeconds)
        val fresh = refill(now - 60_000L, 27.025)
        events.recordRefills(listOf(fresh, old))
        verify(pumpSync, times(1)).insertTherapyEventIfNewWithTimestamp(eq(fresh.timestamp), eq(TE.Type.INSULIN_CHANGE), anyOrNull(), anyOrNull(), any(), any())
    }
}
