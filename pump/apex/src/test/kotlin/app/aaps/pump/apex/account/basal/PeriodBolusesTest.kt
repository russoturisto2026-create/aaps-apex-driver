package app.aaps.pump.apex.account.basal

import app.aaps.pump.apex.account.store.FakeStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The bolus rows written since the period began, as the store holds them. */
class PeriodBolusesTest {

    private val store = FakeStore()
    private val serial = "12345678"
    private val boluses = PeriodBoluses(store, serial)

    @Test
    fun `nothing is held at first`() {
        assertEquals(0.0, boluses.sum(), 1e-9)
    }

    @Test
    fun `rows are added and summed in units`() {
        boluses.add(1L, 0.5)
        boluses.add(2L, 0.325)
        assertEquals(0.825, boluses.sum(), 1e-9)
    }

    @Test
    fun `a row added again by its id replaces the amount`() {
        boluses.add(1L, 0.5)
        boluses.add(1L, 0.7)
        assertEquals(0.7, boluses.sum(), 1e-9)
    }

    @Test
    fun `clear drops all rows`() {
        boluses.add(1L, 0.5)
        boluses.clear()
        assertEquals(0.0, boluses.sum(), 1e-9)
    }

    @Test
    fun `what is held survives a new object over the same store`() {
        boluses.add(1L, 0.5)
        assertEquals(0.5, PeriodBoluses(store, serial).sum(), 1e-9)
    }
}
