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
import app.aaps.pump.atc3.clock.Atc3ClockWatch
import app.aaps.pump.atc3.manager.Atc3Manager
import app.aaps.pump.atc3.protocol.Atc3BolusHistory
import app.aaps.pump.atc3.protocol.Atc3BolusRecord
import app.aaps.pump.atc3.protocol.Atc3ResponseFrame
import app.aaps.pump.atc3.protocol.Atc3StatusV1
import app.aaps.pump.atc3.state.Atc3PumpState
import app.aaps.pump.atc3.store.Atc3Store
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * A slot of the bolus journal the pump did not write: how it is told from a clock put back, how it is
 * kept out of the rows, and what is written in its place.
 */
class Atc3JournalFaultTest : TestBaseWithProfile() {

    @Mock lateinit var pumpSync: PumpSync
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var atc3Manager: Atc3Manager

    private val rows = ArrayList<BS>()
    private lateinit var pumpState: Atc3PumpState
    private lateinit var sync: Atc3HistorySync
    private lateinit var fault: Atc3JournalFault

    private val serial = "12345678"

    /** The oldest record the ring still holds, a day back. */
    private val ringOldestMs get() = now - 24 * 60 * 60_000L

    @BeforeEach
    fun setup() {
        pumpState = Atc3PumpState()
        pumpState.serialNumber = serial
        whenever(rh.gs(anyInt())).thenReturn("mocked resource")
        whenever(rh.gs(anyInt(), anyString(), anyDouble())).thenReturn("mocked resource")
        whenever(preferences.get(LongNonKey.ActivePumpChangeTimestamp)).thenReturn(0L)
        whenever(pumpSync.verifyPumpIdentification(PumpType.ATC3, serial)).thenReturn(true)
        whenever(pumpSync.syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())).thenAnswer {
            rows.add(BS(timestamp = it.getArgument(0), amount = it.getArgument(1), type = it.getArgument(2), ids = IDs(pumpId = it.getArgument(3), pumpSerial = it.getArgument(5))))
            true
        }
        whenever(pumpSync.insertTherapyEventIfNewWithTimestamp(any(), any(), anyOrNull(), anyOrNull(), any(), any())).thenReturn(true)
        whenever(persistenceLayer.getBolusesFromTimeIncludingInvalid(any(), any())).thenAnswer { Single.just(rows.toList()) }
        val trace = Atc3Trace(aapsLogger, preferences)
        val registration = Atc3PumpRegistration(aapsLogger, rh, pumpSync, pumpState)
        val store = Atc3Store(aapsLogger, preferences, uiInteraction, rh)
        sync = Atc3HistorySync(
            aapsLogger, rh, uiInteraction, preferences, store, pumpSync, persistenceLayer, dateUtil, pumpState,
            Atc3ClockWatch(), trace, registration
        )
        fault = Atc3JournalFault(aapsLogger, rh, dateUtil, uiInteraction, pumpSync, atc3Manager, sync, pumpState, trace)
    }

    /** A record at [index] of the answer stamped at the phone's [atMs], second 59 of its minute. */
    private fun record(index: Int, atMs: Long, raw: Int, delivered: Int = raw): Atc3BolusRecord {
        val stamp = Math.floorDiv(atMs, 60_000L) * 60_000L + 59_000L
        return Atc3BolusRecord(
            index = index,
            timestamp = stamp,
            pumpClockUtcSeconds = Atc3StatusV1.wallClockUtcSeconds(stamp),
            rawRequested = raw,
            rawDelivered = delivered
        )
    }

    /** The short answer: the faulty slot in front, holding a record two days old, then the newest real records. */
    private fun shortAnswer(faultAgeMs: Long = 2 * 24 * 60 * 60_000L) = Atc3BolusHistory(
        listOf(
            record(0, now - faultAgeMs, 20),
            record(1, now - 2 * 60_000L, 20),
            record(2, now - 6 * 60_000L, 12),
            record(3, now - 30 * 60_000L, 40)
        ),
        recordCount = 128
    )

    /** The whole journal: the same slot at its own index, and the oldest record of the ring a day back. */
    private fun wholeJournal(faultAgeMs: Long = 2 * 24 * 60 * 60_000L) = Atc3BolusHistory(
        listOf(
            record(0, now - faultAgeMs, 20),
            record(1, now - 2 * 60_000L, 20),
            record(2, now - 6 * 60_000L, 12),
            record(3, now - 30 * 60_000L, 40),
            record(4, ringOldestMs, 8)
        ),
        recordCount = 5
    )

    /** The faults found in the short answer, with the whole journal confirming them. */
    private fun found(): List<JournalFault> {
        whenever(atc3Manager.readFullBolusHistory()).thenReturn(wholeJournal())
        return fault.screen(shortAnswer()).found
    }

    private fun frame(hex: String) = Atc3BolusRecord.decode(Atc3ResponseFrame(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))!!

    // Telling the slot

    @Test
    fun `a record dated behind the record next older in the journal is a suspect, in order is not`() {
        val inOrder = listOf(record(0, now - 60_000L, 20), record(1, now - 5 * 60_000L, 12))
        assertThat(Atc3JournalFault.suspects(inOrder)).isEmpty()

        val suspects = Atc3JournalFault.suspects(shortAnswer().records)
        assertThat(suspects).hasSize(1)
        assertThat(suspects.single().first.index).isEqualTo(0)
        assertThat(suspects.single().second.index).isEqualTo(1)
    }

    @Test
    fun `an empty slot is no record and no suspect`() {
        val records = listOf(record(0, now - 60_000L, 20), record(1, 0L, 0), record(2, now - 5 * 60_000L, 12))
        assertThat(Atc3JournalFault.suspects(records)).isEmpty()
    }

    @Test
    fun `a suspect dated before every other record of the whole journal is the slot the ring dropped`() {
        val suspect = shortAnswer().records[0]
        assertThat(Atc3JournalFault.confirm(listOf(suspect), wholeJournal())).containsExactly(suspect)
    }

    @Test
    fun `a clock put back by minutes is not a fault`() {
        val putBack = shortAnswer(faultAgeMs = 7 * 60_000L)
        val suspect = putBack.records[0]
        assertThat(Atc3JournalFault.suspects(putBack.records).single().first).isEqualTo(suspect)
        assertThat(Atc3JournalFault.confirm(listOf(suspect), wholeJournal(faultAgeMs = 7 * 60_000L))).isEmpty()
    }

    @Test
    fun `the pump's own frames tell the slot`() {
        // The short answer two seconds after the stop: the slot in front holds a record two days old.
        val short = listOf(
            frame("AA1680A321001A0A0702103B1400140000000000C9DC"),
            frame("AA1680A321011A0A0905343B1400140000000000BBC0")
        )
        // The whole journal later: the same bytes at their own index, and the oldest record of the ring a day back.
        val whole = Atc3BolusHistory(
            listOf(
                frame("AA1680A3015B1A0A0905383B1400140000000000B81C"),
                frame("AA1680A3015C1A0A0702103B14001400000000008C3D"),
                frame("AA1680A3015D1A0A0905343B1400140000000000FE21"),
                frame("AA1680A3017F1A0A0814203B01000100000000007271")
            ),
            recordCount = 4
        )
        val suspects = Atc3JournalFault.suspects(short)
        assertThat(suspects).hasSize(1)
        assertThat(suspects.single().first.requestedUnits).isEqualTo(0.5)
        assertThat(Atc3JournalFault.confirm(listOf(suspects.single().first), whole)).hasSize(1)
    }

    // Keeping it out

    @Test
    fun `the slot is taken out of the answer, remembered, and not looked up again`() {
        whenever(atc3Manager.readFullBolusHistory()).thenReturn(wholeJournal())

        val first = fault.screen(shortAnswer())
        assertThat(first.found).hasSize(1)
        assertThat(first.history.records.map { it.index }).containsExactly(1, 2, 3)
        assertThat(sync.knownFaults()).hasSize(1)
        assertThat(sync.unsettledFaults()).hasSize(1)

        val second = fault.screen(shortAnswer())
        assertThat(second.found).isEmpty()
        assertThat(second.history.records.map { it.index }).containsExactly(1, 2, 3)
        assertThat(sync.knownFaults()).hasSize(1)
        verify(atc3Manager, times(1)).readFullBolusHistory()
    }

    @Test
    fun `a clock put back leaves the answer whole, remembers nothing, and is not looked up again`() {
        whenever(atc3Manager.readFullBolusHistory()).thenReturn(wholeJournal(faultAgeMs = 7 * 60_000L))

        val screened = fault.screen(shortAnswer(faultAgeMs = 7 * 60_000L))
        assertThat(screened.found).isEmpty()
        assertThat(screened.history.records).hasSize(4)
        assertThat(sync.knownFaults()).isEmpty()

        fault.screen(shortAnswer(faultAgeMs = 7 * 60_000L))
        verify(atc3Manager, times(1)).readFullBolusHistory()
    }

    @Test
    fun `when the whole journal does not answer, the suspect is held out and offered again`() {
        whenever(atc3Manager.readFullBolusHistory()).thenReturn(null)

        val screened = fault.screen(shortAnswer())
        assertThat(screened.found).isEmpty()
        assertThat(screened.history.records.map { it.index }).containsExactly(1, 2, 3)
        assertThat(sync.knownFaults()).isEmpty()

        fault.screen(shortAnswer())
        verify(atc3Manager, times(2)).readFullBolusHistory()
    }

    // What is written in its place

    @Test
    fun `our bolus waiting takes the fault and is written from what was last seen of it`() = runTest {
        val acceptedAt = now - 20_000L
        sync.expect(acceptedAt, 2.0, BS.Type.NORMAL)
        sync.seen(acceptedAt, 0.45)

        val settled = fault.settleOwn(found())

        assertThat(settled.confirmed).containsExactly(acceptedAt, 0.45)
        assertThat(settled.left).isEmpty()
        assertThat(sync.hasExpectedBolus()).isFalse()
        assertThat(sync.unsettledFaults()).isEmpty()
        val row = rows.single()
        assertThat(row.amount).isEqualTo(0.45)
        assertThat(row.type).isEqualTo(BS.Type.NORMAL)
        assertThat(row.timestamp).isEqualTo(Atc3BolusReconciler.minuteStartOf(acceptedAt))
        verify(uiInteraction).addNotification(eq(Notification.PUMP_SYNC_ERROR), any(), eq(Notification.URGENT))
        verify(pumpSync).insertTherapyEventIfNewWithTimestamp(eq(acceptedAt), eq(TE.Type.NOTE), any(), anyOrNull(), any(), eq(serial))
    }

    @Test
    fun `a bolus never seen delivering keeps waiting, and the fault goes to the count`() = runTest {
        sync.expect(now - 20_000L, 2.0, BS.Type.NORMAL)

        val settled = fault.settleOwn(found())

        assertThat(settled.confirmed).isEmpty()
        assertThat(settled.left).hasSize(1)
        assertThat(sync.hasExpectedBolus()).isTrue()
        assertThat(rows).isEmpty()
    }

    @Test
    fun `a bolus accepted before the record the slot follows does not take the fault`() = runTest {
        val acceptedAt = now - 10 * 60_000L
        sync.expect(acceptedAt, 2.0, BS.Type.NORMAL)
        sync.seen(acceptedAt, 0.45)

        val settled = fault.settleOwn(found())

        assertThat(settled.confirmed).isEmpty()
        assertThat(settled.left).hasSize(1)
        assertThat(sync.hasExpectedBolus()).isTrue()
    }

    @Test
    fun `a refused row leaves the bolus and the fault for the next read`() = runTest {
        whenever(pumpSync.syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())).thenReturn(false)
        val acceptedAt = now - 20_000L
        sync.expect(acceptedAt, 2.0, BS.Type.NORMAL)
        sync.seen(acceptedAt, 0.45)

        val settled = fault.settleOwn(found())

        assertThat(settled.confirmed).isEmpty()
        assertThat(settled.left).isEmpty()
        assertThat(sync.hasExpectedBolus()).isTrue()
        assertThat(sync.unsettledFaults()).hasSize(1)
        verify(uiInteraction, never()).addNotification(anyInt(), any(), anyInt())
    }

    @Test
    fun `with no bolus of ours waiting, the count says what went in, at the read that holds it`() = runTest {
        val left = fault.settleOwn(found()).left
        assertThat(left).hasSize(1)

        val notedAt = fault.settleByCount(left, 0.46, now, 10.0)

        assertThat(notedAt).isEqualTo(now)
        val row = rows.single()
        assertThat(row.amount).isEqualTo(0.45)
        assertThat(row.type).isEqualTo(BS.Type.NORMAL)
        assertThat(row.timestamp).isEqualTo(Atc3BolusReconciler.minuteStartOf(now))
        assertThat(sync.unsettledFaults()).isEmpty()
        verify(uiInteraction).addNotification(eq(Notification.PUMP_SYNC_ERROR), any(), eq(Notification.URGENT))
    }

    @Test
    fun `less than a pulse beyond the journal writes nothing and tells the user the amount is unknown`() = runTest {
        val left = fault.settleOwn(found()).left

        val notedAt = fault.settleByCount(left, 0.01, now, 10.0)

        // Nothing placed, nothing for the loop to decide again: a mark would refuse every SMB until a real bolus lands.
        assertThat(notedAt).isNull()
        assertThat(rows).isEmpty()
        assertThat(sync.unsettledFaults()).isEmpty()
        verify(uiInteraction).addNotification(eq(Notification.PUMP_SYNC_ERROR), any(), eq(Notification.URGENT))
        verify(pumpSync, never()).syncBolusWithPumpId(any(), any(), anyOrNull(), any(), any(), any())
    }

    @Test
    fun `more than one bolus can be is not written as one`() = runTest {
        val left = fault.settleOwn(found()).left

        val notedAt = fault.settleByCount(left, 12.0, now, 10.0)

        assertThat(notedAt).isNull()
        assertThat(rows).isEmpty()
        verify(uiInteraction).addNotification(eq(Notification.PUMP_SYNC_ERROR), any(), eq(Notification.URGENT))
    }
}
