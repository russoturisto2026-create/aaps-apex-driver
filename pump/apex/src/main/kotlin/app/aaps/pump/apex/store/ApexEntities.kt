package app.aaps.pump.apex.store

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One frame of the last answer of a kind, see [app.aaps.pump.apex.store.Snapshot]; position 0 is the newest. */
@Entity(tableName = "snapshot", primaryKeys = ["serial", "kind", "position"])
data class SnapshotEntity(
    val serial: String,
    val kind: String,
    val position: Int,
    val bytes: ByteArray,
    val readAtMs: Long
)

/**
 * One cell of the bolus journal, see [app.aaps.pump.apex.store.BolusCell]: the pump's record,
 * our picture of the bolus (the columns from `acceptedAtMs` to `completed`, null for somebody else's),
 * the record as corrected, whether its row is in AAPS, and the reconciliation that filled it.
 */
@Entity(tableName = "bolus_cell", indices = [Index(value = ["serial", "seq"], unique = true)])
data class BolusCellEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val serial: String,
    val seq: Long?,
    val bytes: ByteArray?,
    val offsetMs: Long?,
    val acceptedAtMs: Long?,
    val orderedUnits: Double?,
    val type: String?,
    val topSeqAtCommand: Long?,
    val seenUnits: Double?,
    val frames: Int,
    val cancelPressed: Boolean,
    val cancelAccepted: Boolean,
    val completed: Boolean,
    val units: Double?,
    val atMs: Long?,
    val faulty: Boolean,
    val inAaps: Boolean,
    val settledAtMs: Long?,
    val dayBolusUnits: Double?
)

/** The offset the pump's clock was last written in. */
@Entity(tableName = "clock")
data class ClockEntity(
    @PrimaryKey val serial: String,
    val offsetMs: Long,
    val writtenAtMs: Long,
    val reason: String
)

/** One clock write, see [app.aaps.pump.apex.store.ClockLogEntry]. */
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
