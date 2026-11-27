package app.aaps.pump.apex.aaps

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.IDs
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/** The AAPS rows of the day, counted up to the heartbeat's minute and not at it. */
class AapsTodayTest {

    private val persistenceLayer: PersistenceLayer = mock {
        on { getBolusesFromTimeToTime(any(), any(), any()) } doReturn emptyList()
        on { getTemporaryBasalsActiveAt(any()) } doReturn emptyList()
        on { getTemporaryBasalsStartingFromTimeToTime(any(), any(), any()) } doReturn emptyList()
    }
    private val profile: EffectiveProfile = mock { on { getBasal(any<Long>()) } doReturn 1.0 }
    private val profileFunction: ProfileFunction = mock { on { getProfile(anyOrNull<Long>()) } doReturn profile }
    private val today = AapsToday(persistenceLayer, profileFunction, mock<DateUtil>())

    @Test
    fun `a bolus row at the heartbeat's minute belongs to what comes after - the count stops a millisecond before it`() {
        runBlocking { today.count(now = 240_000L, from = 120_000L) }
        verifyBlocking(persistenceLayer) { getBolusesFromTimeToTime(120_000L, 239_999L, true) }
    }

    private fun bolus(atMs: Long, ids: IDs) = BS(timestamp = atMs, utcOffset = 0L, amount = 1.0, type = BS.Type.NORMAL, ids = ids, iCfg = mock())

    @Test
    fun `the newest bolus is the newest with a key that stays - a row of ours that waits for its record is not one`() {
        val waiting = bolus(3_000L, IDs(temporaryId = 7L, pumpSerial = "12345678"))
        val fromPump = bolus(2_000L, IDs(pumpId = 5L, pumpSerial = "12345678"))
        val byHand = bolus(1_000L, IDs())
        // The rows come in the order they were made: a row laid over itself later keeps its old place.
        runBlocking { whenever(persistenceLayer.getBolusesFromTime(500L, true)).thenReturn(listOf(byHand, fromPump, waiting)) }
        assertEquals(2_000L, runBlocking { today.newestBolusMs(fromMs = 500L) })
        runBlocking { whenever(persistenceLayer.getBolusesFromTime(500L, true)).thenReturn(listOf(fromPump, byHand, waiting)) }
        assertEquals(2_000L, runBlocking { today.newestBolusMs(fromMs = 500L) })
        runBlocking { whenever(persistenceLayer.getBolusesFromTime(500L, true)).thenReturn(listOf(waiting, byHand)) }
        assertEquals(1_000L, runBlocking { today.newestBolusMs(fromMs = 500L) })
    }

    @Test
    fun `the rows of the pump by their key - not those of another pump, not ours that wait for their record`() {
        val fromPump = bolus(2_000L, IDs(pumpId = 5L, pumpSerial = "12345678"))
        val otherPump = bolus(2_500L, IDs(pumpId = 6L, pumpSerial = "87654321"))
        val waiting = bolus(3_000L, IDs(temporaryId = 7L, pumpSerial = "12345678"))
        runBlocking { whenever(persistenceLayer.getBolusesFromTime(500L, true)).thenReturn(listOf(fromPump, otherPump, waiting, bolus(1_000L, IDs()))) }
        assertEquals(mapOf(5L to 2_000L), runBlocking { today.pumpRowsMs("12345678", 500L) })
    }
}
