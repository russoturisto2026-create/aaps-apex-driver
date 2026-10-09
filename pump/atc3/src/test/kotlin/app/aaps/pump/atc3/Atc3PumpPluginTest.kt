package app.aaps.pump.atc3

import app.aaps.core.data.model.BS
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.pump.atc3.basal.Atc3BasalPeriodKeeper
import app.aaps.pump.atc3.basal.Atc3BasalSpread
import app.aaps.pump.atc3.check.Atc3AapsJournal
import app.aaps.pump.atc3.check.Atc3JournalArithmetic
import app.aaps.pump.atc3.check.Atc3Reconciliation
import app.aaps.pump.atc3.clock.Atc3ClockKeeper
import app.aaps.pump.atc3.clock.Atc3ClockWatch
import app.aaps.pump.atc3.clock.Atc3DayClock
import app.aaps.pump.atc3.command.Atc3BolusDelivery
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.command.Atc3BolusOutcome
import app.aaps.pump.atc3.command.Atc3Failure
import app.aaps.pump.atc3.command.Atc3ProbeLink
import app.aaps.pump.atc3.command.Atc3SetSuspended
import app.aaps.pump.atc3.command.Atc3TbrResult
import app.aaps.pump.atc3.history.Atc3HistoryEvents
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.history.Atc3JournalFault
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.link.Atc3Connection
import app.aaps.pump.atc3.link.Atc3LinkKeeper
import app.aaps.pump.atc3.link.Atc3LinkProtection
import app.aaps.pump.atc3.link.Atc3LinkWatch
import app.aaps.pump.atc3.manager.Atc3Manager
import app.aaps.pump.atc3.protocol.Atc3Alarm
import app.aaps.pump.atc3.protocol.Atc3BolusHistory
import app.aaps.pump.atc3.protocol.Atc3DailyStats
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3Settings
import app.aaps.pump.atc3.protocol.Atc3StatusV2
import app.aaps.pump.atc3.protocol.Atc3Version
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.state.currentCard
import app.aaps.pump.atc3.state.editStatus
import app.aaps.pump.atc3.store.Atc3Store
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.after
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Calendar

/**
 * The barrier that keeps AAPS from acting on a stale idea of what the pump has delivered.
 *
 * The cases here are the ones that matter for safety rather than for the protocol: that a command
 * which changes delivery reads the pump's boluses first, and that a microbolus decided without
 * knowing about a bolus given on the pump is refused rather than stacked on top of it.
 */
class Atc3PumpPluginTest : TestBaseWithProfile() {

    @Mock lateinit var atc3Manager: Atc3Manager
    @Mock lateinit var pumpSync: PumpSync
    @Mock lateinit var bolusDelivery: Atc3BolusDelivery
    @Mock lateinit var atc3Connection: Atc3Connection
    @Mock lateinit var atc3HistorySync: Atc3HistorySync
    @Mock lateinit var historyEvents: Atc3HistoryEvents
    @Mock lateinit var aapsJournal: Atc3AapsJournal
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var uiInteraction: UiInteraction

    /*
     * The trace is a real one on a mocked preference store, which answers false, so it is switched
     * off. Nothing here is about the trace, and a mock would only assert that it was called.
     */

    private lateinit var pumpState: Atc3PumpState
    private lateinit var plugin: Atc3PumpPlugin
    private lateinit var clockKeeper: Atc3ClockKeeper
    private lateinit var linkKeeper: Atc3LinkKeeper
    private lateinit var store: Atc3Store

    /** An answer carrying nothing, which is enough for every case here. */
    private val emptyHistory = Atc3BolusHistory(emptyList(), 0)

    private val clockWatch = Atc3ClockWatch()

    /** Two history reads in a row that found our boluses a minute off: the history wants the clock written. */
    private fun historyTwiceAMinuteOff() {
        clockWatch.matched(-1, now)
        clockWatch.matched(-1, now)
    }

    /** The last status snapshot, read now, this far from the phone. */
    private fun snapshotApart(ms: Long) {
        pumpState.editStatus(readAtMs = now)
        pumpState.editStatus { it.copy(snapshotTime = now + ms) }
    }

    @BeforeEach
    fun setup() {
        BolusProgressData.stopPressed = false
        pumpState = Atc3PumpState()
        whenever(rh.gs(anyInt())).thenReturn("mocked resource")
        whenever(rh.gs(anyInt(), anyOrNull())).thenReturn("mocked resource")
        whenever(rh.gs(anyInt(), anyOrNull(), anyOrNull())).thenReturn("mocked resource")
        whenever(atc3Connection.isConnected).thenReturn(true)
        // A password is entered: without one the driver does nothing and the loop does not run.
        whenever(atc3Connection.isPasswordEntered).thenReturn(true)
        // A protected link is the uninteresting case for everything else in here.
        whenever(atc3Connection.linkProtection).thenReturn(Atc3LinkProtection.PROTECTED)
        whenever(atc3Manager.readBolusHistory()).thenReturn(emptyHistory)
        whenever(atc3HistorySync.recordsMissing(any())).thenReturn(false)
        // Stubbing a suspend function means calling it, and this runs outside a test coroutine.
        runBlocking {
            whenever(atc3HistorySync.reconcileBoluses(any(), any())).thenReturn(Atc3HistorySync.ReconcileResult())
            whenever(aapsJournal.bolusesBetween(any(), any())).thenReturn(0.0)
        // A suspend function returns Object on the JVM, so an unstubbed mock answers null where a
        // plain signature would have answered false or 0, and the driver dies unboxing it. These say
        // the uninteresting thing — the write went in — so each case can stub over it when the
        // answer is what it is about.
            whenever(historyEvents.recordDailyTotals(any())).thenReturn(0)
            whenever(historyEvents.recordRefills(any())).thenReturn(0)
        }
        whenever(commandQueue.readStatus(any(), anyOrNull())).thenReturn(true)
        val trace = Atc3Trace(aapsLogger, preferences)
        clockKeeper = Atc3ClockKeeper(aapsLogger, rh, rxBus, uiInteraction, dateUtil, pumpState, atc3Manager, atc3HistorySync, clockWatch, trace)
        store = Atc3Store(aapsLogger, preferences, uiInteraction, rh)
        val journalFault = Atc3JournalFault(aapsLogger, rh, dateUtil, uiInteraction, pumpSync, atc3Manager, atc3HistorySync, pumpState, trace)
        val reconciliation = Atc3Reconciliation(aapsLogger, rh, rxBus, uiInteraction, pumpState, atc3Manager, atc3HistorySync, journalFault, aapsJournal, store, trace)
        val basalPeriods = Atc3BasalPeriodKeeper(
            aapsLogger, rh, store, dateUtil, commandQueue, pumpState, atc3Manager, atc3HistorySync, aapsJournal, reconciliation, trace, Atc3BasalSpread()
        )
        linkKeeper = Atc3LinkKeeper(aapsLogger, rh, preferences, store, dateUtil, commandQueue, rxBus, uiInteraction, pumpState, atc3Connection, atc3HistorySync, trace)
        plugin = Atc3PumpPlugin(
            aapsLogger, rh, preferences, commandQueue, pumpState, atc3Manager, bolusDelivery, rxBus,
            uiInteraction,
            atc3HistorySync, historyEvents, reconciliation, clockWatch, dateUtil, aapsSchedulers, fabricPrivacy, pumpEnactResultProvider,
            trace,
            clockKeeper,
            basalPeriods,
            linkKeeper,
            atc3Connection
        )
    }

    private fun smb(insulin: Double, lastKnownBolusTime: Long) = DetailedBolusInfo().also {
        it.insulin = insulin
        it.bolusType = BS.Type.SMB
        it.lastKnownBolusTime = lastKnownBolusTime
    }

    private fun manualBolus(insulin: Double, lastKnownBolusTime: Long) = DetailedBolusInfo().also {
        it.insulin = insulin
        it.bolusType = BS.Type.NORMAL
        it.lastKnownBolusTime = lastKnownBolusTime
    }

    @Test
    fun `the plugin reports the pump busy while it is in the middle of something`() = runTest {
        // Wiring rather than logic, but a plugin that answered a flat false here would let the
        // keepalive walk into the middle of a bolus without anything noticing.
        whenever(atc3Manager.isBusy).thenReturn(true)
        assertThat(plugin.isBusy()).isTrue()

        whenever(atc3Manager.isBusy).thenReturn(false)
        assertThat(plugin.isBusy()).isFalse()
    }

    // Daily totals, and refusing to file them under a day we are not sure of

    private fun day(year: Int, month: Int, dayOfMonth: Int, bolus: Double = 1.0) =
        Atc3DailyStats(year, month, dayOfMonth, bolusUnits = bolus, basalUnits = 0.5, tbrUnits = 0.25)

