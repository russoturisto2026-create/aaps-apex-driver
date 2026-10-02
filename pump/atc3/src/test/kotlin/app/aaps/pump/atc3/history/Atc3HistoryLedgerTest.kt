package app.aaps.pump.atc3.history

import app.aaps.core.data.model.BS
import app.aaps.pump.atc3.comm.Atc3BolusFingerprint
import app.aaps.pump.atc3.comm.Atc3BolusRecord
import app.aaps.pump.atc3.comm.Atc3StatusV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The ledger is what stops a bolus being counted twice, so its matching rules are pinned here with
 * the cases the pump produces.
 */
class Atc3HistoryLedgerTest {

    private fun record(seconds: Long, requested: Int, delivered: Int, extReq: Int = 0, extDlv: Int = 0) =
        Atc3BolusRecord(
            index = 0,
            timestamp = seconds * 1000L,
            pumpClockUtcSeconds = seconds,
            rawRequested = requested,
            rawDelivered = delivered,
            rawExtendedRequested = extReq,
            rawExtendedDelivered = extDlv
        )

    /** Second 59 of one minute, where the pump puts the first record of that minute. */
    private val m59 = Math.floorDiv(1_000_000L, 60L) * 60L + 59L

    private fun counted(seconds: Long, requested: Int, delivered: Int) =
        Atc3HistoryLedger().withSeen(SeenBolus(1000L, seconds, Atc3BolusFingerprint(requested, delivered, 0, 0)))

    private fun Atc3HistoryLedger.knows(vararg records: Atc3BolusRecord) = pairWithSeen(records.toList()).size

    @Test
    fun `a record moved back a second inside its minute is still the same record`() {
        // The pump gives the records of one minute seconds 59, 58 and so on, moving a stored one
        // back to make room.
        assertEquals(1, counted(m59, 200, 84).knows(record(m59 - 1, 200, 84)))
    }

    @Test
    fun `the same dose in the next minute is a different record`() {
        assertEquals(0, counted(m59, 200, 84).knows(record(m59 + 60, 200, 84)))
    }

    @Test
    fun `the same record with the delivered amount corrected is still that record`() {
        // The correction has to be recognised, or the pump writing up a step it delivered while
        // stopping would appear as a second bolus.
        assertEquals(1, counted(m59, 200, 84).knows(record(m59, 200, 86)))
    }

    @Test
    fun `a different amount asked for is a different record`() {
        assertEquals(0, counted(m59, 200, 84).knows(record(m59, 40, 40)))
    }

    @Test
    fun `two equal records in one minute with one counted leave the other new`() {
        // Two boluses of 0.1 U in one minute, stamped 59 and 58.
        assertEquals(1, counted(m59, 4, 4).knows(record(m59, 4, 4), record(m59 - 1, 4, 4)))
    }

    @Test
    fun `an id is bumped when the second is already taken`() {
        // The bookkeeping record took the very second its neighbour had just vacated.
        val neighbour = record(1_000_000L, 200, 84)
        val ledger = Atc3HistoryLedger().withSeen(
            SeenBolus(Atc3HistoryLedger().assignPumpId(neighbour), neighbour.pumpClockUtcSeconds, neighbour.fingerprint)
        )
        val correction = record(1_000_000L, 0, 2)
        assertNotEquals(ledger.seen[0].pumpId, ledger.assignPumpId(correction))
    }

    @Test
    fun `the ledger survives being written out and read back`() {
        val ledger = Atc3HistoryLedger(serial = "12345678")
            .withWatermark(1_000_000L, 1_700_000_000_000L)
            .withRecordCount(11)
            .withPending(PendingBolus(42L, 1_700L, 1.0, BS.Type.NORMAL, 0.5, confirmAttempts = 2))
            .withSeen(SeenBolus(1000L, 1_000_000L, Atc3BolusFingerprint(200, 84, 0, 0)))
            .withActiveTbr(
                ActiveTbr(2000L, 1_500L, 1.25, ours = true, ownDurationMs = 3_600_000L, pumpStartUtcSeconds = 1_500L, suspension = true)
            )
            .withOurTbr(1_000_100L, rate = 1.35, durationMinutes = 30, keepFromUtcSeconds = 0L)
            .withOurTbr(1_000_200L, rate = 0.0, durationMinutes = 120, keepFromUtcSeconds = 0L, pumpId = 77L)
            .withOurTbrEnd(77L, 1_700_000_600_000L)
            .withOurTbr(1_000_300L, rate = 0.5, durationMinutes = 15, keepFromUtcSeconds = 0L, pumpId = 78L, pumpStart = true)

        val back = Atc3HistoryLedger.decode(ledger.encode(), "12345678")
        assertEquals(ledger, back)
    }

