package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.account.AapsWrite
import app.aaps.pump.apex.account.time.ApexClock
import app.aaps.pump.apex.protocol.ApexBolusRecord
import app.aaps.pump.apex.protocol.ApexResponseFrame
import app.aaps.pump.apex.protocol.ApexResponseParser
import kotlin.math.abs

/** Check 3: the boluses given over time, the journal kept as the past, no row twice, and every notice or deep path event. */
object ReplayCheckBolus {

    /** The records of the journal answer of heartbeat [i], newest first. */
    fun records(run: ReplayRun, i: Int): List<ApexBolusRecord> =
        run.results[i].beat.journalHex.orEmpty().mapNotNull { hex -> frame(hex)?.let { ApexBolusRecord.decode(it) } }

    fun frame(hex: String): ApexResponseFrame? =
        ApexResponseParser().feed(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }).firstOrNull()

    private val routine = listOf("got no answer", "no answer to the read of the", "not on the half-hour grid")

    fun run(run: ReplayRun, report: ReplayReport) {
        report.section("3. Boluses")
        val rows = run.bolusRows
        val firstJournal = run.results.firstOrNull { it.beat.journalHex != null }
        report.line("  bolus rows written: ${rows.size}")
        rows.forEach { report.line("    ${run.at(it.beat)} id ${it.write.pumpId} at ${ReplayTimeline.clock(it.write.timestampMs)} ${it.write.amountUnits} U ${it.write.type}") }
        firstJournal?.let { first ->
            val early = rows.filter { it.beat <= first.index }
            report.line("  first journal answer at ${run.at(first.index)}: rows written at or before it: ${early.size}")
            if (early.isNotEmpty()) report.failure("the first journal answer gave ${early.size} bolus rows, it must be kept as the past")
            val top = records(run, first.index).firstOrNull()
            val older = rows.filter { top != null && it.write.timestampMs <= top.clockSeconds * 1000 }
            if (older.isNotEmpty()) report.failure("a row stands at or before the top record of the first answer (the past): ${older.map { it.write.pumpId }}")
        }
        for (c in run.file.commands.filter { it.op == "13" }) command(run, report, c)
        val dup = rows.groupBy { it.write.pumpId }.filter { it.value.size > 1 }.keys +
            rows.groupBy { it.write.timestampMs to it.write.amountUnits }.filter { it.value.size > 1 }.flatMap { e -> e.value.map { it.write.pumpId } }
        if (dup.isNotEmpty()) report.failure("bolus rows twice: ${dup.toSet()}")
        newRecords(run, report)
        events(run, report)
    }

    /** One bolus given over time: exactly one row, with what the record says was delivered. */
    private fun command(run: ReplayRun, report: ReplayReport, c: ReplayCommand) {
        val dose = (c.payload.substring(2, 4).toInt(16) * 256 + c.payload.substring(0, 2).toInt(16)) * 0.025
        val pumpSeconds = (c.ms - run.lagNear(c.ms)) / 1000
        val seen = run.results.indices.filter { run.results[it].beat.journalHex != null }.flatMap { records(run, it) }
        val record = seen.firstOrNull { it.clockSeconds in (pumpSeconds - 60)..(pumpSeconds + 120) }
        val head = "13 ${ReplayTimeline.clock(c.ms)} asked $dose U"
        if (record == null) return report.failure("$head: no record in any journal answer")
        val delivered = record.totalDeliveredUnits
        val minute = ApexClock.minuteStart(record.clockSeconds * 1000)
        val mine = run.bolusRows.filter { abs(it.write.timestampMs - minute) < ApexClock.MINUTE_MS }
        report.line("  $head: record key ${record.clockSeconds} (${ReplayTimeline.clock(record.clockSeconds * 1000)}) delivered ${record.deliveredUnits} + extended ${record.extendedDeliveredUnits} = $delivered U; rows ${mine.size}")
        if (mine.size != 1) report.failure("$head: ${mine.size} rows, expected one")
        else if (abs(mine[0].write.amountUnits - delivered) > 1e-6) report.failure("$head: row ${mine[0].write.amountUnits} U, the record says $delivered U")
    }

    /** Every record that is new in the journal after the first answer has one row, and every row has a record. */
    private fun newRecords(run: ReplayRun, report: ReplayReport) {
        val first = run.results.indexOfFirst { it.beat.journalHex != null }.takeIf { it >= 0 } ?: return
        val past = records(run, first).map { it.clockSeconds to it.rawDelivered + it.rawExtendedDelivered }.toSet()
        val later = run.results.indices.filter { it > first && run.results[it].beat.journalHex != null }.flatMap { records(run, it) }
            .map { it.clockSeconds to it.rawDelivered + it.rawExtendedDelivered }.toSet() - past
        report.line("  records new after the first answer: ${later.size}, rows: ${run.bolusRows.size}")
        if (later.size != run.bolusRows.size) report.problem("records new after the first answer ${later.size}, rows ${run.bolusRows.size}")
    }

    /** Notices, deep path events and the driver's warnings and errors that are not plain silence, with the frames of the heartbeat. */
    fun events(run: ReplayRun, report: ReplayReport) {
        val deepNames = setOf(
            "bolus_faulty_cell", "bolus_journal_restart", "bolus_journal_whole", "bolus_journal_whole_unread", "bolus_journal_whole_unlaid", "bolus_journal_started_over", "bolus_journal_unlaid", "bolus_no_record",
            "bolus_unknown", "bolus_by_days", "bolus_row_out_of_bounds", "bolus_days_out_of_bounds", "bolus_records_over_days"
        )
        var count = 0
        for (r in run.results) {
            val lines = r.logs.filter { l -> l.level != "INFO" && routine.none { l.message.contains(it) } }.map { "${it.level} ${it.message}" } +
                r.trace.filter { it.name in deepNames }.map { "TRACE $it" } +
                r.writes.filterIsInstance<AapsWrite.Notice>().map { "NOTICE ${it.kind} ${it.units} U" }
            if (lines.isEmpty()) continue
            count++
            report.line("  ${run.at(r.index)}: ${lines.joinToString(" | ")}")
            r.beat.journalHex?.take(3)?.forEach { report.line("      journal top frame $it") }
            report.problem("event at ${run.at(r.index)}: ${lines.first()}")
        }
        report.line("  notices ${run.notices.size}, heartbeats with a deep path event or a warning that is not plain silence: $count")
    }
}
