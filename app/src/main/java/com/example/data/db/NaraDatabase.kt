package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FavoriteDramaEntity::class, WatchHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class NaraDatabase : RoomDatabase() {

    abstract fun naraDao(): NaraDao

    companion object {
        @Volatile
        private var INSTANCE: NaraDatabase? = null

        fun getInstance(context: Context): NaraDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NaraDatabase::class.java,
                    "nara_streaming.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
