package app.aaps.pump.atc3.history

import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.atc3.Atc3Const
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Atc3TbrTrackerTest {

    private val now = 1_700_000_000_000L

    private fun step(
        active: ActiveTbr?,
        tbrRunning: Boolean,
        rate: Double,
        phoneNow: Long = now,
        suspended: Boolean = false,
        pumpStart: PumpTbr? = null,
        durationMs: Long? = null,
        endedAtMs: Long? = null,
        pausedAtMs: Long? = null,
        resumedAtMs: Long? = null
    ) = Atc3TbrTracker.step(active, suspended, tbrRunning, rate, durationMs, pumpStart, phoneNow, endedAtMs, pausedAtMs, resumedAtMs)

    private fun pumpTbr(atMs: Long, minutes: Int?, rate: Double? = null) =
        PumpTbr(atMs, atMs / 1000L, minutes?.let { it * 60_000L }, rate?.let { Math.round(it / Atc3Const.DOSE_SCALE).toInt() })

    @Test
    fun `a replacement is not anchored at the start of what it replaced`() {
        // The pause case: a temporary basal is interrupted, the pump resumes, and object 0x0A still
        // hands back the start of the interrupted one -- hours old. Anchored there the new record
        // would open behind a period AAPS has already accounted for.
        val interruptedStart = now - 3 * 3600_000L
        val active = ActiveTbr(
            pumpId = 1L, startedAtMs = interruptedStart, rate = 0.0, ours = false,
            ownDurationMs = 24 * 3600_000L
        )
        val (actions, entry) = step(active, tbrRunning = true, rate = 2.0, pumpStart = pumpTbr(interruptedStart, 30, rate = 0.0))

        val start = actions.filterIsInstance<Atc3TbrAction.Start>().single()
        assertEquals(now, start.timestamp)
        assertEquals(now, entry!!.startedAtMs)
    }

    @Test
    fun `a start later than the one being replaced is still believed`() {
        val active = ActiveTbr(
            pumpId = 1L, startedAtMs = now - 30 * 60_000L, rate = 1.0, ours = false,
            ownDurationMs = 60 * 60_000L
        )
        val real = now - 4 * 60_000L
        val (actions, entry) = step(active, tbrRunning = true, rate = 2.0, pumpStart = pumpTbr(real, 45))

        assertEquals(real, actions.filterIsInstance<Atc3TbrAction.Stop>().single().timestamp)
        assertEquals(real, actions.filterIsInstance<Atc3TbrAction.Start>().single().timestamp)
        assertEquals(real, entry!!.startedAtMs)
    }

    @Test
    fun `a temporary basal is closed where the pump says it ended`() {
        val started = now - 20 * 60_000L
        val active = ActiveTbr(pumpId = 1L, startedAtMs = started, rate = 0.0, ours = false, ownDurationMs = 45 * 60_000L)
        val reallyEnded = now - 4 * 60_000L

        val (actions, entry) = step(active, tbrRunning = false, rate = 0.0, endedAtMs = reallyEnded)

        assertNull(entry)
        assertEquals(reallyEnded, actions.filterIsInstance<Atc3TbrAction.Stop>().single().timestamp)
    }

    @Test
    fun `one gone early without a real end is closed at the poll`() {
        val started = now - 20 * 60_000L
        val active = ActiveTbr(pumpId = 1L, startedAtMs = started, rate = 2.0, ours = false, ownDurationMs = 30 * 60_000L)

        val (actions, _) = step(active, tbrRunning = false, rate = 0.0)

        assertEquals(now, actions.filterIsInstance<Atc3TbrAction.Stop>().single().timestamp)
    }

    @Test
    fun `one that ran its course is closed at its start plus its duration`() {
        val started = now - 40 * 60_000L
        val active = ActiveTbr(pumpId = 1L, startedAtMs = started, rate = 2.0, ours = true, ownDurationMs = 30 * 60_000L)

        val (actions, _) = step(active, tbrRunning = false, rate = 0.0)

        assertEquals(started + 30 * 60_000L, actions.filterIsInstance<Atc3TbrAction.Stop>().single().timestamp)
    }

    @Test
    fun `nothing running and nothing recorded means nothing to do`() {
        val (actions, active) = step(null, tbrRunning = false, rate = 0.0)
        assertTrue(actions.isEmpty())
        assertNull(active)
    }

    @Test
    fun `a temporary basal the driver did not start is recorded once and then left alone`() {
        val (started, first) = step(null, tbrRunning = true, rate = 1.5, durationMs = 30 * 60_000L)
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, started.single())
        assertEquals(1.5, start.rate, 1e-9)
        assertEquals(30 * 60_000L, start.durationMs)
        assertEquals(PumpSync.TemporaryBasalType.NORMAL, start.type)

        val later = now + 300_000L
        val (same, second) = step(first, tbrRunning = true, rate = 1.5, phoneNow = later, durationMs = 30 * 60_000L)
        assertEquals(Atc3TbrAction.None, same.single())

        val (stopped, none) = step(second, tbrRunning = false, rate = 0.0, phoneNow = later)
        assertInstanceOf(Atc3TbrAction.Stop::class.java, stopped.single())
        assertNull(none)
    }

    @Test
    fun `a temporary basal AAPS asked for keeps the duration AAPS asked for`() {
        val (action, entry) = Atc3TbrTracker.startedByAaps(now, rate = 1.25, durationMs = 3_600_000L)
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, action)
        assertEquals(3_600_000L, start.durationMs)

        val (actions, _) = step(entry, tbrRunning = true, rate = 1.25, phoneNow = now + 600_000L, durationMs = 3_600_000L)
        assertEquals(Atc3TbrAction.None, actions.single())
    }

    @Test
    fun `a temporary basal AAPS asked for is recorded from the acknowledgement and known under the pump's start`() {
        // The pump stamps it with the minute of its last snapshot, before the command: here 65 s
        // before the acknowledgement. The row begins where the insulin did; the stamp is the
        // identity its journal record will carry.
        val pumpAt = now - 65_000L
        val (action, entry) = Atc3TbrTracker.startedByAaps(now, 1.25, 3_600_000L, pumpTbr(pumpAt, 60))
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, action)
        assertEquals(now, start.timestamp)
        assertEquals(now, entry.startedAtMs)
        assertEquals(pumpAt / 1000L, entry.pumpStartUtcSeconds)
        assertEquals(pumpAt, entry.pumpStartMs)
        assertEquals(pumpAt, entry.anchorMs)
        assertTrue(entry.ours)
    }

    @Test
    fun `a stranger's stamp before our acknowledgement begins their row where ours began`() {
        // Ours acknowledged at :05 of the minute, a stranger's set at :30 and stamped with the
        // minute: the stamp is before our row. Ours is cut to nothing and theirs begins where ours
        // did, which is where the pump's journal keeps both -- see Atc3TbrBook.
        val minute = now - now % 60_000L
        val (_, ours) = Atc3TbrTracker.startedByAaps(minute + 5_000L, 0.4, 30 * 60_000L, pumpTbr(minute, 30, rate = 0.4))
        val (actions, theirs) = step(
            ours, tbrRunning = true, rate = 3.0, durationMs = 30 * 60_000L, phoneNow = minute + 100_000L,
            pumpStart = pumpTbr(minute, 30, rate = 3.0)
        )
        val stop = assertInstanceOf(Atc3TbrAction.Stop::class.java, actions[0])
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, actions[1])
        assertEquals(minute + 5_000L, stop.timestamp)
        assertEquals(minute + 5_000L, start.timestamp)
        assertEquals(minute / 1000L, theirs?.pumpStartUtcSeconds)
        assertEquals(minute, theirs?.pumpStartMs)
        assertFalse(theirs!!.ours)
    }

    @Test
    fun `a pump started temporary basal is recorded once ours has run its course`() {
        val (_, ours) = Atc3TbrTracker.startedByAaps(now, rate = 3.0, durationMs = 30 * 60_000L, pumpTbr(now, 30))
        val afterItEnded = now + 32 * 60_000L
        val (actions, entry) = step(ours, tbrRunning = true, rate = 3.0, phoneNow = afterItEnded, durationMs = 30 * 60_000L)
        // Ours ended at its own end, not at the poll.
        assertEquals(now + 30 * 60_000L, actions.filterIsInstance<Atc3TbrAction.Stop>().single().timestamp)
        assertEquals(afterItEnded, actions.filterIsInstance<Atc3TbrAction.Start>().single().timestamp)
        assertEquals(false, entry?.ours)
    }

    @Test
    fun `a rate change closes one record and opens another`() {
        val (_, entry) = step(null, tbrRunning = true, rate = 1.5)
        val (actions, next) = step(entry, tbrRunning = true, rate = 2.0, phoneNow = now + 60_000L)
        assertEquals(2, actions.size)
        val stop = assertInstanceOf(Atc3TbrAction.Stop::class.java, actions[0])
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, actions[1])
        assertNotEquals(stop.endPumpId, start.pumpId)
        assertEquals(2.0, next?.rate)
    }

    @Test
    fun `a duration change in Status V1 closes one record and opens another`() {
        val (_, entry) = step(null, tbrRunning = true, rate = 1.5, durationMs = 30 * 60_000L, pumpStart = pumpTbr(now, 30))
        val (actions, next) = step(entry, tbrRunning = true, rate = 1.5, phoneNow = now + 60_000L, durationMs = 60 * 60_000L)
        assertEquals(2, actions.size)
        assertEquals(60 * 60_000L, next?.ownDurationMs)
    }

    @Test
    fun `a zero rate is a temporary basal, not a stop`() {
        val (actions, _) = step(null, tbrRunning = true, rate = 0.0)
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, actions.single())
        assertEquals(PumpSync.TemporaryBasalType.NORMAL, start.type)
    }

    // What the pump says about when its temporary basal began, object 0x0A

    @Test
    fun `a temporary basal is anchored where the pump says it began`() {
        val began = now - 15 * 60_000L
        val (actions, entry) = step(null, tbrRunning = true, rate = 1.5, pumpStart = pumpTbr(began, null))
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, actions.single())
        assertEquals(began, start.timestamp)
        assertEquals(began, entry?.startedAtMs)
    }

    @Test
    fun `without the pump's start the record falls back on when it was noticed`() {
        val (actions, _) = step(null, tbrRunning = true, rate = 1.5)
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, actions.single())
        assertEquals(now, start.timestamp)
    }

    @Test
    fun `a temporary basal renewed at the same rate and duration is a new one from the pump's later start`() {
        // The pump wrote the first one's record when it was replaced, and the second has a record
        // of its own to come: one row each, the first closed where the second began. Left as it
        // was, the second's insulin would land in the first.
        val first = now - 30 * 60_000L
        val (_, entry) = step(null, tbrRunning = true, rate = 2.0, phoneNow = first, pumpStart = pumpTbr(first, 60))
        val (actions, next) = step(entry, tbrRunning = true, rate = 2.0, durationMs = 60 * 60_000L, pumpStart = pumpTbr(now, 60))
        val stop = assertInstanceOf(Atc3TbrAction.Stop::class.java, actions[0])
        assertEquals(now, stop.timestamp)
        assertEquals(Atc3PumpId.tbrEndOf(entry!!.pumpId), stop.endPumpId)
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, actions[1])
        assertEquals(now, start.timestamp)
        assertNotEquals(entry.pumpId, next?.pumpId)
        assertEquals(now / 1000L, next?.pumpStartUtcSeconds)
    }

    @Test
    fun `another rate in the same minute is a new record from the same start, under its own id`() {
        // The pump keeps starts to the whole minute: the loop's 0.4 U/h and a stranger's 3.0 U/h set
        // 18 seconds later both carry the same minute.
        val minute = now - now % 60_000L
        val (_, loop) = Atc3TbrTracker.startedByAaps(minute + 11_000L, 0.4, 30 * 60_000L, pumpTbr(minute, 30, rate = 0.4))
        val (actions, stranger) = step(
            loop, tbrRunning = true, rate = 3.0, durationMs = 30 * 60_000L, phoneNow = minute + 100_000L,
            pumpStart = pumpTbr(minute, 30, rate = 3.0)
        )
        val stop = assertInstanceOf(Atc3TbrAction.Stop::class.java, actions[0])
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, actions[1])
        // The loop's row begins at its acknowledgement, and the stranger's stamp is before it.
        assertEquals(minute + 11_000L, stop.timestamp)
        assertEquals(minute + 11_000L, start.timestamp)
        assertNotEquals(loop.pumpId, start.pumpId)
        assertEquals(Atc3PumpId.tbrEndOf(loop.pumpId), stop.endPumpId)
        assertEquals(minute + 11_000L, stranger?.startedAtMs)
        assertEquals(minute / 1000L, stranger?.pumpStartUtcSeconds)
    }

    @Test
    fun `seven temporary basals of one minute get seven ids and seven end ids`() {
        val minute = now - now % 60_000L
        var previous: ActiveTbr? = null
        val ids = HashSet<Long>()
        val ends = HashSet<Long>()
        for (i in 0 until 7) {
            val (_, entry) = Atc3TbrTracker.startedByAaps(minute + i * 8_000L, 0.5 + i, 30 * 60_000L, pumpTbr(minute, 30), previous)
            ids.add(entry.pumpId)
            ends.add(Atc3PumpId.tbrEndOf(entry.pumpId))
            previous = entry
        }
        assertEquals(7, ids.size)
        assertEquals(7, ends.size)
    }

    @Test
    fun `the same temporary basal seen again is left alone`() {
        val began = now - 10 * 60_000L
        val begin = pumpTbr(began, 30)
        val (_, entry) = step(null, tbrRunning = true, rate = 2.0, phoneNow = began, pumpStart = begin)
        val (actions, next) = step(entry, tbrRunning = true, rate = 2.0, pumpStart = pumpTbr(began + 1_000L, 30))
        assertEquals(Atc3TbrAction.None, actions.single())
        assertEquals(entry?.pumpId, next?.pumpId)
    }

    @Test
    fun `a record that outlives the poll interval cannot lapse between two polls`() {
        assertTrue(Atc3Const.TBR_HORIZON_MS > 15 * 60_000L)
    }

    @Test
    fun `a duration the pump gave is used instead of the horizon`() {
        val began = now - 5 * 60_000L
        val (actions, entry) = step(null, tbrRunning = true, rate = 2.0, pumpStart = pumpTbr(began, 30))
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, actions.single())
        assertEquals(30 * 60_000L, start.durationMs)
        assertEquals(30 * 60_000L, entry?.ownDurationMs)
    }

    @Test
    fun `the horizon is still there when the pump gives no duration`() {
        val began = now - 5 * 60_000L
        val (actions, _) = step(null, tbrRunning = true, rate = 2.0, pumpStart = pumpTbr(began, null))
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, actions.single())
        assertEquals(Atc3Const.TBR_HORIZON_MS, start.durationMs)
    }

    // Our own temporary basal whose pump start was not read right after the command

    @Test
    fun `our own temporary basal is known under the pump's start once it is read, and stays where it began`() {
        val (_, ours) = Atc3TbrTracker.startedByAaps(now, rate = 1.25, durationMs = 30 * 60_000L)
        val pumpAt = now - 8_000L
        val (actions, entry) = step(ours, tbrRunning = true, rate = 1.25, phoneNow = now + 300_000L, durationMs = 30 * 60_000L, pumpStart = pumpTbr(pumpAt, 30))
        val retime = assertInstanceOf(Atc3TbrAction.Retime::class.java, actions.single())
        assertEquals(now, retime.timestamp)
        assertEquals(pumpAt / 1000L, retime.utcSeconds)
        assertEquals(ours.pumpId, retime.pumpId)
        assertEquals(30 * 60_000L, retime.durationMs)
        assertEquals(now, entry?.startedAtMs)
        assertEquals(pumpAt / 1000L, entry?.pumpStartUtcSeconds)
        assertEquals(pumpAt, entry?.pumpStartMs)
        assertEquals(ours.pumpId, entry?.pumpId)
    }

    @Test
    fun `a later command of the same rate and duration is not taken for ours, and changes nothing`() {
        val (_, ours) = Atc3TbrTracker.startedByAaps(now, rate = 1.25, durationMs = 30 * 60_000L)
        val theirs = now + 3 * 60_000L
        val (actions, entry) = step(ours, tbrRunning = true, rate = 1.25, phoneNow = now + 300_000L, durationMs = 30 * 60_000L, pumpStart = pumpTbr(theirs, 30))
        assertEquals(Atc3TbrAction.None, actions.single())
        assertEquals(ours.pumpId, entry?.pumpId)
        assertNull(entry?.pumpStartUtcSeconds)
    }

    // Which reads a tick needs

    @Test
    fun `object 0x0A is read only when the pump and the ledger disagree`() {
        val active = ActiveTbr(1L, now - 60_000L, 2.0, ours = true, ownDurationMs = 30 * 60_000L, pumpStartUtcSeconds = (now - 60_000L) / 1000L)
        val thirty = 30 * 60_000L
        assertFalse(Atc3TbrTracker.needsStartRead(active, false, true, 2.0, thirty, now))
        assertTrue(Atc3TbrTracker.needsStartRead(null, false, true, 2.0, thirty, now))
        assertTrue(Atc3TbrTracker.needsStartRead(active, false, true, 2.5, thirty, now))
        assertTrue(Atc3TbrTracker.needsStartRead(active, false, true, 2.0, 60 * 60_000L, now))
        assertTrue(Atc3TbrTracker.needsStartRead(active, false, true, 2.0, thirty, now + 31 * 60_000L))
        assertTrue(Atc3TbrTracker.needsStartRead(active.copy(pumpStartUtcSeconds = null), false, true, 2.0, thirty, now))
        assertFalse(Atc3TbrTracker.needsStartRead(active, true, true, 2.0, thirty, now))
        assertFalse(Atc3TbrTracker.needsStartRead(active, false, false, 0.0, null, now))
    }

    @Test
    fun `object 0x0A is read when the pump counts fewer minutes than ours has run`() {
        // A stranger's 3.0 U/h for 30 min over the loop's 3.0 U/h for
        // 30 min, ten minutes old. Status V1 looks the same but for the count of minutes.
        val active = ActiveTbr(1L, now - 10 * 60_000L, 3.0, ours = true, ownDurationMs = 30 * 60_000L, pumpStartUtcSeconds = (now - 10 * 60_000L) / 1000L)
        val thirty = 30 * 60_000L
        assertTrue(Atc3TbrTracker.needsStartRead(active, false, true, 3.0, thirty, now, elapsedMinutes = 1))
        assertFalse(Atc3TbrTracker.needsStartRead(active, false, true, 3.0, thirty, now, elapsedMinutes = 10))
        // The pump counts whole minutes and its clock sits a little off: a minute or two short is ours.
        assertFalse(Atc3TbrTracker.needsStartRead(active, false, true, 3.0, thirty, now, elapsedMinutes = 8))
        assertFalse(Atc3TbrTracker.needsStartRead(active, false, true, 3.0, thirty, now, elapsedMinutes = null))
    }

    @Test
    fun `a later start from the pump at the same rate and duration is another temporary basal`() {
        val begun = now - 10 * 60_000L
        val active = ActiveTbr(1L, begun, 3.0, ours = true, ownDurationMs = 30 * 60_000L, pumpStartUtcSeconds = begun / 1000L)
        val theirs = now - 60_000L
        val (actions, entry) = step(active, tbrRunning = true, rate = 3.0, phoneNow = now, durationMs = 30 * 60_000L, pumpStart = pumpTbr(theirs, 30, rate = 3.0))
        val stop = assertInstanceOf(Atc3TbrAction.Stop::class.java, actions[0])
        assertEquals(theirs, stop.timestamp)
        assertEquals(Atc3PumpId.tbrEndOf(1L), stop.endPumpId)
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, actions[1])
        assertEquals(theirs, start.timestamp)
        assertEquals(3.0, start.rate, 1e-9)
        assertEquals(theirs / 1000L, entry?.pumpStartUtcSeconds)
        assertFalse(entry!!.ours)
    }

    @Test
    fun `a stop worked out inside a temporary basal cuts it into the part before, the stop and the continuation`() {
        val begun = now - 10 * 60_000L
        val active = ActiveTbr(1L, begun, 3.0, ours = true, ownDurationMs = 30 * 60_000L, pumpStartUtcSeconds = begun / 1000L)
        val stopAt = begun + 3 * 60_000L
        val (actions, entry) = Atc3TbrTracker.splitByStop(active, stopAt, 100_000L)
        assertEquals(4, actions.size)
        val cut = assertInstanceOf(Atc3TbrAction.Stop::class.java, actions[0])
        assertEquals(stopAt, cut.timestamp)
        assertEquals(Atc3PumpId.tbrEndOf(1L), cut.endPumpId)
        val pause = assertInstanceOf(Atc3TbrAction.Start::class.java, actions[1])
        assertEquals(stopAt, pause.timestamp)
        assertEquals(PumpSync.TemporaryBasalType.PUMP_SUSPEND, pause.type)
        assertEquals(100_000L, pause.durationMs)
        val resumed = assertInstanceOf(Atc3TbrAction.Stop::class.java, actions[2])
        assertEquals(stopAt + 100_000L, resumed.timestamp)
        val goOn = assertInstanceOf(Atc3TbrAction.Start::class.java, actions[3])
        assertEquals(stopAt + 100_000L, goOn.timestamp)
        assertEquals(3.0, goOn.rate, 1e-9)
        assertEquals(begun + 30 * 60_000L - (stopAt + 100_000L), goOn.durationMs)
        assertEquals(begun / 1000L, entry?.pumpStartUtcSeconds)
        assertEquals(goOn.pumpId, entry?.pumpId)
        assertNotEquals(1L, goOn.pumpId)
    }

    @Test
    fun `a stop worked out at the very end of a temporary basal leaves no continuation`() {
        // Thirty minutes started 29.5 minutes ago: a stop of 100 s begun a minute ago outlasts it.
        val begun = now - 30 * 60_000L + 30_000L
        val active = ActiveTbr(1L, begun, 3.0, ours = true, ownDurationMs = 30 * 60_000L, pumpStartUtcSeconds = begun / 1000L)
        val (actions, entry) = Atc3TbrTracker.splitByStop(active, now - 60_000L, 100_000L)
        assertEquals(3, actions.size)
        assertNull(entry)
    }

    @Test
    fun `the continuation of a temporary basal a stop interrupted is not replaced by itself`() {
        // The loop's 0.0 U/h for 60 min stamped 06:20:00, a stop from
        // 06:25 to 06:31:03; the continuation runs from the resume for the 48 min left, while
        // Status V1 goes on saying 60. The same end is the same temporary basal.
        val stamp = now - 11 * 60_000L
        val stop = ActiveTbr(1L, now - 6 * 60_000L, 0.0, ours = false, ownDurationMs = Atc3Const.SUSPEND_HORIZON_MS, suspension = true)
        val resumedAt = now - 60_000L
        val (_, goingOn) = step(
            stop, tbrRunning = true, rate = 0.0, durationMs = 60 * 60_000L, phoneNow = now,
            pumpStart = pumpTbr(stamp, 60, rate = 0.0), resumedAtMs = resumedAt
        )
        assertEquals(resumedAt, goingOn?.startedAtMs)
        assertEquals(stamp + 60 * 60_000L - resumedAt, goingOn?.ownDurationMs)
        assertEquals(stamp, goingOn?.pumpStartMs)
        assertFalse(Atc3TbrTracker.needsStartRead(goingOn, false, true, 0.0, 60 * 60_000L, now + 60_000L))
        val (actions, same) = step(goingOn, tbrRunning = true, rate = 0.0, durationMs = 60 * 60_000L, phoneNow = now + 60_000L, pumpStart = pumpTbr(stamp, 60, rate = 0.0))
        assertEquals(Atc3TbrAction.None, actions.single())
        assertEquals(goingOn?.pumpId, same?.pumpId)
    }

    @Test
    fun `object 0x0B is read only for one gone before its time`() {
        val active = ActiveTbr(1L, now - 60_000L, 2.0, ours = false, ownDurationMs = 30 * 60_000L)
        assertTrue(Atc3TbrTracker.needsEndRead(active, false, false, now))
        assertFalse(Atc3TbrTracker.needsEndRead(active, false, false, now + 30 * 60_000L))
        assertFalse(Atc3TbrTracker.needsEndRead(active, false, true, now))
        assertFalse(Atc3TbrTracker.needsEndRead(active.copy(suspension = true), false, false, now))
        assertFalse(Atc3TbrTracker.needsEndRead(null, false, false, now))
    }

    // A stopped pump, which AAPS only learns about as a temporary basal of zero

    @Test
    fun `a stopped pump opens a temporary basal of zero`() {
        val (actions, entry) = step(null, suspended = true, tbrRunning = false, rate = 0.0)
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, actions.single())
        assertEquals(0.0, start.rate, 1e-9)
        assertEquals(PumpSync.TemporaryBasalType.PUMP_SUSPEND, start.type)
        assertEquals(0.0, entry?.rate)
        assertTrue(entry!!.suspension)
    }

    @Test
    fun `the record of a stopped pump does not expire between polls`() {
        val (actions, _) = step(null, suspended = true, tbrRunning = false, rate = 0.0)
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, actions.single())
        assertEquals(Atc3Const.SUSPEND_HORIZON_MS, start.durationMs)
        assertTrue(start.durationMs > Atc3Const.TBR_HORIZON_MS)
    }

    @Test
    fun `a stopped pump stays one record across polls`() {
        val (_, first) = step(null, suspended = true, tbrRunning = false, rate = 0.0)
        val (actions, second) = step(first, suspended = true, tbrRunning = false, rate = 0.0, phoneNow = now + 600_000L)
        val extend = assertInstanceOf(Atc3TbrAction.Extend::class.java, actions.single())
        assertEquals(first?.pumpId, extend.pumpId)
        assertEquals(PumpSync.TemporaryBasalType.PUMP_SUSPEND, extend.type)
        assertEquals(first?.pumpId, second?.pumpId)
    }

    @Test
    fun `resuming the pump closes the record`() {
        val (_, suspendedEntry) = step(null, suspended = true, tbrRunning = false, rate = 0.0)
        val (actions, none) = step(suspendedEntry, tbrRunning = false, rate = 0.0, phoneNow = now + 600_000L)
        assertEquals(now + 600_000L, assertInstanceOf(Atc3TbrAction.Stop::class.java, actions.single()).timestamp)
        assertNull(none)
    }

    @Test
    fun `a temporary basal after a stopped pump closes the stop where it begins`() {
        val (_, suspendedEntry) = step(null, suspended = true, tbrRunning = false, rate = 0.0)
        val began = now + 300_000L
        val (actions, entry) = step(suspendedEntry, tbrRunning = true, rate = 2.0, phoneNow = now + 600_000L, pumpStart = pumpTbr(began, 30))
        assertEquals(began, actions.filterIsInstance<Atc3TbrAction.Stop>().single().timestamp)
        assertFalse(entry!!.suspension)
    }

    @Test
    fun `a stopped pump wins over a temporary basal the status still reports`() {
        val (actions, entry) = step(null, suspended = true, tbrRunning = true, rate = 2.0)
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, actions.single())
        assertEquals(0.0, start.rate, 1e-9)
        assertEquals(PumpSync.TemporaryBasalType.PUMP_SUSPEND, start.type)
        assertEquals(0.0, entry?.rate)
    }

    @Test
    fun `stopping the pump during a temporary basal closes it and opens a suspension`() {
        val (_, running) = step(null, tbrRunning = true, rate = 1.5)
        val (actions, entry) = step(running, suspended = true, tbrRunning = true, rate = 1.5, phoneNow = now + 60_000L)
        assertEquals(2, actions.size)
        assertInstanceOf(Atc3TbrAction.Stop::class.java, actions[0])
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, actions[1])
        assertEquals(0.0, start.rate, 1e-9)
        assertEquals(PumpSync.TemporaryBasalType.PUMP_SUSPEND, start.type)
        assertEquals(0.0, entry?.rate)
    }

    // The moments of a stop and a resume, which are the pump's and not the tick's

    @Test
    fun `a stop is recorded from the moment the pump says it stopped`() {
        val stoppedAt = now - 90_000L
        val (actions, entry) = step(null, suspended = true, tbrRunning = false, rate = 0.0, pausedAtMs = stoppedAt)
        val start = assertInstanceOf(Atc3TbrAction.Start::class.java, actions.single())
        assertEquals(stoppedAt, start.timestamp)
        assertEquals(stoppedAt, entry!!.startedAtMs)
    }

    @Test
    fun `a stop during a temporary basal closes it at the pump's moment of stopping`() {
        val (_, running) = step(null, tbrRunning = true, rate = 3.0, pumpStart = pumpTbr(now - 40_000L, 30))
        val stoppedAt = now - 5_000L
        val (actions, entry) = step(running, suspended = true, tbrRunning = true, rate = 3.0, pausedAtMs = stoppedAt)
        assertEquals(stoppedAt, actions.filterIsInstance<Atc3TbrAction.Stop>().single().timestamp)
        assertEquals(stoppedAt, actions.filterIsInstance<Atc3TbrAction.Start>().single().timestamp)
        assertTrue(entry!!.suspension)
    }

    @Test
    fun `a stop known only to the minute does not begin before the temporary basal it interrupted`() {
        // The pump keeps the stop as 13:56; the temporary basal began at 13:56:19.
        val began = now - 41_000L
        val (_, running) = step(null, tbrRunning = true, rate = 3.0, pumpStart = pumpTbr(began, 30))
        val stopMinute = now - 60_000L
        val (actions, _) = step(running, suspended = true, tbrRunning = true, rate = 3.0, pausedAtMs = stopMinute)
        assertEquals(began, actions.filterIsInstance<Atc3TbrAction.Stop>().single().timestamp)
        assertEquals(began, actions.filterIsInstance<Atc3TbrAction.Start>().single().timestamp)
    }

    @Test
    fun `resuming closes the stop at the moment the pump says it resumed`() {
        val (_, stopped) = step(null, suspended = true, tbrRunning = false, rate = 0.0, pausedAtMs = now - 300_000L)
        val resumedAt = now - 37_000L
        val (actions, none) = step(stopped, tbrRunning = false, rate = 0.0, resumedAtMs = resumedAt)
        assertEquals(resumedAt, assertInstanceOf(Atc3TbrAction.Stop::class.java, actions.single()).timestamp)
        assertNull(none)
    }

    @Test
    fun `a temporary basal the stop interrupted goes on from the resume for what is left of its time`() {
        // 3.0 U/h for 30 minutes from 13:56:19, stopped at 13:56:59, resumed
        // at 13:59:23, and object 0x0A still says 13:56:19. AAPS gets a new record of the same
        // temporary basal from 13:59:23 for the 26 minutes 56 seconds that remain of it.
        val began = now - 300_000L
        val (_, stopped) = step(null, suspended = true, tbrRunning = false, rate = 0.0, pausedAtMs = now - 260_000L)
        val resumedAt = now - 116_000L
        val (actions, entry) = step(
            stopped, tbrRunning = true, rate = 3.0, durationMs = 30 * 60_000L,
            pumpStart = pumpTbr(began, 30, rate = 3.0), resumedAtMs = resumedAt
        )
        assertEquals(resumedAt, actions.filterIsInstance<Atc3TbrAction.Stop>().single().timestamp)
        val start = actions.filterIsInstance<Atc3TbrAction.Start>().single()
        assertEquals(resumedAt, start.timestamp)
        assertEquals(began + 30 * 60_000L - resumedAt, start.durationMs)
        assertEquals(3.0, start.rate, 1e-9)
        // Still the temporary basal the pump began earlier: its journal record will carry that start.
        assertEquals(began / 1000L, entry!!.pumpStartUtcSeconds)
        assertFalse(entry.suspension)
    }

    @Test
    fun `a temporary basal begun after the resume is recorded from its own start`() {
        val (_, stopped) = step(null, suspended = true, tbrRunning = false, rate = 0.0, pausedAtMs = now - 300_000L)
        val resumedAt = now - 120_000L
        val began = now - 60_000L
        val (actions, entry) = step(
            stopped, tbrRunning = true, rate = 2.0, durationMs = 30 * 60_000L,
            pumpStart = pumpTbr(began, 30, rate = 2.0), resumedAtMs = resumedAt
        )
        assertEquals(resumedAt, actions.filterIsInstance<Atc3TbrAction.Stop>().single().timestamp)
        val start = actions.filterIsInstance<Atc3TbrAction.Start>().single()
        assertEquals(began, start.timestamp)
        assertEquals(30 * 60_000L, start.durationMs)
        assertEquals(began / 1000L, entry!!.pumpStartUtcSeconds)
    }

    @Test
    fun `without a moment from the pump a stop and a resume are recorded from the tick`() {
        val (actions, _) = step(null, suspended = true, tbrRunning = false, rate = 0.0)
        assertEquals(now, assertInstanceOf(Atc3TbrAction.Start::class.java, actions.single()).timestamp)
    }
}
