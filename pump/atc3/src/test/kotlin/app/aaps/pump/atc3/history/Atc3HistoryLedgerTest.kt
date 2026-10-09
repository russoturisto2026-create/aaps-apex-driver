package app.aaps.pump.atc3.history

import app.aaps.core.data.model.BS
import app.aaps.pump.atc3.store.Atc3Store
import app.aaps.pump.atc3.store.Atc3StoredState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What the ledger keeps across a restart comes back as it went: the boluses expected and the temporary basal open. */
class Atc3HistoryLedgerTest {

    private fun roundTrip(ledger: Atc3HistoryLedger) = Atc3Store.readParts(Atc3Store.encode(Atc3StoredState(ledger = ledger))).state.ledger

    @Test
    fun `the ledger survives being written out and read back`() {
        val ledger = Atc3HistoryLedger(serial = "12345678")
            .withWatermark(1_000_000L, 1_700_000_000_000L)
            .withExpected(ExpectedBolus(1_700_000_000_000L, 1.0, BS.Type.SMB, startUtcSeconds = 1_000_010L))
            .withActiveTbr(
                ActiveTbr(2000L, 1_500L, 1.25, ours = true, ownDurationMs = 3_600_000L, pumpStartUtcSeconds = 1_500L, suspension = true)
            )

        val back = roundTrip(ledger)
        assertEquals(ledger, back)
    }
}
