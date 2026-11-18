package app.aaps.pump.apex.account.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The store keeps what a block stored only when the block ran to its end. */
class FakeStoreTest {

    private val store = FakeStore()
    private val serial = "12345678"

    @Test
    fun `a block that ends keeps everything, a block that throws keeps nothing`() {
        store.transaction {
            store.putState(serial, "a", "1")
            store.saveSnapshot(serial, SnapshotKind.STATUS, Snapshot(listOf(byteArrayOf(1)), 1L))
        }
        assertEquals("1", store.state(serial, "a"))

        assertThrows(IllegalStateException::class.java) {
            store.transaction {
                store.putState(serial, "a", "2")
                store.putState(serial, "b", "1")
                store.saveSnapshot(serial, SnapshotKind.STATUS, Snapshot(listOf(byteArrayOf(2)), 2L))
                store.addAction(serial, Action(acceptedAtMs = 1L, amount = 1.0, type = "NORMAL", journalTopSeq = 0L))
                throw IllegalStateException("the pump stopped answering")
            }
        }
        assertEquals("1", store.state(serial, "a"))
        assertNull(store.state(serial, "b"))
        assertEquals(1L, store.snapshot(serial, SnapshotKind.STATUS)!!.readAtMs)
        assertEquals(0, store.actions(serial).size)
    }

    @Test
    fun `a pump forgotten has nothing kept, another pump keeps its own`() {
        store.saveSnapshot(serial, SnapshotKind.BOLUS, Snapshot(listOf(byteArrayOf(1)), 1L))
        store.addAction(serial, Action(acceptedAtMs = 1L, amount = 0.5, type = "NORMAL", journalTopSeq = 3L))
        store.putState(serial, "recordsSeen.BOLUS", "3")
        store.saveClock(serial, ClockOffset(0L, 1L, "test"), 3L)
        store.putState("87654321", "recordsSeen.BOLUS", "9")

        store.forgetPump(serial)

        assertNull(store.snapshot(serial, SnapshotKind.BOLUS))
        assertEquals(0, store.actions(serial).size)
        assertNull(store.state(serial, "recordsSeen.BOLUS"))
        assertNull(store.clock(serial))
        assertEquals(0, store.clockLog(serial).size)
        assertEquals("9", store.state("87654321", "recordsSeen.BOLUS"))
    }
}
