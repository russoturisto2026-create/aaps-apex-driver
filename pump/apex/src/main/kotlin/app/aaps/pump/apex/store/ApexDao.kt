package app.aaps.pump.apex.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class ApexDao {

    // Snapshots

    @Query("SELECT * FROM snapshot WHERE serial = :serial AND kind = :kind ORDER BY position ASC")
    abstract fun snapshot(serial: String, kind: String): List<SnapshotEntity>

    @Query("DELETE FROM snapshot WHERE serial = :serial AND kind = :kind")
    abstract fun dropSnapshot(serial: String, kind: String)

    @Insert
    abstract fun putSnapshot(frames: List<SnapshotEntity>)

    /** The new answer takes the place of the last one, in one step. */
    @Transaction
    open fun replaceSnapshot(serial: String, kind: String, frames: List<SnapshotEntity>) {
        dropSnapshot(serial, kind)
        putSnapshot(frames)
    }

    // Bolus cells

    @Query("SELECT * FROM bolus_cell WHERE serial = :serial ORDER BY id ASC")
    abstract fun cells(serial: String): List<BolusCellEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun putCell(cell: BolusCellEntity): Long

    @Query("DELETE FROM bolus_cell WHERE serial = :serial AND id IN (:ids)")
    abstract fun dropCells(serial: String, ids: List<Long>)

    // Clock

    @Query("SELECT * FROM clock WHERE serial = :serial")
    abstract fun clock(serial: String): ClockEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun putClock(clock: ClockEntity)

    @Insert
    abstract fun putClockLog(entry: ClockLogEntity)

    @Query("SELECT * FROM clock_log WHERE serial = :serial ORDER BY writtenAtMs ASC")
    abstract fun clockLog(serial: String): List<ClockLogEntity>

    @Query("DELETE FROM clock_log WHERE serial = :serial AND writtenAtMs < :olderThanMs")
    abstract fun pruneClockLog(serial: String, olderThanMs: Long)

    @Query("DELETE FROM clock_log WHERE serial = :serial AND bolusCount > :bolusCount")
    abstract fun dropClockLogAbove(serial: String, bolusCount: Long): Int

    // State notes

    @Query("SELECT value FROM state WHERE serial = :serial AND `key` = :key")
    abstract fun state(serial: String, key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun putState(state: StateEntity)

    @Query("DELETE FROM state WHERE serial = :serial AND `key` = :key")
    abstract fun removeState(serial: String, key: String)

    @Query("DELETE FROM snapshot WHERE serial = :serial")
    abstract fun dropSnapshots(serial: String)

    @Query("DELETE FROM bolus_cell WHERE serial = :serial")
    abstract fun dropAllCells(serial: String)

    @Query("DELETE FROM clock WHERE serial = :serial")
    abstract fun dropClock(serial: String)

    @Query("DELETE FROM clock_log WHERE serial = :serial")
    abstract fun dropClockLog(serial: String)

    @Query("DELETE FROM state WHERE serial = :serial")
    abstract fun dropStates(serial: String)

    /** Everything kept for this pump, in one go. */
    @Transaction
    open fun forgetPump(serial: String) {
        dropSnapshots(serial)
        dropAllCells(serial)
        dropClock(serial)
        dropClockLog(serial)
        dropStates(serial)
    }
}
