package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.bolus.BolusRecordFrames.record
import app.aaps.pump.apex.protocol.ApexBolusRecord
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A record the pump cannot have written, by the bounds of the reconciliation, see [FaultyCell.implausible]. */
class RecordBoundsTest {

    /** 2026-11-03 14:00 UTC, a whole minute. */
    private val two = 1_793_714_400L
    private val nowMs = two * 1000L

    private fun implausible(clockSeconds: Long, requestedRaw: Int, deliveredRaw: Int, reservoir: Double? = 200.0): Boolean =
        FaultyCell.implausible(ApexBolusRecord.decode(record(clockSeconds, requestedRaw, deliveredRaw).frame)!!, clockSeconds * 1000L, reservoir, nowMs)

    @Test
    fun `a record stamped a minute past now is sound, one stamped an hour past now is not`() {
        assertFalse(implausible(two + 60L, 20, 20))
        assertTrue(implausible(two + 3600L, 20, 20))
    }

    @Test
    fun `a record of more than the reservoir held is not sound - with no status seen there is no bound`() {
        assertFalse(implausible(two - 60L, 800, 800, reservoir = null))
        assertTrue(implausible(two - 60L, 800, 800, reservoir = 10.0))
        assertFalse(implausible(two - 60L, 400, 400, reservoir = 10.0), "the reservoir itself is within bounds")
    }

    @Test
    fun `a record that delivered more than it asked for is not sound`() {
        assertTrue(implausible(two - 60L, 336, 400), "asked 8.4, delivered 10.0: the faulty cell of 19.11")
        assertTrue(implausible(two - 60L, 80, 81), "one step above is out too")
        assertFalse(implausible(two - 60L, 80, 80))
        assertFalse(implausible(two - 60L, 80, 10), "a stopped bolus delivered less")
        assertFalse(implausible(two - 60L, 0, 2), "asked 0, delivered 0.05: a cancel with no bolus running")
    }

    @Test
    fun `an ask between two steps is given rounded up to the step - one step above it is not sound`() {
        assertFalse(implausible(two - 60L, 105, 106), "asked 2.625, delivered 2.65")
        assertFalse(implausible(two - 60L, 193, 194), "asked 4.825, delivered 4.85")
        assertTrue(implausible(two - 60L, 105, 107), "asked 2.625, delivered 2.675")
        assertTrue(implausible(two - 60L, 30, 31), "below 1 U the step is 0.025: asked 0.75, delivered 0.775")
        assertFalse(ApexBolusRecord(0, 0L, 0, 0, rawExtendedRequested = 105, rawExtendedDelivered = 106).deliveredAboveAsked, "the extended part the same way")
        assertTrue(ApexBolusRecord(0, 0L, 0, 0, rawExtendedRequested = 104, rawExtendedDelivered = 106).deliveredAboveAsked, "an extended part one step above a whole ask")
    }

    @Test
    fun `a record a day old is sound - there is no bound from the past`() {
        assertFalse(implausible(two - 86_400L, 20, 20, reservoir = 10.0))
    }
}
