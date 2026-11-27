package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.protocol.ApexDailyRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The day the screen compares, as it stood at the mark of the heartbeat. */
class PictureReportTest {

    /** 28.11 by the pump's clock, written in a time zone 8 hours ahead of UTC. */
    private val dayKey = 1_795_824_000L
    private val offsetMs = 8 * 3_600_000L
    private val midnightMs = dayKey * 1000L - offsetMs

    @Test
    fun `at the pump's midnight the new day holds nothing at the mark, later it is as read`() {
        // Read at 00:00:15 with one portion given after midnight.
        val read = ApexDailyRecord(0, 0.0, 0.0, 0.025, dayKey)
        val atMidnight = PictureReport.atMark(read, midnightMs + 15_400L, offsetMs)
        assertEquals(0.0, atMidnight.bolusUnits + atMidnight.basalUnits + atMidnight.tbrUnits)
        assertEquals(dayKey, atMidnight.dayClockSeconds)
        val atFive = ApexDailyRecord(0, 0.0, 0.0, 0.125, dayKey)
        assertEquals(atFive, PictureReport.atMark(atFive, midnightMs + 5 * 60_000L + 10_700L, offsetMs))
    }
}