    /** Put the phone on 20 August 2026, the day the usable records are dated. */
    private fun phoneOnThatDay() {
        whenever(dateUtil.now()).thenReturn(
            Calendar.getInstance().apply { clear(); set(2026, 7, 20, 13, 45, 0) }.timeInMillis
        )
    }

    @Test
    fun `daily totals reach AAPS when one of them lands on today's date`() = runTest {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        phoneOnThatDay()
        whenever(atc3Manager.readDailyStats()).thenReturn(listOf(day(2026, 8, 19), day(2026, 8, 20)))

        val result = plugin.loadTDDs()

        assertThat(result.success).isTrue()
        verify(historyEvents, times(1)).recordDailyTotals(any())
    }

    @Test
    fun `nothing is filed when no total lands on the pump's own date`() = runTest {
        // The reading of the date bytes is checked against the pump's own date. If it were wrong, a
        // whole day of insulin would go under the wrong date, so nothing is written at all.
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        phoneOnThatDay()
        whenever(atc3Manager.readDailyStats()).thenReturn(listOf(day(2019, 3, 4), day(2019, 3, 5)))

        val result = plugin.loadTDDs()

        assertThat(result.success).isFalse()
        verify(historyEvents, never()).recordDailyTotals(any())
    }

    @Test
    fun `days from before the pump was used are left out`() = runTest {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        phoneOnThatDay()
        whenever(atc3Manager.readDailyStats())
            .thenReturn(listOf(Atc3DailyStats(2000, 0, 0, 0.0, 0.0, 0.0), day(2026, 8, 20)))

        plugin.loadTDDs()

        argumentCaptor<List<Atc3DailyStats>>().apply {
            verify(historyEvents).recordDailyTotals(capture())
            assertThat(firstValue).hasSize(1)
            assertThat(firstValue.single().day).isEqualTo(20)
        }
    }

    @Test
    fun `an empty answer is a failure rather than a silent success`() = runTest {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        whenever(atc3Manager.readDailyStats()).thenReturn(emptyList())

        assertThat(plugin.loadTDDs().success).isFalse()
        verify(historyEvents, never()).recordDailyTotals(any())
    }

    // What the schedule says, whatever the pump is doing about it

    /** Fill the pump's own profile slots with one rate throughout the day. */
    private fun pumpProfileOf(rate: Double) {
        pumpState.editStatus { it.copy(activeProfileIndex = Atc3Const.DRIVER_PROFILE_INDEX) }
        pumpState.profiles = Atc3PumpState.Profiles(Array(Atc3Protocol.PROFILE_COUNT) { DoubleArray(Atc3Protocol.BASAL_SLOTS) { rate } }, null)
    }

    @Test
    fun `a stopped pump still reports the rate its schedule calls for`() = runTest {
        // AAPS's own disconnect stops delivery without touching this: the zero temporary basal and
        // the running mode carry that fact. A pump that stopped by itself is reported the same way.
        pumpProfileOf(0.8)
        pumpState.editStatus { it.copy(suspended = true) }
        pumpState.editStatus { it.copy(scheduledBasalRate = 0.0) }

        assertThat(plugin.baseBasalRate).isEqualTo(0.8)
    }

    @Test
    fun `a running pump reports what the pump itself says`() = runTest {
        pumpProfileOf(0.8)
        pumpState.editStatus { it.copy(suspended = false) }
        pumpState.editStatus { it.copy(scheduledBasalRate = 1.2) }

        assertThat(plugin.baseBasalRate).isEqualTo(1.2)
    }

    @Test
    fun `a stopped pump whose schedule is unknown reports nothing rather than a guess`() = runTest {
        // Zero also stops the loop, which is right when the driver knows nothing about the pump.
        pumpState.profiles = null
        pumpState.editStatus { it.copy(suspended = true) }

        assertThat(plugin.baseBasalRate).isEqualTo(0.0)
    }

    // The shape of the answers AAPS gets back

    @Test
    fun `every command refuses plainly while the pump is not connected`() = runTest {
        whenever(atc3Connection.isConnected).thenReturn(false)

        val results = listOf(
            plugin.setTempBasalAbsolute(1.0, 30, validProfile, false, tbrTypeNormal),
            plugin.cancelTempBasal(false),
            plugin.setNewBasalProfile(validProfile),
            plugin.deliverTreatment(manualBolus(1.0, lastKnownBolusTime = 0L)),
            plugin.loadTDDs()
        )

        assertThat(results.none { it.success }).isTrue()
        assertThat(results.none { it.enacted }).isTrue()
        // Nothing may be attempted on a pump that is not there.
        verify(bolusDelivery, never()).bolus(any(), any(), any())
        verify(atc3Manager, never()).setTempBasal(any(), any())
    }

    // The pump lock, which the driver reads out of Status V1 instead of finding out by being refused

    @Test
    fun `a locked pump is not sent the commands it is going to refuse`() = runTest {
        // A locked pump answers reads and turns down every control command, so the exchange would
        // buy nothing but a refusal frame that does not say why. The lock was in the last status.
        pumpState.editStatus { it.copy(locked = true) }
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        pumpState.editStatus { it.copy(tbrActive = true) }

        val results = listOf(
            plugin.setTempBasalAbsolute(1.0, 30, validProfile, false, tbrTypeNormal),
            plugin.cancelTempBasal(false),
            plugin.setNewBasalProfile(validProfile),
            plugin.deliverTreatment(manualBolus(1.0, lastKnownBolusTime = 0L)),
            plugin.executeCustomCommand(Atc3SetSuspended(true))!!
        )

        assertThat(results.none { it.success }).isTrue()
        assertThat(results.none { it.enacted }).isTrue()
        verify(bolusDelivery, never()).bolus(any(), any(), any())
        verify(atc3Manager, never()).setTempBasal(any(), any())
        verify(atc3Manager, never()).cancelTempBasal()
        verify(atc3Manager, never()).writeBasalProfile(any(), any())
        verify(atc3Manager, never()).setSuspended(any())
    }

    @Test
    fun `a locked pump does not have its clock written either`() = runTest {
        // The clock write is a control command like the rest of them, and the clock will still be
        // there to correct once somebody unlocks the pump.
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        pumpState.editStatus { it.copy(locked = true) }
        historyTwiceAMinuteOff()

        plugin.getPumpStatus("test")

        verify(atc3Manager, never()).writeClock(any())
    }

    @Test
    fun `an unlocked pump is not held back`() = runTest {
        // The guard must not be the reason a command never goes out: the ordinary path still runs.
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        whenever(atc3Manager.writeClock(any())).thenReturn(null)
        pumpState.editStatus { it.copy(locked = false) }
        historyTwiceAMinuteOff()

        plugin.getPumpStatus("test")

        verify(atc3Manager, times(1)).writeClock(now)
    }

    @Test
    fun `a bolus of nothing is refused before anything is sent`() = runTest {
        val result = plugin.deliverTreatment(manualBolus(0.0, lastKnownBolusTime = 0L))

        assertThat(result.success).isFalse()
        verify(bolusDelivery, never()).bolus(any(), any(), any())
    }

    @Test
    fun `the profile is reported as set until the pump has been read`() = runTest {
        // Saying otherwise would have AAPS queue a profile write it cannot yet carry out.
        pumpState.profiles = null

        assertThat(plugin.isThisProfileSet(validProfile)).isTrue()
    }

    @Test
    fun `a profile in a slot the driver does not own does not count as set`() = runTest {
        pumpState.profiles = Atc3PumpState.Profiles(Array(Atc3Protocol.PROFILE_COUNT) { DoubleArray(Atc3Protocol.BASAL_SLOTS) { 1.0 } }, null)
        pumpState.editStatus { it.copy(activeProfileIndex = Atc3Const.DRIVER_PROFILE_INDEX + 1) }

        assertThat(plugin.isThisProfileSet(validProfile)).isFalse()
    }

