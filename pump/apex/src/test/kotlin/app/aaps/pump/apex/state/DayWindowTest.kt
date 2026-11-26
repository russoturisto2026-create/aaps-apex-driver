package app.aaps.pump.apex.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Where the comparison of the day starts, by the heartbeats of 08.11 on the bench. */
class DayWindowTest {

    private val window = DayWindow()
    private val plus8 = 8 * 3_600_000L
    private val plus3 = 3 * 3_600_000L
    private val day7 = 1_794_009_600L
    private val day8 = 1_794_096_000L

    /** A moment of 08.11 by the phone in +8. */
    private fun at(h: Int, m: Int) = day8 * 1000L + (h * 60 + m) * 60_000L - plus8

    private fun count(atMs: Long, dayKey: Long = day8, offsetMs: Long = plus8, bolus: Double, basal: Double = 0.0) =
        DayWindow.Count(atMs, dayKey, offsetMs, bolus + basal, bolus, basal)

    private fun move(now: DayWindow.Count, writtenAtMs: Long? = null) = window.move(now, writtenAtMs, at(0, 0))

    @Test
    fun `the first heartbeat after AAPS started is the start, with its count`() {
        val first = count(at(7, 25), bolus = 12.1)
        assertEquals(first, move(first))
        assertEquals(first, move(count(at(7, 30), bolus = 12.5)))
    }

    @Test
    fun `the pump's midnight starts from nothing at the phone's midnight`() {
        move(count(at(0, 0) - 300_000L, dayKey = day7, bolus = 20.0))
        val start = move(count(at(0, 0) + 5_000L, bolus = 0.0))
        assertEquals(at(0, 0), start.atMs)
        assertEquals(0.0, start.bolusUnits)
    }

    @Test
    fun `the zone moved and the pump went on in its day - the last heartbeat before the write is the start`() {
        // 12:55 in +8 the phone goes to Moscow: the date stays 08.11, the pump goes on in its day.
        move(count(at(7, 25), bolus = 12.1))
        val before = count(at(12, 55), bolus = 41.7)
        move(before)
        val start = move(count(at(13, 0), offsetMs = plus3, bolus = 41.7), writtenAtMs = at(12, 55) + 12_000L)
        assertEquals(before, start)
        assertEquals(before, move(count(at(13, 5), offsetMs = plus3, bolus = 42.2), writtenAtMs = at(12, 55) + 12_000L))
    }

    @Test
    fun `the zone moved over midnight and the pump opened a new day - the clock write is the start, from nothing`() {
        // 01:20 in +8 the phone goes to Moscow: the pump's clock goes back to 07.11 20:20 and opens a new 07.11.
        move(count(at(1, 15), bolus = 0.5))
        val writtenAtMs = at(1, 20) + 15_000L
        val start = move(count(at(1, 25), dayKey = day7, offsetMs = plus3, bolus = 0.0), writtenAtMs)
        assertEquals(at(1, 20), start.atMs)
        assertEquals(0.0, start.bolusUnits)
        assertEquals(start, move(count(at(1, 30), dayKey = day7, offsetMs = plus3, bolus = 0.4), writtenAtMs))
    }

    @Test
    fun `the start is the whole minute of the heartbeat - the rows the pump wrote after it begin at its minute`() {
        // 18.11: the heartbeat of 17:22 came at 17:22:00.283; the stopped bolus after it has its row at 17:22:00.000.
        val first = move(count(at(17, 22) + 283L, bolus = 261.225))
        assertEquals(at(17, 22), first.atMs)
    }

    @Test
    fun `a count below the start is a day begun over after a power loss - that heartbeat is the start`() {
        move(count(at(7, 25), bolus = 12.1, basal = 1.0))
        val anew = count(at(9, 0), bolus = 0.0, basal = 0.05)
        assertEquals(anew, move(anew))
    }
}
