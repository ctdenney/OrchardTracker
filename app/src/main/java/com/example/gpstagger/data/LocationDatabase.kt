package com.example.gpstagger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TaggedLocation::class, DeletedLocation::class, RowEntity::class],
    version = 6,
    exportSchema = false
)
abstract class LocationDatabase : RoomDatabase() {

    abstract fun locationDao(): LocationDao
    abstract fun rowDao(): RowDao

    companion object {
        @Volatile
        private var INSTANCE: LocationDatabase? = null

        /** v1 -> v2: adds the slot column (default -1 for pre-existing rows). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE tagged_locations ADD COLUMN slot INTEGER NOT NULL DEFAULT -1"
                )
            }
        }

        /** v2 -> v3: adds uuid, updated_at, and synced columns for server sync. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE tagged_locations ADD COLUMN uuid TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE tagged_locations ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE tagged_locations ADD COLUMN synced INTEGER NOT NULL DEFAULT 0"
                )
                // Backfill UUIDs and updated_at for existing rows
                database.execSQL(
                    "UPDATE tagged_locations SET uuid = (lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))),2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))),2) || '-' || lower(hex(randomblob(6)))), updated_at = timestamp WHERE uuid = ''"
                )
            }
        }

        /** v3 -> v4: adds deleted_locations tombstone table for sync. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS deleted_locations (uuid TEXT NOT NULL PRIMARY KEY, deletedAt INTEGER NOT NULL)"
                )
            }
        }

        /** v4 -> v5: adds rows table for orchard-row definitions synced from server. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS rows (
                        uuid TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        block TEXT NOT NULL DEFAULT '',
                        start_lat REAL NOT NULL,
                        start_lng REAL NOT NULL,
                        end_lat REAL NOT NULL,
                        end_lng REAL NOT NULL,
                        width_m REAL NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL,
                        deleted INTEGER NOT NULL DEFAULT 0,
                        synced INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /** v5 -> v6: adds coverage span columns to the rows table. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE rows ADD COLUMN coverage_min_t REAL")
                database.execSQL("ALTER TABLE rows ADD COLUMN coverage_max_t REAL")
            }
        }

        fun getDatabase(context: Context): LocationDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    LocationDatabase::class.java,
                    "gps_tagger_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
