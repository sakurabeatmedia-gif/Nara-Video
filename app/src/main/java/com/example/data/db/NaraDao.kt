package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NaraDao {

    // --- FAVORITES ---
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteDramaEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE provider = :provider AND dramaId = :dramaId)")
    fun isFavorite(provider: String, dramaId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteDramaEntity)

    @Query("DELETE FROM favorites WHERE provider = :provider AND dramaId = :dramaId")
    suspend fun deleteFavorite(provider: String, dramaId: String)

    // --- WATCH HISTORY ---
    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC")
    fun getAllHistory(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE provider = :provider AND dramaId = :dramaId LIMIT 1")
    fun getHistory(provider: String, dramaId: String): Flow<WatchHistoryEntity?>

    @Query("SELECT * FROM watch_history WHERE provider = :provider AND dramaId = :dramaId LIMIT 1")
    suspend fun getHistoryDirect(provider: String, dramaId: String): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateHistory(history: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE provider = :provider AND dramaId = :dramaId")
    suspend fun deleteHistory(provider: String, dramaId: String)

    @Query("DELETE FROM watch_history")
    suspend fun clearHistory()
}
