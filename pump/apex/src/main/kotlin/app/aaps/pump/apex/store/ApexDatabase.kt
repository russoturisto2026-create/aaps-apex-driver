package app.aaps.pump.apex.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** The driver's own database: the last answers of the pump, its own actions, the clock offset and notes, all by pump serial. */
@Database(
    entities = [SnapshotEntity::class, ActionEntity::class, ClockEntity::class, ClockLogEntity::class, StateEntity::class],
    exportSchema = true,
    version = ApexDatabase.VERSION
)
abstract class ApexDatabase : RoomDatabase() {

    abstract fun dao(): ApexDao

    companion object {

        const val VERSION = 8

        /**
         * 7 to 8: the action table loses four columns nothing used: the kind (only boluses), the
         * duration (always 0), the clock offset (never set) and the completion flag (never read).
         * Old SQLite cannot drop a column, so the table is made anew and the rows are carried over.
         * Every bolus of ours that waits for its record stays as it was.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_7_8_SQL.forEach { db.execSQL(it) }
            }
        }

        /** The statements of [MIGRATION_7_8], in order; the new table is what the schema of version 8 holds. */
        val MIGRATION_7_8_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `action_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `serial` TEXT NOT NULL, " +
                "`acceptedAtMs` INTEGER NOT NULL, `amount` REAL NOT NULL, `type` TEXT NOT NULL, `journalTopSeq` INTEGER NOT NULL, " +
                "`recordSeq` INTEGER, `seenUnits` REAL, `done` INTEGER NOT NULL)",
            "INSERT INTO `action_new` (`id`, `serial`, `acceptedAtMs`, `amount`, `type`, `journalTopSeq`, `recordSeq`, `seenUnits`, `done`) " +
                "SELECT `id`, `serial`, `acceptedAtMs`, `amount`, `type`, `journalTopSeq`, `recordSeq`, `seenUnits`, `done` FROM `action`",
            "DROP TABLE `action`",
            "ALTER TABLE `action_new` RENAME TO `action`",
            "CREATE INDEX IF NOT EXISTS `index_action_serial_acceptedAtMs` ON `action` (`serial`, `acceptedAtMs`)"
        )

        /** A database older than version 7 is made anew: those versions never left the bench. */
        fun build(context: Context): ApexDatabase =
            Room.databaseBuilder(context.applicationContext, ApexDatabase::class.java, "apex_database.db")
                .addMigrations(MIGRATION_7_8)
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
