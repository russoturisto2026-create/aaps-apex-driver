package app.aaps.pump.atc3.store

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.basal.Atc3BasalPeriod
import app.aaps.pump.atc3.history.ActiveTbr
import app.aaps.pump.atc3.history.Atc3HistoryLedger
import app.aaps.pump.atc3.history.ExpectedBolus
import app.aaps.pump.atc3.history.LearnedBolus
import app.aaps.pump.atc3.keys.Atc3StringNonKey
import app.aaps.pump.atc3.link.Atc3LinkWatch
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.TimeZone

class Atc3StoreTest : TestBase() {

    @Mock lateinit var preferences: Preferences
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var rh: ResourceHelper

    private fun store() = Atc3Store(aapsLogger, preferences, uiInteraction, rh)

    private val ledger = Atc3HistoryLedger(serial = "11223344")
        .withWatermark(1_000_000L, 1_700_000_000_000L)
        .withExpected(ExpectedBolus(1_700L, 1.0, BS.Type.SMB, startUtcSeconds = 61L))
        .withActiveTbr(ActiveTbr(2000L, 1_500L, 1.25, ours = true, ownDurationMs = 3_600_000L, pumpStartUtcSeconds = 1_500L, pumpStartMs = 1_400L))

    private val state = Atc3StoredState(
        ledger = ledger,
        learned = listOf(LearnedBolus(1_700_000_200_000L, 0.35, 1_700_000_100_000L)),
        basalPeriod = Atc3BasalPeriod.State(
            Atc3BasalPeriod.Mark(1_700_001_800_000L, 21.625, 1_700_001_805_000L),
            Atc3BasalPeriod.Closed(Atc3BasalPeriod.Mark(1_700_000_000_000L, 20.0, 1_700_000_004_000L), 1_700_001_800_000L, 1_700_001_805_000L)
        ),
        lastAnswer = Atc3LinkWatch.Stop(1_700_001_800_000L, 21.625),
        linkStop = Atc3LinkWatch.Stop(1_700_000_000_000L, 20.0)
    )

    @Test
    fun `the state comes back as it went in`() {
        assertThat(Atc3Store.readParts(Atc3Store.encode(state)).state).isEqualTo(state)
        assertThat(Atc3Store.readParts(Atc3Store.encode(Atc3StoredState())).state).isEqualTo(Atc3StoredState())
    }

