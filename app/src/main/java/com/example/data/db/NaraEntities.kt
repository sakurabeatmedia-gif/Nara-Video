package com.example.data.db

import androidx.room.Entity
import com.example.model.DramaProvider

@Entity(
    tableName = "favorites",
    primaryKeys = ["provider", "dramaId"]
)
data class FavoriteDramaEntity(
    val provider: String = DramaProvider.MELOLO.name,
    val dramaId: String,
    val title: String,
    val cover: String,
    val totalEpisodes: Int = 0,
    val categories: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "watch_history",
    primaryKeys = ["provider", "dramaId"]
)
data class WatchHistoryEntity(
    val provider: String = DramaProvider.MELOLO.name,
    val dramaId: String,
    val title: String,
    val cover: String,
    val lastEpisode: Int = 1,
    val lastPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val totalEpisodes: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)
