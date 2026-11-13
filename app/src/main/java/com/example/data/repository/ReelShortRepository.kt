package com.example.data.repository

import com.example.data.api.JsonDataParser
import com.example.data.api.NetworkClient
import com.example.data.api.ReelShortApiService
import com.example.data.api.ReelShortJsonParser
import com.example.model.Drama
import com.example.model.DramaDetail
import com.example.model.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response

class ReelShortRepository(
    private val service: ReelShortApiService =
        NetworkClient.reelShortService
) {

    suspend fun getHome(): Result<List<Drama>> =
        withContext(Dispatchers.IO) {
            requestList { service.getHome() }
        }

    suspend fun getTrending(): Result<List<Drama>> =
        withContext(Dispatchers.IO) {
            requestList { service.getTrending() }
        }

    suspend fun search(query: String): Result<List<Drama>> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                return@withContext Result.success(emptyList())
            }

            requestList {
                service.search(query.trim())
            }
        }

    suspend fun getDetail(
        dramaId: String
    ): Result<DramaDetail> = withContext(Dispatchers.IO) {
        try {
            val json = requireResponse(
                service.getDetail(dramaId)
            )

            Result.success(
                ReelShortJsonParser.parseDramaDetail(
                    dramaId,
                    json
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getVideoSource(
        dramaId: String,
        episodeNum: Int
    ): Result<VideoSource> = withContext(Dispatchers.IO) {
        try {
            val json = requireResponse(
                service.getAllEpisodes(dramaId)
            )

            val video = JsonDataParser.parseVideoSource(
                dramaId,
                episodeNum,
                json
            )

            if (video != null && video.videoUrl.isNotBlank()) {
                Result.success(video)
            } else {
                Result.failure(
                    Exception(
                        "URL video ReelShort episode $episodeNum tidak ditemukan."
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun requestList(
        request: suspend () -> Response<ResponseBody>
    ): Result<List<Drama>> {
        return try {
            val json = requireResponse(request())
            val error = ReelShortJsonParser.checkApiError(json)

            if (error != null) {
                throw ApiException(error.first, error.second)
            }

            Result.success(
                ReelShortJsonParser.parseDramaList(json)
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun requireResponse(
        response: Response<ResponseBody>
    ): String {
        if (response.isSuccessful) {
            return response.body()?.string().orEmpty()
        }

        val code = response.code()
        response.errorBody()?.close()

        val message = when (code) {
            401, 403 -> "Akses API ReelShort ditolak."
            429 -> "Batas permintaan API tercapai. Coba lagi nanti."
            else -> "Gagal mengambil data ReelShort."
        }

        throw ApiException(code, message)
    }
}