    @Test
    fun `a stopped pump is sent no cancel and its stop stays open`() = runTest {
        // AAPS cancels the temporary basal when it sees the pump stopped. The stopped pump still
        // reports its temporary basal as running; sending the cancel would close the stop's row,
        // and AAPS would count the scheduled basal for minutes of no delivery.
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        pumpState.editStatus { it.copy(suspended = true) }
        pumpState.editStatus { it.copy(tbrActive = true) }

        val result = plugin.cancelTempBasal(false)

        assertThat(result.success).isTrue()
        assertThat(result.enacted).isFalse()
        assertThat(result.isTempCancel).isTrue()
        verify(atc3Manager, never()).cancelTempBasal()
        verify(atc3HistorySync, never()).tbrStopped(any())
        // The stop is written from the status all the same: a short pause can be over before the
        // next tick, and skipping this would leave it out of AAPS.
        verify(atc3HistorySync, times(1)).onStatus(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `an alarm that stops delivery has the pump stopped, once`() = runTest {
        for (alarm in listOf(Atc3Alarm.RESERVOIR_EMPTY, Atc3Alarm.DAILY_LIMIT)) {
            clearInvocations(atc3Manager)
            whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
            whenever(atc3Manager.setSuspended(true)).thenReturn(null)
            pumpState.editStatus { it.copy(activeAlarmCodes = (listOf(alarm)).map { a -> a.code }) }
            pumpState.editStatus { it.copy(suspended = false) }

            plugin.getPumpStatus("test")
            verify(atc3Manager, times(1)).setSuspended(true)

            // Stopped now: the next tick sends nothing more.
            pumpState.editStatus { it.copy(suspended = true) }
            plugin.getPumpStatus("test")
            verify(atc3Manager, times(1)).setSuspended(true)
        }
    }

    @Test
    fun `a pump under an alarm that stops delivery is sent no cancel either`() = runTest {
        // The daily dose limit: the pump takes a cancel and drops its temporary basal while
        // delivering nothing, and the row of the stop would be closed for it.
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        pumpState.editStatus { it.copy(activeAlarmCodes = (listOf(Atc3Alarm.DAILY_LIMIT)).map { a -> a.code }) }
        pumpState.editStatus { it.copy(tbrActive = true) }

        val result = plugin.cancelTempBasal(false)

        assertThat(result.success).isTrue()
        assertThat(result.enacted).isFalse()
        verify(atc3Manager, never()).cancelTempBasal()
        verify(atc3HistorySync, never()).tbrStopped(any())
    }

    @Test
    fun `cancelling a temporary basal that is not running succeeds without sending anything`() = runTest {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        pumpState.editStatus { it.copy(tbrActive = false) }

        val result = plugin.cancelTempBasal(false)

        assertThat(result.success).isTrue()
        assertThat(result.enacted).isFalse()
        assertThat(result.isTempCancel).isTrue()
        verify(atc3Manager, never()).cancelTempBasal()
        // AAPS's own copy may still be open, from a temporary basal stopped on the pump itself.
        verify(atc3HistorySync, times(1)).tbrStopped(any())
    }

    @Test
    fun `what the pump does not offer is refused rather than pretended`() = runTest {
        assertThat(plugin.setTempBasalPercent(80, 30, validProfile, false, tbrTypeNormal).success).isFalse()
        assertThat(plugin.setExtendedBolus(1.0, 30).success).isFalse()
        assertThat(plugin.cancelExtendedBolus().success).isFalse()
    }

    // Never asking the pump for more than it is set to allow

    private fun settingsWith(maxBasal: Double, maxBolus: Double) = Atc3Settings(
        lowBolusSpeed = false, keypadLock = false, autoOff = false, basalPatterns = false,
        dailyLimitEnabled = false, english = true, alarmSignalType = 0, brightnessLevel = 0,
        autoOffHours = 1, lowInsulinUnits = 20, lowInsulinHalfHours = 4, extendedBolusAllowed = false,
        bgReminder = false, alarmDuration = 1, screenTimeoutRaw = 100, dailyLimitUnits = 100,
        maxBasalRaw = (maxBasal / Atc3Protocol.DOSE_SCALE).toInt(),
        maxBolusRaw = (maxBolus / Atc3Protocol.DOSE_SCALE).toInt()
    )

    @Test
    fun `a basal beyond what the pump allows is cut down to it`() = runTest {
        pumpState.editStatus(settings = settingsWith(maxBasal = 2.5, maxBolus = 10.0))

        val asked = ConstraintObject(9.0, aapsLogger)
        plugin.applyBasalConstraints(asked, validProfile)

        assertThat(asked.value()).isEqualTo(2.5)
    }

    @Test
    fun `a bolus beyond what the pump allows is cut down to it`() = runTest {
        pumpState.editStatus(settings = settingsWith(maxBasal = 2.5, maxBolus = 10.0))

        val asked = ConstraintObject(25.0, aapsLogger)
        plugin.applyBolusConstraints(asked)

        assertThat(asked.value()).isEqualTo(10.0)
    }

    @Test
    fun `nothing is limited before the pump has been read`() = runTest {
        // Inventing a limit before the pump has said anything would be a guess; the model's own
        // bound already applies.
        pumpState.reset()

        val asked = ConstraintObject(9.0, aapsLogger)
        plugin.applyBasalConstraints(asked, validProfile)

        assertThat(asked.value()).isEqualTo(9.0)
    }

    // Stopping and resuming the pump from AAPS

    @Test
    fun `stopping the pump goes to the pump and then reads the state back`() = runTest {
        whenever(atc3Manager.setSuspended(any())).thenReturn(null)
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }

        val result = plugin.executeCustomCommand(Atc3SetSuspended(suspended = true))

        assertThat(result?.success).isTrue()
        verify(atc3Manager, times(1)).setSuspended(true)
        // The status read is what carries the stop into the insulin on board as a zero temporary
        // basal, rather than waiting for the next poll to notice.
        verify(atc3HistorySync, times(1)).onStatus(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a pump that would not stop is reported as a failure`() = runTest {
        whenever(atc3Manager.setSuspended(any())).thenReturn(Atc3Failure("the pump still reports itself as running"))

        val result = plugin.executeCustomCommand(Atc3SetSuspended(suspended = true))

        assertThat(result?.success).isFalse()
        assertThat(result?.enacted).isFalse()
    }

    @Test
    fun `resuming asks for the opposite state`() = runTest {
        whenever(atc3Manager.setSuspended(any())).thenReturn(null)
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }

        plugin.executeCustomCommand(Atc3SetSuspended(suspended = false))

        verify(atc3Manager, times(1)).setSuspended(false)
    }

    // A held link gone quiet is questioned on the queue's thread, not on the timer that noticed it

    @Test
    fun `the question to a quiet pump is carried out as the queue's command`() = runTest {
        whenever(atc3Manager.probeQuietLink()).thenReturn(true)

        val result = plugin.executeCustomCommand(Atc3ProbeLink())

        assertThat(result?.success).isTrue()
        assertThat(result?.enacted).isFalse()
        verify(atc3Manager, times(1)).probeQuietLink()
    }

    @Test
    fun `a pump that did not answer the question is reported as a failure`() = runTest {
        whenever(atc3Manager.probeQuietLink()).thenReturn(false)

        assertThat(plugin.executeCustomCommand(Atc3ProbeLink())?.success).isFalse()
    }

    @Test
    fun `an unknown custom command is not swallowed`() = runTest {
        val other = object : app.aaps.core.interfaces.queue.CustomCommand {
            override val statusDescription = "SOMETHING ELSE"
        }
        assertThat(plugin.executeCustomCommand(other)).isNull()
    }

    // Not asking the pump for what cannot have changed, see readProfilesIfNeeded and readBatteryIfDue

    @Test
    fun `the stored profiles are read once and not again in every connection`() = runTest {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        whenever(atc3Manager.readBasalProfiles(anyOrNull())).thenAnswer { call ->
            pumpState.profiles = Atc3PumpState.Profiles(Array(Atc3Protocol.PROFILE_COUNT) { DoubleArray(Atc3Protocol.BASAL_SLOTS) }, call.getArgument(0))
            true
        }
        pumpState.editStatus { it.copy(activeProfileIndex = Atc3Const.DRIVER_PROFILE_INDEX) }

        plugin.getPumpStatus("first")
        plugin.getPumpStatus("second")

        // They only change when AAPS writes them or somebody edits them on the pump, and a write
        // of our own reads them back itself. Confirming them in every connection would buy nothing.
        verify(atc3Manager, times(1)).readBasalProfiles(anyOrNull())
    }

    @Test
    fun `the stored profiles are read again when the pump has moved to another profile`() = runTest {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        whenever(atc3Manager.readBasalProfiles(anyOrNull())).thenAnswer { call ->
            pumpState.profiles = Atc3PumpState.Profiles(Array(Atc3Protocol.PROFILE_COUNT) { DoubleArray(Atc3Protocol.BASAL_SLOTS) }, call.getArgument(0))
            true
        }
        pumpState.editStatus { it.copy(activeProfileIndex = Atc3Const.DRIVER_PROFILE_INDEX) }
        plugin.getPumpStatus("first")

        // Status V1 says this in every status at no cost, which is what makes the expensive read
        // avoidable rather than merely rarer.
        pumpState.editStatus { it.copy(activeProfileIndex = Atc3Const.DRIVER_PROFILE_INDEX + 1) }
        plugin.getPumpStatus("second")

        verify(atc3Manager, times(2)).readBasalProfiles(anyOrNull())
    }

    @Test
    fun `a read that failed is not remembered as having happened`() = runTest {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        whenever(atc3Manager.readBasalProfiles(anyOrNull())).thenReturn(false)

        plugin.getPumpStatus("first")
        plugin.getPumpStatus("second")

        verify(atc3Manager, times(2)).readBasalProfiles(anyOrNull())
    }

    @Test
    fun `the battery voltage is not read again in the same hour`() = runTest {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        whenever(atc3Manager.readStatusV2()).thenReturn(Atc3StatusV2(0.0, 0.0))

        plugin.getPumpStatus("first")
        plugin.getPumpStatus("second")

        // Status V2 carries nothing else the driver uses, and a battery moves over days. The
        // percentage AAPS shows comes from Status V1 and is still read every time.
        verify(atc3Manager, times(1)).readStatusV2()
    }

    // The pump clock, which every history stamp depends on

    private fun verifyTimeChangeNotice(count: Int) {
        verify(uiInteraction, times(count)).addNotificationValidFor(
            eq(Notification.INSIGHT_DATE_TIME_UPDATED), any(), any(), any()
        )
    }

    /** The tick after AAPS starts puts the clock on the phone's; what follows is about the ticks after it. */
    private fun clockSetAtStart() {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        whenever(atc3Manager.writeClock(any())).thenReturn(null)
        plugin.getPumpStatus("start")
        verify(atc3Manager, times(1)).writeClock(any())
    }

    @Test
    fun `a pump on firmware older than the password is run, under the warning a pump set to 000000 gets`() = runTest {
        pumpState.version = Atc3Version(firmware = listOf(1, 1, 0, 9), protocolMajor = 4, protocolMinor = 12, unknown = emptyList())
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        // Such firmware works under 000000, which the pump accepts.
        whenever(atc3Connection.linkProtection).thenReturn(Atc3LinkProtection.UNPROTECTED)

        plugin.getPumpStatus("test")

        verify(atc3Manager, times(1)).readStatus()
        verify(uiInteraction).addNotification(eq(Notification.WRONG_PUMP_PASSWORD), any(), eq(Notification.NORMAL))
        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isTrue()
    }

    @Test
    fun `a pump with no authorisation service at all is run under the same warning`() = runTest {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        whenever(atc3Connection.linkProtection).thenReturn(Atc3LinkProtection.UNSUPPORTED)

        plugin.getPumpStatus("test")

        verify(atc3Manager, times(1)).readStatus()
        verify(uiInteraction).addNotification(eq(Notification.WRONG_PUMP_PASSWORD), any(), eq(Notification.NORMAL))
    }

    @Test
    fun `a pump on firmware with a password is run without that warning`() = runTest {
        pumpState.version = Atc3Version(firmware = listOf(1, 1, 1, 0), protocolMajor = 4, protocolMinor = 12, unknown = emptyList())
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }

        plugin.getPumpStatus("test")

        verify(atc3Manager, times(1)).readStatus()
        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isTrue()
    }

    @Test
    fun `the loop does not run while the pump allows extended and dual boluses`() = runTest {
        pumpState.editStatus(settings = settingsWith(maxBasal = 5.0, maxBolus = 12.0).copy(extendedBolusAllowed = true))
        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isFalse()

        pumpState.editStatus(settings = settingsWith(maxBasal = 5.0, maxBolus = 12.0))
        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isTrue()
    }

    @Test
    fun `the first tick after AAPS starts puts the pump clock on the phone's`() = runTest {
        clockSetAtStart()
    }

    @Test
    fun `the pump clock is left alone while the history finds our boluses where they started`() = runTest {
        clockSetAtStart()
        clockWatch.matched(0, now)

        plugin.getPumpStatus("test")

        verify(atc3Manager, times(1)).writeClock(any())
    }

    @Test
    fun `one read a minute off is not enough to set the clock`() = runTest {
        clockSetAtStart()
        clockWatch.matched(-1, now)

        plugin.getPumpStatus("test")

        verify(atc3Manager, times(1)).writeClock(any())
    }

    @Test
    fun `a mode change right after the clock was set does not write it again`() = runTest {
        clockSetAtStart()
        clockKeeper.clockSyncWanted = true
        whenever(dateUtil.now()).thenReturn(now + 60_000L)
        pumpState.editStatus(readAtMs = now + 60_000L)
        pumpState.editStatus { it.copy(snapshotTime = now + 60_000L) }

        plugin.getPumpStatus("test")

        verify(atc3Manager, times(1)).writeClock(any())
    }

    @Test
    fun `eight hours after it was set the pump clock is put on the phone's again`() = runTest {
        clockSetAtStart()
        whenever(dateUtil.now()).thenReturn(now + Atc3Const.CLOCK_SYNC_EVERY_MS)
        pumpState.editStatus(readAtMs = now + Atc3Const.CLOCK_SYNC_EVERY_MS)
        pumpState.editStatus { it.copy(snapshotTime = now + Atc3Const.CLOCK_SYNC_EVERY_MS) }

        plugin.getPumpStatus("test")

        verify(atc3Manager, times(2)).writeClock(any())
    }

    @Test
    fun `two history reads a minute off set the pump clock without a word`() = runTest {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        whenever(atc3Manager.writeClock(any())).thenReturn(null)
        historyTwiceAMinuteOff()

        plugin.getPumpStatus("test")

        verify(atc3Manager, times(1)).writeClock(now)
        verifyTimeChangeNotice(0)
    }

    @Test
    fun `the history is read and taken in before the pump clock is set, and the import mark moves after`() = runTest {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        whenever(atc3Manager.writeClock(any())).thenReturn(null)
        historyTwiceAMinuteOff()

        plugin.getPumpStatus("test")

        val order = inOrder(atc3HistorySync, atc3Manager, bolusDelivery)
        order.verify(atc3HistorySync).reconcileBoluses(any(), any())
        order.verify(atc3Manager).writeClock(now)
        order.verify(atc3HistorySync).onPumpClockWritten(now)
    }

    @Test
    fun `the pump clock is not set while the history cannot be read first`() = runTest {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        whenever(atc3Manager.readBolusHistory()).thenReturn(null)
        historyTwiceAMinuteOff()

        plugin.getPumpStatus("test")

        verify(atc3Manager, never()).writeClock(any())
    }

    @Test
    fun `a snapshot fifty five minutes or more from the phone is left alone and raises an alarm`() = runTest {
        // A difference of hours is a disagreement about what time it is, not drift. Nothing is
        // written, whatever the history says.
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        snapshotApart(Atc3Const.CLOCK_MAX_CORRECTION_MS + 1)
        historyTwiceAMinuteOff()

        plugin.getPumpStatus("test")

        verify(atc3Manager, never()).writeClock(any())
        verify(uiInteraction, times(1)).addNotification(
            eq(Notification.OVER_24H_TIME_CHANGE_REQUESTED), any(), eq(Notification.URGENT)
        )
    }

    @Test
    fun `a snapshot fifty five minutes or more from the phone stops the loop`() = runTest {
        snapshotApart(-(Atc3Const.CLOCK_MAX_CORRECTION_MS + 1))

        val allowed = plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger))

