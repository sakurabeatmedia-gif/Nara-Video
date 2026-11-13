package com.example.data.repository

import com.example.data.api.DramaBiteApiService
import com.example.data.api.DramaBiteJsonParser
import com.example.data.api.DramaBoxApiService
import com.example.data.api.DramaBoxJsonParser
import com.example.data.api.DramaWaveApiService
import com.example.data.api.DramaWaveJsonParser
import com.example.data.api.GoodShortApiService
import com.example.data.api.GoodShortJsonParser
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
    private val dramaWaveService: DramaWaveApiService = NetworkClient.dramaWaveService,
    private val dramaBiteService: DramaBiteApiService = NetworkClient.dramaBiteService,
    private val goodShortService: GoodShortApiService = NetworkClient.goodShortService
) {
    private val dramaBoxEpisodesCache =
        ConcurrentHashMap<String, List<Episode>>()

    suspend fun getHomeCatalog(): Result<MergedHomeCatalog> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val meloloDef = async { fetchMeloloHome() }
                val waveDef = async { fetchDramaWaveHome() }
                val biteDef = async { fetchDramaBiteHome() }
                val goodShortDef = async { fetchGoodShortHome() }
                val langDef = async { fetchPreferredDramaBoxLang() }

                val meloloRes = meloloDef.await()
                val waveRes = waveDef.await()
                val biteRes = biteDef.await()
                val goodShortRes = goodShortDef.await()
                val lang = langDef.await()

                val trendingDef = async {
                    fetchDramaBoxList({
                        dramaBoxService.getTrending(lang)
                    }, markPopular = true)
                }
                val latestDef = async {
                    fetchDramaBoxList({
                        dramaBoxService.getLatest(lang)
                    }, markLatest = true)
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
                    fetchDramaBoxList({
                        dramaBoxService.getDubbed(
                            classify = "terpopuler",
                            page = 1,
                            lang = lang
                        )
                    }, markPopular = true)
                }

                val trending = trendingDef.await()
                val latest = latestDef.await()
                val forYou = forYouDef.await()
                val browse = browseDef.await()
                val dubbed = dubbedDef.await()

                val melolo = meloloRes.getOrNull().orEmpty()
                val wave = waveRes.getOrNull().orEmpty()
                val bite = biteRes.getOrNull().orEmpty()
                val goodShort = goodShortRes.getOrNull().orEmpty()
                val dbTrending = trending.getOrNull().orEmpty()
                val dbLatest = latest.getOrNull().orEmpty()
                val dbForYou = forYou.getOrNull().orEmpty()
                val dbBrowse = browse.getOrNull().orEmpty()
                val dbDubbed = dubbed.getOrNull().orEmpty()

                val all = CatalogMerger.mergeAndDeduplicate(
                    CatalogMerger.interleaveAndDeduplicate(
                        dbTrending,
                        melolo
                    ),
                    wave,
                    bite,
                    goodShort,
                    dbForYou,
                    dbLatest,
                    dbBrowse,
                    dbDubbed
                )

                if (all.isEmpty()) {
                    val error = listOf(
                        meloloRes.exceptionOrNull(),
                        waveRes.exceptionOrNull(),
                        biteRes.exceptionOrNull(),
                        goodShortRes.exceptionOrNull(),
                        trending.exceptionOrNull(),
                        latest.exceptionOrNull(),
                        forYou.exceptionOrNull(),
                        browse.exceptionOrNull(),
                        dubbed.exceptionOrNull()
                    ).firstOrNull { it != null }

                    return@coroutineScope Result.failure(
                        error ?: Exception(
                            "Drama tidak tersedia untuk saat ini."
                        )
                    )
                }

                Result.success(
                    MergedHomeCatalog(
                        banners = CatalogMerger.mergeAndDeduplicate(
                            dbTrending,
                            wave,
                            bite,
                            goodShort,
                            melolo
                        ).take(6),
                        rekomendasi = CatalogMerger.mergeAndDeduplicate(
                            dbForYou,
                            wave,
                            bite,
                            goodShort,
                            melolo
                        ).ifEmpty { all },
                        terpopuler = CatalogMerger.mergeAndDeduplicate(
                            dbTrending,
                            dbDubbed,
                            wave.filter { it.isPopular },
                            bite.filter { it.isPopular },
                            goodShort.filter { it.isPopular },
                            melolo.filter { it.isPopular }
                        ).ifEmpty { all },
                        terbaru = CatalogMerger.mergeAndDeduplicate(
                            dbLatest,
                            dbBrowse,
                            wave.filter { it.isLatest },
                            bite.filter { it.isLatest },
                            goodShort.filter { it.isLatest },
                            melolo.filter { it.isLatest }
                        ).ifEmpty { all },
                        allCombined = all
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
                    val response = meloloService.search(query = cleanQuery)
                    val json = requireResponse(response)
                    JsonDataParser.parseDramaList(json)
                }
            }

            val boxDef = async {
                runCatching {
                    val response = dramaBoxService.search(query = cleanQuery)
                    DramaBoxJsonParser.parseDramaList(
                        requireResponse(response)
                    )
                }
            }

            val waveDef = async {
                runCatching {
                    val response = dramaWaveService.search(query = cleanQuery)
                    val json = requireResponse(response)
                    checkApiError(
                        DramaWaveJsonParser.checkApiError(json)
                    )
                    DramaWaveJsonParser.parseDramaList(json)
                }
            }

            val biteDef = async {
                runCatching {
                    val response = dramaBiteService.search(query = cleanQuery)
                    val json = requireResponse(response)
                    checkApiError(
                        DramaBiteJsonParser.checkApiError(json)
                    )
                    DramaBiteJsonParser.parseDramaList(json)
                }
            }

            val goodShortDef = async {
                runCatching {
                    val response = goodShortService.search(query = cleanQuery)
                    val json = requireResponse(response)
                    checkApiError(
                        GoodShortJsonParser.checkApiError(json)
                    )
                    GoodShortJsonParser.parseDramaList(json)
                }
            }

            val melolo = meloloDef.await()
            val box = boxDef.await()
            val wave = waveDef.await()
            val bite = biteDef.await()
            val goodShort = goodShortDef.await()

            val combined = CatalogMerger.mergeAndDeduplicate(
                melolo.getOrNull().orEmpty(),
                box.getOrNull().orEmpty(),
                wave.getOrNull().orEmpty(),
                bite.getOrNull().orEmpty(),
                goodShort.getOrNull().orEmpty()
            )

            if (combined.isEmpty() &&
                melolo.isFailure &&
                box.isFailure &&
                wave.isFailure &&
                bite.isFailure &&
                goodShort.isFailure
            ) {
                return@coroutineScope Result.failure(
                    melolo.exceptionOrNull()
                        ?: box.exceptionOrNull()
                        ?: wave.exceptionOrNull()
                        ?: bite.exceptionOrNull()
                        ?: goodShort.exceptionOrNull()
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
                    val json = requireResponse(
                        meloloService.getDetail(id = dramaId)
                    )
                    Result.success(
                        JsonDataParser.parseDramaDetail(dramaId, json)
                    )
                }

                DramaProvider.DRAMABOX -> coroutineScope {
                    val detailDef = async {
                        dramaBoxService.getDetail(bookId = dramaId)
                    }
                    val episodesDef = async {
                        runCatching {
                            dramaBoxService.getAllEpisodes(bookId = dramaId)
                        }.getOrNull()
                    }

                    val detailJson = requireResponse(detailDef.await())
                    val episodesJson = episodesDef.await()?.let {
                        runCatching { requireResponse(it) }.getOrNull()
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
                        dramaBoxEpisodesCache[dramaId] = detail.episodes
                    }

                    Result.success(detail)
                }

                DramaProvider.DRAMAWAVE -> {
                    val json = requireResponse(
                        dramaWaveService.getDetail(id = dramaId)
                    )
                    Result.success(
                        DramaWaveJsonParser.parseDramaDetail(
                            dramaId,
                            json
                        )
                    )
                }

                DramaProvider.DRAMABITE -> {
                    val json = requireResponse(
                        dramaBiteService.getDetail(id = dramaId)
                    )
                    Result.success(
                        DramaBiteJsonParser.parseDramaDetail(
                            dramaId,
                            json
                        )
                    )
                }

                DramaProvider.GOODSHORT -> {
                    val json = requireResponse(
                        goodShortService.getDetail(id = dramaId)
                    )
                    Result.success(
                        GoodShortJsonParser.parseDramaDetail(
                            dramaId,
                            json
                        )
                    )
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
                    val json = requireResponse(
                        meloloService.getVideo(
                            id = dramaId,
                            ep = episodeNum
                        )
                    )
                    val video = JsonDataParser.parseVideoSource(
                        dramaId,
                        episodeNum,
                        json
                    )

                    if (video != null && video.videoUrl.isNotEmpty()) {
                        Result.success(video)
                    } else {
                        Result.failure(Exception("Video tidak dapat diputar."))
                    }
                }

                DramaProvider.DRAMABOX -> {
                    var episodes = if (!forceRefresh) {
                        dramaBoxEpisodesCache[dramaId]
                    } else {
                        null
                    }

                    if (episodes.isNullOrEmpty()) {
                        val json = requireResponse(
                            dramaBoxService.getAllEpisodes(
                                bookId = dramaId
                            )
                        )
                        episodes = DramaBoxJsonParser.parseAllEpisodes(json)

                        if (episodes.isNotEmpty()) {
                            dramaBoxEpisodesCache[dramaId] = episodes
                        }
                    }

                    val target = episodes.find {
                        it.episodeNum == episodeNum
                    }

                    val video = target?.let {
                        DramaBoxJsonParser.toVideoSource(dramaId, it)
                    }

                    if (video != null && video.videoUrl.isNotEmpty()) {
                        Result.success(video)
                    } else {
                        Result.failure(Exception("Video tidak dapat diputar."))
                    }
                }

                DramaProvider.DRAMABITE -> {
                    val json = requireResponse(
                        dramaBiteService.getVideo(
                            id = dramaId,
                            episode = episodeNum
                        )
                    )

                    val video = JsonDataParser.parseVideoSource(
                        dramaId,
                        episodeNum,
                        json
                    )

                    if (video != null && video.videoUrl.isNotEmpty()) {
                        Result.success(video)
                    } else {
                        Result.failure(Exception("Video DramaBite tidak tersedia."))
                    }
                }

                DramaProvider.GOODSHORT -> {
                    val json = requireResponse(
                        goodShortService.getAllEpisodes(id = dramaId)
                    )

                    val video = JsonDataParser.parseVideoSource(
                        dramaId,
                        episodeNum,
                        json
                    )

                    if (video != null && video.videoUrl.isNotEmpty()) {
                        Result.success(video)
                    } else {
                        Result.failure(Exception("Video GoodShort tidak tersedia."))
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
    ): Flow<Boolean> =
        dao.isFavorite(provider.name, dramaId)

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

    suspend fun clearAllHistory() =
        withContext(Dispatchers.IO) {
            dao.clearHistory()
        }

    private suspend fun fetchMeloloHome(): Result<List<Drama>> {
        return try {
            val json = requireResponse(
                meloloService.getHome(offset = 0)
            )
            Result.success(JsonDataParser.parseDramaList(json))
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    private suspend fun fetchDramaWaveHome(): Result<List<Drama>> {
        return try {
            val json = requireResponse(dramaWaveService.getHome())
            checkApiError(DramaWaveJsonParser.checkApiError(json))
            Result.success(DramaWaveJsonParser.parseDramaList(json))
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    private suspend fun fetchDramaBiteHome(): Result<List<Drama>> {
        return try {
            val json = requireResponse(dramaBiteService.getHome())
            checkApiError(DramaBiteJsonParser.checkApiError(json))
            Result.success(DramaBiteJsonParser.parseDramaList(json))
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    private suspend fun fetchGoodShortHome(): Result<List<Drama>> {
        return try {
            val json = requireResponse(goodShortService.getHome())
            checkApiError(GoodShortJsonParser.checkApiError(json))
            Result.success(GoodShortJsonParser.parseDramaList(json))
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    private suspend fun fetchPreferredDramaBoxLang(): String {
        return try {
            val json = requireResponse(dramaBoxService.getLanguages())
            val langs = DramaBoxJsonParser.parseLanguages(json)

            if (langs.any {
                    it.first.equals("id", ignoreCase = true)
                }
            ) {
                "id"
            } else {
                langs.firstOrNull()?.first ?: "id"
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
            val json = requireResponse(call())
            Result.success(
                DramaBoxJsonParser.parseDramaList(
                    json,
                    markPopular = markPopular,
                    markLatest = markLatest
                )
            )
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    private fun requireResponse(
        response: Response<okhttp3.ResponseBody>
    ): String {
        if (response.isSuccessful) {
            return response.body()?.string().orEmpty()
        }

        val code = response.code()
        val message = when (code) {
            401, 403 -> "Akses layanan ditolak. Periksa konfigurasi API."
            429 -> "Layanan sedang sibuk. Coba kembali beberapa saat lagi."
            500, 502, 503, 504 ->
                "Koneksi sedang bermasalah. Silakan coba kembali."
            else -> "Drama tidak tersedia untuk saat ini."
        }

        response.errorBody()?.close()
        throw ApiException(code, message)
    }

    private fun checkApiError(error: Pair<Int, String>?) {
        if (error != null) {
            throw ApiException(error.first, error.second)
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

        return Exception("$name belum terhubung ke layanan video.")
    }

    private fun mapException(e: Throwable): Throwable {
        return when (e) {
            is ApiException -> e
            is SocketTimeoutException,
            is UnknownHostException,
            is IOException ->
                Exception("Koneksi sedang bermasalah. Silakan coba kembali.")
            else ->
                Exception(
                    e.message ?: "Drama tidak tersedia untuk saat ini."
                )
        }
    }
}

class ApiException(
    val code: Int,
    message: String
) : Exception(message)