    @Test
    fun `a note moved onto the pump's start says so and keeps its id`() {
        val ledger = Atc3HistoryLedger(serial = "11223344")
            .withOurTbr(1_000_009L, rate = 1.35, durationMinutes = 30, keepFromUtcSeconds = 0L, pumpId = 5L)
            .withOurTbrOnPumpStart(5L, 1_000_001L)

        val back = Atc3HistoryLedger.decode(ledger.encode(), "11223344").ourTbrs.single()

        assertEquals(1_000_001L, back.startUtcSeconds)
        assertEquals(5L, back.pumpId)
        assertEquals(true, back.pumpStart)
        assertNull(back.endMs)
    }

    @Test
    fun `a temporary basal written before the pump start was recorded still reads back`() {
        // A line without the newest field must not take the whole ledger down with it.
        val stored = """
            atc3-ledger-v1
            w|1000000|1|11|12345678|1700000000000
            t|2000|1500|50|1|3600000
            """.trimIndent()

        val back = Atc3HistoryLedger.decode(stored, "12345678")

        assertEquals(2000L, back.activeTbr?.pumpId)
        assertEquals(1.25, back.activeTbr?.rate)
        assertNull(back.activeTbr?.pumpStartUtcSeconds)
    }

    @Test
    fun `the note of a temporary basal of ours carries what it was, not only when it began`() {
        // The start alone cannot recognise our own work in the journal: the journal keeps its starts
        // to the whole minute and this note is written to the second, so the window has to be a
        // minute wide, and a stranger's temporary basal set in the same minute falls inside it. The
        // rate is stored in the pump's own raw steps, as every other amount here is.
        val ledger = Atc3HistoryLedger(serial = "11223344")
            .withOurTbr(1_000_000L, rate = 1.35, durationMinutes = 30, keepFromUtcSeconds = 0L)

        val back = Atc3HistoryLedger.decode(ledger.encode(), "11223344").ourTbrs.single()

        assertEquals(1_000_000L, back.startUtcSeconds)
        assertEquals(54, back.rawRate)
        assertEquals(30, back.durationMinutes)
    }

    @Test
    fun `a note written before notes carried a rate still reads, and records no rate`() {
        // The ledger version is deliberately not moved for this: discarding the whole file would
        // take the bolus bookkeeping with it -- pending boluses AAPS is already counting -- which is
        // far worse to lose than a day of temporary basal notes. A note with no rate recognises
        // nothing, which is the safe half of the choice.
        val stored = """
            atc3-ledger-v1
            w|1000000|1|11|11223344|1700000000000|900000
            o|1000000,1000200
            """.trimIndent()

        val back = Atc3HistoryLedger.decode(stored, "11223344")

        assertEquals(2, back.ourTbrs.size)
        assertEquals(1_000_000L, back.ourTbrs[0].startUtcSeconds)
        assertNull(back.ourTbrs[0].rawRate)
        assertNull(back.ourTbrs[0].durationMinutes)
        // And writing the ledger out again does not invent figures the pump never gave.
        assertEquals(back, Atc3HistoryLedger.decode(back.encode(), "11223344"))
    }

    @Test
    fun `an unreadable ledger reads back empty rather than throwing`() {
        for (stored in listOf("", "nonsense", "atc3-ledger-v9\nw|1|1|1|12345678", "atc3-ledger-v1\np|broken")) {
            val back = Atc3HistoryLedger.decode(stored, "12345678")
            assertTrue(back.seen.isEmpty() && back.pending.isEmpty())
        }
    }

    @Test
    fun `a ledger belonging to another pump is discarded`() {
        val stored = Atc3HistoryLedger(serial = "12345678")
            .withWatermark(1_000_000L, 1_700_000_000_000L)
            .withSeen(SeenBolus(1000L, 1_000_000L, Atc3BolusFingerprint(200, 84, 0, 0)))
            .encode()
        val back = Atc3HistoryLedger.decode(stored, "87654321")
        assertTrue(back.seen.isEmpty())
        assertEquals(0L, back.importFromUtcSeconds)
    }

    @Test
    fun `the oldest records fall out once the ledger is full`() {
        var ledger = Atc3HistoryLedger()
        for (i in 1..45) {
            ledger = ledger.withSeen(SeenBolus(i.toLong(), 1_000_000L + i, Atc3BolusFingerprint(i, i, 0, 0)))
        }
        assertEquals(40, ledger.seen.size)
        assertTrue(ledger.seen.none { it.pumpClockUtcSeconds == 1_000_001L })
        assertTrue(ledger.seen.any { it.pumpClockUtcSeconds == 1_000_045L })
    }

