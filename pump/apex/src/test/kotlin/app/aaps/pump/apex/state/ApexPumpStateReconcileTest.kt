package app.aaps.pump.apex.state

import app.aaps.pump.apex.pulse.PictureSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The screen compares the day only at a heartbeat whose reconciliation put nothing off. */
class ApexPumpStateReconcileTest {

    private val state = ApexPumpState()

    private fun picture(atMs: Long, counter: Double, reconciled: Boolean) = PictureSummary(
        heartbeatAtMs = atMs, readAtMs = atMs + 3_000L, counterUnits = counter,
        dayBolusUnits = 1.0, dayBasalUnits = 2.0, dayTbrUnits = 0.0,
        topBolusAtMs = null, topBolusUnits = null, clockErrorMs = null, brokenJournals = emptyList(),
        reconciled = reconciled
    )

    @Test
    fun `a heartbeat with something put off is no comparison point`() {
        state.lastPicture = picture(1_000L, 3.0, reconciled = false)
        assertNull(state.reconcilePointFlow.value)
    }

    @Test
    fun `the point stays at the last full reconciliation while the next ones are put off`() {
        state.lastPicture = picture(1_000L, 3.0, reconciled = true)
        state.lastPicture = picture(301_000L, 3.9, reconciled = false)
        assertEquals(1_000L, state.reconcilePointFlow.value!!.atMs)
        assertEquals(3.0, state.reconcilePointFlow.value!!.counterUnits, 1e-9)
        state.lastPicture = picture(601_000L, 8.0, reconciled = true)
        assertEquals(601_000L, state.reconcilePointFlow.value!!.atMs)
    }
}
