package app.aaps.pump.atc3.history

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.Profile
import app.aaps.pump.atc3.Atc3Pump
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Calendar

/**
 * Which moment the scheduled rate is read at.
 *
 * The comparison's whole worth is that the two sides are worked out over the same interval. The
 * rate the profile holds is read at moments of the reader's choosing, and a moment outside the
 * interval is a rate that was never running in it.
 */
class Atc3AapsJournalTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer

    private lateinit var journal: Atc3AapsJournal
    private lateinit var profile: Profile

    private val serial = "12345678"

    /** A local moment today, so the half hour boundaries fall where the code puts them. */
    private fun at(hour: Int, minute: Int, second: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, second)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @BeforeEach
    fun setup() {
        val atc3Pump = Atc3Pump()
        atc3Pump.serialNumber = serial
        journal = Atc3AapsJournal(persistenceLayer, profileFunction, atc3Pump)
        profile = mock()
        whenever(profile.getBasal(any())).thenReturn(RATE)
        whenever(persistenceLayer.getBolusesFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        whenever(persistenceLayer.getTemporaryBasalsActiveBetweenTimeAndTime(any(), any())).thenReturn(emptyList())
        whenever(persistenceLayer.getEffectiveProfileSwitchesFromTimeToTime(any(), any(), any()))
            .thenReturn(emptyList())
    }

    /** The profile runs from [from] on and there is none before it, as after a first setup. */
    private fun profileRunningFrom(from: Long) {
        whenever(profileFunction.getProfile(any())).thenAnswer { invocation ->
            if (invocation.getArgument<Long>(0) >= from) profile else null
        }
    }

    /** One profile until [at], another from it on, as a percentage switch makes. */
    private fun profileChangingAt(at: Long, before: Double, after: Double) {
        val earlier: Profile = mock()
        whenever(earlier.getBasal(any())).thenReturn(before)
        val later: Profile = mock()
        whenever(later.getBasal(any())).thenReturn(after)
        whenever(profileFunction.getProfile(any())).thenAnswer { invocation ->
            if (invocation.getArgument<Long>(0) >= at) later else earlier
        }
    }

    @Test
    fun `a profile switched inside the interval is credited from where it began`() = runTest {
        // 150 % until 07:04:32 and 100 % from it on, inside an interval that runs across it. The
        // pump is put on the new rate the moment the switch is made, so the journal has to follow
        // it there and not at the next half hour.
        val switch = at(7, 4, 32)
        profileChangingAt(switch, before = 1.35, after = 0.9)
        whenever(persistenceLayer.getEffectiveProfileSwitchesFromTimeToTime(any(), any(), any()))
            .thenReturn(listOf(effectiveProfileSwitch.copy(timestamp = switch)))
        val from = at(7, 3, 0)
        val to = at(7, 8, 0)

        val breakdown = journal.insulinBetween(from, to)

        assertThat(breakdown).isNotNull()
        val expected = 1.35 * (switch - from) / 3_600_000.0 + 0.9 * (to - switch) / 3_600_000.0
        assertThat(breakdown!!.scheduledUnits).isWithin(1e-6).of(expected)
    }

    /** The effective switches the database is to hand back for any window. */
    private fun switchesAt(vararg moments: Long) {
        whenever(persistenceLayer.getEffectiveProfileSwitchesFromTimeToTime(any(), any(), any()))
            .thenReturn(moments.map { effectiveProfileSwitch.copy(timestamp = it) })
    }

    @Test
    fun `a switch on the half hour is credited from there and not counted twice`() = runTest {
        // The two reasons to split the interval fall on the same moment here. The rate after it
        // must be credited once, not once for the boundary and once for the switch.
        val switch = at(8, 0, 0)
        profileChangingAt(switch, before = 1.35, after = 0.9)
        switchesAt(switch)
        val from = at(7, 50, 0)
        val to = at(8, 10, 0)

        val breakdown = journal.insulinBetween(from, to)

        assertThat(breakdown).isNotNull()
        val expected = 1.35 * 10 / 60.0 + 0.9 * 10 / 60.0
        assertThat(breakdown!!.scheduledUnits).isWithin(1e-6).of(expected)
    }

    @Test
    fun `a switch outside the interval changes nothing inside it`() = runTest {
        // The window the database is asked for is the interval's own, but a caller that answered
        // more widely must not move the rate inside it.
        profileChangingAt(at(9, 0, 0), before = 0.9, after = 1.35)
        switchesAt(at(9, 0, 0))
        val from = at(7, 10, 0)
        val to = at(7, 15, 0)

        val breakdown = journal.insulinBetween(from, to)

        assertThat(breakdown).isNotNull()
        assertThat(breakdown!!.scheduledUnits).isWithin(1e-6).of(0.9 * 5 / 60.0)
    }

    @Test
    fun `two switches in one interval are each credited from where they began`() = runTest {
        // 0.9 until 07:02, 1.35 until 07:06, 0.45 after. Given out of order on purpose: the
        // arithmetic walks forward and would step over a moment it was handed late.
        val first = at(7, 2, 0)
        val second = at(7, 6, 0)
        whenever(profileFunction.getProfile(any())).thenAnswer { invocation ->
            val instant = invocation.getArgument<Long>(0)
            val rate = when {
                instant >= second -> 0.45
                instant >= first  -> 1.35
                else              -> 0.9
            }
            mock<Profile>().also { whenever(it.getBasal(any())).thenReturn(rate) }
        }
        switchesAt(second, first)
        val from = at(7, 0, 0)
        val to = at(7, 10, 0)

        val breakdown = journal.insulinBetween(from, to)

        assertThat(breakdown).isNotNull()
        val expected = 0.9 * 2 / 60.0 + 1.35 * 4 / 60.0 + 0.45 * 4 / 60.0
        assertThat(breakdown!!.scheduledUnits).isWithin(1e-6).of(expected)
    }

    @Test
    fun `the scheduled basal of an interval that began after the profile did is the profile's`() = runTest {
        // The profile begins at 05:41:29 and the interval is wholly inside it, but the half hour
        // boundary before the interval -- 05:30 -- is not, and that is where the rate was read.
        profileRunningFrom(at(5, 41, 29))
        val from = at(5, 42, 46)
        val to = at(5, 47, 44)

        val breakdown = journal.insulinBetween(from, to)

        assertThat(breakdown).isNotNull()
        assertThat(breakdown!!.scheduledUnits).isWithin(1e-6).of(RATE * (to - from) / 3_600_000.0)
    }

    @Test
    fun `an interval whose half hour boundary the profile already covers is unaffected`() = runTest {
        profileRunningFrom(at(5, 41, 29))
        val from = at(6, 2, 0)
        val to = at(6, 7, 0)

        val breakdown = journal.insulinBetween(from, to)

        assertThat(breakdown).isNotNull()
        assertThat(breakdown!!.scheduledUnits).isWithin(1e-6).of(RATE * (to - from) / 3_600_000.0)
    }

    private companion object {

        /** The rate the bench profile was running at when this was found, U/h. */
        const val RATE = 0.9
    }
}
