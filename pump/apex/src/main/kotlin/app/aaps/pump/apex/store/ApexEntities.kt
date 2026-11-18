package app.aaps.pump.apex.store

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One frame of the last answer of a kind, see [app.aaps.pump.apex.account.store.Snapshot]; position 0 is the newest. */
@Entity(tableName = "snapshot", primaryKeys = ["serial", "kind", "position"])
data class SnapshotEntity(
    val serial: String,
    val kind: String,
    val position: Int,
    val bytes: ByteArray,
    val readAtMs: Long
)

/** The driver's own action with insulin, see [app.aaps.pump.apex.account.store.Action]. */
@Entity(tableName = "action", indices = [Index("serial", "acceptedAtMs")])
data class ActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val serial: String,
    val acceptedAtMs: Long,
    val amount: Double,
    val type: String,
    val journalTopSeq: Long,
    val recordSeq: Long?,
    val seenUnits: Double?,
    val done: Boolean
)

/** The offset the pump's clock was last written in. */
@Entity(tableName = "clock")
data class ClockEntity(
    @PrimaryKey val serial: String,
    val offsetMs: Long,
    val writtenAtMs: Long,
    val reason: String
)

/** One clock write, see [app.aaps.pump.apex.account.store.ClockLogEntry]. */
@Entity(tableName = "clock_log", indices = [Index("serial", "writtenAtMs")])
data class ClockLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val serial: String,
    val writtenAtMs: Long,
    val oldOffsetMs: Long?,
    val newOffsetMs: Long,
    val reason: String,
    val bolusCount: Long
)

/** A short note about the current state, by key. */
@Entity(tableName = "state", primaryKeys = ["serial", "key"])
data class StateEntity(
    val serial: String,
    val key: String,
    val value: String
)
