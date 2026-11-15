package app.aaps.pump.apex.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** The driver's own database: the last answers of the pump, its own actions, the clock offset and notes, all by pump serial. */
@Database(
    entities = [SnapshotEntity::class, ActionEntity::class, ClockEntity::class, ClockLogEntity::class, StateEntity::class],
    exportSchema = true,
    version = ApexDatabase.VERSION
)
abstract class ApexDatabase : RoomDatabase() {

    abstract fun dao(): ApexDao

    companion object {

        const val VERSION = 7

        fun build(context: Context): ApexDatabase =
            Room.databaseBuilder(context.applicationContext, ApexDatabase::class.java, "apex_database.db")
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
