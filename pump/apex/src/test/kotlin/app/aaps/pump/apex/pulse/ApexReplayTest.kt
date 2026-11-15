package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.account.basal.ExactBasalMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A recorded pump fed through the real heartbeat code, heartbeat by heartbeat. Skipped when
 * `APEX_REPLAY_FILE` does not name a file. The report goes to `build/replay-report.txt`.
 */
class ApexReplayTest {

    private val file = ReplayFile.fromEnvironment()

    @Test
    fun `the recorded exchange runs through the heartbeat code and is judged`() {
        assumeTrue(file != null, "APEX_REPLAY_FILE is not set or the file is missing")
        val record = file!!
        val rig = ReplayRig(record, ExactBasalMode.OFF)
        val off = ReplayRun(record, rig.run(), rig.serial)
        val halfRig = ReplayRig(record, ExactBasalMode.HEARTBEAT)
        val half = ReplayRun(record, halfRig.run(), halfRig.serial)
        val report = ReplayReport()
        report.line("Replay of ${record.beats.size} heartbeats and ${record.commands.size} commands of another client, a pump with none of the optional objects.")
        report.line("Checks 1, 2, 3, 5 and 6 are made with the exact basal record off; check 4 with it on, by heartbeats.")
        ReplayCheckTbr.run(off, report)
        ReplayCheckBolus.run(off, report)
        ReplayCheckBasal.run(half, report)
        ReplayCheckBeats.run(off, report)
        report.section("Problems found, ${report.problems.size}")
        report.problems.forEachIndexed { i, p -> report.line("  ${i + 1}. $p") }
        val dir = File("build").apply { mkdirs() }
        File(dir, "replay-report.txt").writeText(report.text())
        File(dir, "replay-timeline-off.txt").writeText(ReplayTimeline.full(off.results))
        File(dir, "replay-timeline-half.txt").writeText(ReplayTimeline.full(half.results))
        assertEquals(record.beats.size, off.results.size)
        assertEquals(record.beats.size, half.results.size)
        assertTrue(report.failures.isEmpty(), "the driver failed what is certain:\n" + report.failures.joinToString("\n"))
    }
}
