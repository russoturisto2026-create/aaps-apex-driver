package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.account.basal.ExactBasalMode
import app.aaps.pump.apex.account.journal.JournalSnapshot
import app.aaps.pump.apex.account.store.SnapshotKind
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.state.ApexAbilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A recorded pump that has every object, fed twice through the real heartbeat code: once with the
 * driver told the pump has all the objects, once with none of the optional ones. The two ways of
 * learning what the pump did are compared on the same data. Skipped when `APEX_REPLAY_FULL_FILE`
 * does not name a file. The report goes to `build/replay-two-ways.txt`.
 */
class ApexReplayTwoWaysTest {

    private val file = ReplayAllFile.fromEnvironment()

    private class Pass(val run: ReplayRun, val source: ReplaySource)

    @Test
    fun `the same record gives the same facts with every object and with none`() {
        assumeTrue(file != null, "APEX_REPLAY_FULL_FILE is not set or the file is missing")
        val record = file!!
        val all = pass(record, ApexAbilities(lastTbrCommand = true, finishedTbr = true, dayRecord = true, statusV2 = true), "SVDJAB")
        val none = pass(record, ApexAbilities(lastTbrCommand = false, finishedTbr = false, dayRecord = false, statusV2 = false), "SJ")
        val report = ReplayReport()
        report.line("Replay of ${record.beatTimes.size} heartbeats, ${record.commands.size} commands and ${record.clockWrites.size} clock writes, a pump with every object.")
        report.line("Pass all: the driver is told the pump has every object. Pass none: it is told it has none. Both with the exact basal record by half hours.")
        report.line("Every temporary basal and bolus is another client's to the driver under test, in both passes.")
        report.line("Answers per heartbeat: ${record.answers.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value.size}" }}; commands ${record.commands.groupingBy { it.group + " " + it.op }.eachCount().entries.sortedBy { it.key }.joinToString()}")
        clockMoves(record, report)
        ReplayCompareTbr.run(all.run, none.run, record, all.source.notes, report)
        for ((name, p) in listOf("all" to all, "none" to none)) {
            val sub = ReplayReport()
            ReplayCheckTbr.run(p.run, sub)
            report.section("2. Pass $name against the commands, and the start minute from the status")
            sub.lines.drop(1).forEach { report.line(it) }
            sub.problems.forEach { report.problem("pass $name: $it") }
            ReplayCompareTbr.placement(p.run, record, name, report)
        }
        ReplayCompareBolus.run(all.run, none.run, record, report)
        ReplayCompareBasal.run(all.run, none.run, record, report)
        for ((name, p) in listOf("all" to all, "none" to none)) {
            val sub = ReplayReport()
            ReplayCheckBasal.run(p.run, sub)
            report.section("4b. Pass $name, each period: count moved, boluses off, result")
            sub.lines.drop(1).forEach { report.line(it) }
            sub.problems.forEach { report.problem("pass $name: $it") }
        }
        ReplayCompareAsks.run(all.run, all.source, none.run, none.source, report)
        val failures = ArrayList<String>(report.failures)
        for ((name, p) in listOf("all" to all, "none" to none)) {
            val sub = ReplayReport()
            ReplayCheckBeats.run(p.run, sub)
            report.section("6. Pass $name, heartbeats without answers and other things")
            sub.lines.forEach { report.line(it) }
            sub.problems.forEach { report.problem("pass $name: $it") }
            failures.addAll(sub.failures.map { "pass $name: $it" })
        }
        report.section("Problems found, ${report.problems.size}")
        report.problems.forEachIndexed { i, p -> report.line("  ${i + 1}. $p") }
        val dir = File("build").apply { mkdirs() }
        File(dir, "replay-two-ways.txt").writeText(report.text())
        File(dir, "replay-two-ways-all.txt").writeText(ReplayTimeline.full(all.run.results))
        File(dir, "replay-two-ways-none.txt").writeText(ReplayTimeline.full(none.run.results))
        assertEquals(record.beatTimes.size, all.run.results.size)
        assertEquals(record.beatTimes.size, none.run.results.size)
        assertTrue(failures.isEmpty(), "the driver failed what is certain:\n" + failures.joinToString("\n"))
    }

    private fun pass(record: ReplayAllFile, abilities: ApexAbilities, kinds: String): Pass {
        val source = ReplaySource(record, kinds.toSet())
        val replay = record.asReplayFile()
        lateinit var rig: ReplayRig
        val applied = HashSet<ClockWrite>()
        /** The clock writer of the recording driver, as it ran: the new offset and the bolus count are kept, the old span goes to the log. */
        fun writeClocksUpTo(ms: Long) {
            for (w in record.clockWrites.filter { it.changed && it.atMs <= ms && it !in applied }) {
                applied.add(w)
                val count = JournalSnapshot(rig.store, rig.serial, SnapshotKind.BOLUS).topSeq()
                ApexClock.clockWritten(rig.store, rig.serial, w.offsetMs, w.atMs, "replay", count)
            }
        }
        source.beforeServe = ::writeClocksUpTo
        rig = ReplayRig(replay, ExactBasalMode.HEARTBEAT, abilities, source) { k -> writeClocksUpTo(record.windowEnd(k) - 1) }
        return Pass(ReplayRun(replay, rig.run(), rig.serial, record::offsetAt), source)
    }

    private fun clockMoves(record: ReplayAllFile, report: ReplayReport) {
        val moves = record.clockWrites.filter { it.changed }
        report.line("Clock writes ${record.clockWrites.size}; moves of the offset ${moves.size}: " +
            moves.joinToString { "${ReplayTimeline.clock(it.atMs)} -> ${it.offsetMs / 60_000} min" })
        val before = record.clockWrites.count { it.atMs < record.beatTimes.first() }
        if (before > 0) report.line("  $before clock writes come before the first heartbeat and are not applied")
    }
}
