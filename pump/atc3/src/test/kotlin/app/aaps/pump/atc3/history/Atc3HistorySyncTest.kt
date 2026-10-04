package app.aaps.pump.atc3.history

import app.aaps.pump.atc3.comm.Atc3FinishedTbr
import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.LongNonKey
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.Atc3Pump
import app.aaps.pump.atc3.comm.Atc3BolusRecord
import app.aaps.core.data.model.TE
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.pump.atc3.keys.Atc3LongNonKey
import app.aaps.pump.atc3.comm.Atc3RefillRecord
import app.aaps.pump.atc3.comm.Atc3ResponseFrame
import app.aaps.pump.atc3.comm.Atc3StatusV1
import app.aaps.pump.atc3.comm.Atc3TbrRecord
import app.aaps.pump.atc3.comm.Atc3TbrStatus
import app.aaps.pump.atc3.keys.Atc3StringNonKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import app.aaps.pump.atc3.trace.Atc3Trace
import kotlinx.coroutines.test.runTest
import java.util.TimeZone

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

    private lateinit var atc3Pump: Atc3Pump
    private lateinit var clockWatch: Atc3ClockWatch
    private lateinit var sync: Atc3HistorySync

    private val serial = "12345678"

    @BeforeEach
    fun setup() {
        atc3Pump = Atc3Pump()
        atc3Pump.serialNumber = serial
        whenever(rh.gs(anyInt())).thenReturn("mocked resource")
        whenever(preferences.get(Atc3StringNonKey.HistoryLedger)).thenReturn("")
        whenever(preferences.get(LongNonKey.ActivePumpChangeTimestamp)).thenReturn(0L)
        // These say the uninteresting thing — the write went in — so each case can stub over it
        // when the answer is what it is about.
        whenever(pumpSync.syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())).thenReturn(true)
        whenever(pumpSync.syncStopTemporaryBasalWithPumpId(any(), any(), any(), any(), any())).thenReturn(true)
        whenever(pumpSync.syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())).thenReturn(true)
        whenever(pumpSync.createOrUpdateTotalDailyDose(any(), any(), any(), any(), anyOrNull(), any(), any())).thenReturn(true)
        clockWatch = Atc3ClockWatch()
        sync = Atc3HistorySync(
            aapsLogger, rh, uiInteraction, preferences, pumpSync, dateUtil, atc3Pump,
            clockWatch, Atc3Trace(aapsLogger, preferences)
        )
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

    private suspend fun rowsWritable() {
        whenever(pumpSync.addBolusWithTempId(any(), any(), any(), any(), any(), any())).thenReturn(true)
        whenever(pumpSync.syncBolusWithTempId(any(), any(), any(), anyOrNull(), anyOrNull(), any(), any()))
            .thenReturn(true)
    }

    /** @param delivered defaults to [raw]; give it separately to make a corrected record. */
    private fun record(secondsAgo: Long, raw: Int, delivered: Int = raw) = Atc3BolusRecord(
        index = 0,
        timestamp = now - secondsAgo * 1000L,
        pumpClockUtcSeconds = (now - secondsAgo * 1000L) / 1000L,
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

    /** Take the first pass, which files everything the pump already held without importing it. */
    private suspend fun afterFirstPass() {
        whenever(pumpSync.verifyPumpIdentification(any(), any())).thenReturn(true)
        sync.reconcileBoluses(listOf(record(3600, 40)), 1)
    }

    @Test
    fun `an unadopted pump has nothing written and nothing filed away`() = runTest {
        // AAPS is holding somebody else's pump, so every write would be refused. Filing the records
        // as counted here would lose them for good: nothing offers a record twice.
        whenever(pumpSync.verifyPumpIdentification(any(), any())).thenReturn(false)

        val result = sync.reconcileBoluses(listOf(record(60, 40)), 1)

        assertThat(result.confirmedUnits).isNull()
        assertThat(result.newestImportedAtMs).isNull()
        verify(pumpSync, never()).syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())
        verify(preferences, never()).put(eq(Atc3StringNonKey.HistoryLedger), any<String>())
    }

    @Test
    fun `an unadopted pump does not count as a successful read`() = runTest {
        whenever(pumpSync.verifyPumpIdentification(any(), any())).thenReturn(false)

        sync.reconcileBoluses(listOf(record(60, 40)), 1)

        // Nothing was learned, so the barrier must still consider the knowledge stale.
        assertThat(sync.historyFresh(now)).isFalse()
    }

    @Test
    fun `a bolus given on the pump reaches AAPS once the pump is adopted`() = runTest {
        afterFirstPass()

        val result = sync.reconcileBoluses(listOf(record(3600, 40), record(60, 80)), 2)

        verify(pumpSync, times(1)).syncBolusWithPumpId(
            any(), eq(2.0), eq(BS.Type.NORMAL), any(), eq(PumpType.ATC3), eq(serial)
        )
        assertThat(result.newestImportedAtMs).isNotNull()
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

    // Each thing the reconciler can decide, and the call it has to turn into

    @Test
    fun `the bolus AAPS started becomes the pump's own record under its temporary id`() = runTest {
        afterFirstPass()
        whenever(pumpSync.addBolusWithTempId(any(), any(), any(), any(), any(), any())).thenReturn(true)
        whenever(pumpSync.syncBolusWithTempId(any(), any(), any(), anyOrNull(), anyOrNull(), any(), any()))
            .thenReturn(true)
        val temporaryId = sync.registerPending(now - 30_000L, 2.0, BS.Type.SMB)

        val record = ownRecord(now - 30_000L, 80)
        val result = sync.reconcileBoluses(listOf(record), 1)

        assertThat(result.confirmedUnits).isEqualTo(2.0)
        // Dated by the pump's record, the journal moment of this bolus.
        verify(pumpSync, times(1)).syncBolusWithTempId(
            eq(record.timestamp), eq(2.0), eq(temporaryId), eq(BS.Type.SMB), anyOrNull(), any(), any()
        )
        // It is ours, so it must not also arrive as somebody else's bolus.
        verify(pumpSync, never()).syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())
    }

    @Test
    fun `a bolus whose row the user deleted still reaches AAPS, under the pump's id`() = runTest {
        afterFirstPass()
        whenever(pumpSync.addBolusWithTempId(any(), any(), any(), any(), any(), any())).thenReturn(true)
        // The row is gone, so updating it finds nothing.
        whenever(pumpSync.syncBolusWithTempId(any(), any(), any(), anyOrNull(), anyOrNull(), any(), any()))
            .thenReturn(false)
        sync.registerPending(now - 30_000L, 2.0, BS.Type.SMB)

        sync.reconcileBoluses(listOf(ownRecord(now - 30_000L, 80)), 1)

        verify(pumpSync, times(1)).syncBolusWithPumpId(
            any(), eq(2.0), eq(BS.Type.SMB), any(), any(), any()
        )
    }

    // A bolus the pump closed with its completion frame

    @Test
    fun `a completed bolus is closed at the frame's amount, at its start, under the id its record will have`() = runTest {
        afterFirstPass()
        rowsWritable()
        val startedAt = now - 30_000L
        val temporaryId = sync.registerPending(startedAt, 2.0, BS.Type.SMB)

        assertThat(sync.settleCompleted(temporaryId, 2.0)).isTrue()

        val expectedId = Atc3HistoryLedger().assignPumpId(ownRecord(startedAt, 80))
        verify(pumpSync, times(1)).syncBolusWithTempId(
            eq(startedAt), eq(2.0), eq(temporaryId), eq(BS.Type.SMB), eq(expectedId), any(), any()
        )
        assertThat(atc3Pump.lastBolusTime).isEqualTo(startedAt)
    }

    @Test
    fun `the record of a completed bolus dates the row once, and a later read changes nothing`() = runTest {
        afterFirstPass()
        rowsWritable()
        val startedAt = now - 30_000L
        val temporaryId = sync.registerPending(startedAt, 2.0, BS.Type.SMB)
        sync.settleCompleted(temporaryId, 2.0)
        val record = ownRecord(startedAt, 80)

        sync.reconcileBoluses(listOf(record), 1)
        sync.reconcileBoluses(listOf(record), 1)

        // The write that closed it, then the one that dated it by the pump's stamp, under our id
        // and without a type; the second read recognises the record and writes nothing.
        verify(pumpSync, times(1)).syncBolusWithTempId(any(), any(), any(), anyOrNull(), anyOrNull(), any(), any())
        verify(pumpSync, times(1)).syncBolusWithPumpId(eq(record.timestamp), eq(2.0), isNull(), any(), any(), any())
        verify(pumpSync, times(1)).syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())
    }

    // Whether the pump has written our bolus down, asked right after its completion frame

    @Test
    fun `the record of a just completed bolus is recognised by its dose in the start minute or either side`() = runTest {
        val startedAt = now - 30_000L
        assertThat(sync.holdsRecordOf(listOf(ownRecord(startedAt, 80)), startedAt, 2.0)).isTrue()
        assertThat(sync.holdsRecordOf(listOf(ownRecord(startedAt, 80, shiftMinutes = -1)), startedAt, 2.0)).isTrue()
        assertThat(sync.holdsRecordOf(listOf(ownRecord(startedAt, 80, delivered = 60)), startedAt, 2.0)).isTrue()
        // Another dose, or two minutes away, is not it.
        assertThat(sync.holdsRecordOf(listOf(ownRecord(startedAt, 40)), startedAt, 2.0)).isFalse()
        assertThat(sync.holdsRecordOf(listOf(ownRecord(startedAt, 80, shiftMinutes = -2)), startedAt, 2.0)).isFalse()
        assertThat(sync.holdsRecordOf(emptyList(), startedAt, 2.0)).isFalse()
    }

    // The boluses the delivery check counts: learned, not dated

    @Test
    fun `a bolus counts for the check from the moment its amount is learned, wherever its row is dated`() = runTest {
        // 2026-09-28 09:54: the check anchored at 09:53:58, our 0.5 U started at 09:54:02 and
        // was closed on its completion frame; its record then sat in the pump's minute 09:53 and
        // moved the row there, before the anchor -- and out of every time window. The moment the
        // driver learned the amount is after the anchor whatever the row says.
        afterFirstPass()
        rowsWritable()
        val anchoredAt = now - 40_000L
        val startedAt = now - 30_000L
        val temporaryId = sync.registerPending(startedAt, 0.5, BS.Type.SMB)
        assertThat(sync.bolusesLearnedAfter(anchoredAt)).isWithin(1e-9).of(0.0)

        whenever(dateUtil.now()).thenReturn(now - 10_000L)
        sync.settleCompleted(temporaryId, 0.5)
        assertThat(sync.bolusesLearnedAfter(anchoredAt)).isWithin(1e-9).of(0.5)

        // The record a minute early moves the row; the amount is the same, nothing new is learned.
        whenever(dateUtil.now()).thenReturn(now)
        sync.reconcileBoluses(listOf(ownRecord(startedAt, 20, shiftMinutes = -1)), 1)
        assertThat(sync.bolusesLearnedAfter(anchoredAt)).isWithin(1e-9).of(0.5)

        // Anchored again after the check that counted it: it is not the new interval's. It is kept
        // all the same while a half hour it lies in may still be closed by the pump's count, which
        // counts its boluses from a mark of its own, and goes once it is older than that.
        sync.forgetBolusesLearnedUpTo(now)
        assertThat(sync.bolusesLearnedAfter(now)).isWithin(1e-9).of(0.0)
        assertThat(sync.bolusesLearnedAfter(anchoredAt)).isWithin(1e-9).of(0.5)
        whenever(dateUtil.now()).thenReturn(now + 4 * 60 * 60_000L)
        sync.forgetBolusesLearnedUpTo(now)
        assertThat(sync.bolusesLearnedAfter(anchoredAt)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `a bolus that began before the anchor and was learned after it spoils the anchor`() = runTest {
        // 2026-10-03 03:52: AAPS was killed while our 2.0 U ran and anchored again on restart, with
        // part of the bolus already in the pump's count; the bolus was learned after the anchor.
        afterFirstPass()
        rowsWritable()
        val startedAt = now - 30_000L
        val anchoredAt = now - 20_000L
        val temporaryId = sync.registerPending(startedAt, 2.0, BS.Type.NORMAL)
        whenever(dateUtil.now()).thenReturn(now - 10_000L)
        sync.settleCompleted(temporaryId, 2.0)
        assertThat(sync.bolusStraddles(anchoredAt, anchoredAt)).isTrue()
        // An anchor read before the bolus began is a good one.
        assertThat(sync.bolusStraddles(startedAt - 5_000L, startedAt - 5_000L)).isFalse()
    }

    @Test
    fun `a record that says otherwise than the completion frame changes what was learned by the difference`() = runTest {
        afterFirstPass()
        rowsWritable()
        val startedAt = now - 30_000L
        val temporaryId = sync.registerPending(startedAt, 2.0, BS.Type.SMB)
        sync.settleCompleted(temporaryId, 2.0)

        // The pump kept 1.5 U of it.
        sync.reconcileBoluses(listOf(ownRecord(startedAt, 80, delivered = 60)), 1)

        assertThat(sync.bolusesLearnedAfter(0L)).isWithin(1e-9).of(1.5)
    }

    @Test
    fun `a stranger's bolus is learned when it is imported, and a pending one when its record resolves it`() = runTest {
        afterFirstPass()
        rowsWritable()
        whenever(pumpSync.syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())).thenReturn(true)
        sync.registerPending(now - 30_000L, 2.0, BS.Type.SMB)

        sync.reconcileBoluses(listOf(record(600, 40), ownRecord(now - 30_000L, 80)), 2)

        assertThat(sync.bolusesLearnedAfter(0L)).isWithin(1e-9).of(3.0)
        // Read again, the same records are known and nothing is learned twice.
        sync.reconcileBoluses(listOf(record(600, 40), ownRecord(now - 30_000L, 80)), 2)
        assertThat(sync.bolusesLearnedAfter(0L)).isWithin(1e-9).of(3.0)
    }

    @Test
    fun `a bolus with no row cannot be closed on its completion frame`() = runTest {
        // No row was created, so there is nothing to close and the history has to decide.
        assertThat(sync.settleCompleted(0L, 2.0)).isFalse()
    }

    // What the history says about the pump's clock

    @Test
    fun `two reads that find our boluses a minute early ask for the clock to be written`() = runTest {
        afterFirstPass()
        rowsWritable()
        val first = now - 10 * 60_000L
        val second = now - 5 * 60_000L

        val firstId = sync.registerPending(first, 1.0, BS.Type.SMB)
        sync.settleCompleted(firstId, 1.0)
        sync.reconcileBoluses(listOf(ownRecord(first, 40, shiftMinutes = -1)), 1)
        assertThat(clockWatch.needsSetting(now)).isFalse()

        val secondId = sync.registerPending(second, 1.0, BS.Type.SMB)
        sync.settleCompleted(secondId, 1.0)
        sync.reconcileBoluses(listOf(ownRecord(second, 40, shiftMinutes = -1)), 1)
        assertThat(clockWatch.needsSetting(now)).isTrue()
        // Recognised both times: no row of its own, only our row dated by the pump's record.
        verify(pumpSync, times(1)).syncBolusWithPumpId(eq(ownRecord(first, 40, shiftMinutes = -1).timestamp), any(), isNull(), any(), any(), any())
        verify(pumpSync, times(1)).syncBolusWithPumpId(eq(ownRecord(second, 40, shiftMinutes = -1).timestamp), any(), isNull(), any(), any(), any())
        verify(pumpSync, times(2)).syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())
    }

    @Test
    fun `a read that finds our bolus in its own minute clears the count`() = runTest {
        afterFirstPass()
        rowsWritable()
        val first = now - 10 * 60_000L
        val second = now - 5 * 60_000L

        val firstId = sync.registerPending(first, 1.0, BS.Type.SMB)
        sync.settleCompleted(firstId, 1.0)
        sync.reconcileBoluses(listOf(ownRecord(first, 40, shiftMinutes = -1)), 1)

        val secondId = sync.registerPending(second, 1.0, BS.Type.SMB)
        sync.settleCompleted(secondId, 1.0)
        sync.reconcileBoluses(listOf(ownRecord(second, 40)), 1)

        assertThat(clockWatch.needsSetting(now)).isFalse()
    }

    @Test
    fun `a correction keeps the type of the row it corrects`() = runTest {
        // Passing NORMAL here would quietly turn a microbolus into a meal bolus.
        afterFirstPass()
        sync.reconcileBoluses(listOf(record(60, 80)), 1)

        // The same bolus, whose delivered amount the pump has since written up. What was asked for
        // never changes, which is how the two readings are known to be one record.
        sync.reconcileBoluses(listOf(record(60, 80, delivered = 82)), 1)

        // Written as the arithmetic rather than as 2.05: raw 82 times the dose scale is not exactly
        // that in binary floating point, and the comparison is exact.
        verify(pumpSync, times(1)).syncBolusWithPumpId(
            any(), eq(82 * Atc3Const.DOSE_SCALE), eq(null), any(), any(), any()
        )
    }

    @Test
    fun `a bolus the pump never wrote down is zeroed once a later record proves it`() = runTest {
        afterFirstPass()
        whenever(pumpSync.addBolusWithTempId(any(), any(), any(), any(), any(), any())).thenReturn(true)
        whenever(pumpSync.syncBolusWithTempId(any(), any(), any(), anyOrNull(), anyOrNull(), any(), any()))
            .thenReturn(true)
        val temporaryId = sync.registerPending(now - 40 * 60_000L, 2.0, BS.Type.NORMAL)
        sync.onProgress(temporaryId, 0.5)

        // Reads without the record, however many, only count.
        repeat(20) { sync.reconcileBoluses(emptyList(), 0) }
        verify(pumpSync, never()).syncBolusWithTempId(any(), eq(0.0), any(), anyOrNull(), anyOrNull(), any(), any())

        // A bolus ten minutes after ours is in the history, and ours is not.
        sync.reconcileBoluses(listOf(ownRecord(now - 30 * 60_000L, 40)), 1)

        // Zero, not the half unit the progress frames showed. That figure is for the screen; a
        // bolus closed at it is a guess written into the record as though it were a fact. Left at
        // the amount it was started with it would park insulin in IOB that may never have gone in.
        verify(pumpSync, times(1)).syncBolusWithTempId(
            any(), eq(0.0), eq(temporaryId), anyOrNull(), anyOrNull(), any(), any()
        )
    }

    @Test
    fun `what the ledger learned is written down`() = runTest {
        afterFirstPass()
        verify(preferences, atLeastOnce()).put(eq(Atc3StringNonKey.HistoryLedger), any<String>())
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

    @Test
    fun `the note of a temporary basal of ours is written on the scale the journal uses`() = runTest {
        // The note this writes is the only thing that recognises our own work in the journal, and
        // every journal record is keyed by the pump's wall-clock digits read through a UTC calendar.
        // A note written as a plain epoch second would sit a whole timezone offset away from every
        // record, and the guard could never fire.
        //
        // The timezone is fixed here rather than inherited, because on a build machine running on
        // UTC the two scales coincide and this test would prove nothing.
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        try {
            sync.tbrStartedByAaps(ackAtMs = now, rate = 3.0, durationMinutes = 30)

            val stored = argumentCaptor<String>()
            verify(preferences, atLeastOnce()).put(eq(Atc3StringNonKey.HistoryLedger), stored.capture())
            val ourTbrs = Atc3HistoryLedger.decode(stored.lastValue, serial).ourTbrs
            assertThat(ourTbrs).hasSize(1)
            // The scale a journal record would carry for this same instant, reached the way the
            // records reach it: through the pump's six clock bytes and the decoder.
            val asTheJournalWouldKeyIt = Atc3StatusV1.decodeClockUtcSeconds(
                Atc3ResponseFrame(ByteArray(Atc3ResponseFrame.DATA_BASE_OFFSET) + Atc3StatusV1.encodeClock(now)),
                0
            )
            assertThat(ourTbrs.single().startUtcSeconds).isEqualTo(asTheJournalWouldKeyIt)
            // And that is three hours away from the plain epoch second on a UTC+3 phone.
            assertThat(ourTbrs.single().startUtcSeconds).isNotEqualTo(now / 1000L)
            // The note carries what the temporary basal was, not only when it began: the start
            // alone cannot tell our own record, quantised to the journal's whole minute, from a
            // stranger's set in the same minute. The rate is the pump's own raw steps.
            assertThat(ourTbrs.single().rawRate).isEqualTo(120)
            assertThat(ourTbrs.single().durationMinutes).isEqualTo(30)
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun `a successful read makes the knowledge fresh`() = runTest {
        afterFirstPass()
        assertThat(sync.historyFresh(now)).isTrue()
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
    fun `the loop's last word is what it set, and none once it cancelled`() = runTest {
        val ours = now - 10 * 60_000L
        ourTwoUnitsAt(ours)
        assertThat(sync.loopTbr()?.rawRate).isEqualTo(80)
        assertThat(sync.loopTbr()?.durationMinutes).isEqualTo(30)
        sync.loopCancelledTbr(now)
        assertThat(sync.loopTbr()?.rawRate).isNull()
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

    // The journal shaping the rows AAPS already holds

    @Test
    fun `a temporary basal of ours stays as ordered when the journal is read, and is not taken for a stranger's`() = runTest {
        afterFirstPass()
        val pumpStart = now - 10 * 60_000L
        val started = Atc3TbrStatus(
            startTimestamp = pumpStart, startUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(pumpStart),
            rate = 3.0, percent = null, durationMinutes = 30, deliveredUnits = 0.0
        )
        sync.tbrStartedByAaps(ackAtMs = pumpStart + 2_000L, rate = 3.0, durationMinutes = 30, pumpStart = started)
        val record = Atc3TbrRecord(
            index = 0, startTimestamp = pumpStart, startUtcSeconds = started.startUtcSeconds,
            rate = 3.0, percent = null, durationMinutes = 30, deliveredUnits = 0.2
        )
        sync.tbrStopped(pumpStart + 280_000L) { null }

        sync.reconcileTbrHistory(listOf(record))

        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())
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

    // Closing a row at what its record delivered, the moment it closes

    @Test
    fun `a temporary basal of ours replaced by the loop closes where the next begins and stays as ordered`() = runTest {
        afterFirstPass()
        val pumpStart = now - 10 * 60_000L
        val started = Atc3TbrStatus(
            startTimestamp = pumpStart, startUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(pumpStart),
            rate = 3.0, percent = null, durationMinutes = 30, deliveredUnits = 0.0
        )
        sync.tbrStartedByAaps(ackAtMs = pumpStart + 2_000L, rate = 3.0, durationMinutes = 30, pumpStart = started)
        val record = Atc3TbrRecord(
            index = 0, startTimestamp = pumpStart, startUtcSeconds = started.startUtcSeconds,
            rate = 3.0, percent = null, durationMinutes = 30, deliveredUnits = 0.2
        )
        val nextStart = pumpStart + 4 * 60_000L + 40_000L
        val next = Atc3TbrStatus(
            startTimestamp = nextStart, startUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(nextStart),
            rate = 1.0, percent = null, durationMinutes = 30, deliveredUnits = 0.0
        )
        var reads = 0
        sync.tbrStartedByAaps(ackAtMs = nextStart + 2_000L, rate = 1.0, durationMinutes = 30, pumpStart = next) { reads++; listOf(record) }

        assertThat(reads).isEqualTo(0)
        verify(pumpSync, times(1)).syncStopTemporaryBasalWithPumpId(eq(nextStart + 2_000L), any(), any(), any(), any())
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(eq(pumpStart + 2_000L), eq(3.0), eq(30 * 60_000L), eq(true), eq(PumpSync.TemporaryBasalType.NORMAL), any(), any(), any())
    }

    @Test
    fun `our temporary basal replaced by a stranger's ends by its record when the stamp is earlier`() = runTest {
        // Ours 3.0 U/h acknowledged 09:44:14, a stranger's 0.5 U/h
        // set 09:52:03 and stamped 09:51:00; the record of ours says 0.400 U, which is 480 s of
        // 3.0 U/h. Ours ends at 09:52:14 by the record, theirs begins there.
        afterFirstPass()
        val stamp = now - 20 * 60_000L
        val ack = stamp + 14_000L
        val ours = Atc3TbrStatus(
            startTimestamp = stamp, startUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(stamp),
            rate = 3.0, percent = null, durationMinutes = 30, deliveredUnits = 0.0
        )
        sync.tbrStartedByAaps(ackAtMs = ack, rate = 3.0, durationMinutes = 30, pumpStart = ours)
        val theirStamp = stamp + 7 * 60_000L
        val theirs = Atc3TbrStatus(
            startTimestamp = theirStamp, startUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(theirStamp),
            rate = 0.5, percent = null, durationMinutes = 45, deliveredUnits = 0.0
        )
        val record = Atc3TbrRecord(
            index = 0, startTimestamp = stamp, startUtcSeconds = ours.startUtcSeconds,
            rate = 3.0, percent = null, durationMinutes = 30, deliveredUnits = 0.4
        )
        val tick = theirStamp + 3 * 60_000L
        whenever(dateUtil.now()).thenReturn(tick)
        sync.onStatus(
            suspended = false, tbrRunning = true, rate = 0.5, durationMs = 45 * 60_000L, pumpTbrStart = theirs,
            journal = { listOf(record) }
        )

        val byRecord = ack + 480_000L
        verify(pumpSync, times(1)).syncStopTemporaryBasalWithPumpId(eq(byRecord), any(), any(), any(), any())
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            eq(byRecord), eq(0.5), any(), eq(true), eq(PumpSync.TemporaryBasalType.NORMAL), any(), any(), any()
        )
        assertThat(sync.openTbr()?.startedAtMs).isEqualTo(byRecord)
        // Ours holds its 0.400 U at its own rate: nothing to reshape.
        verify(pumpSync, times(2)).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())
    }

    @Test
    fun `at a low rate the stamp is the nearer end and the record does not move it`() = runTest {
        // 0.1 U/h delivers a pulse every quarter of an hour: one pulse says 15 min, the stamp says
        // 20 min, and the stamp is the later of the two lower bounds.
        afterFirstPass()
        val stamp = now - 30 * 60_000L
        val ack = stamp + 10_000L
        val ours = Atc3TbrStatus(
            startTimestamp = stamp, startUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(stamp),
            rate = 0.1, percent = null, durationMinutes = 60, deliveredUnits = 0.0
        )
        sync.tbrStartedByAaps(ackAtMs = ack, rate = 0.1, durationMinutes = 60, pumpStart = ours)
        val theirStamp = stamp + 20 * 60_000L
        val theirs = Atc3TbrStatus(
            startTimestamp = theirStamp, startUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(theirStamp),
            rate = 2.0, percent = null, durationMinutes = 30, deliveredUnits = 0.0
        )
        val record = Atc3TbrRecord(
            index = 0, startTimestamp = stamp, startUtcSeconds = ours.startUtcSeconds,
            rate = 0.1, percent = null, durationMinutes = 60, deliveredUnits = 0.025
        )
        whenever(dateUtil.now()).thenReturn(theirStamp + 2 * 60_000L)
        sync.onStatus(
            suspended = false, tbrRunning = true, rate = 2.0, durationMs = 30 * 60_000L, pumpTbrStart = theirs,
            journal = { listOf(record) }
        )

        verify(pumpSync, times(1)).syncStopTemporaryBasalWithPumpId(eq(theirStamp), any(), any(), any(), any())
        assertThat(sync.openTbr()?.startedAtMs).isEqualTo(theirStamp)
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
        val record = Atc3TbrRecord(
            index = 0, startTimestamp = pumpStart, startUtcSeconds = started.startUtcSeconds,
            rate = 3.0, percent = null, durationMinutes = 30, deliveredUnits = 0.2
        )
        var reads = 0
        sync.tbrStopped(pumpStart + 170_000L) { reads++; listOf(record) }

        assertThat(reads).isEqualTo(0)
        verify(pumpSync, times(1)).syncStopTemporaryBasalWithPumpId(eq(pumpStart + 170_000L), any(), any(), any(), any())
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(eq(pumpStart + 2_000L), eq(3.0), eq(30 * 60_000L), eq(true), eq(PumpSync.TemporaryBasalType.NORMAL), any(), any(), any())
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())
    }

    @Test
    fun `a zero temporary basal does not ask the journal when it closes`() = runTest {
        afterFirstPass()
        sync.tbrStartedByAaps(ackAtMs = now - 60_000L, rate = 0.0, durationMinutes = 30)
        var reads = 0
        sync.tbrStopped(now) { reads++; emptyList() }
        assertThat(reads).isEqualTo(0)
        verify(pumpSync, times(1)).syncStopTemporaryBasalWithPumpId(eq(now), any(), any(), any(), any())
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

        sync.recordRefills(listOf(old))
        verify(pumpSync, never()).insertTherapyEventIfNewWithTimestamp(any(), any(), anyOrNull(), anyOrNull(), any(), any())
        verify(preferences).put(Atc3LongNonKey.LastRefillSeconds, old.utcSeconds)

        whenever(preferences.get(Atc3LongNonKey.LastRefillSeconds)).thenReturn(old.utcSeconds)
        val fresh = refill(now - 60_000L, 27.025)
        sync.recordRefills(listOf(fresh, old))
        verify(pumpSync, times(1)).insertTherapyEventIfNewWithTimestamp(eq(fresh.timestamp), eq(TE.Type.INSULIN_CHANGE), anyOrNull(), anyOrNull(), any(), any())
    }

    @Test
    fun `a journal that does not answer leaves the close where it was`() = runTest {
        afterFirstPass()
        sync.tbrStartedByAaps(ackAtMs = now - 60_000L, rate = 2.0, durationMinutes = 30)
        sync.tbrStopped(now) { null }
        verify(pumpSync, times(1)).syncStopTemporaryBasalWithPumpId(eq(now), any(), any(), any(), any())
    }

    @Test
    fun `a row closes at its own rate, its start moved by the insulin its minute gives it`() = runTest {
        afterFirstPass()
        val minute = now - 10 * 60_000L
        // The loop's 0.75 U/h acknowledged at :10 of the minute, and a stranger's 3.0 U/h set
        // after it in the same minute, stamped with the minute: the stranger's row begins where the
        // loop's did and the loop's is cut to nothing.
        val loops = Atc3TbrStatus(
            startTimestamp = minute, startUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(minute),
            rate = 0.75, percent = null, durationMinutes = 30, deliveredUnits = 0.0
        )
        sync.tbrStartedByAaps(ackAtMs = minute + 10_000L, rate = 0.75, durationMinutes = 30, pumpStart = loops)
        val stranger = Atc3TbrStatus(
            startTimestamp = minute, startUtcSeconds = loops.startUtcSeconds,
            rate = 3.0, percent = null, durationMinutes = 30, deliveredUnits = 0.0
        )
        val loopsRecord = Atc3TbrRecord(
            index = 0, startTimestamp = minute, startUtcSeconds = loops.startUtcSeconds,
            rate = 0.75, percent = null, durationMinutes = 30, deliveredUnits = 0.05
        )
        sync.onStatus(
            suspended = false, tbrRunning = true, rate = 3.0, durationMs = 30 * 60_000L, pumpTbrStart = stranger,
            journal = { listOf(loopsRecord) }
        )
        // The journal is read on a comparison: the loop's 0.05 U are handed to the stranger's row.
        sync.reconcileTbrHistory(listOf(loopsRecord))

        // Four minutes later the stranger's is cancelled; its record says it delivered 0.05 U.
        val strangerRecord = Atc3TbrRecord(
            index = 0, startTimestamp = minute, startUtcSeconds = loops.startUtcSeconds,
            rate = 3.0, percent = null, durationMinutes = 30, deliveredUnits = 0.05
        )
        sync.tbrStopped(minute + 4 * 60_000L) { listOf(strangerRecord, loopsRecord) }

        // 0.05 own plus 0.05 handed over is 120 s of 3.0 U/h; the row keeps 3.0 U/h and its start
        // moves later, by no more than a minute from where it began.
        val start = minute + 10_000L + 60_000L
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            eq(start), eq(3.0), eq(minute + 4 * 60_000L - start), eq(true), eq(PumpSync.TemporaryBasalType.NORMAL), any(), any(), any()
        )
    }

    @Test
    fun `a stop the ticks missed is recorded for the length the pump's count evidences`() = runTest {
        afterFirstPass()
        sync.recordDerivedStop(now - 5 * 60_000L, 3 * 60_000L)
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            eq(now - 5 * 60_000L), eq(0.0), eq(3 * 60_000L), eq(true), eq(PumpSync.TemporaryBasalType.PUMP_SUSPEND), any(), any(), any()
        )
    }

    // A bolus answered as delivered while the link was down

    @Test
    fun `a bolus answered as delivered whole whose record says less is said to be short when the record comes`() = runTest {
        afterFirstPass()
        rowsWritable()
        val startedAt = now - 5 * 60_000L
        val temporaryId = sync.registerPending(startedAt, 2.0, BS.Type.NORMAL)
        sync.answeredWhole(temporaryId)
        assertThat(sync.pendingBolusUnits()).isWithin(1e-9).of(2.0)

        // The pump's record: 2.0 asked, 0.6 delivered (raw steps of 0.025).
        sync.reconcileBoluses(listOf(ownRecord(startedAt, 80, delivered = 24)), 1)

        verify(uiInteraction, times(1)).addNotification(eq(Notification.PUMP_ERROR), anyOrNull(), eq(Notification.URGENT))
        assertThat(sync.pendingBolusUnits()).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `a bolus answered as delivered whole whose record agrees is not spoken of again`() = runTest {
        afterFirstPass()
        rowsWritable()
        val startedAt = now - 5 * 60_000L
        val temporaryId = sync.registerPending(startedAt, 2.0, BS.Type.NORMAL)
        sync.answeredWhole(temporaryId)

        sync.reconcileBoluses(listOf(ownRecord(startedAt, 80)), 1)

        verify(uiInteraction, never()).addNotification(eq(Notification.PUMP_ERROR), anyOrNull(), any())
    }

    // The basal of a passed half hour by the pump's count

    @Test
    fun `a passed half hour is one row at what the pump counted, its command rows go and the running one goes on`() = runTest {
        afterFirstPass()
        whenever(pumpSync.invalidateTemporaryBasalWithPumpId(any(), any(), any())).thenReturn(true)
        val start = now - 40 * 60_000L
        val end = now - 10 * 60_000L
        // Ours, set five minutes before the watershed, on the pump's stamp twenty seconds before.
        val ack = end - 5 * 60_000L
        val stamp = ack - 20_000L
        sync.tbrStartedByAaps(
            ackAtMs = ack, rate = 2.0, durationMinutes = 30,
            pumpStart = Atc3TbrStatus(stamp, Atc3StatusV1.wallClockUtcSeconds(stamp), 2.0, null, 30, 0.0)
        )
        val runningId = sync.openTbr()!!.pumpId
        val rows = listOf(
            // Begun before the period and reaching into it.
            Atc3HistorySync.JournalRow(101L, start - 2 * 60_000L, 5 * 60_000L, 0.5, isAbsolute = true, stop = false),
            // A command of the period.
            Atc3HistorySync.JournalRow(102L, start + 60_000L, 5 * 60_000L, 1.0, isAbsolute = true, stop = false),
            Atc3HistorySync.JournalRow(runningId, ack, 30 * 60_000L, 2.0, isAbsolute = true, stop = false)
        )

        val closed = sync.writeBasalFact(start, end, units = 0.5, rows = rows, pumpTbrDurationMs = 30 * 60_000L)

        assertThat(closed).isTrue()
        // The command of the period is taken out, and only it.
        verify(pumpSync, times(1)).invalidateTemporaryBasalWithPumpId(eq(102L), any(), any())
        verify(pumpSync, never()).invalidateTemporaryBasalWithPumpId(eq(101L), any(), any())
        verify(pumpSync, never()).invalidateTemporaryBasalWithPumpId(eq(runningId), any(), any())
        // The one begun before is cut where the period begins.
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            eq(start - 2 * 60_000L), eq(0.5), eq(2 * 60_000L), eq(true), isNull(), eq(101L), any(), any()
        )
        // The running one goes on from the watershed to the end the pump keeps for it.
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            eq(end), eq(2.0), eq(stamp + 30 * 60_000L - end), eq(true), isNull(), eq(runningId), any(), any()
        )
        assertThat(sync.openTbr()!!.startedAtMs).isEqualTo(end)
        // And the period is one row: 0.5 U over half an hour.
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            eq(start), eq(1.0), eq(30 * 60_000L), eq(true), eq(PumpSync.TemporaryBasalType.NORMAL),
            eq(Atc3PumpId.of(start, Atc3PumpId.KIND_BASAL_FACT)), any(), any()
        )
    }

    @Test
    fun `a row of the period that runs past its end begins there instead of being taken out`() = runTest {
        afterFirstPass()
        whenever(pumpSync.invalidateTemporaryBasalWithPumpId(any(), any(), any())).thenReturn(true)
        val start = now - 40 * 60_000L
        val end = now - 10 * 60_000L
        // Closed two seconds after the read the period ends at, by a command of the same tick.
        val rows = listOf(Atc3HistorySync.JournalRow(103L, end - 4 * 60_000L, 4 * 60_000L + 2_000L, 1.5, isAbsolute = true, stop = false))

        sync.writeBasalFact(start, end, units = 0.5, rows = rows, pumpTbrDurationMs = null)

        verify(pumpSync, never()).invalidateTemporaryBasalWithPumpId(eq(103L), any(), any())
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(eq(end), eq(1.5), eq(2_000L), eq(true), isNull(), eq(103L), any(), any())
    }

    @Test
    fun `a temporary basal begun before the period keeps its time before it and goes on as a row of its own`() = runTest {
        // The mode switched on while a temporary basal ran, and it is still running at the first
        // watershed. Moved whole to the period's end, the time it ran before the period would be
        // gone from AAPS, and counted there as the scheduled rate.
        afterFirstPass()
        whenever(pumpSync.invalidateTemporaryBasalWithPumpId(any(), any(), any())).thenReturn(true)
        val start = now - 40 * 60_000L
        val end = now - 10 * 60_000L
        val ack = start - 8 * 60_000L
        val stamp = ack - 20_000L
        sync.tbrStartedByAaps(
            ackAtMs = ack, rate = 2.0, durationMinutes = 60,
            pumpStart = Atc3TbrStatus(stamp, Atc3StatusV1.wallClockUtcSeconds(stamp), 2.0, null, 60, 0.0)
        )
        val runningId = sync.openTbr()!!.pumpId
        val rows = listOf(Atc3HistorySync.JournalRow(runningId, ack, 60 * 60_000L, 2.0, isAbsolute = true, stop = false))

        assertThat(sync.writeBasalFact(start, end, units = 0.5, rows = rows, pumpTbrDurationMs = 60 * 60_000L)).isTrue()

        verify(pumpSync, never()).invalidateTemporaryBasalWithPumpId(eq(runningId), any(), any())
        // Its row ends where the period begins.
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            eq(ack), eq(2.0), eq(8 * 60_000L), eq(true), isNull(), eq(runningId), any(), any()
        )
        // And it goes on from the period's end under an id of its own, to the end the pump keeps for it.
        val goesOnId = Atc3PumpId.tbrStartAfter(end, runningId)
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            eq(end), eq(2.0), eq(stamp + 60 * 60_000L - end), eq(true), eq(PumpSync.TemporaryBasalType.NORMAL), eq(goesOnId), any(), any()
        )
        assertThat(sync.openTbr()!!.pumpId).isEqualTo(goesOnId)
        assertThat(sync.openTbr()!!.startedAtMs).isEqualTo(end)
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            eq(start), eq(1.0), eq(30 * 60_000L), eq(true), eq(PumpSync.TemporaryBasalType.NORMAL),
            eq(Atc3PumpId.of(start, Atc3PumpId.KIND_BASAL_FACT)), any(), any()
        )
    }

    @Test
    fun `a period closed again takes a row of its own and the one written before is taken out`() = runTest {
        afterFirstPass()
        whenever(pumpSync.invalidateTemporaryBasalWithPumpId(any(), any(), any())).thenReturn(true)
        val start = now - 40 * 60_000L
        val end = now - 5 * 60_000L

        sync.writeBasalFact(start, end, units = 0.7, rows = emptyList(), pumpTbrDurationMs = null, write = 1)

        verify(pumpSync, times(1)).invalidateTemporaryBasalWithPumpId(eq(Atc3PumpId.of(start, Atc3PumpId.KIND_BASAL_FACT)), any(), any())
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            eq(start), any(), eq(35 * 60_000L), eq(true), eq(PumpSync.TemporaryBasalType.NORMAL),
            eq(Atc3PumpId.of(start, Atc3PumpId.KIND_BASAL_FACT, 1)), any(), any()
        )
    }

    @Test
    fun `a period is not closed while our own running row is not yet known under the pump's start`() = runTest {
        afterFirstPass()
        val start = now - 40 * 60_000L
        val end = now - 10 * 60_000L
        sync.tbrStartedByAaps(ackAtMs = end - 60_000L, rate = 2.0, durationMinutes = 30)

        assertThat(sync.writeBasalFact(start, end, units = 0.5, rows = emptyList(), pumpTbrDurationMs = 30 * 60_000L)).isFalse()

        verify(pumpSync, never()).syncTemporaryBasalWithPumpId(eq(start), any(), any(), any(), anyOrNull(), any(), any(), any())
    }

    @Test
    fun `in the exact mode nothing is written into a closed half hour`() = runTest {
        afterFirstPass()
        val closedBefore = now - 10 * 60_000L
        whenever(preferences.get(Atc3BooleanKey.ExactBasal)).thenReturn(true)
        whenever(preferences.get(Atc3StringNonKey.BasalPeriod))
            .thenReturn(Atc3BasalPeriod.State(Atc3BasalPeriod.Mark(closedBefore, 20.0, closedBefore)).encode())

        // A stop the pump dates a minute before the period closed begins where it closed.
        sync.onStatus(suspended = true, tbrRunning = false, rate = 0.0, durationMs = null, pumpTbrStart = null, pausedAtMs = closedBefore - 60_000L)
        verify(pumpSync, times(1)).syncTemporaryBasalWithPumpId(
            eq(closedBefore), eq(0.0), any(), eq(true), eq(PumpSync.TemporaryBasalType.PUMP_SUSPEND), any(), any(), any()
        )
        verify(pumpSync, never()).syncTemporaryBasalWithPumpId(
            eq(closedBefore - 60_000L), any(), any(), any(), anyOrNull(), any(), any(), any()
        )

        // And its end, dated inside the closed period too, is kept past it: AAPS ends whatever
        // row runs at the moment it is given, which there is the period's own.
        sync.onStatus(suspended = false, tbrRunning = false, rate = 0.0, durationMs = null, pumpTbrStart = null, resumedAtMs = closedBefore - 30_000L)
        verify(pumpSync, times(1)).syncStopTemporaryBasalWithPumpId(eq(closedBefore + 1_000L), any(), any(), any(), any())

        // A stop that lies wholly inside is in the pump's count already.
        sync.recordDerivedStop(closedBefore - 5 * 60_000L, 3 * 60_000L)
        verify(pumpSync, never()).syncTemporaryBasalWithPumpId(
            any(), eq(0.0), eq(3 * 60_000L), any(), anyOrNull(), any(), any(), any()
        )
    }
}
