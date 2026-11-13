package com.example.data.repository

import com.example.data.api.JsonDataParser
import com.example.data.api.MeloloApiService
import com.example.data.api.NetworkClient
import com.example.data.db.FavoriteDramaEntity
import com.example.data.db.NaraDao
import com.example.data.db.WatchHistoryEntity
import com.example.model.Drama
import com.example.model.DramaDetail
import com.example.model.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NaraRepository(
    private val apiService: MeloloApiService = NetworkClient.meloloService,
    private val dao: NaraDao
) {

    // --- HOME DRAMAS ---
    suspend fun getHomeDramas(offset: Int = 0): Result<List<Drama>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getHome(offset = offset)
            val result = processResponse(response)
            if (result.isSuccess) {
                val json = result.getOrNull() ?: ""
                val apiError = JsonDataParser.checkApiError(json)
                if (apiError != null) {
                    return@withContext Result.failure(ApiException(apiError.first, apiError.second))
                }
                val list = JsonDataParser.parseDramaList(json)
                Result.success(list)
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("Gagal memuat data drama"))
            }
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    // --- SEARCH DRAMAS ---
    suspend fun searchDramas(query: String): Result<List<Drama>> = withContext(Dispatchers.IO) {
        if (query.trim().isEmpty()) {
            return@withContext Result.success(emptyList())
        }
        try {
            val response = apiService.search(query = query.trim())
            val result = processResponse(response)
            if (result.isSuccess) {
                val json = result.getOrNull() ?: ""
                val apiError = JsonDataParser.checkApiError(json)
                if (apiError != null) {
                    return@withContext Result.failure(ApiException(apiError.first, apiError.second))
                }
                val list = JsonDataParser.parseDramaList(json)
                Result.success(list)
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("Gagal mencari drama"))
            }
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    // --- DETAIL DRAMA ---
    suspend fun getDramaDetail(dramaId: String): Result<DramaDetail> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getDetail(id = dramaId)
            val result = processResponse(response)
            if (result.isSuccess) {
                val json = result.getOrNull() ?: ""
                val detail = JsonDataParser.parseDramaDetail(dramaId, json)
                Result.success(detail)
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("Gagal memuat detail drama"))
            }
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    // --- VIDEO SOURCE ---
    suspend fun getVideoSource(dramaId: String, episodeNum: Int): Result<VideoSource> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getVideo(id = dramaId, ep = episodeNum)
            val result = processResponse(response)
            if (result.isSuccess) {
                val json = result.getOrNull() ?: ""
                val video = JsonDataParser.parseVideoSource(dramaId, episodeNum, json)
                if (video != null && video.videoUrl.isNotEmpty()) {
                    Result.success(video)
                } else {
                    Result.failure(Exception("Sumber video untuk episode ini belum tersedia dari server."))
                }
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("Gagal memuat video episode"))
            }
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    // --- FAVORITES ---
    fun getAllFavorites(): Flow<List<FavoriteDramaEntity>> = dao.getAllFavorites()

    fun isFavorite(dramaId: String): Flow<Boolean> = dao.isFavorite(dramaId)

    suspend fun toggleFavorite(drama: Drama, isFav: Boolean) = withContext(Dispatchers.IO) {
        if (isFav) {
            dao.deleteFavorite(drama.id)
        } else {
            dao.insertFavorite(
                FavoriteDramaEntity(
                    dramaId = drama.id,
                    title = drama.title,
                    cover = drama.cover,
                    totalEpisodes = drama.totalEpisodes,
                    categories = drama.categories.joinToString(", ")
                )
            )
        }
    }

    suspend fun removeFavorite(dramaId: String) = withContext(Dispatchers.IO) {
        dao.deleteFavorite(dramaId)
    }

    // --- WATCH HISTORY ---
    fun getAllHistory(): Flow<List<WatchHistoryEntity>> = dao.getAllHistory()

    fun getHistory(dramaId: String): Flow<WatchHistoryEntity?> = dao.getHistory(dramaId)

    suspend fun saveWatchProgress(
        dramaId: String,
        title: String,
        cover: String,
        episodeNum: Int,
        positionMs: Long,
        durationMs: Long,
        totalEpisodes: Int
    ) = withContext(Dispatchers.IO) {
        dao.insertOrUpdateHistory(
            WatchHistoryEntity(
                dramaId = dramaId,
                title = title,
                cover = cover,
                lastEpisode = episodeNum,
                lastPositionMs = positionMs,
                durationMs = durationMs,
                totalEpisodes = totalEpisodes,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteHistory(dramaId: String) = withContext(Dispatchers.IO) {
        dao.deleteHistory(dramaId)
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        dao.clearHistory()
    }

    // --- HTTP & ERROR PROCESSOR ---
    private fun processResponse(response: Response<okhttp3.ResponseBody>): Result<String> {
        val code = response.code()
        return if (response.isSuccessful) {
            val body = response.body()?.string() ?: ""
            Result.success(body)
        } else {
            val rawError = try {
                response.errorBody()?.string()?.trim() ?: ""
            } catch (_: Exception) {
                ""
            }

            val errorMsg = when (code) {
                401, 403 -> "API Key salah / tidak valid"
                429 -> "HTTP 429: Batas kuota permintaan API tercapai. Silakan coba lagi nanti."
                500 -> "Server API mengalami gangguan internal (HTTP 500). Silakan coba lagi nanti."
                502 -> "Server API sedang bermasalah (HTTP 502). Silakan coba lagi beberapa saat lagi."
                504 -> "Server API timeout (HTTP 504). Silakan coba lagi."
                else -> "HTTP $code: Gagal menghubungi server API${if (rawError.isNotEmpty()) " ($rawError)" else ""}."
            }
            Result.failure(ApiException(code, errorMsg))
        }
    }

    private fun mapException(e: Throwable): Throwable {
        return when (e) {
            is ApiException -> e
            is SocketTimeoutException -> Exception("Koneksi gagal (Timeout). Periksa jaringan internet Anda lalu coba lagi.")
            is UnknownHostException -> Exception("Koneksi gagal. Tidak dapat terhubung ke server API. Periksa jaringan internet Anda.")
            is IOException -> Exception("Koneksi gagal: ${e.localizedMessage ?: "Jaringan terputus"}. Periksa jaringan internet Anda.")
            else -> e
        }
    }
}

class ApiException(val code: Int, message: String) : Exception(message)
