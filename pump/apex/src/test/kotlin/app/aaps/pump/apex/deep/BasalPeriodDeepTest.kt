package app.aaps.pump.apex.deep

import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.NoticeKind
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/** Basal by the count is never more than the pump's maximum basal gives over the period; the rest is a bolus and a notice. */
class BasalPeriodDeepTest {

    private val deep = BasalPeriodDeep(AAPSLoggerTest(), mock(ApexTrace::class.java))
    private val minute = 60_000L
    private val start = 1_800_000_000_000L - 1_800_000_000_000L % minute

    @Test
    fun `6 U per hour over two heartbeats of 5 minutes gives 1 U at most`() {
        val capped = deep.capAtMaxBasal(start, start + 10 * minute, 1.0, 6.0)
        assertEquals(1.0, capped.basalUnits, 1e-9)
        assertTrue(capped.writes.isEmpty())
    }

    @Test
    fun `the rest above the maximum is a bolus in the middle of the period, and the user is told`() {
        val capped = deep.capAtMaxBasal(start, start + 10 * minute, 2.65, 6.0)
        assertEquals(1.0, capped.basalUnits, 1e-9)
        val bolus = capped.writes.filterIsInstance<AapsWrite.Bolus>().single()
        assertEquals(1.65, bolus.amountUnits, 1e-9)
        assertEquals(start + 5 * minute, bolus.timestampMs)
        assertEquals(AapsWrite.NORMAL, bolus.type)
        val notice = capped.writes.filterIsInstance<AapsWrite.Notice>().single()
        assertEquals(NoticeKind.BASAL_ABOVE_MAX, notice.kind)
        assertEquals(1.65, notice.units, 1e-9)
    }

    @Test
    fun `with no maximum known all of it is basal`() {
        assertEquals(2.65, deep.capAtMaxBasal(start, start + 10 * minute, 2.65, null).basalUnits, 1e-9)
    }

    @Test
    fun `two periods give two different bolus row numbers`() {
        val first = deep.capAtMaxBasal(start, start + 5 * minute, 1.0, 6.0).writes.filterIsInstance<AapsWrite.Bolus>().single()
        val second = deep.capAtMaxBasal(start + 5 * minute, start + 10 * minute, 1.0, 6.0).writes.filterIsInstance<AapsWrite.Bolus>().single()
        assertTrue(first.pumpId != second.pumpId)
        assertEquals(0.5, first.amountUnits, 1e-9)
    }
}
