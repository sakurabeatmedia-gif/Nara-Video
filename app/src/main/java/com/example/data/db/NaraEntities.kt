package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteDramaEntity(
    @PrimaryKey
    val dramaId: String,
    val title: String,
    val cover: String,
    val totalEpisodes: Int = 0,
    val categories: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey
    val dramaId: String,
    val title: String,
    val cover: String,
    val lastEpisode: Int = 1,
    val lastPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val totalEpisodes: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)
