package app.aaps.pump.apex.state

import app.aaps.pump.apex.account.basal.BasalPeriodKeeper
import app.aaps.pump.apex.account.basal.PeriodBoluses
import app.aaps.pump.apex.account.bolus.BolusAccount
import app.aaps.pump.apex.account.bolus.BolusCountMark
import app.aaps.pump.apex.account.store.FakeStore
import app.aaps.pump.apex.account.store.Snapshot
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApexAbilitiesTest {

    private val store = FakeStore()
    private val serial = "12345678"

    @Test
    fun `abilities are kept as one line and read back the same`() {
        val some = ApexAbilities(lastTbrCommand = false, finishedTbr = true, dayRecord = false, statusV2 = true)
        assertEquals(some, ApexAbilities.decode(some.encode("1.1.1"), "1.1.1"))
        ApexAbilities.store(AAPSLoggerTest(), store, serial, "1.1.1", some)
        assertEquals(some, ApexAbilities.stored(store, serial, "1.1.1"))
    }

    @Test
    fun `abilities probed on another firmware are not taken`() {
        ApexAbilities.store(AAPSLoggerTest(), store, serial, "1.1.1", ApexAbilities(lastTbrCommand = false))
        assertNull(ApexAbilities.stored(store, serial, "6.25"))
    }

    @Test
    fun `a line that is not well formed is not taken`() {
        assertNull(ApexAbilities.decode("garbage", "1.1.1"))
        assertNull(ApexAbilities.decode("1.1.1|101", "1.1.1"))
        assertNull(ApexAbilities.decode("1.1.1|10x1", "1.1.1"))
    }

    @Test
    fun `unknown means every object is present`() {
        val unknown = ApexAbilities.of(store, serial, "1.1.1")
        assertTrue(unknown.lastTbrCommand && unknown.finishedTbr && unknown.dayRecord && unknown.statusV2)
        // No firmware known yet, or no pump: the same.
        assertEquals(unknown, ApexAbilities.of(store, serial, null))
        assertEquals(unknown, ApexAbilities.of(store, "", "1.1.1"))
    }

    @Test
    fun `what was probed is what is used`() {
        ApexAbilities.store(AAPSLoggerTest(), store, serial, "1.1.1", ApexAbilities(lastTbrCommand = false, finishedTbr = false))
        val probed = ApexAbilities.of(store, serial, "1.1.1")
        assertEquals(false, probed.lastTbrCommand)
        assertEquals(false, probed.finishedTbr)
        assertTrue(probed.dayRecord && probed.statusV2)
    }

    private val dayKept = byteArrayOf(1, 2, 3)
    private val bolusCopy = byteArrayOf(4, 5, 6)

    private fun keepReferencePoints() {
        store.saveSnapshot(serial, SnapshotKind.DAILY, Snapshot(listOf(dayKept), 10L))
        store.saveSnapshot(serial, SnapshotKind.BOLUS, Snapshot(listOf(bolusCopy), 10L))
        store.putState(serial, BolusAccount.STATE_SETTLED_SINCE_HEARTBEAT, "0.3;0.5")
        store.putState(serial, BolusCountMark.STATE_KEY, "count mark")
        store.putState(serial, BasalPeriodKeeper.STATE_PERIOD, "period")
        store.putState(serial, PeriodBoluses.STATE_KEY, "1:12")
    }

    private fun assertDropped() {
        assertTrue(store.snapshot(serial, SnapshotKind.DAILY)?.frames.isNullOrEmpty())
        assertNull(store.state(serial, BolusAccount.STATE_SETTLED_SINCE_HEARTBEAT))
        assertNull(store.state(serial, BolusCountMark.STATE_KEY))
        assertNull(store.state(serial, BasalPeriodKeeper.STATE_PERIOD))
        assertNull(store.state(serial, PeriodBoluses.STATE_KEY))
        assertKeptWhatDoesNotDepend()
    }

    private fun assertKeptWhatDoesNotDepend() {
        assertArrayEquals(bolusCopy, store.snapshot(serial, SnapshotKind.BOLUS)!!.frames.single())
    }

    @Test
    fun `new abilities drop the reference points of the spans and keep the bolus copy`() {
        ApexAbilities.store(AAPSLoggerTest(), store, serial, "1.1.1", ApexAbilities())
        keepReferencePoints()
        ApexAbilities.store(AAPSLoggerTest(), store, serial, "1.1.1", ApexAbilities(dayRecord = false))
        assertDropped()
        assertEquals(ApexAbilities(dayRecord = false), ApexAbilities.stored(store, serial, "1.1.1"))
    }

    @Test
    fun `the first abilities kept for a pump drop the reference points too`() {
        keepReferencePoints()
        ApexAbilities.store(AAPSLoggerTest(), store, serial, "1.1.1", ApexAbilities())
        assertDropped()
    }

    @Test
    fun `the same abilities kept again drop nothing`() {
        ApexAbilities.store(AAPSLoggerTest(), store, serial, "1.1.1", ApexAbilities(statusV2 = false))
        keepReferencePoints()
        ApexAbilities.store(AAPSLoggerTest(), store, serial, "1.1.1", ApexAbilities(statusV2 = false))
        assertArrayEquals(dayKept, store.snapshot(serial, SnapshotKind.DAILY)!!.frames.single())
        assertEquals("0.3;0.5", store.state(serial, BolusAccount.STATE_SETTLED_SINCE_HEARTBEAT))
        assertEquals("count mark", store.state(serial, BolusCountMark.STATE_KEY))
        assertEquals("period", store.state(serial, BasalPeriodKeeper.STATE_PERIOD))
        assertEquals("1:12", store.state(serial, PeriodBoluses.STATE_KEY))
        assertKeptWhatDoesNotDepend()
    }
}
