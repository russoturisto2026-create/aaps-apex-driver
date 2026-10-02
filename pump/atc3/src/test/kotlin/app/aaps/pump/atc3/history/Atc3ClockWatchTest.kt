package app.aaps.pump.atc3.history

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Deciding off the pump's own bolus records whether its clock has to be written.
 *
 * The pump stamps a bolus record with the minute the bolus started. A history read matches the
 * records of our own boluses with one shift of that minute for the whole read; a read matched only
 * a minute off counts a miss, an exact one clears the count, and two misses in a row write the clock.
 */
class Atc3ClockWatchTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 1_700_000_000_000L

    @Test
    fun `an exact match asks for nothing`() {
        val watch = Atc3ClockWatch()
        watch.matched(0, now)
        watch.matched(0, now + hour)

        assertThat(watch.needsSetting(now + hour)).isFalse()
    }

    @Test
    fun `two temporary basals in a row stamped a minute and a half early write the clock`() {
        // The stamp is the pump's last whole minute: 63 s behind an acknowledgement at :03 is a
        // clock a few seconds behind; 95 s is at least half a minute behind.
        val watch = Atc3ClockWatch()
        watch.ownTbrStamped(63_000L, now)
        watch.ownTbrStamped(79_000L, now + 5 * minute)
        assertThat(watch.needsSetting(now + 5 * minute)).isFalse()

        watch.ownTbrStamped(95_000L, now + 10 * minute)
        assertThat(watch.needsSetting(now + 10 * minute)).isFalse()
        watch.ownTbrStamped(100_000L, now + 15 * minute)
        assertThat(watch.needsSetting(now + 15 * minute)).isTrue()
    }

    @Test
    fun `a stamp after the acknowledgement is the pump ahead and counts the same`() {
        val watch = Atc3ClockWatch()
        watch.ownTbrStamped(-5_000L, now)
        watch.ownTbrStamped(-40_000L, now + 5 * minute)
        assertThat(watch.needsSetting(now + 5 * minute)).isTrue()
    }

    @Test
    fun `a temporary basal stamped well clears its own count and not the boluses' one`() {
        val watch = Atc3ClockWatch()
        watch.ownTbrStamped(100_000L, now)
        watch.ownTbrStamped(20_000L, now + 5 * minute)
        watch.ownTbrStamped(100_000L, now + 10 * minute)
        assertThat(watch.needsSetting(now + 10 * minute)).isFalse()

        watch.matched(-1, now + 11 * minute)
        watch.ownTbrStamped(20_000L, now + 12 * minute)
        watch.matched(-1, now + 13 * minute)
        assertThat(watch.needsSetting(now + 13 * minute)).isTrue()
    }

    @Test
    fun `a clock write forgets the temporary basal misses too`() {
        val watch = Atc3ClockWatch()
        watch.ownTbrStamped(100_000L, now)
        watch.forget()
        watch.ownTbrStamped(100_000L, now + 5 * minute)
        assertThat(watch.needsSetting(now + 5 * minute)).isFalse()
    }

    @Test
    fun `one read a minute off is not enough to move the pump's clock`() {
        val watch = Atc3ClockWatch()
        watch.matched(-1, now)

        assertThat(watch.needsSetting(now)).isFalse()
    }

    @Test
    fun `two reads in a row a minute off write the clock`() {
        val watch = Atc3ClockWatch()
        watch.matched(-1, now)
        watch.matched(-1, now + hour)

        assertThat(watch.needsSetting(now + hour)).isTrue()
    }

    @Test
    fun `an exact match in between clears the count`() {
        val watch = Atc3ClockWatch()
        watch.matched(1, now)
        watch.matched(0, now + hour)
        watch.matched(1, now + 2 * hour)

        assertThat(watch.needsSetting(now + 2 * hour)).isFalse()
    }

    @Test
    fun `right after a clock write nothing is counted`() {
        val watch = Atc3ClockWatch()
        watch.matched(-1, now)
        watch.matched(-1, now)
        assertThat(watch.needsSetting(now)).isTrue()

        // The clock these two were about no longer exists.
        watch.forget()

        assertThat(watch.needsSetting(now)).isFalse()
        watch.matched(-1, now)
        assertThat(watch.needsSetting(now)).isFalse()
    }

    @Test
    fun `a verdict older than a day is no verdict at all`() {
        val watch = Atc3ClockWatch()
        watch.matched(-1, now)
        watch.matched(-1, now)

        // Nothing this old is acted on anywhere else in the driver, and the answer has to be "no
        // verdict".
        assertThat(watch.needsSetting(now + 25 * hour)).isFalse()
    }

    @Test
    fun `a miss older than a day does not count towards the next one`() {
        val watch = Atc3ClockWatch()
        watch.matched(-1, now)
        watch.matched(-1, now + 25 * hour)

        assertThat(watch.needsSetting(now + 25 * hour)).isFalse()
    }
}
