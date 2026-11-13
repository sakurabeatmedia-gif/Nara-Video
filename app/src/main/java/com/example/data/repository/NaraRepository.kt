package com.example.data.repository

import com.example.data.api.DramaBoxApiService
import com.example.data.api.DramaBoxJsonParser
import com.example.data.api.JsonDataParser
import com.example.data.api.MeloloApiService
import com.example.data.api.NetworkClient
import com.example.data.db.FavoriteDramaEntity
import com.example.data.db.NaraDao
import com.example.data.db.WatchHistoryEntity
import com.example.model.Drama
import com.example.model.DramaDetail
import com.example.model.DramaProvider
import com.example.model.Episode
import com.example.model.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

data class MergedHomeCatalog(
    val banners: List<Drama>,
    val rekomendasi: List<Drama>,
    val terpopuler: List<Drama>,
    val terbaru: List<Drama>,
    val allCombined: List<Drama>
)

class NaraRepository(
    private val meloloService: MeloloApiService = NetworkClient.meloloService,
    private val dramaBoxService: DramaBoxApiService = NetworkClient.dramaBoxService,
    private val dao: NaraDao
) {
    private val dramaBoxEpisodesCache = ConcurrentHashMap<String, List<Episode>>()

    suspend fun getHomeCatalog(): Result<MergedHomeCatalog> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val meloloHomeDef = async { fetchMeloloHome() }
                val langDef = async { fetchPreferredDramaBoxLang() }

                val meloloHomeRes = meloloHomeDef.await()
                val lang = langDef.await()

                val dbTrendingDef = async {
                    fetchDramaBoxList(
                        { dramaBoxService.getTrending(lang) },
                        markPopular = true
                    )
                }
                val dbLatestDef = async {
                    fetchDramaBoxList(
                        { dramaBoxService.getLatest(lang) },
                        markLatest = true
                    )
                }
                val dbForYouDef = async {
                    fetchDramaBoxList({ dramaBoxService.getForYou(lang) })
                }
                val dbBrowseDef = async {
                    fetchDramaBoxList({
                        dramaBoxService.getBrowse(page = 1, lang = lang)
                    })
                }
                val dbDubbedDef = async {
                    fetchDramaBoxList(
                        {
                            dramaBoxService.getDubbed(
                                classify = "terpopuler",
                                page = 1,
                                lang = lang
                            )
                        },
                        markPopular = true
                    )
                }

                val dbTrendingRes = dbTrendingDef.await()
                val dbLatestRes = dbLatestDef.await()
                val dbForYouRes = dbForYouDef.await()
                val dbBrowseRes = dbBrowseDef.await()
                val dbDubbedRes = dbDubbedDef.await()

                val meloloList = meloloHomeRes.getOrNull().orEmpty()
                val dbTrending = dbTrendingRes.getOrNull().orEmpty()
                val dbLatest = dbLatestRes.getOrNull().orEmpty()
                val dbForYou = dbForYouRes.getOrNull().orEmpty()
                val dbBrowse = dbBrowseRes.getOrNull().orEmpty()
                val dbDubbed = dbDubbedRes.getOrNull().orEmpty()

                val allMerged = CatalogMerger.mergeAndDeduplicate(
                    CatalogMerger.interleaveAndDeduplicate(
                        dbTrending,
                        meloloList
                    ),
                    dbForYou,
                    dbLatest,
                    dbBrowse,
                    dbDubbed
                )

                if (allMerged.isEmpty()) {
                    val firstFailure = listOf(
                        meloloHomeRes.exceptionOrNull(),
                        dbTrendingRes.exceptionOrNull(),
                        dbLatestRes.exceptionOrNull(),
                        dbForYouRes.exceptionOrNull(),
                        dbBrowseRes.exceptionOrNull(),
                        dbDubbedRes.exceptionOrNull()
                    ).firstOrNull()

                    return@coroutineScope Result.failure(
                        firstFailure ?: Exception(
                            "Drama tidak tersedia untuk saat ini. Silakan coba kembali."
                        )
                    )
                }

                val banners = CatalogMerger.interleaveAndDeduplicate(
                    dbTrending.filter { it.cover.isNotEmpty() },
                    meloloList.filter { it.cover.isNotEmpty() }
                ).take(6)

                val rekomendasi =
                    CatalogMerger.interleaveAndDeduplicate(
                        dbForYou,
                        meloloList
                    )

                val terpopuler = CatalogMerger.mergeAndDeduplicate(
                    dbTrending,
                    dbDubbed,
                    meloloList.filter { it.isPopular }
                )

                val terbaru = CatalogMerger.mergeAndDeduplicate(
                    dbLatest,
                    dbBrowse,
                    meloloList.filter { it.isLatest }
                )

                Result.success(
                    MergedHomeCatalog(
                        banners = banners.ifEmpty { allMerged.take(5) },
                        rekomendasi = rekomendasi.ifEmpty { allMerged },
                        terpopuler = terpopuler.ifEmpty { allMerged },
                        terbaru = terbaru.ifEmpty { allMerged },
                        allCombined = allMerged
                    )
                )
            }
        }

    suspend fun searchDramas(query: String): Result<List<Drama>> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()

            if (cleanQuery.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            coroutineScope {
                val meloloDef = async {
                    try {
                        val response = meloloService.search(query = cleanQuery)
                        val res = processResponse(response)

                        if (res.isSuccess) {
                            val json = res.getOrNull().orEmpty()
                            val apiErr = JsonDataParser.checkApiError(json)

                            if (apiErr != null) {
                                Result.failure(
                                    ApiException(
                                        apiErr.first,
                                        "Drama tidak tersedia."
                                    )
                                )
                            } else {
                                Result.success(
                                    JsonDataParser.parseDramaList(json)
                                )
                            }
                        } else {
                            Result.failure(
                                res.exceptionOrNull()
                                    ?: Exception("Koneksi sedang bermasalah.")
                            )
                        }
                    } catch (e: Exception) {
                        Result.failure(mapException(e))
                    }
                }

                val dramaBoxDef = async {
                    try {
                        val response = dramaBoxService.search(query = cleanQuery)
                        val res = processResponse(response)

                        if (res.isSuccess) {
                            Result.success(
                                DramaBoxJsonParser.parseDramaList(
                                    res.getOrNull().orEmpty()
                                )
                            )
                        } else {
                            Result.failure(
                                res.exceptionOrNull()
                                    ?: Exception("Koneksi sedang bermasalah.")
                            )
                        }
                    } catch (e: Exception) {
                        Result.failure(mapException(e))
                    }
                }

                val meloloRes = meloloDef.await()
                val dramaBoxRes = dramaBoxDef.await()

                if (meloloRes.isFailure && dramaBoxRes.isFailure) {
                    return@coroutineScope Result.failure(
                        meloloRes.exceptionOrNull()
                            ?: dramaBoxRes.exceptionOrNull()
                            ?: Exception(
                                "Koneksi sedang bermasalah. Silakan coba kembali."
                            )
                    )
                }

                Result.success(
                    CatalogMerger.interleaveAndDeduplicate(
                        meloloRes.getOrNull().orEmpty(),
                        dramaBoxRes.getOrNull().orEmpty()
                    )
                )
            }
        }

    suspend fun getDramaDetail(
        provider: DramaProvider,
        dramaId: String
    ): Result<DramaDetail> = withContext(Dispatchers.IO) {
        try {
            when (provider) {
                DramaProvider.MELOLO -> {
                    val response = meloloService.getDetail(id = dramaId)
                    val result = processResponse(response)

                    if (result.isSuccess) {
                        Result.success(
                            JsonDataParser.parseDramaDetail(
                                dramaId,
                                result.getOrNull().orEmpty()
                            )
                        )
                    } else {
                        Result.failure(
                            result.exceptionOrNull()
                                ?: Exception("Drama tidak tersedia.")
                        )
                    }
                }

                DramaProvider.DRAMABOX -> coroutineScope {
                    val detailDef = async {
                        dramaBoxService.getDetail(bookId = dramaId)
                    }
                    val allEpsDef = async {
                        runCatching {
                            dramaBoxService.getAllEpisodes(bookId = dramaId)
                        }.getOrNull()
                    }

                    val detailRes = processResponse(detailDef.await())

                    if (detailRes.isFailure) {
                        return@coroutineScope Result.failure(
                            detailRes.exceptionOrNull()
                                ?: Exception("Drama tidak tersedia.")
                        )
                    }

                    val detailJson = detailRes.getOrNull().orEmpty()
                    val allEpsResp = allEpsDef.await()

                    val allEpsJson = if (allEpsResp != null) {
                        processResponse(allEpsResp).getOrNull()
                    } else {
                        null
                    }

                    val detail = DramaBoxJsonParser.parseDramaDetail(
                        dramaId,
                        detailJson,
                        allEpsJson
                    )

                    if (detail.episodes.any { it.videoUrl.isNotEmpty() }) {
                        dramaBoxEpisodesCache[dramaId] = detail.episodes
                    }

                    Result.success(detail)
                }

                else -> Result.failure(unsupportedProvider(provider))
            }
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    suspend fun getVideoSource(
        provider: DramaProvider,
        dramaId: String,
        episodeNum: Int,
        forceRefresh: Boolean = false
    ): Result<VideoSource> = withContext(Dispatchers.IO) {
        try {
            when (provider) {
                DramaProvider.MELOLO -> {
                    val response = meloloService.getVideo(
                        id = dramaId,
                        ep = episodeNum
                    )
                    val result = processResponse(response)

                    if (result.isSuccess) {
                        val video = JsonDataParser.parseVideoSource(
                            dramaId,
                            episodeNum,
                            result.getOrNull().orEmpty()
                        )

                        if (video != null && video.videoUrl.isNotEmpty()) {
                            Result.success(video)
                        } else {
                            Result.failure(
                                Exception(
                                    "Video tidak dapat diputar. Silakan coba kembali."
                                )
                            )
                        }
                    } else {
                        Result.failure(
                            result.exceptionOrNull()
                                ?: Exception("Video tidak dapat diputar.")
                        )
                    }
                }

                DramaProvider.DRAMABOX -> {
                    var episodes = if (!forceRefresh) {
                        dramaBoxEpisodesCache[dramaId]
                    } else {
                        null
                    }

                    if (episodes.isNullOrEmpty()) {
                        val response = dramaBoxService.getAllEpisodes(
                            bookId = dramaId
                        )
                        val result = processResponse(response)

                        if (result.isFailure) {
                            return@withContext Result.failure(
                                result.exceptionOrNull()
                                    ?: Exception("Video tidak dapat diputar.")
                            )
                        }

                        val parsed = DramaBoxJsonParser.parseAllEpisodes(
                            result.getOrNull().orEmpty()
                        )

                        if (parsed.isNotEmpty()) {
                            dramaBoxEpisodesCache[dramaId] = parsed
                        }

                        episodes = parsed
                    }

                    val targetEpisode = episodes.find {
                        it.episodeNum == episodeNum
                    }

                    val video = targetEpisode?.let {
                        DramaBoxJsonParser.toVideoSource(dramaId, it)
                    }

                    if (video != null && video.videoUrl.isNotEmpty()) {
                        Result.success(video)
                    } else {
                        Result.failure(
                            Exception(
                                "Video tidak dapat diputar. Silakan coba kembali."
                            )
                        )
                    }
                }

                else -> Result.failure(unsupportedProvider(provider))
            }
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    fun getAllFavorites(): Flow<List<FavoriteDramaEntity>> =
        dao.getAllFavorites()

    fun isFavorite(
        provider: DramaProvider,
        dramaId: String
    ): Flow<Boolean> = dao.isFavorite(provider.name, dramaId)

    suspend fun toggleFavorite(
        drama: Drama,
        isFav: Boolean
    ) = withContext(Dispatchers.IO) {
        if (isFav) {
            dao.deleteFavorite(drama.provider.name, drama.id)
        } else {
            dao.insertFavorite(
                FavoriteDramaEntity(
                    provider = drama.provider.name,
                    dramaId = drama.id,
                    title = drama.title,
                    cover = drama.cover,
                    totalEpisodes = drama.totalEpisodes,
                    categories = drama.categories.joinToString(", ")
                )
            )
        }
    }

    suspend fun removeFavorite(
        provider: DramaProvider,
        dramaId: String
    ) = withContext(Dispatchers.IO) {
        dao.deleteFavorite(provider.name, dramaId)
    }

    fun getAllHistory(): Flow<List<WatchHistoryEntity>> =
        dao.getAllHistory()

    fun getHistory(
        provider: DramaProvider,
        dramaId: String
    ): Flow<WatchHistoryEntity?> =
        dao.getHistory(provider.name, dramaId)

    suspend fun saveWatchProgress(
        provider: DramaProvider,
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
                provider = provider.name,
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

    suspend fun deleteHistory(
        provider: DramaProvider,
        dramaId: String
    ) = withContext(Dispatchers.IO) {
        dao.deleteHistory(provider.name, dramaId)
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        dao.clearHistory()
    }

    private suspend fun fetchMeloloHome(): Result<List<Drama>> {
        return try {
            val response = meloloService.getHome(offset = 0)
            val result = processResponse(response)

            if (result.isSuccess) {
                val json = result.getOrNull().orEmpty()
                val apiError = JsonDataParser.checkApiError(json)

                if (apiError != null) {
                    Result.failure(
                        ApiException(
                            apiError.first,
                            "Drama tidak tersedia."
                        )
                    )
                } else {
                    Result.success(JsonDataParser.parseDramaList(json))
                }
            } else {
                Result.failure(
                    result.exceptionOrNull()
                        ?: Exception("Drama tidak tersedia.")
                )
            }
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    private suspend fun fetchPreferredDramaBoxLang(): String {
        return try {
            val response = dramaBoxService.getLanguages()
            val result = processResponse(response)

            if (result.isSuccess) {
                val langs = DramaBoxJsonParser.parseLanguages(
                    result.getOrNull().orEmpty()
                )

                if (langs.any { it.first.equals("id", ignoreCase = true) }) {
                    "id"
                } else {
                    langs.firstOrNull()?.first ?: "id"
                }
            } else {
                "id"
            }
        } catch (_: Exception) {
            "id"
        }
    }

    private suspend fun fetchDramaBoxList(
        call: suspend () -> Response<okhttp3.ResponseBody>,
        markPopular: Boolean = false,
        markLatest: Boolean = false
    ): Result<List<Drama>> {
        return try {
            val response = call()
            val result = processResponse(response)

            if (result.isSuccess) {
                Result.success(
                    DramaBoxJsonParser.parseDramaList(
                        result.getOrNull().orEmpty(),
                        markPopular = markPopular,
                        markLatest = markLatest
                    )
                )
            } else {
                Result.failure(
                    result.exceptionOrNull()
                        ?: Exception("Drama tidak tersedia.")
                )
            }
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    private fun unsupportedProvider(
        provider: DramaProvider
    ): Exception {
        val platformName = when (provider) {
            DramaProvider.DRAMAWAVE -> "DramaWave"
            DramaProvider.REELSHORT -> "ReelShort"
            DramaProvider.DRAMABITE -> "DramaBite"
            DramaProvider.GOODSHORT -> "GoodShort"
            else -> provider.name
        }

        return Exception(
            "$platformName belum terhubung ke layanan API."
        )
    }

    private fun processResponse(
        response: Response<okhttp3.ResponseBody>
    ): Result<String> {
        val code = response.code()

        return if (response.isSuccessful) {
            val body = response.body()?.string() ?: ""
            Result.success(body)
        } else {
            runCatching {
                response.errorBody()?.close()
            }

            val errorMsg = when (code) {
                401, 403 ->
                    "Drama tidak tersedia untuk saat ini. Silakan coba kembali."
                429 ->
                    "Layanan sedang sibuk. Silakan coba kembali beberapa saat lagi."
                500, 502, 503, 504 ->
                    "Koneksi sedang bermasalah. Silakan coba kembali."
                else ->
                    "Drama tidak tersedia untuk saat ini. Silakan coba kembali."
            }

            Result.failure(ApiException(code, errorMsg))
        }
    }

    private fun mapException(e: Throwable): Throwable {
        return when (e) {
            is ApiException -> e
            is SocketTimeoutException,
            is UnknownHostException,
            is IOException ->
                Exception("Koneksi sedang bermasalah. Silakan coba kembali.")
            is IllegalStateException ->
                Exception("Drama tidak tersedia untuk saat ini. Silakan coba kembali.")
            else ->
                Exception("Drama tidak tersedia untuk saat ini. Silakan coba kembali.")
        }
    }
}

class ApiException(
    val code: Int,
    message: String
) : Exception(message)
