package app.aaps.pump.apex.exchange

import app.aaps.pump.apex.ApexConst
import app.aaps.pump.apex.exchange.ApexExchange.Companion.askAgain
import app.aaps.pump.apex.exchange.ExchangeTiming.clockErrorOf
import app.aaps.pump.apex.exchange.ExchangeTiming.heartbeatClearUntil
import app.aaps.pump.apex.exchange.ApexExchange.Companion.usableAnswer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApexExchangeTest {

    private val beat = ApexConst.HEARTBEAT_PERIOD_DEFAULT * 60_000L
    private val last = 1_000_000L

    @Test
    fun `no request waits before a heartbeat was seen, nor far from the next one`() {
        assertNull(heartbeatClearUntil(0L, last, beat))
        assertNull(heartbeatClearUntil(last, last + 60_000L, beat))
        assertNull(heartbeatClearUntil(last, last + beat - 1_501L, beat))
    }

    @Test
    fun `a request just before the heartbeat is due waits until 0,7 s after it`() {
        assertEquals(last + beat + 700L, heartbeatClearUntil(last, last + beat - 1_500L, beat))
        assertEquals(last + beat + 700L, heartbeatClearUntil(last, last + beat + 500L, beat))
    }

    @Test
    fun `the heartbeat on second 0 of the pump's clock, heard 0,3 s later, tells how far the pump's clock is off`() {
        val minute = 1_793_266_260_000L
        assertEquals(0L, clockErrorOf(minute + 300L))
        // Heard on second 2.6: the pump's clock is 2.3 s behind.
        assertEquals(2_300L, clockErrorOf(minute + 2_600L))
        // Heard on second 59.8 of the minute before: the pump's clock is 0.5 s ahead.
        assertEquals(-500L, clockErrorOf(minute - 200L))
    }

    @Test
    fun `on a period of one minute the next heartbeat is waited for a minute after the last`() {
        val minute = 60_000L
        assertEquals(last + minute + 700L, heartbeatClearUntil(last, last + minute - 1_000L, minute))
        assertNull(heartbeatClearUntil(last, last + 30_000L, minute))
    }

    @Test
    fun `a heartbeat that did not come keeps the beat`() {
        assertEquals(last + 2 * beat + 700L, heartbeatClearUntil(last, last + 2 * beat - 1_000L, beat))
        assertNull(heartbeatClearUntil(last, last + beat + 60_000L, beat))
    }

    @Test
    fun `a read is asked again while silent, broken or unsound, three asks in all`() {
        for (outcome in listOf(ApexExchange.Ask.SILENT, ApexExchange.Ask.BROKEN, ApexExchange.Ask.UNSOUND, ApexExchange.Ask.UNDER_HEARTBEAT)) {
            assertTrue(askAgain(outcome, asked = 1, read = true))
            assertTrue(askAgain(outcome, asked = 2, read = true))
            assertFalse(askAgain(outcome, asked = 3, read = true))
        }
        assertEquals(3, ReadRule.READS)
    }

    @Test
    fun `a command is never sent twice, not even when its answer came broken`() {
        for (outcome in ApexExchange.Ask.entries) assertFalse(askAgain(outcome, asked = 1, read = false))
    }

    @Test
    fun `a lost link or an answer is not asked again`() {
        assertFalse(askAgain(ApexExchange.Ask.LINK_LOST, asked = 1, read = true))
        assertFalse(askAgain(ApexExchange.Ask.ANSWERED, asked = 1, read = true))
    }

    @Test
    fun `no answer and an answer that cannot be read count alike toward the pump not working`() {
        for (outcome in listOf(ApexExchange.Ask.SILENT, ApexExchange.Ask.BROKEN, ApexExchange.Ask.UNSOUND)) {
            assertEquals(false, usableAnswer(outcome, silenceDropsLink = true), outcome.name)
        }
        assertEquals(true, usableAnswer(ApexExchange.Ask.UNDER_HEARTBEAT, silenceDropsLink = true))
        assertNull(usableAnswer(ApexExchange.Ask.LINK_LOST, silenceDropsLink = true))
        assertNull(usableAnswer(ApexExchange.Ask.UNSOUND, silenceDropsLink = false))
        assertNull(usableAnswer(ApexExchange.Ask.SILENT, silenceDropsLink = false))
    }
}
