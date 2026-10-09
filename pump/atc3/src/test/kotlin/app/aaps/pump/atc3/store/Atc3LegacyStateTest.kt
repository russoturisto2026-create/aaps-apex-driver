package app.aaps.pump.atc3.store

import app.aaps.pump.atc3.history.Atc3HistoryLedger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The ledger as earlier versions wrote it, read once on the first start after an update. */
class Atc3LegacyStateTest {

    @Test
    fun `a temporary basal written before the pump start was recorded still reads back`() {
        // A line without the newest field must not take the whole ledger down with it.
        val stored = """
            atc3-ledger-v1
            w|1000000|1|11|12345678|1700000000000
            t|2000|1500|50|1|3600000
            """.trimIndent()

        val back = Atc3LegacyState.ledger(stored, "12345678")

        assertEquals(2000L, back.activeTbr?.pumpId)
        assertEquals(1.25, back.activeTbr?.rate)
        assertNull(back.activeTbr?.pumpStartUtcSeconds)
    }

    @Test
    fun `the notes of our temporary basals an earlier version kept are passed over`() {
        // They served the shaping of rows by the pump's journal, which is gone; the rest of the ledger reads.
        val stored = """
            atc3-ledger-v1
            w|1000000|1|11|11223344|1700000000000|900000
            o|1000000:120:30:5:1,1000200
            """.trimIndent()

        val back = Atc3LegacyState.ledger(stored, "11223344")

        assertEquals(Atc3HistoryLedger(serial = "11223344", importFromUtcSeconds = 1_000_000L, importFromPhoneMs = 1_700_000_000_000L, firstPassDone = true), back)
        assertEquals(back, Atc3Store.readParts(Atc3Store.encode(Atc3StoredState(ledger = back))).state.ledger)
    }

    @Test
    fun `an unreadable ledger reads back empty rather than throwing`() {
        for (stored in listOf("", "nonsense", "atc3-ledger-v9\nw|1|1|1|12345678", "atc3-ledger-v1\np|broken")) {
            assertEquals(Atc3HistoryLedger(serial = "12345678"), Atc3LegacyState.ledger(stored, "12345678"))
        }
    }

    @Test
    fun `a ledger belonging to another pump is discarded`() {
        val stored = """
            atc3-ledger-v1
            w|1000000|1|11|12345678|1700000000000
            s|1000|1000000|200|84|0|0
            """.trimIndent()
        assertEquals(Atc3HistoryLedger(serial = "87654321"), Atc3LegacyState.ledger(stored, "87654321"))
    }

    /** Earlier versions kept the bolus records they had counted and the boluses they waited for; AAPS's rows say that now. */
    @Test
    fun `what earlier versions kept of bolus records is passed over`() {
        val stored = """
            atc3-ledger-v1
            w|1000000|1|11|11223344|1700000000000
            p|7|1000000|40|0|SMB|0|0|0
            x|1788758092627|40|10|1788760013399|1788768059000
            s|1000|1000000|200|84|0|0
            """.trimIndent()

        val back = Atc3LegacyState.ledger(stored, "11223344")

        assertEquals(1_000_000L, back.importFromUtcSeconds)
        assertTrue(back.expected.isEmpty())
    }

    @Test
    fun `the serial a stored ledger belongs to is read back, and nothing from an empty or foreign store`() {
        assertEquals("11223344", Atc3LegacyState.serialOf("atc3-ledger-v1\nw|0|0|0|11223344"))
        assertNull(Atc3LegacyState.serialOf(""))
        assertNull(Atc3LegacyState.serialOf("some-other-format\nw|0|0|0|11223344"))
    }
}