    @Test
    fun `a start worked out from the clock is written, not worked out again on reading`() {
        // A default left out of the document would be recomputed through the timezone in force on reading.
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
            val expected = ExpectedBolus(1_700_000_000_000L, 1.0, BS.Type.SMB)
            val text = Atc3Store.encode(Atc3StoredState(ledger = Atc3HistoryLedger(serial = "11223344").withExpected(expected)))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Singapore"))
            assertThat(Atc3Store.readParts(text).state.ledger.expected.single().startUtcSeconds).isEqualTo(expected.startUtcSeconds)
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun `a field the version does not know is passed over`() {
        val text = Atc3Store.encode(state).replaceFirst("{", "{\"later\":1,")
        assertThat(Atc3Store.readParts(text).state).isEqualTo(state)
    }

    @Test
    fun `the earlier keys are moved into the document once, and removed`() {
        whenever(preferences.getIfExists(Atc3StringNonKey.HistoryLedger)).thenReturn(
            "atc3-ledger-v1\nw|1000000|1|11|11223344|1700000000000|0|0\np|42|1700|40|20|SMB|||2|61"
        )
        whenever(preferences.getIfExists(Atc3StringNonKey.CheckLearned)).thenReturn("1700000200000;0.35;1700000100000")
        whenever(preferences.getIfExists(Atc3StringNonKey.BasalPeriod))
            .thenReturn("1700001800000;21.625;1700001805000|1700000000000;20.0;1700000004000;1700001800000;1700001805000;1;0")
        whenever(preferences.getIfExists(Atc3StringNonKey.LastAnswer)).thenReturn("1700001800000;21.625")
        whenever(preferences.getIfExists(Atc3StringNonKey.LinkStop)).thenReturn("1700000000000;20.0")

        val moved = store().state

        assertThat(moved.ledger.serial).isEqualTo("11223344")
        assertThat(moved.ledger.importFromUtcSeconds).isEqualTo(1_000_000L)
        // What earlier versions kept of bolus records is not carried over: AAPS's rows say which records it has.
        assertThat(moved.ledger.expected).isEmpty()
        assertThat(moved.learned).isEqualTo(state.learned)
        assertThat(moved.basalPeriod).isEqualTo(state.basalPeriod)
        assertThat(moved.lastAnswer).isEqualTo(state.lastAnswer)
        assertThat(moved.linkStop).isEqualTo(state.linkStop)
        val written = argumentCaptor<String>()
        verify(preferences).put(eq(Atc3StringNonKey.State), written.capture())
        assertThat(Atc3Store.readParts(written.firstValue).state).isEqualTo(moved)
        for (key in listOf(
            Atc3StringNonKey.HistoryLedger, Atc3StringNonKey.CheckLearned, Atc3StringNonKey.BasalPeriod,
            Atc3StringNonKey.LastAnswer, Atc3StringNonKey.LinkStop
        )) verify(preferences).remove(key)
    }

    @Test
    fun `a document with no earlier keys beside it is read as it is`() {
        whenever(preferences.getIfExists(Atc3StringNonKey.State)).thenReturn(Atc3Store.encode(state))

        assertThat(store().state).isEqualTo(state)
        verify(preferences, never()).put(any<Atc3StringNonKey>(), any<String>())
        verify(preferences, never()).remove(any<Atc3StringNonKey>())
        verify(uiInteraction, never()).addNotification(any(), any(), any())
    }

    @Test
    fun `an earlier key written again is the newer part, and the parts it does not hold stay`() {
        // An earlier build run after the move, or an erase cut short: only the keys that hold something speak.
        whenever(preferences.getIfExists(Atc3StringNonKey.State)).thenReturn(Atc3Store.encode(state))
        whenever(preferences.getIfExists(Atc3StringNonKey.LastAnswer)).thenReturn("1700009000000;30.0")

        val taken = store().state

        assertThat(taken).isEqualTo(state.copy(lastAnswer = Atc3LinkWatch.Stop(1_700_009_000_000L, 30.0)))
        val written = argumentCaptor<String>()
        verify(preferences).put(eq(Atc3StringNonKey.State), written.capture())
        assertThat(Atc3Store.readParts(written.firstValue).state).isEqualTo(taken)
        verify(preferences).remove(Atc3StringNonKey.LastAnswer)
    }

    @Test
    fun `the ledger key is erased last`() {
        // An erase cut short then leaves the ledger, not the parts around it.
        whenever(preferences.getIfExists(Atc3StringNonKey.LastAnswer)).thenReturn("1700009000000;30.0")

        store().state

        val order = inOrder(preferences)
        order.verify(preferences).remove(Atc3StringNonKey.LinkStop)
        order.verify(preferences).remove(Atc3StringNonKey.HistoryLedger)
    }

    @Test
    fun `a copy already kept aside is not written over`() {
        whenever(preferences.getIfExists(Atc3StringNonKey.State)).thenReturn("{not json")
        whenever(preferences.getIfExists(Atc3StringNonKey.StateSetAside)).thenReturn("{the first")

        store().state

        verify(preferences, never()).put(eq(Atc3StringNonKey.StateSetAside), any<String>())
    }

    @Test
    fun `nothing stored anywhere is an empty state and nothing written`() {
        assertThat(store().state).isEqualTo(Atc3StoredState())
        verify(preferences, never()).put(eq(Atc3StringNonKey.State), any<String>())
    }

    @Test
    fun `an unreadable document is kept aside and told, not written over`() {
        whenever(preferences.getIfExists(Atc3StringNonKey.State)).thenReturn("{not json")
        val store = store()

        assertThat(store.state).isEqualTo(Atc3StoredState())
        store.update { it.copy(lastAnswer = state.lastAnswer) }

        verify(preferences).put(Atc3StringNonKey.StateSetAside, "{not json")
        verify(preferences, never()).put(eq(Atc3StringNonKey.StateSetAside), eq(Atc3Store.encode(Atc3StoredState(lastAnswer = state.lastAnswer))))
        verify(uiInteraction, times(1)).addNotification(any(), anyOrNull(), any())
    }

    @Test
    fun `a part that does not read loses only that part`() {
        // A bolus type this version does not know: the ledger goes, the rest stays.
        val text = Atc3Store.encode(state).replace("\"SMB\"", "\"LATER\"")

        val read = Atc3Store.readParts(text)

        assertThat(read.lost).containsExactly("ledger")
        assertThat(read.state.ledger).isEqualTo(Atc3HistoryLedger())
        assertThat(read.state.copy(ledger = state.ledger)).isEqualTo(state)
    }

    @Test
    fun `a part that does not read keeps the document aside, and what read is used`() {
        whenever(preferences.getIfExists(Atc3StringNonKey.State))
            .thenReturn(Atc3Store.encode(state).replace("\"SMB\"", "\"LATER\""))

        val read = store().state

        assertThat(read.learned).isEqualTo(state.learned)
        verify(preferences).put(eq(Atc3StringNonKey.StateSetAside), any<String>())
        verify(uiInteraction, times(1)).addNotification(any(), anyOrNull(), any())
    }

    @Test
    fun `a document from a newer version is kept aside`() {
        val newer = Atc3Store.encode(state).replaceFirst("\"version\":1", "\"version\":2")
        whenever(preferences.getIfExists(Atc3StringNonKey.State)).thenReturn(newer)

        assertThat(store().state).isEqualTo(state)
        verify(preferences).put(Atc3StringNonKey.StateSetAside, newer)
    }

    @Test
    fun `a document written before it carried a version is version 1`() {
        val unversioned = Atc3Store.encode(state).replaceFirst("\"version\":1,", "")

        assertThat(Atc3Store.readParts(unversioned).version).isEqualTo(1)
        assertThat(Atc3Store.readParts(unversioned).lost).isEmpty()
    }

    @Test
    fun `a state that cannot be written is not taken`() {
        val store = store()

        assertThrows<SerializationException> {
            store.update { it.copy(learned = listOf(LearnedBolus(1L, Double.NaN, 1L))) }
        }

        assertThat(store.state).isEqualTo(Atc3StoredState())
    }

    @Test
    fun `a change is written whole, and a change to nothing is not written`() {
        val store = store()

        store.update { it.copy(lastAnswer = state.lastAnswer) }
        store.update { it.copy(lastAnswer = state.lastAnswer) }

        val written = argumentCaptor<String>()
        verify(preferences, times(1)).put(eq(Atc3StringNonKey.State), written.capture())
        assertThat(Atc3Store.readParts(written.firstValue).state).isEqualTo(Atc3StoredState(lastAnswer = state.lastAnswer))
    }
}
