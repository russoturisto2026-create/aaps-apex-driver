package app.aaps.pump.apex.account

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking

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
}
