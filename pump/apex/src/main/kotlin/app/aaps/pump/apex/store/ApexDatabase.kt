package app.aaps.pump.apex.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** The driver's own database: the last answers of the pump, its own bolus journal, the clock offset and notes, all by pump serial. */
@Database(
    entities = [SnapshotEntity::class, BolusCellEntity::class, ClockEntity::class, ClockLogEntity::class, StateEntity::class],
    exportSchema = true,
    version = ApexDatabase.VERSION
)
abstract class ApexDatabase : RoomDatabase() {

    abstract fun dao(): ApexDao

    companion object {

        const val VERSION = 13

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

        /**
         * 8 to 9: the action table becomes the picture of our bolus: the progress frames seen, the cancel
         * asked and accepted, the completion. A bolus of ours that waits keeps what it had, with nothing seen.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_8_9_SQL.forEach { db.execSQL(it) }
            }
        }

        /** The statements of [MIGRATION_8_9], in order. */
        val MIGRATION_8_9_SQL = listOf("frames", "cancelPressed", "cancelAccepted", "completed").map {
            "ALTER TABLE `action` ADD COLUMN `$it` INTEGER NOT NULL DEFAULT 0"
        }

        /**
         * 9 to 10: the driver keeps its own bolus journal, the cells. The records of the copy of the
         * journal (the last answer, newest first, the first with the number of records seen) and our
         * boluses not reconciled yet move into cells; the copy and the action table go. The records of the
         * copy were reconciled by the older driver: their rows are in AAPS. When the last answer was read
         * becomes a note.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_9_10_SQL.forEach { db.execSQL(it) }
            }
        }

        /** The create statement of the cells, as the schema of version 10 holds it. */
        const val BOLUS_CELL_SQL =
            "CREATE TABLE IF NOT EXISTS `bolus_cell` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `serial` TEXT NOT NULL, `seq` INTEGER, " +
                "`bytes` BLOB, `offsetMs` INTEGER, `acceptedAtMs` INTEGER, `orderedUnits` REAL, `type` TEXT, `topSeqAtCommand` INTEGER, " +
                "`seenUnits` REAL, `frames` INTEGER NOT NULL, `cancelPressed` INTEGER NOT NULL, `cancelAccepted` INTEGER NOT NULL, " +
                "`completed` INTEGER NOT NULL, `units` REAL, `atMs` INTEGER, `faulty` INTEGER NOT NULL, `inAaps` INTEGER NOT NULL)"

        /** The statements of [MIGRATION_9_10], in order. */
        val MIGRATION_9_10_SQL = listOf(
            BOLUS_CELL_SQL,
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_bolus_cell_serial_seq` ON `bolus_cell` (`serial`, `seq`)",
            "INSERT INTO `bolus_cell` (`serial`, `seq`, `bytes`, `frames`, `cancelPressed`, `cancelAccepted`, `completed`, `faulty`, `inAaps`) " +
                "SELECT s.`serial`, CAST(st.`value` AS INTEGER) - s.`position`, s.`bytes`, 0, 0, 0, 0, 0, 1 FROM `snapshot` s " +
                "JOIN `state` st ON st.`serial` = s.`serial` AND st.`key` = 'recordsSeen.BOLUS' WHERE s.`kind` = 'BOLUS' ORDER BY s.`serial`, s.`position` DESC",
            "INSERT INTO `bolus_cell` (`serial`, `acceptedAtMs`, `orderedUnits`, `type`, `topSeqAtCommand`, `seenUnits`, `frames`, `cancelPressed`, " +
                "`cancelAccepted`, `completed`, `faulty`, `inAaps`) SELECT `serial`, `acceptedAtMs`, `amount`, `type`, `journalTopSeq`, `seenUnits`, " +
                "`frames`, `cancelPressed`, `cancelAccepted`, `completed`, 0, 1 FROM `action` WHERE `done` = 0 ORDER BY `acceptedAtMs`, `id`",
            "INSERT OR REPLACE INTO `state` (`serial`, `key`, `value`) SELECT `serial`, 'bolusJournalReadAtMs', CAST(`readAtMs` AS TEXT) FROM `snapshot` " +
                "WHERE `kind` = 'BOLUS' AND `position` = 0",
            "DELETE FROM `snapshot` WHERE `kind` = 'BOLUS'",
            "DROP TABLE `action`"
        )

        /**
         * 10 to 11: the notes older drivers left and nothing reads any more go: the bolus count mark, the
         * faulty cells, the temporary basal notes and the count of temporary basal records. The tables stay.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(MIGRATION_10_11_SQL)
            }
        }

        /** The notes [MIGRATION_10_11] removes. */
        val STALE_KEYS = listOf(
            "bolusCountMark", "bolusFaultyCells", "tbrGateMark", "ourTbrs", "foreignTbrs", "lastTbr", "openTbr", "tbrKnownMs", "recordsSeen.TBR"
        )

        /** The statement of [MIGRATION_10_11]. */
        val MIGRATION_10_11_SQL = "DELETE FROM `state` WHERE `key` IN (" + STALE_KEYS.joinToString(", ") { "'$it'" } + ")"

        /** 11 to 12: the base of the old bolus row numbers goes: a row's key comes from the record itself now. The tables stay. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(MIGRATION_11_12_SQL)
            }
        }

        /** The notes [MIGRATION_11_12] removes. */
        val STALE_KEYS_12 = listOf("pumpIdBase")

        /** The statement of [MIGRATION_11_12]. */
        val MIGRATION_11_12_SQL = "DELETE FROM `state` WHERE `key` IN (" + STALE_KEYS_12.joinToString(", ") { "'$it'" } + ")"

        /** 12 to 13: each cell keeps when the reconciliation that filled it ran and the bolus field of its day then. Old cells have none. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_12_13_SQL.forEach { db.execSQL(it) }
            }
        }

        /** The statements of [MIGRATION_12_13]. */
        val MIGRATION_12_13_SQL = listOf(
            "ALTER TABLE `bolus_cell` ADD COLUMN `settledAtMs` INTEGER",
            "ALTER TABLE `bolus_cell` ADD COLUMN `dayBolusUnits` REAL"
        )

        fun build(context: Context): ApexDatabase =
            Room.databaseBuilder(context.applicationContext, ApexDatabase::class.java, "apex_database.db")
                .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                // A base older than version 7, or newer than this driver (an older driver put over a newer one), is made anew.
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
