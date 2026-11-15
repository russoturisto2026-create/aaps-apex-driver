package app.aaps.pump.apex.store

import app.aaps.pump.apex.account.store.Action
import app.aaps.pump.apex.account.store.ActionKind
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.ClockLogEntry
import app.aaps.pump.apex.account.store.ClockOffset
import app.aaps.pump.apex.account.store.Snapshot
import app.aaps.pump.apex.account.store.SnapshotKind
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

    override fun actions(serial: String): List<Action> = dao.actions(serial).map { it.toAction() }

    override fun addAction(serial: String, action: Action): Action {
        val id = dao.putAction(action.toEntity(serial).copy(id = 0L))
        return action.copy(id = id)
    }

    override fun updateAction(serial: String, action: Action) {
        dao.putAction(action.toEntity(serial))
    }

    override fun dropActions(serial: String, kind: ActionKind) =
        dao.dropActions(serial, kind.name)

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

    override fun state(serial: String, key: String): String? = dao.state(serial, key)

    override fun putState(serial: String, key: String, value: String?) {
        if (value == null) dao.removeState(serial, key) else dao.putState(StateEntity(serial, key, value))
    }

    override fun forgetPump(serial: String) = dao.forgetPump(serial)

    private fun ActionEntity.toAction() =
        Action(id, ActionKind.valueOf(kind), acceptedAtMs, amount, type, journalTopSeq, clockOffsetMs, recordSeq, seenUnits, finished, done)

    private fun Action.toEntity(serial: String) =
        ActionEntity(id, serial, kind.name, acceptedAtMs, amount, 0, type, journalTopSeq, clockOffsetMs, recordSeq, seenUnits, finished, done)
}
