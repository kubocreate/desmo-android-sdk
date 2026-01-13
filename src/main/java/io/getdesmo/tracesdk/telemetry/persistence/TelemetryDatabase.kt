package io.getdesmo.tracesdk.telemetry.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for persisting telemetry data.
 *
 * Stores pending telemetry batches that failed to upload, enabling
 * retry when network becomes available.
 */
@Database(
    entities = [PendingTelemetryEntity::class],
    version = 1,
    exportSchema = false
)
internal abstract class TelemetryDatabase : RoomDatabase() {

    abstract fun telemetryDao(): TelemetryDao

    companion object {
        private const val DATABASE_NAME = "desmo_telemetry.db"

        @Volatile
        private var instance: TelemetryDatabase? = null

        /**
         * Get the singleton database instance.
         *
         * Uses double-checked locking for thread safety.
         */
        fun getInstance(context: Context): TelemetryDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): TelemetryDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                TelemetryDatabase::class.java,
                DATABASE_NAME
            ).build()
        }
    }
}
