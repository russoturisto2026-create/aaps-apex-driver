package app.aaps.pump.atc3.link

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * A pump that has stopped answering: when the user is told, and when the pump is held to be stopped.
 *
 * The rules pinned down here: told at a quarter of an hour and every quarter after; stopped at
 * half an hour.
 */
class Atc3LinkWatchTest {

    private val minute = 60_000L

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
}