        assertThat(allowed.value()).isFalse()
    }

    @Test
    fun `a snapshot within the limit leaves the loop alone`() = runTest {
        snapshotApart(Atc3Const.CLOCK_MAX_CORRECTION_MS - 1)

        val allowed = plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger))

        assertThat(allowed.value()).isTrue()
    }

    @Test
    fun `a phone time change of half an hour moves the pump clock and says so`() = runTest {
        // A half hour timezone, read against a snapshot that can be up to a minute old.
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        whenever(atc3Manager.writeClock(any())).thenReturn(null)
        plugin.timezoneOrDSTChanged(TimeChangeType.TimezoneChanged)
        snapshotApart(-(30 * 60 * 1000L + 45_000L))

        plugin.getPumpStatus("test")

        verify(atc3Manager, times(1)).writeClock(now)
        verifyTimeChangeNotice(1)
    }

    @Test
    fun `a snapshot between five and fifty five minutes from the phone sets the clock at once and says so`() = runTest {
        // The middle band: no bolus history could pair anything this far off,
        // so the snapshot itself is the trigger, and the user is told.
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        whenever(atc3Manager.writeClock(any())).thenReturn(null)
        snapshotApart(10 * 60 * 1000L)

        plugin.getPumpStatus("test")

        verify(atc3Manager, times(1)).writeClock(now)
        verify(uiInteraction, times(1)).addNotificationValidFor(
            eq(Notification.INSIGHT_DATE_TIME_UPDATED), any(), eq(Notification.INFO), any()
        )
    }

    @Test
    fun `a phone time change of an hour writes nothing and stops the loop`() = runTest {
        // Fifty five minutes and more is the band where the driver does not move the clock, a
        // daylight saving hour included.
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        plugin.timezoneOrDSTChanged(TimeChangeType.DSTStarted)
        snapshotApart(60 * 60 * 1000L)

        plugin.getPumpStatus("test")

        verify(atc3Manager, never()).writeClock(any())
        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isFalse()
    }

    @Test
    fun `a clock write that does not take warns the user`() = runTest {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        whenever(atc3Manager.writeClock(any())).thenReturn("the pump did not acknowledge the clock")
        historyTwiceAMinuteOff()

        plugin.getPumpStatus("test")

        verify(uiInteraction, times(1)).addNotification(
            eq(Notification.PUMP_WARNING), any(), eq(Notification.NORMAL)
        )
    }

    @Test
    fun `a phone that changed timezone asks to read the pump`() = runTest {
        // Nothing is sent from the callback itself: wrong thread, and the pump may be out of range.
        plugin.timezoneOrDSTChanged(TimeChangeType.TimezoneChanged)

        verify(commandQueue, times(1)).readStatus(any(), anyOrNull())
    }

    // Telling the user the pump itself is stopped

    @Test
    fun `a stopped pump is reported to the user`() = runTest {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        pumpState.editStatus { it.copy(suspended = true) }

        plugin.getPumpStatus("test")

        verify(uiInteraction, times(1)).addNotification(
            eq(Notification.PUMP_SUSPENDED), any(), eq(Notification.NORMAL)
        )
    }

    @Test
    fun `a running pump raises nothing and clears what was there`() = runTest {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        pumpState.editStatus { it.copy(suspended = false) }
        val dismissed = mutableListOf<Int>()
        val subscription = rxBus.toObservable(EventDismissNotification::class.java)
            .subscribe { dismissed.add(it.id) }

        plugin.getPumpStatus("test")

        verify(uiInteraction, never()).addNotification(
            eq(Notification.PUMP_SUSPENDED), any(), any()
        )
        assertThat(dismissed).contains(Notification.PUMP_SUSPENDED)
        subscription.dispose()
    }

    // The barrier: a command that changes delivery reads the pump's boluses first

    @Test
    fun `a temporary basal reads the pump's boluses first`() = runTest {
        whenever(atc3Manager.setTempBasal(any(), any())).thenReturn(
            app.aaps.pump.atc3.command.Atc3TbrResult(now, 1.0, 30, "not the point of this test")
        )

        plugin.setTempBasalAbsolute(1.0, 30, validProfile, false, tbrTypeNormal)

        verify(atc3Manager, times(1)).readBolusHistory()
        verify(atc3HistorySync, times(1)).reconcileBoluses(any(), any())
    }

    @Test
    fun `a temporary basal reads them again however recently they were read`() = runTest {
        // A bolus given on the pump between two commands has to reach AAPS before the second.
        whenever(atc3Manager.setTempBasal(any(), any())).thenReturn(
            app.aaps.pump.atc3.command.Atc3TbrResult(now, 1.0, 30, "not the point of this test")
        )

        plugin.setTempBasalAbsolute(1.0, 30, validProfile, false, tbrTypeNormal)
        plugin.setTempBasalAbsolute(1.0, 30, validProfile, false, tbrTypeNormal)

        verify(atc3Manager, times(2)).readBolusHistory()
    }

    @Test
    fun `cancelling a temporary basal reads them too`() = runTest {
        // The status comes first: without one there is nothing to compare and no command goes
        // out at all; with one, and no comparison possible yet, the history is the barrier.
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }

        plugin.cancelTempBasal(false)

        verify(atc3Manager, times(1)).readBolusHistory()
    }

    @Test
    fun `writing the basal profile reads them too`() = runTest {
        whenever(atc3Manager.writeBasalProfile(any(), any())).thenReturn("not the point of this test")

        plugin.setNewBasalProfile(validProfile)

        verify(atc3Manager, times(1)).readBolusHistory()
    }

    // Asking for a read when nothing else will, see Atc3PumpPlugin.onStart

    /** Enabling the plugin is what subscribes it, see PluginBase.setPluginEnabledBlocking. */
    private fun startPlugin() = plugin.setPluginEnabledBlocking(PluginType.PUMP, true)

    /** The watchers a started plugin launches must not outlive the test that started them. */
    @AfterEach
    fun stopPlugin() = plugin.onStop()

    /** Pretend the pump's state was last reconciled [minutes] ago. */
    private fun stateAge(minutes: Long) =
        whenever(atc3HistorySync.stateAgeMs(now)).thenReturn(minutes * 60_000L)

    @Test
    fun `the loop finishing a decision asks for a read when the state is a cycle old`() = runTest {
        // Asked here rather than at the glucose so that the read and whatever the loop then asks
        // the pump for are queued together and travel in one connection.
        stateAge(5)
        startPlugin()

        rxBus.send(EventAPSCalculationFinished())

        verify(commandQueue, timeout(2_000).times(1)).readStatus(any(), anyOrNull())
        plugin.onStop()
    }

    @Test
    fun `the loop finishing a decision asks for nothing when the state is fresh`() = runTest {
        stateAge(1)
        startPlugin()

        rxBus.send(EventAPSCalculationFinished())

        verify(commandQueue, after(300).never()).readStatus(any(), anyOrNull())
        plugin.onStop()
    }

    @Test
    fun `a glucose value alone does not pre-empt the read that would have shared a connection`() = runTest {
        // Four minutes old: what the state is at the glucose when the loop decided on the previous
        // one, and deliberately not enough for the glucose to ask. Asking here would open a
        // connection of its own two seconds after the glucose, closed again before the loop's
        // command turned up. Five minutes, a whole cycle without a decision, is where it asks.
        stateAge(4)
        startPlugin()

        rxBus.send(EventNewBG(now))

        verify(commandQueue, after(300).never()).readStatus(any(), anyOrNull())
        plugin.onStop()
    }

    @Test
    fun `a glucose value asks once the loop has plainly stopped deciding`() = runTest {
        // The fallback for glucose values that produce no decision: without it nothing would read
        // the pump on those.
        stateAge(11)
        startPlugin()

        rxBus.send(EventNewBG(now))

        verify(commandQueue, timeout(2_000).times(1)).readStatus(any(), anyOrNull())
        plugin.onStop()
    }

    @Test
    fun `a freshly read bolus history does not pass for a freshly read pump`() = runTest {
        // Every command reads the bolus history on its way past, and asking about the history here
        // would let that count as having looked at the pump. Nothing would have looked at the
        // temporary basal or the suspension, so the busier the loop was, the longer a temporary
        // basal started on the keypad would stay invisible.
        stateAge(5)
        startPlugin()

        rxBus.send(EventAPSCalculationFinished())

        verify(commandQueue, timeout(2_000).times(1)).readStatus(any(), anyOrNull())
        plugin.onStop()
    }

    // Judging a finished bolus by what the pump wrote down, see Atc3PumpPlugin.judgeBolus

    /** Drive a bolus to its end with the pump recording [recorded] units. */
    private suspend fun deliver(requested: Double, recorded: Double, cancelled: Boolean = false): PumpEnactResult {
        whenever(bolusDelivery.bolus(any(), any(), any()))
            .thenReturn(Atc3BolusOutcome.Delivered(recorded, cancelled = cancelled, acceptedAtMs = now))
        whenever(atc3HistorySync.reconcileBoluses(any(), any()))
            .thenReturn(Atc3HistorySync.ReconcileResult(confirmed = mapOf(now to recorded)))
        return plugin.deliverTreatment(manualBolus(requested, lastKnownBolusTime = 0L))
    }

    @Test
    fun `a bolus the pump delivered in full is a success`() = runTest {
        val result = deliver(requested = 2.0, recorded = 2.0)
        assertThat(result.success).isTrue()
        assertThat(result.bolusDelivered).isEqualTo(2.0)
    }

    @Test
    fun `a bolus that stopped part way through is a failure carrying what did go in`() = runTest {
        // Reporting success here would leave the loop believing the insulin it asked for is on
        // board, when most of it never left the pump.
        val result = deliver(requested = 2.0, recorded = 0.5)
        assertThat(result.success).isFalse()
        assertThat(result.enacted).isTrue()
        assertThat(result.bolusDelivered).isEqualTo(0.5)
    }

    @Test
    fun `a bolus the user stopped is a success however little went in`() = runTest {
        val result = deliver(requested = 2.0, recorded = 0.5, cancelled = true)
        assertThat(result.success).isTrue()
        assertThat(result.bolusDelivered).isEqualTo(0.5)
    }

    @Test
    fun `a shortfall smaller than one step of the pump is not a shortfall`() = runTest {
        // The pump works in 0.025 U steps, so anything inside one of them is rounding.
        val result = deliver(requested = 2.0, recorded = 1.99)
        assertThat(result.success).isTrue()
    }

    @Test
    fun `a bolus the pump answered about and has not recorded is judged on what it reported while running`() = runTest {
        // The pump was asked and holds no record, so the progress frames are all there is. Stalling
        // at nothing must not pass as done just because the record is not there yet.
        whenever(bolusDelivery.bolus(any(), any(), any()))
            .thenReturn(Atc3BolusOutcome.Delivered(0.0, acceptedAtMs = now))

        val result = plugin.deliverTreatment(manualBolus(2.0, lastKnownBolusTime = 0L))

        assertThat(result.success).isFalse()
        assertThat(result.bolusDelivered).isEqualTo(0.0)
        // Asked as many times as the record may take to appear, after the read before the bolus.
        verify(atc3Manager, times(Atc3Const.BOLUS_RECORD_POLL_ATTEMPTS + 1)).readBolusHistory()
    }

    @Test
    fun `a full bolus the pump has not recorded yet is still a success`() = runTest {
        whenever(bolusDelivery.bolus(any(), any(), any()))
            .thenReturn(Atc3BolusOutcome.Delivered(2.0, acceptedAtMs = now))
        whenever(atc3Manager.readBolusHistory()).thenReturn(null)

        val result = plugin.deliverTreatment(manualBolus(2.0, lastKnownBolusTime = 0L))

        assertThat(result.success).isTrue()
        assertThat(result.bolusDelivered).isEqualTo(2.0)
    }

    @Test
    fun `a bolus the pump could not be asked about is judged on what it reported while running`() = runTest {
        // No history could be read: the link cut the watching short. What went in is said as seen;
        // the row comes from the record on the next connection, whatever the pump went on to deliver.
        whenever(rh.gs(eq(R.string.atc3_bolus_short), anyOrNull(), anyOrNull())).thenReturn("short")
        whenever(bolusDelivery.bolus(any(), any(), any()))
            .thenReturn(Atc3BolusOutcome.Delivered(0.4, acceptedAtMs = now))
        whenever(atc3Manager.readBolusHistory()).thenReturn(null)

        val result = plugin.deliverTreatment(manualBolus(1.0, lastKnownBolusTime = 0L))

        assertThat(result.success).isFalse()
        assertThat(result.bolusDelivered).isEqualTo(0.4)
        assertThat(result.comment).isEqualTo("short")
    }

    @Test
    fun `a bolus the pump was asked about and did not record is still called short`() = runTest {
        // Here the pump answered, and the absence of a record for this bolus says something about
        // it. That one keeps the shortfall it always had.
        whenever(rh.gs(R.string.atc3_bolus_unconfirmed)).thenReturn("unconfirmed")
        whenever(rh.gs(eq(R.string.atc3_bolus_short), anyOrNull(), anyOrNull())).thenReturn("short")
        whenever(bolusDelivery.bolus(any(), any(), any()))
            .thenReturn(Atc3BolusOutcome.Delivered(0.4, acceptedAtMs = now))

        val result = plugin.deliverTreatment(manualBolus(1.0, lastKnownBolusTime = 0L))

        assertThat(result.success).isFalse()
        assertThat(result.comment).isEqualTo("short")
    }

    // A bolus of ours: expected from the pump's acceptance, written from the pump's record

    /** Drive a bolus the pump accepts at [acceptedAt] and ends as [outcome], its record saying the same. */
    private suspend fun deliverAccepted(
        requested: Double,
        outcome: Atc3BolusOutcome.Delivered,
        acceptedAt: Long = now
    ): PumpEnactResult {
        whenever(bolusDelivery.bolus(any(), any(), any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val onAccepted = invocation.arguments[1] as suspend (Long) -> Unit
            runBlocking { onAccepted(acceptedAt) }
            outcome
        }
        whenever(atc3HistorySync.reconcileBoluses(any(), any()))
            .thenReturn(Atc3HistorySync.ReconcileResult(confirmed = mapOf(acceptedAt to outcome.reportedUnits)))
        return plugin.deliverTreatment(manualBolus(requested, lastKnownBolusTime = 0L))
    }

    @Test
    fun `a bolus is expected when the pump accepts it, and its record is read at once`() = runTest {
        // No row of our own: the row comes from the pump's record, read right after the bolus.
        val result = deliverAccepted(2.0, Atc3BolusOutcome.Delivered(2.0, completed = true, acceptedAtMs = now))

        assertThat(result.success).isTrue()
        assertThat(result.bolusDelivered).isEqualTo(2.0)
        val order = inOrder(atc3HistorySync, atc3Manager)
        order.verify(atc3HistorySync, times(1)).expect(eq(now), eq(2.0), eq(BS.Type.NORMAL))
        order.verify(atc3Manager, times(1)).readBolusHistory()
        order.verify(atc3HistorySync, times(1)).reconcileBoluses(any(), any())
    }

    @Test
    fun `a bolus cut short is judged by what its record says went in`() = runTest {
        val result = deliverAccepted(2.0, Atc3BolusOutcome.Delivered(0.5, completed = false, acceptedAtMs = now))

        assertThat(result.success).isFalse()
        assertThat(result.bolusDelivered).isEqualTo(0.5)
    }

    @Test
    fun `two boluses may follow each other at once`() = runTest {
        // Nothing holds the second one back: the minute tells its record from the first one's.
        deliverAccepted(1.0, Atc3BolusOutcome.Delivered(1.0, completed = true, acceptedAtMs = now))
        deliverAccepted(1.0, Atc3BolusOutcome.Delivered(1.0, completed = true, acceptedAtMs = now))

        verify(bolusDelivery, times(2)).bolus(any(), any(), any())
    }

    // Refusing a microbolus decided without knowing about insulin somebody else gave

    @Test
    fun `a microbolus decided before a bolus a tick imported is refused too`() = runTest {
        pumpAnswersWithCount(10.0)
        // The tick's read of the journal finds the bolus; the read in front of the microbolus finds nothing new.
        whenever(atc3HistorySync.reconcileBoluses(any(), any()))
            .thenReturn(Atc3HistorySync.ReconcileResult(newestImportedAtMs = now - 60_000L, importedUnits = 1.0))
            .thenReturn(Atc3HistorySync.ReconcileResult())
        plugin.getPumpStatus("test")

        val result = plugin.deliverTreatment(smb(0.5, lastKnownBolusTime = now - 120_000L))

        assertThat(result.success).isFalse()
        verify(bolusDelivery, never()).bolus(any(), any(), any())
    }

    @Test
    fun `a microbolus is refused when a bolus reached us after the loop decided`() = runTest {
        whenever(atc3HistorySync.reconcileBoluses(any(), any())).thenReturn(
            Atc3HistorySync.ReconcileResult(newestImportedAtMs = now - 60_000L)
        )

        val result = plugin.deliverTreatment(smb(0.5, lastKnownBolusTime = now - 120_000L))

        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
        verify(bolusDelivery, never()).bolus(any(), any(), any())
    }

    @Test
    fun `a microbolus is refused when a bolus the loop did not know of turns up in front of it, however old`() = runTest {
        // The first comparison after a start: nothing to compare against, the journal is read.
        pumpAnswersWithCount(10.0)
        whenever(atc3HistorySync.reconcileBoluses(any(), any())).thenReturn(
            Atc3HistorySync.ReconcileResult(newestImportedAtMs = now - 300_000L)
        )

        val result = plugin.deliverTreatment(smb(0.5, lastKnownBolusTime = now - 120_000L))

        assertThat(result.success).isFalse()
        verify(bolusDelivery, never()).bolus(any(), any(), any())
    }

    @Test
    fun `a microbolus is delivered when nothing unknown turned up`() = runTest {
        pumpAnswersWithCount(10.0)
        whenever(bolusDelivery.bolus(any(), any(), any())).thenReturn(app.aaps.pump.atc3.command.Atc3BolusOutcome.NotSent)

        plugin.deliverTreatment(smb(0.5, lastKnownBolusTime = now - 120_000L))

        verify(bolusDelivery, times(1)).bolus(any(), any(), any())
    }

    // What the pump's count says and the journal does not explain is the pump's own way of
    // delivering: counted, and settled by the half hour. It refuses nothing. Only a bolus the loop
    // did not know of, or a journal that cannot be read, refuses the loop's command.

    /**
     * The pump has counted [excess] more than the AAPS journal accounts for since the read before,
     * and its journals hold nothing AAPS did not.
     */
    private fun pumpCountedMoreThanTheJournal(excess: Double) {
        pumpAnswersWithCount(10.0)
        runBlocking {
            whenever(aapsJournal.insulinBetween(any(), any(), any())).thenReturn(Atc3JournalArithmetic.Breakdown(0.0, 0.0, 0.0))
        }
        // The first read begins the window the comparison counts from.
        plugin.getPumpStatus("test")
        pumpAnswersWithCount(10.0 + excess)
    }

    @Test
    fun `a microbolus goes through when the pump counted insulin its journal does not explain`() = runTest {
        pumpCountedMoreThanTheJournal(1.2)
        whenever(bolusDelivery.bolus(any(), any(), any())).thenReturn(app.aaps.pump.atc3.command.Atc3BolusOutcome.NotSent)

        plugin.deliverTreatment(smb(0.5, lastKnownBolusTime = now - 120_000L))

        verify(bolusDelivery, times(1)).bolus(any(), any(), any())
    }

    @Test
    fun `the loop's temporary basal goes through when the pump counted insulin its journal does not explain`() = runTest {
        pumpCountedMoreThanTheJournal(1.2)
        whenever(atc3Manager.setTempBasal(any(), any())).thenReturn(Atc3TbrResult(now, 1.0, 30, null))

        val result = plugin.setTempBasalAbsolute(1.0, 30, validProfile, false, tbrTypeNormal)

        assertThat(result.success).isTrue()
        verify(atc3Manager, times(1)).setTempBasal(any(), any())
    }

    @Test
    fun `the bolus journal is read in front of the loop's temporary basal whatever the count says`() = runTest {
        pumpCountedMoreThanTheJournal(0.0)
        whenever(atc3Manager.setTempBasal(any(), any())).thenReturn(Atc3TbrResult(now, 1.0, 30, null))
        clearInvocations(atc3Manager)

        plugin.setTempBasalAbsolute(1.0, 30, validProfile, false, tbrTypeNormal)

        verify(atc3Manager, times(1)).readBolusHistory()
    }

    @Test
    fun `the loop's temporary basal is refused when a bolus the loop did not know of turns up in front of it`() = runTest {
        pumpCountedMoreThanTheJournal(0.0)
        whenever(atc3HistorySync.reconcileBoluses(any(), any())).thenReturn(
            Atc3HistorySync.ReconcileResult(newestImportedAtMs = now - 60_000L, importedUnits = 1.0)
        )

        val result = plugin.setTempBasalAbsolute(1.0, 30, validProfile, false, tbrTypeNormal)

        assertThat(result.success).isFalse()
        verify(atc3Manager, never()).setTempBasal(any(), any())
    }

    @Test
    fun `a difference the journal did not explain is not read for again on the next tick`() = runTest {
        pumpCountedMoreThanTheJournal(1.2)
        plugin.getPumpStatus("test")
        clearInvocations(atc3Manager)

        plugin.getPumpStatus("test")

        verify(atc3Manager, never()).readBolusHistory()
    }

    @Test
    fun `a microbolus is refused when the journals that would explain the count could not be read`() = runTest {
        pumpCountedMoreThanTheJournal(1.2)
        whenever(atc3Manager.readBolusHistory()).thenReturn(null)

        val result = plugin.deliverTreatment(smb(0.5, lastKnownBolusTime = now - 120_000L))

        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
        verify(bolusDelivery, never()).bolus(any(), any(), any())
    }

    @Test
    fun `a microbolus is refused when the pump's status could not be read at all`() = runTest {
        whenever(atc3Manager.readStatus()).thenReturn(null)

        val result = plugin.deliverTreatment(smb(0.5, lastKnownBolusTime = now - 120_000L))

        assertThat(result.success).isFalse()
        verify(bolusDelivery, never()).bolus(any(), any(), any())
    }

    @Test
    fun `a microbolus goes through when the pump's count and the journal agree`() = runTest {
        pumpCountedMoreThanTheJournal(0.0)
        whenever(bolusDelivery.bolus(any(), any(), any())).thenReturn(app.aaps.pump.atc3.command.Atc3BolusOutcome.NotSent)

        plugin.deliverTreatment(smb(0.5, lastKnownBolusTime = now - 120_000L))

        verify(bolusDelivery, times(1)).bolus(any(), any(), any())
    }

    @Test
    fun `a bolus the user asked for goes through whatever the comparison said`() = runTest {
        pumpCountedMoreThanTheJournal(1.2)
        whenever(bolusDelivery.bolus(any(), any(), any())).thenReturn(app.aaps.pump.atc3.command.Atc3BolusOutcome.NotSent)

        plugin.deliverTreatment(manualBolus(0.5, lastKnownBolusTime = now - 120_000L))

        verify(bolusDelivery, times(1)).bolus(any(), any(), any())
    }

    @Test
    fun `a bolus the user asked for is never taken away from them`() = runTest {
        // The same situation that refuses a microbolus: the user is standing at the phone and the
        // decision is theirs, so it goes through.
        whenever(atc3HistorySync.reconcileBoluses(any(), any())).thenReturn(
            Atc3HistorySync.ReconcileResult(newestImportedAtMs = now - 60_000L)
        )
        whenever(bolusDelivery.bolus(any(), any(), any())).thenReturn(app.aaps.pump.atc3.command.Atc3BolusOutcome.NotSent)

        plugin.deliverTreatment(manualBolus(0.5, lastKnownBolusTime = now - 120_000L))

        verify(bolusDelivery, times(1)).bolus(any(), any(), any())
    }

    @Test
    fun `a microbolus with no decision stamp is still refused by a bolus found in front of it`() = runTest {
        // The stamp only tells a bolus the loop knew from one it did not; a bolus imported now was unknown to it either way.
        pumpAnswersWithCount(10.0)
        whenever(atc3HistorySync.reconcileBoluses(any(), any())).thenReturn(
            Atc3HistorySync.ReconcileResult(newestImportedAtMs = now - 60_000L)
        )

        val result = plugin.deliverTreatment(smb(0.5, lastKnownBolusTime = 0L))

        assertThat(result.success).isFalse()
        verify(bolusDelivery, never()).bolus(any(), any(), any())
    }

    companion object {

        private val tbrTypeNormal = app.aaps.core.interfaces.pump.PumpSync.TemporaryBasalType.NORMAL
    }

    // A temporary basal set or cancelled on the pump is recorded, and nothing is sent back

    /** The pump now runs [rate] for [minutes], or none, whatever AAPS holds. */
    private fun pumpRunsByHand(rate: Double?, minutes: Int = 30) = runBlocking {
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        pumpState.editStatus { it.copy(tbrActive = rate != null) }
        pumpState.editStatus { it.copy(tbrRate = rate ?: 0.0) }
        pumpState.editStatus { it.copy(tbrDurationMinutes = if (rate != null) minutes else 0) }
    }

    /**
     * The driver sends the pump nothing of its own. What somebody set on the keypad is written
     * into AAPS so that the insulin on board is right, and the loop decides on it next cycle;
     * putting it back, or remembering what the loop last asked for, is not the driver's to do.
     */
    @Test
    fun `a temporary basal set by hand is recorded and left alone`() = runTest {
        pumpRunsByHand(3.0)
        whenever(bolusDelivery.bolus(any(), any(), any())).thenReturn(Atc3BolusOutcome.NotSent)

        plugin.getPumpStatus("test")
        plugin.deliverTreatment(manualBolus(0.5, lastKnownBolusTime = now - 600_000L))

        verify(atc3Manager, never()).setTempBasal(any(), any())
        verify(atc3Manager, never()).cancelTempBasal()
        verify(atc3HistorySync, atLeastOnce()).onStatus(eq(false), eq(true), eq(3.0), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a temporary basal cancelled by hand stays cancelled`() = runTest {
        pumpRunsByHand(null)

        plugin.getPumpStatus("test")

        verify(atc3Manager, never()).setTempBasal(any(), any())
        verify(atc3HistorySync, atLeastOnce()).onStatus(eq(false), eq(false), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    /**
     * The verdict is taken before anything of the read is written: what the pump did on its own
     * since the window began is measured against the journal the loop decided on. Written first, a
     * temporary basal set by hand would already be in the journal by the time it is compared,
     * and the comparison would never see it.
     */
    @Test
    fun `on a tick the count is compared before the pump's state is written into AAPS`() = runTest {
        pumpCountedMoreThanTheJournal(0.0)
        clearInvocations(aapsJournal, atc3HistorySync)

        plugin.getPumpStatus("test")

        val order = inOrder(aapsJournal, atc3HistorySync)
        order.verify(aapsJournal).insulinBetween(any(), any(), any())
        order.verify(atc3HistorySync).onStatus(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `before the loop's temporary basal the count is compared before the pump's state is written`() = runTest {
        pumpCountedMoreThanTheJournal(0.0)
        whenever(atc3Manager.setTempBasal(any(), any())).thenReturn(Atc3TbrResult(now, 1.0, 30, null))
        clearInvocations(aapsJournal, atc3HistorySync)

        plugin.setTempBasalAbsolute(1.0, 30, validProfile, false, tbrTypeNormal)

        val order = inOrder(aapsJournal, atc3HistorySync)
        order.verify(aapsJournal).insulinBetween(any(), any(), any())
        order.verify(atc3HistorySync).onStatus(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `before a bolus the count is compared before the pump's state is written`() = runTest {
        pumpCountedMoreThanTheJournal(0.0)
        whenever(bolusDelivery.bolus(any(), any(), any())).thenReturn(Atc3BolusOutcome.NotSent)
        clearInvocations(aapsJournal, atc3HistorySync)

        plugin.deliverTreatment(smb(0.5, lastKnownBolusTime = now - 120_000L))

        val order = inOrder(aapsJournal, atc3HistorySync)
        order.verify(aapsJournal).insulinBetween(any(), any(), any())
        order.verify(atc3HistorySync).onStatus(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a stopped pump is sent no bolus at all`() = runTest {
        // An SMB decided before a pause must not go out to a stopped pump.
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
        pumpState.editStatus { it.copy(suspended = true) }

        val smbResult = plugin.deliverTreatment(smb(0.5, lastKnownBolusTime = now - 600_000L))
        val manualResult = plugin.deliverTreatment(manualBolus(0.5, lastKnownBolusTime = 0L))

        assertThat(smbResult.success).isFalse()
        assertThat(manualResult.success).isFalse()
        verify(bolusDelivery, never()).bolus(any(), any(), any())
    }

    // No Bluetooth password entered in AAPS

    @Test
    fun `with no password entered the loop does not run`() = runTest {
        whenever(atc3Connection.isPasswordEntered).thenReturn(false)

        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isFalse()
    }

    // A refill is a beginning

    @Test
    fun `a reservoir that went up starts the comparison anew, by the pump's journals`() = runTest {
        pumpAnswersWithCount(10.0)
        runBlocking {
            whenever(aapsJournal.insulinBetween(any(), any(), any())).thenReturn(Atc3JournalArithmetic.Breakdown(0.0, 0.0, 0.0))
        }
        pumpState.editStatus { it.copy(reservoirUnits = 27.55) }
        // The first reads after a start have nothing to compare against and the clock to set, and
        // read the journals; after them a read that agrees with the journal reads nothing.
        plugin.getPumpStatus("test")
        plugin.getPumpStatus("test")
        clearInvocations(atc3Manager)
        plugin.getPumpStatus("test")
        verify(atc3Manager, never()).readBolusHistory()

        pumpState.editStatus { it.copy(reservoirUnits = 304.775) }
        plugin.getPumpStatus("test")

        verify(atc3Manager, atLeastOnce()).readBolusHistory()
    }

    // A pump that has stopped answering

    /** The pump's last answer before a silence, on the day of [now] whatever the zone the test runs in. */
    private fun lastAnswer() = maxOf(now - 40 * 60_000L, Atc3DayClock.dayStartOf(now) + 1_000L)

    /** A status read at [now] with the pump's count at [counter], and journals that answer. */
    private fun pumpAnswersWithCount(counter: Double) {
        pumpState.editStatus(readAtMs = now) { it.copy(snapshotTime = now, deliveredTodayUnits = counter) }
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
    }

    /** A paired pump whose last status read was this long ago, with that answer on disk as a tick leaves it. */
    private fun pumpSilentFor(minutes: Long) {
        startPlugin()
        whenever(preferences.get(Atc3StringKey.Atc3SerialNumber)).thenReturn("A1B2C3")
        val answered = now - minutes * 60_000L
        pumpState.lastConnection = answered
        store.update { it.copy(lastAnswer = Atc3LinkWatch.Stop(answered, 10.0)) }
    }

    @Test
    fun `a pump that answered ten minutes ago is left alone`() = runTest {
        pumpSilentFor(10)

        linkKeeper.checkLink()

        verify(uiInteraction, never()).addNotificationWithSound(any(), any(), any(), anyOrNull())
        verify(atc3HistorySync, never()).recordLinkStop(any(), any())
    }

    @Test
    fun `a quarter of an hour without an answer is told once, aloud, and nothing is held yet`() = runTest {
        pumpSilentFor(16)

        linkKeeper.checkLink()
        linkKeeper.checkLink()

        verify(uiInteraction, times(1)).addNotificationWithSound(eq(Notification.PUMP_UNREACHABLE), any(), eq(Notification.URGENT), anyOrNull())
        assertThat(store.state.linkStop).isNull()
        verify(atc3HistorySync, never()).recordLinkStop(any(), any())
        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isTrue()
    }

    @Test
    fun `half an hour without an answer holds the pump stopped from its last answer and stops the loop`() = runTest {
        pumpSilentFor(31)
        val answered = now - 31 * 60_000L

        linkKeeper.checkLink()

        assertThat(store.state.linkStop).isEqualTo(Atc3LinkWatch.Stop(answered, 10.0))
        // No basal from the last answer on, as far as the next telling.
        verify(atc3HistorySync).recordLinkStop(answered, 31 * 60_000L + Atc3LinkWatch.ALARM_AFTER_MS)
        verify(uiInteraction, times(1)).addNotificationWithSound(eq(Notification.PUMP_UNREACHABLE), any(), eq(Notification.URGENT), anyOrNull())
        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isFalse()
    }

    @Test
    fun `the silence asks the queue for a status, which is what finds the pump`() = runTest {
        pumpSilentFor(16)

        linkKeeper.checkLink()

        verify(commandQueue, times(1)).readStatus(any(), anyOrNull())
    }

    @Test
    fun `a pump held stopped for want of an answer stops the loop`() = runTest {
        store.update { it.copy(linkStop = Atc3LinkWatch.Stop(lastAnswer(), 10.0)) }

        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isFalse()
    }

    @Test
    fun `the answer that ends the stop closes its row at the answer, writes nothing else, and lets the loop go on`() = runTest {
        val from = lastAnswer()
        store.update { it.copy(linkStop = Atc3LinkWatch.Stop(from, 10.0)) }
        // The count went on by 3 U: that shows in the window, not in the rows.
        pumpAnswersWithCount(13.0)

        plugin.getPumpStatus("test")

        verify(atc3HistorySync).recordLinkStop(from, now - from)
        verify(atc3HistorySync, never()).tbrStartedByAaps(any(), any(), any(), anyOrNull(), any())
        assertThat(store.state.linkStop).isNull()
        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isTrue()
    }

    @Test
    fun `every status read is kept as the answer a silence would be counted from`() = runTest {
        pumpAnswersWithCount(13.0)

        plugin.getPumpStatus("test")

        assertThat(store.state.lastAnswer).isEqualTo(Atc3LinkWatch.Stop(now, 13.0))
    }

    // A pump that owes insulin and does not count any

    /** The status read [tick] ticks of five minutes after [now], with the count standing at [counter]. */
    private fun pumpCounts(tick: Int, counter: Double) {
        val readAt = now + tick * 300_000L
        pumpState.editStatus(readAtMs = readAt) { it.copy(snapshotTime = readAt, deliveredTodayUnits = counter) }
        whenever(atc3Manager.readStatus()).thenAnswer { pumpState.currentCard() }
    }

    /** The journal owes 1 U/h over any interval it is asked about. */
    private fun journalOwesOneUnitAnHour() = runBlocking {
        whenever(aapsJournal.insulinBetween(any(), any(), any())).thenAnswer {
            val from = it.getArgument<Long>(0)
            val to = it.getArgument<Long>(1)
            Atc3JournalArithmetic.Breakdown(0.0, 0.0, (to - from) / 3_600_000.0)
        }
    }

    @Test
    fun `a count that stands still while insulin is owed is told at two ticks and stops the loop at three`() = runTest {
        journalOwesOneUnitAnHour()
        pumpCounts(0, 10.0)
        plugin.getPumpStatus("test")
        pumpCounts(1, 10.0)
        plugin.getPumpStatus("test")
        verify(uiInteraction, never()).addNotification(eq(Notification.PUMP_ERROR), any(), eq(Notification.URGENT))

        pumpCounts(2, 10.0)
        plugin.getPumpStatus("test")

        verify(uiInteraction, times(1)).addNotification(eq(Notification.PUMP_ERROR), any(), eq(Notification.URGENT))
        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isTrue()

        pumpCounts(3, 10.0)
        plugin.getPumpStatus("test")

        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isFalse()
    }

    @Test
    fun `the count moving again lets the loop go on`() = runTest {
        journalOwesOneUnitAnHour()
        for (tick in 0..3) {
            pumpCounts(tick, 10.0)
            plugin.getPumpStatus("test")
        }
        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isFalse()

        pumpCounts(4, 10.1)
        plugin.getPumpStatus("test")

        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isTrue()
    }
}
