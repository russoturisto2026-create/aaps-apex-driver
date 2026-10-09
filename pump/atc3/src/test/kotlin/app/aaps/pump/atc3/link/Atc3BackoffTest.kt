package app.aaps.pump.atc3.link

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The wait before the next attempt at a link, on a clock the test sets. */
class Atc3BackoffTest {

    private var now = 1_000_000L
    private val backoff = Atc3Backoff { now }

    @Test
    fun `a stack that never answers is waited for longer each round, up to a cap`() {
        val waits = List(7) { backoff.afterEnd(wasReady = false, stackAnswered = false, released = false) }

        assertThat(waits).containsExactly(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L).inOrder()
        assertThat(backoff.failures).isEqualTo(7)
    }

    @Test
    fun `a stack that answers is waited for the same each time, and the count starts over`() {
        backoff.afterEnd(wasReady = false, stackAnswered = false, released = false)

        assertThat(backoff.afterEnd(wasReady = false, stackAnswered = true, released = false)).isEqualTo(Atc3Backoff.ANSWERED_MS)
        assertThat(backoff.failures).isEqualTo(0)
    }

    @Test
    fun `a usable link let go of waits only for the radio to let it go`() {
        assertThat(backoff.afterEnd(wasReady = true, stackAnswered = true, released = true)).isEqualTo(Atc3Backoff.RELEASE_SETTLE_MS)
        assertThat(backoff.afterEnd(wasReady = true, stackAnswered = true, released = false)).isEqualTo(0L)
        assertThat(backoff.remainingMs).isEqualTo(0L)
    }

    @Test
    fun `the wait runs out with the clock`() {
        backoff.afterEnd(wasReady = true, stackAnswered = true, released = true)
        now += 1_000L

        assertThat(backoff.remainingMs).isEqualTo(Atc3Backoff.RELEASE_SETTLE_MS - 1_000L)
        now += Atc3Backoff.RELEASE_SETTLE_MS
        assertThat(backoff.remainingMs).isEqualTo(0L)
    }

    @Test
    fun `a refused start holds off without shortening a longer wait`() {
        repeat(5) { backoff.afterEnd(wasReady = false, stackAnswered = false, released = false) }

        backoff.afterRefusal()

        assertThat(backoff.remainingMs).isEqualTo(60_000L)
    }
}
