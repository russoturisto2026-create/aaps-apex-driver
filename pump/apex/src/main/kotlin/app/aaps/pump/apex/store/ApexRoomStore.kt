package app.aaps.pump.apex.store

import java.util.concurrent.Callable
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/** [ApexStore] on the Room database: a thin mapping, no logic. */
@SingleIn(AppScope::class)
@Inject
class ApexRoomStore(private val database: ApexDatabase, private val dao: ApexDao) : ApexStore {

    override fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable { block() })

    override fun snapshot(serial: String, kind: SnapshotKind): Snapshot? =
        dao.snapshot(serial, kind.name).takeIf { it.isNotEmpty() }?.let { rows -> Snapshot(rows.map { it.bytes }, rows.first().readAtMs) }

    override fun saveSnapshot(serial: String, kind: SnapshotKind, snapshot: Snapshot) =
        dao.replaceSnapshot(serial, kind.name, snapshot.frames.mapIndexed { position, bytes -> SnapshotEntity(serial, kind.name, position, bytes, snapshot.readAtMs) })

    override fun cells(serial: String): List<BolusCell> = dao.cells(serial).map { it.toCell() }

    override fun putCell(serial: String, cell: BolusCell): BolusCell {
        val id = dao.putCell(cell.toEntity(serial))
        return if (cell.id == 0L) cell.copy(id = id, action = cell.action?.copy(id = id)) else cell
    }

    override fun dropCells(serial: String, ids: Collection<Long>) {
        if (ids.isNotEmpty()) dao.dropCells(serial, ids.toList())
    }

    override fun clock(serial: String): ClockOffset? = dao.clock(serial)?.let { ClockOffset(it.offsetMs, it.writtenAtMs, it.reason) }

    override fun saveClock(serial: String, offset: ClockOffset, bolusCount: Long) {
        val old = dao.clock(serial)?.offsetMs
        dao.putClock(ClockEntity(serial, offset.offsetMs, offset.writtenAtMs, offset.reason))
        dao.putClockLog(
            ClockLogEntity(
                serial = serial, writtenAtMs = offset.writtenAtMs, oldOffsetMs = old, newOffsetMs = offset.offsetMs, reason = offset.reason,
                bolusCount = bolusCount
            )
        )
    }

    override fun clockLog(serial: String): List<ClockLogEntry> =
        dao.clockLog(serial).map { ClockLogEntry(it.writtenAtMs, it.oldOffsetMs, it.newOffsetMs, it.reason, it.bolusCount) }

    override fun pruneClockLog(serial: String, olderThanMs: Long) = dao.pruneClockLog(serial, olderThanMs)

    override fun dropClockLogAbove(serial: String, bolusCount: Long): Int = dao.dropClockLogAbove(serial, bolusCount)

    override fun state(serial: String, key: String): String? = dao.state(serial, key)

    override fun putState(serial: String, key: String, value: String?) {
        if (value == null) dao.removeState(serial, key) else dao.putState(StateEntity(serial, key, value))
    }

    override fun forgetPump(serial: String) = dao.forgetPump(serial)

    private fun BolusCellEntity.toCell(): BolusCell {
        val action = if (acceptedAtMs == null || orderedUnits == null || type == null || topSeqAtCommand == null) null
        else Action(id, acceptedAtMs, orderedUnits, type, topSeqAtCommand, seenUnits, frames, cancelPressed, cancelAccepted, completed)
        return BolusCell(id, seq, bytes, offsetMs, action, units, atMs, faulty, inAaps, settledAtMs, dayBolusUnits)
    }

    private fun BolusCell.toEntity(serial: String) = BolusCellEntity(
        id = id, serial = serial, seq = seq, bytes = bytes, offsetMs = offsetMs,
        acceptedAtMs = action?.acceptedAtMs, orderedUnits = action?.amount, type = action?.type, topSeqAtCommand = action?.journalTopSeq,
        seenUnits = action?.seenUnits, frames = action?.frames ?: 0, cancelPressed = action?.cancelPressed ?: false,
        cancelAccepted = action?.cancelAccepted ?: false, completed = action?.completed ?: false,
        units = units, atMs = atMs, faulty = faulty, inAaps = inAaps, settledAtMs = settledAtMs, dayBolusUnits = dayBolusUnits
    )
}
