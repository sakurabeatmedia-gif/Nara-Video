package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FavoriteDramaEntity::class, WatchHistoryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class NaraDatabase : RoomDatabase() {

    abstract fun naraDao(): NaraDao

    companion object {
        @Volatile
        private var INSTANCE: NaraDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS favorites_new (
                        provider TEXT NOT NULL DEFAULT 'MELOLO',
                        dramaId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        cover TEXT NOT NULL,
                        totalEpisodes INTEGER NOT NULL DEFAULT 0,
                        categories TEXT NOT NULL DEFAULT '',
                        addedAt INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(provider, dramaId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO favorites_new (provider, dramaId, title, cover, totalEpisodes, categories, addedAt)
                    SELECT 'MELOLO', dramaId, title, cover, totalEpisodes, categories, addedAt FROM favorites
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE favorites")
                db.execSQL("ALTER TABLE favorites_new RENAME TO favorites")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS watch_history_new (
                        provider TEXT NOT NULL DEFAULT 'MELOLO',
                        dramaId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        cover TEXT NOT NULL,
                        lastEpisode INTEGER NOT NULL DEFAULT 1,
                        lastPositionMs INTEGER NOT NULL DEFAULT 0,
                        durationMs INTEGER NOT NULL DEFAULT 0,
                        totalEpisodes INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(provider, dramaId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO watch_history_new (provider, dramaId, title, cover, lastEpisode, lastPositionMs, durationMs, totalEpisodes, updatedAt)
                    SELECT 'MELOLO', dramaId, title, cover, lastEpisode, lastPositionMs, durationMs, totalEpisodes, updatedAt FROM watch_history
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE watch_history")
                db.execSQL("ALTER TABLE watch_history_new RENAME TO watch_history")
            }
        }

        fun getInstance(context: Context): NaraDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NaraDatabase::class.java,
                    "nara_streaming.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