    @Test
    fun `a settled bolus survives the round trip`() {
        val ledger = Atc3HistoryLedger(serial = "11223344")
            .withSettled(SettledBolus(startedAtMs = 1788758092627L, requestedUnits = 1.0, units = 0.25, settledAtMs = 1788760013399L))
        val back = Atc3HistoryLedger.decode(ledger.encode(), "11223344")
        val settled = back.settled.single()
        assertEquals(1788758092627L, settled.startedAtMs)
        assertEquals(1.0, settled.requestedUnits, 1e-9)
        assertEquals(0.25, settled.units, 1e-9)
        assertEquals(1788760013399L, settled.settledAtMs)
    }

    @Test
    fun `a bolus closed on its completion frame survives the round trip with its id and start`() {
        val ledger = Atc3HistoryLedger(serial = "11223344")
            .withSettled(
                SettledBolus(
                    startedAtMs = 1788758092627L, requestedUnits = 1.0, units = 1.0, settledAtMs = 1788758130000L,
                    pumpId = 1788768059000L, startUtcSeconds = 1788768052L
                )
            )
        assertEquals(ledger, Atc3HistoryLedger.decode(ledger.encode(), "11223344"))
    }

    @Test
    fun `lines written before the start was stored still read, with the start worked out`() {
        val stored = """
            atc3-ledger-v1
            w|1000000|1|11|11223344|1700000000000
            p|7|1000000|40|0|SMB|0|0|0
            x|1788758092627|40|10|1788760013399
            """.trimIndent()

        val back = Atc3HistoryLedger.decode(stored, "11223344")

        assertEquals(Atc3StatusV1.wallClockUtcSeconds(1_000_000L), back.pending.single().startUtcSeconds)
        assertEquals(Atc3StatusV1.wallClockUtcSeconds(1788758092627L), back.settled.single().startUtcSeconds)
        assertEquals(0L, back.settled.single().pumpId)
    }

    @Test
    fun `an id held by a completed bolus is not handed to another record`() {
        val ledger = Atc3HistoryLedger().withSettled(
            SettledBolus(1_000_000L, 1.0, 1.0, 1_030_000L, pumpId = Atc3HistoryLedger().assignOwnPumpId(1_000_000L), startUtcSeconds = 1_000_000L)
        )
        val sameSecond = record(Math.floorDiv(1_000_000L, 60L) * 60L + 59L, 80, 80)
        assertNotEquals(ledger.settled.single().pumpId, ledger.assignPumpId(sameSecond))
    }

    @Test
    fun `a pending bolus stored with the old window flag and end of delivery still reads`() {
        val stored = "atc3-ledger-v1\n" +
            "w|0|1|0|11223344|0|0\n" +
            "p|7|1000|80|0|NORMAL|1|401000|3|60"
        val pending = Atc3HistoryLedger.decode(stored, "11223344").pending.single()
        assertEquals(PendingBolus(7L, 1000L, 2.0, BS.Type.NORMAL, 0.0, confirmAttempts = 3, startUtcSeconds = 60L), pending)
    }

    @Test
    fun `a note keeps where its row begins across the round trip`() {
        val ledger = Atc3HistoryLedger(serial = "11223344")
            .withOurTbr(1_000_300L, rate = 3.0, durationMinutes = 30, keepFromUtcSeconds = 0L, pumpId = 78L, pumpStart = true, rowMs = 1_700_000_123_456L)
            .withOurTbrEnd(78L, 1_700_000_223_456L)
        val back = Atc3HistoryLedger.decode(ledger.encode(), "11223344").ourTbrs.single()
        assertEquals(1_700_000_123_456L, back.rowMs)
        assertEquals(1_700_000_223_456L, back.endMs)
        // The journal's account replaces the end the tick gave it.
        val shaped = Atc3HistoryLedger.decode(ledger.encode(), "11223344").withOurTbrEnd(78L, 1_700_000_200_000L, overwrite = true)
        assertEquals(1_700_000_200_000L, shaped.ourTbrs.single().endMs)
    }

    @Test
    fun `a note keeps the insulin handed over from a row of its minute across the round trip`() {
        val ledger = Atc3HistoryLedger(serial = "11223344")
            .withOurTbr(1_000_300L, rate = 0.75, durationMinutes = 30, keepFromUtcSeconds = 0L, pumpId = 78L, pumpStart = true, rowMs = 1_700_000_123_456L)
            .withOurTbrCarried(78L, 0.05)
            .withOurTbrShaped(78L, 0.10, 1_700_000_363_456L)
        val back = Atc3HistoryLedger.decode(ledger.encode(), "11223344").ourTbrs.single()
        assertEquals(0.05, back.carriedUnits!!, 1e-9)
        assertEquals(0.10, back.shapedUnits!!, 1e-9)
    }
}
