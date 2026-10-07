package app.aaps.pump.atc3.link

import app.aaps.pump.atc3.clock.Atc3DayClock
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * A pump that has stopped answering: when the user is told, when the pump is held to be stopped,
 * and what is written for the time without an answer once it answers again.
 *
 * The rules pinned down here: told at a quarter of an hour and every quarter after; stopped at
 * half an hour; a count that went on is the basal of the silence, a count that began anew leaves
 * nothing to count by and the silence is a stop, as is the time before a midnight nothing is known
 * of.
 */
class Atc3LinkWatchTest {

    private val minute = 60_000L

    private fun at(hour: Int, min: Int, day: Int = 5): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, day, hour, min, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun account(
        stop: Atc3LinkWatch.Stop, readMs: Long, counter: Double,
        bolus: Double = 0.0, dayTotal: Double? = null, bolusSinceMidnight: Double = 0.0
    ) = Atc3LinkWatch.account(stop, readMs, counter, bolus, Atc3DayClock.dayStartOf(readMs), dayTotal, bolusSinceMidnight)

    @Test
    fun `the user is told at a quarter of an hour of silence and at every quarter after`() {
        assertThat(Atc3LinkWatch.alarmsDue(14 * minute)).isEqualTo(0)
        assertThat(Atc3LinkWatch.alarmsDue(15 * minute)).isEqualTo(1)
        assertThat(Atc3LinkWatch.alarmsDue(29 * minute)).isEqualTo(1)
        assertThat(Atc3LinkWatch.alarmsDue(30 * minute)).isEqualTo(2)
        assertThat(Atc3LinkWatch.alarmsDue(200 * minute)).isEqualTo(13)
    }

    @Test
    fun `the pump is held to be stopped at half an hour of silence`() {
        assertThat(Atc3LinkWatch.stopDue(29 * minute)).isFalse()
        assertThat(Atc3LinkWatch.stopDue(30 * minute)).isTrue()
    }

    @Test
    fun `the last answer is stored and read back`() {
        val stop = Atc3LinkWatch.Stop(at(8, 46), 13.4)
        assertThat(Atc3LinkWatch.Stop.decode(stop.encode())).isEqualTo(stop)
        assertThat(Atc3LinkWatch.Stop.decode("")).isNull()
    }

    @Test
    fun `a count that went on through the silence is its basal, less the boluses`() {
        // Out of reach for three hours at 1 U/h, with a bolus of 2 U given on the pump's buttons.
        val result = account(Atc3LinkWatch.Stop(at(9, 0), 10.0), at(12, 0), 15.0, bolus = 2.0)
        assertThat(result.outcome).isEqualTo(Atc3LinkWatch.Outcome.COUNTED)
        assertThat(result.stretches).containsExactly(Atc3LinkWatch.Stretch(at(9, 0), at(12, 0), 3.0))
    }

    @Test
    fun `a pump that stood without power and kept its count comes out at nothing`() {
        val result = account(Atc3LinkWatch.Stop(at(9, 0), 10.0), at(12, 0), 10.0)
        assertThat(result.outcome).isEqualTo(Atc3LinkWatch.Outcome.COUNTED)
        assertThat(result.stretches.single().units).isEqualTo(0.0)
    }

    @Test
    fun `a count that began anew on the same day leaves the silence a stop`() {
        // 13.600 U at the last answer, 0.000 U after the count began anew.
        val result = account(Atc3LinkWatch.Stop(at(13, 33), 13.6), at(16, 52), 0.0)
        assertThat(result.outcome).isEqualTo(Atc3LinkWatch.Outcome.COUNT_RESET)
        assertThat(result.stretches).containsExactly(Atc3LinkWatch.Stretch(at(13, 33), at(16, 52), 0.0))
    }

    @Test
    fun `boluses rounded past the count leave no negative basal`() {
        val result = account(Atc3LinkWatch.Stop(at(9, 0), 10.0), at(9, 40), 10.5, bolus = 0.525)
        assertThat(result.stretches.single().units).isEqualTo(0.0)
    }

    @Test
    fun `across midnight the day that ended is counted to its total`() {
        // 20 U at the last answer, the day ended at 22 U, 1.5 U counted since midnight.
        val result = account(Atc3LinkWatch.Stop(at(22, 0, day = 4), 20.0), at(1, 30), 1.5, dayTotal = 22.0)
        assertThat(result.outcome).isEqualTo(Atc3LinkWatch.Outcome.COUNTED)
        assertThat(result.stretches).containsExactly(Atc3LinkWatch.Stretch(at(22, 0, day = 4), at(1, 30), 3.5))
    }

    @Test
    fun `across midnight without the total the time before it is a stop and the time after is counted`() {
        val result = account(Atc3LinkWatch.Stop(at(22, 0, day = 4), 20.0), at(1, 30), 2.5, bolusSinceMidnight = 1.0)
        assertThat(result.outcome).isEqualTo(Atc3LinkWatch.Outcome.BEFORE_MIDNIGHT_UNKNOWN)
        assertThat(result.stretches).containsExactly(
            Atc3LinkWatch.Stretch(at(22, 0, day = 4), at(0, 0), 0.0),
            Atc3LinkWatch.Stretch(at(0, 0), at(1, 30), 1.5)
        ).inOrder()
    }

    @Test
    fun `a total below the count at the last answer is no total to count to`() {
        val result = account(Atc3LinkWatch.Stop(at(22, 0, day = 4), 20.0), at(1, 30), 2.5, dayTotal = 3.0)
        assertThat(result.outcome).isEqualTo(Atc3LinkWatch.Outcome.BEFORE_MIDNIGHT_UNKNOWN)
    }
}
