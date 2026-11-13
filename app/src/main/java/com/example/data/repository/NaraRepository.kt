package com.example.data.repository

import com.example.data.api.DramaBoxApiService
import com.example.data.api.DramaBoxJsonParser
import com.example.data.api.DramaWaveApiService
import com.example.data.api.DramaWaveJsonParser
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
    private val dao: NaraDao,
    private val dramaWaveService: DramaWaveApiService = NetworkClient.dramaWaveService
) {
    private val dramaBoxEpisodesCache =
        ConcurrentHashMap<String, List<Episode>>()

    suspend fun getHomeCatalog(): Result<MergedHomeCatalog> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val meloloDef = async { fetchMeloloHome() }
                val langDef = async { fetchPreferredDramaBoxLang() }
                val waveDef = async { fetchDramaWaveHome() }

                val meloloRes = meloloDef.await()
                val lang = langDef.await()
                val waveRes = waveDef.await()

                val trendingDef = async {
                    fetchDramaBoxList(
                        { dramaBoxService.getTrending(lang) },
                        markPopular = true
                    )
                }
                val latestDef = async {
                    fetchDramaBoxList(
                        { dramaBoxService.getLatest(lang) },
                        markLatest = true
                    )
                }
                val forYouDef = async {
                    fetchDramaBoxList {
                        dramaBoxService.getForYou(lang)
                    }
                }
                val browseDef = async {
                    fetchDramaBoxList {
                        dramaBoxService.getBrowse(
                            page = 1,
                            lang = lang
                        )
                    }
                }
                val dubbedDef = async {
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

                val trending = trendingDef.await()
                val latest = latestDef.await()
                val forYou = forYouDef.await()
                val browse = browseDef.await()
                val dubbed = dubbedDef.await()

                val meloloList = meloloRes.getOrNull().orEmpty()
                val waveList = waveRes.getOrNull().orEmpty()
                val dbTrending = trending.getOrNull().orEmpty()
                val dbLatest = latest.getOrNull().orEmpty()
                val dbForYou = forYou.getOrNull().orEmpty()
                val dbBrowse = browse.getOrNull().orEmpty()
                val dbDubbed = dubbed.getOrNull().orEmpty()

                val allMerged = CatalogMerger.mergeAndDeduplicate(
                    CatalogMerger.interleaveAndDeduplicate(
                        dbTrending,
                        meloloList
                    ),
                    waveList,
                    dbForYou,
                    dbLatest,
                    dbBrowse,
                    dbDubbed
                )

                if (allMerged.isEmpty()) {
                    val firstFailure = listOf(
                        meloloRes.exceptionOrNull(),
                        waveRes.exceptionOrNull(),
                        trending.exceptionOrNull(),
                        latest.exceptionOrNull(),
                        forYou.exceptionOrNull(),
                        browse.exceptionOrNull(),
                        dubbed.exceptionOrNull()
                    ).firstOrNull { it != null }

                    return@coroutineScope Result.failure(
                        firstFailure ?: Exception(
                            "Drama tidak tersedia untuk saat ini. Silakan coba kembali."
                        )
                    )
                }

                val banners = CatalogMerger.mergeAndDeduplicate(
                    dbTrending.filter { it.cover.isNotEmpty() },
                    waveList.filter { it.cover.isNotEmpty() },
                    meloloList.filter { it.cover.isNotEmpty() }
                ).take(6)

                val rekomendasi = CatalogMerger.mergeAndDeduplicate(
                    dbForYou,
                    waveList,
                    meloloList
                )

                val terpopuler = CatalogMerger.mergeAndDeduplicate(
                    dbTrending,
                    dbDubbed,
                    waveList.filter { it.isPopular },
                    meloloList.filter { it.isPopular }
                )

                val terbaru = CatalogMerger.mergeAndDeduplicate(
                    dbLatest,
                    dbBrowse,
                    waveList.filter { it.isLatest },
                    meloloList.filter { it.isLatest }
                )

                Result.success(
                    MergedHomeCatalog(
                        banners = banners.ifEmpty {
                            allMerged.take(5)
                        },
                        rekomendasi = rekomendasi.ifEmpty {
                            allMerged
                        },
                        terpopuler = terpopuler.ifEmpty {
                            allMerged
                        },
                        terbaru = terbaru.ifEmpty {
                            allMerged
                        },
                        allCombined = allMerged
                    )
                )
            }
        }

    suspend fun searchDramas(
        query: String
    ): Result<List<Drama>> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()

        if (cleanQuery.isEmpty()) {
            return@withContext Result.success(emptyList())
        }

        coroutineScope {
            val meloloDef = async {
                runCatching {
                    val response = meloloService.search(
                        query = cleanQuery
                    )
                    val result = processResponse(response)
                    if (result.isFailure) {
                        throw result.exceptionOrNull()
                            ?: Exception("Pencarian gagal.")
                    }

                    val json = result.getOrNull().orEmpty()
                    val apiError = JsonDataParser.checkApiError(json)
                    if (apiError != null) {
                        throw ApiException(
                            apiError.first,
                            "Drama tidak tersedia."
                        )
                    }

                    JsonDataParser.parseDramaList(json)
                }
            }

            val dramaBoxDef = async {
                runCatching {
                    val response = dramaBoxService.search(
                        query = cleanQuery
                    )
                    val result = processResponse(response)
                    if (result.isFailure) {
                        throw result.exceptionOrNull()
                            ?: Exception("Pencarian gagal.")
                    }

                    DramaBoxJsonParser.parseDramaList(
                        result.getOrNull().orEmpty()
                    )
                }
            }

            val waveDef = async {
                runCatching {
                    val response = dramaWaveService.search(
                        query = cleanQuery
                    )
                    val result = processResponse(response)
                    if (result.isFailure) {
                        throw result.exceptionOrNull()
                            ?: Exception("Pencarian gagal.")
                    }

                    val json = result.getOrNull().orEmpty()
                    val apiError =
                        DramaWaveJsonParser.checkApiError(json)

                    if (apiError != null) {
                        throw ApiException(
                            apiError.first,
                            "Pencarian DramaWave gagal."
                        )
                    }

                    DramaWaveJsonParser.parseDramaList(json)
                }
            }

            val melolo = meloloDef.await()
            val dramaBox = dramaBoxDef.await()
            val wave = waveDef.await()

            val combined = CatalogMerger.mergeAndDeduplicate(
                melolo.getOrNull().orEmpty(),
                dramaBox.getOrNull().orEmpty(),
                wave.getOrNull().orEmpty()
            )

            if (combined.isEmpty() &&
                melolo.isFailure &&
                dramaBox.isFailure &&
                wave.isFailure
            ) {
                return@coroutineScope Result.failure(
                    melolo.exceptionOrNull()
                        ?: dramaBox.exceptionOrNull()
                        ?: wave.exceptionOrNull()
                        ?: Exception("Pencarian gagal.")
                )
            }

            Result.success(combined)
        }
    }

    suspend fun getDramaDetail(
        provider: DramaProvider,
        dramaId: String
    ): Result<DramaDetail> = withContext(Dispatchers.IO) {
        try {
            when (provider) {
                DramaProvider.MELOLO -> {
                    val response = meloloService.getDetail(
                        id = dramaId
                    )
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
                        dramaBoxService.getDetail(
                            bookId = dramaId
                        )
                    }
                    val episodesDef = async {
                        runCatching {
                            dramaBoxService.getAllEpisodes(
                                bookId = dramaId
                            )
                        }.getOrNull()
                    }

                    val detailResult = processResponse(
                        detailDef.await()
                    )

                    if (detailResult.isFailure) {
                        return@coroutineScope Result.failure(
                            detailResult.exceptionOrNull()
                                ?: Exception("Drama tidak tersedia.")
                        )
                    }

                    val detailJson =
                        detailResult.getOrNull().orEmpty()
                    val episodesResponse = episodesDef.await()

                    val episodesJson = episodesResponse?.let {
                        processResponse(it).getOrNull()
                    }

                    val detail = DramaBoxJsonParser.parseDramaDetail(
                        dramaId,
                        detailJson,
                        episodesJson
                    )

                    if (detail.episodes.any {
                            it.videoUrl.isNotEmpty()
                        }
                    ) {
                        dramaBoxEpisodesCache[dramaId] =
                            detail.episodes
                    }

                    Result.success(detail)
                }

                DramaProvider.DRAMAWAVE -> {
                    val response = dramaWaveService.getDetail(
                        id = dramaId
                    )
                    val result = processResponse(response)

                    if (result.isFailure) {
                        Result.failure(
                            result.exceptionOrNull()
                                ?: Exception("Detail DramaWave tidak tersedia.")
                        )
                    } else {
                        Result.success(
                            DramaWaveJsonParser.parseDramaDetail(
                                dramaId,
                                result.getOrNull().orEmpty()
                            )
                        )
                    }
                }

                else -> Result.failure(
                    unsupportedProvider(provider)
                )
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

                        if (video != null &&
                            video.videoUrl.isNotEmpty()
                        ) {
                            Result.success(video)
                        } else {
                            Result.failure(
                                Exception("Video tidak dapat diputar.")
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
                        val response =
                            dramaBoxService.getAllEpisodes(
                                bookId = dramaId
                            )
                        val result = processResponse(response)

                        if (result.isFailure) {
                            return@withContext Result.failure(
                                result.exceptionOrNull()
                                    ?: Exception("Video tidak dapat diputar.")
                            )
                        }

                        val parsed =
                            DramaBoxJsonParser.parseAllEpisodes(
                                result.getOrNull().orEmpty()
                            )

                        if (parsed.isNotEmpty()) {
                            dramaBoxEpisodesCache[dramaId] = parsed
                        }

                        episodes = parsed
                    }

                    val target = episodes.find {
                        it.episodeNum == episodeNum
                    }

                    val video = target?.let {
                        DramaBoxJsonParser.toVideoSource(
                            dramaId,
                            it
                        )
                    }

                    if (video != null &&
                        video.videoUrl.isNotEmpty()
                    ) {
                        Result.success(video)
                    } else {
                        Result.failure(
                            Exception("Video tidak dapat diputar.")
                        )
                    }
                }

                else -> Result.failure(
                    unsupportedProvider(provider)
                )
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
    ): Flow<Boolean> =
        dao.isFavorite(provider.name, dramaId)

    suspend fun toggleFavorite(
        drama: Drama,
        isFav: Boolean
    ) = withContext(Dispatchers.IO) {
        if (isFav) {
            dao.deleteFavorite(
                drama.provider.name,
                drama.id
            )
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

    suspend fun clearAllHistory() =
        withContext(Dispatchers.IO) {
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
                    Result.success(
                        JsonDataParser.parseDramaList(json)
                    )
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

    private suspend fun fetchDramaWaveHome(): Result<List<Drama>> {
        return try {
            val response = dramaWaveService.getHome()
            val result = processResponse(response)

            if (result.isFailure) {
                return Result.failure(
                    result.exceptionOrNull()
                        ?: Exception("DramaWave tidak tersedia.")
                )
            }

            val json = result.getOrNull().orEmpty()
            val apiError = DramaWaveJsonParser.checkApiError(json)

            if (apiError != null) {
                Result.failure(
                    ApiException(
                        apiError.first,
                        "DramaWave tidak tersedia."
                    )
                )
            } else {
                Result.success(
                    DramaWaveJsonParser.parseDramaList(json)
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

                if (langs.any {
                        it.first.equals("id", ignoreCase = true)
                    }
                ) {
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
        val name = when (provider) {
            DramaProvider.DRAMAWAVE -> "DramaWave"
            DramaProvider.REELSHORT -> "ReelShort"
            DramaProvider.DRAMABITE -> "DramaBite"
            DramaProvider.GOODSHORT -> "GoodShort"
            else -> provider.name
        }

        return Exception(
            "$name belum terhubung ke layanan video."
        )
    }

    private fun processResponse(
        response: Response<okhttp3.ResponseBody>
    ): Result<String> {
        val code = response.code()

        return if (response.isSuccessful) {
            Result.success(response.body()?.string() ?: "")
        } else {
            runCatching {
                response.errorBody()?.close()
            }

            val message = when (code) {
                401, 403 ->
                    "Akses layanan ditolak. Periksa konfigurasi API."
                429 ->
                    "Layanan sedang sibuk. Coba kembali beberapa saat lagi."
                500, 502, 503, 504 ->
                    "Koneksi sedang bermasalah. Silakan coba kembali."
                else ->
                    "Drama tidak tersedia untuk saat ini. Silakan coba kembali."
            }

            Result.failure(ApiException(code, message))
        }
    }

    private fun mapException(e: Throwable): Throwable {
        return when (e) {
            is ApiException -> e
            is SocketTimeoutException,
            is UnknownHostException,
            is IOException ->
                Exception(
                    "Koneksi sedang bermasalah. Silakan coba kembali."
                )
            is IllegalStateException ->
                Exception(
                    "Drama tidak tersedia untuk saat ini. Silakan coba kembali."
                )
            else ->
                Exception(
                    "Drama tidak tersedia untuk saat ini. Silakan coba kembali."
                )
        }
    }
}

class ApiException(
    val code: Int,
    message: String
) : Exception(message)
