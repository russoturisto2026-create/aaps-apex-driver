package app.aaps.pump.apex.account.bolus

import app.aaps.pump.apex.account.basal.Delivered
import app.aaps.pump.apex.account.store.FakeStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The mark a pump without the daily statistics counts its boluses from. */
class BolusCountMarkTest {

    private val store = FakeStore()
    private val serial = "12345678"

    @Test
    fun `a mark with the reservoir is kept and read back`() {
        BolusCountMark(store, serial).move(BolusSpanEnd.Count(5_000L, Delivered.Count(12.5, 20_000L), 187.325))

        val mark = BolusCountMark(store, serial).get()!!
        assertEquals(5_000L, mark.atMs)
        assertEquals(12.5, mark.count.units, 1e-9)
        assertEquals(20_000L, mark.count.pumpDay)
        assertEquals(187.325, mark.reservoirUnits!!, 1e-9)
    }

    @Test
    fun `a mark with no reservoir is kept and read back`() {
        BolusCountMark(store, serial).move(BolusSpanEnd.Count(5_000L, Delivered.Count(12.5, 20_000L)))

        assertNull(BolusCountMark(store, serial).get()!!.reservoirUnits)
    }

    @Test
    fun `a mark stored before the reservoir was kept still reads, the reservoir unknown`() {
        store.putState(serial, BolusCountMark.STATE_KEY, "5000;C;500;20000")

        val mark = BolusCountMark(store, serial).get()!!
        assertEquals(5_000L, mark.atMs)
        assertEquals(12.5, mark.count.units, 1e-9)
        assertNull(mark.reservoirUnits)
    }

    @Test
    fun `a line that is not a mark reads as none`() {
        store.putState(serial, BolusCountMark.STATE_KEY, "5000;C;500")

        assertNull(BolusCountMark(store, serial).get())
    }
}
