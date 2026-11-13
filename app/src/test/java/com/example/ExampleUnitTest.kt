package com.example

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ads.AdInstanceHandle
import com.example.ads.InterstitialAdController
import com.example.ads.InterstitialAdGateway
import com.example.data.api.ApiAuthManager
import com.example.data.api.ApiConstants
import com.example.data.api.ApiShortInterceptor
import com.example.data.api.DramaBoxApiService
import com.example.data.api.DramaBoxJsonParser
import com.example.data.api.JsonDataParser
import com.example.data.api.MeloloApiService
import com.example.data.db.NaraDatabase
import com.example.data.repository.CatalogMerger
import com.example.data.repository.NaraRepository
import com.example.model.DetectedVideoFormat
import com.example.model.Drama
import com.example.model.DramaProvider
import com.example.player.CencMp4DataSource
import com.example.player.VideoFormatDetector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(UnstableApi::class)
class ExampleUnitTest {

    private lateinit var db: NaraDatabase

    @Before
    fun setupDb() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    // =========================================================
    // 1. AUTHENTICATION: 3 KEYS, 401/403 ROTATION, 429, 502/503/504
    // =========================================================

    @Test
    fun authInterceptor_rotatesThroughThreeKeysOnceOn401And403() {
        assertEquals(3, ApiConstants.API_KEYS.size)
        assertEquals("vbs_cbb6e561d49018a8ed3f1f55bfd0e6662f157f753f7ca08a", ApiConstants.API_KEYS[0])
        assertEquals("vbs_7dd91a0c2f823aac18c1b08883cd6d0c5cced4e903b7cc62", ApiConstants.API_KEYS[1])
        assertEquals("vbs_0d7e1b255475439d9d808f700e60d87182faef0054d21020", ApiConstants.API_KEYS[2])

        val sentKeys = mutableListOf<String>()
        val authManager = ApiAuthManager(ApiConstants.API_KEYS)
        val sleepCalls = mutableListOf<Long>()

        val client = OkHttpClient.Builder()
            .addInterceptor(ApiShortInterceptor(authManager) { sleepCalls.add(it) })
            .addInterceptor { chain ->
                val req = chain.request()
                val headerValues = req.headers(ApiConstants.HEADER_API_KEY)
                // Never send multiple keys in one request
                assertEquals(1, headerValues.size)
                assertEquals(ApiConstants.ACCEPT_JSON, req.header(ApiConstants.HEADER_ACCEPT))
                val key = headerValues[0]
                sentKeys.add(key)

                val code = when (key) {
                    ApiConstants.API_KEYS[0] -> 401
                    ApiConstants.API_KEYS[1] -> 403
                    else -> 200
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        client.newCall(Request.Builder().url("https://apishort.com/v1/melolo/api/home").build())
            .execute().use { resp ->
                assertEquals(200, resp.code)
            }

        assertEquals(ApiConstants.API_KEYS, sentKeys)
        assertEquals(2, authManager.getCurrentKeyIndex())
    }

    @Test
    fun authInterceptor_attemptsEachKeyAtMostOnceWhenAllKeysFail() {
        val sentKeys = mutableListOf<String>()
        val authManager = ApiAuthManager(ApiConstants.API_KEYS)

        val client = OkHttpClient.Builder()
            .addInterceptor(ApiShortInterceptor(authManager) {})
            .addInterceptor { chain ->
                val req = chain.request()
                sentKeys.add(req.header(ApiConstants.HEADER_API_KEY)!!)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("{}".toResponseBody())
                    .build()
            }
            .build()

        client.newCall(Request.Builder().url("https://apishort.com/v1/melolo/api/home").build())
            .execute().use { resp ->
                assertEquals(401, resp.code)
            }

        assertEquals(3, sentKeys.size)
        assertEquals(ApiConstants.API_KEYS.toSet(), sentKeys.toSet())
    }

    @Test
    fun authInterceptor_doesNotRotateOn502_503_504_andRespects429RetryAfter() {
        // Test 502/503/504 never rotates key
        for (gatewayCode in listOf(502, 503, 504)) {
            val sentKeys = mutableListOf<String>()
            val authManager = ApiAuthManager(ApiConstants.API_KEYS)
            val client = OkHttpClient.Builder()
                .addInterceptor(ApiShortInterceptor(authManager) {})
                .addInterceptor { chain ->
                    val req = chain.request()
                    sentKeys.add(req.header(ApiConstants.HEADER_API_KEY)!!)
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(gatewayCode)
                        .message("Gateway Error")
                        .body("".toResponseBody())
                        .build()
                }
                .build()

            client.newCall(Request.Builder().url("https://apishort.com/test").build()).execute().use { resp ->
                assertEquals(gatewayCode, resp.code)
            }
            assertTrue(sentKeys.all { it == ApiConstants.API_KEYS[0] })
            assertEquals(0, authManager.getCurrentKeyIndex())
        }

        // Test 429 respects Retry-After without rotating key
        val sent429Keys = mutableListOf<String>()
        val sleeps = mutableListOf<Long>()
        val authManager429 = ApiAuthManager(ApiConstants.API_KEYS)
        var calls = 0
        val client429 = OkHttpClient.Builder()
            .addInterceptor(ApiShortInterceptor(authManager429) { sleeps.add(it) })
            .addInterceptor { chain ->
                val req = chain.request()
                sent429Keys.add(req.header(ApiConstants.HEADER_API_KEY)!!)
                calls++
                if (calls == 1) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .header("Retry-After", "2")
                        .body("".toResponseBody())
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{}".toResponseBody())
                        .build()
                }
            }
            .build()

        client429.newCall(Request.Builder().url("https://apishort.com/test").build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        assertEquals(listOf(ApiConstants.API_KEYS[0], ApiConstants.API_KEYS[0]), sent429Keys)
        assertEquals(listOf(2000L), sleeps)
        assertEquals(0, authManager429.getCurrentKeyIndex())
    }

    // =========================================================
    // 2. MELOLO + DRAMABOX PARSING
    // =========================================================

    @Test
    fun meloloParsers_handleHomeSearchDetailAndVideo() {
        val searchJson = """
            {
              "code": 0,
              "data": [
                {"id": "1757306532031237470", "name": "Cinta", "cover": "", "episodes": 0},
                {"id": "7621495490156170200", "name": "Dia Ketagihan", "cover": "https://cdn.example/c.jpg", "episodes": 85}
              ]
            }
        """.trimIndent()
        val dramas = JsonDataParser.parseDramaList(searchJson)
        assertEquals(1, dramas.size)
        assertEquals(DramaProvider.MELOLO, dramas[0].provider)
        assertEquals("7621495490156170200", dramas[0].id)

        val detailJson = """
            {
              "code": 0,
              "id": "7621495490156170200",
              "title": "Dia Ketagihan",
              "cover": "https://cdn.example/c.jpg",
              "episodes": 2,
              "videos": [
                {"duration": 159, "episode": 1, "vid": "v1"},
                {"duration": 140, "episode": 2, "vid": "v2"}
              ]
            }
        """.trimIndent()
        val detail = JsonDataParser.parseDramaDetail("7621495490156170200", detailJson)
        assertEquals(2, detail.episodes.size)
        assertEquals("v1", detail.episodes[0].videoId)

        val videoJson = """
            {
              "code": 200,
              "locked": false,
              "videoUrl": "https://v19-novel.fizzostatic.org/stream?t=1",
              "qualityList": [
                {"label": "720p", "url": "https://v19-novel.fizzostatic.org/stream?t=1", "key": "1c047bf2d3182e20fd8f5d5bebf85703", "kid": "68674af2f8818bcd08ac15e20010508b"}
              ]
            }
        """.trimIndent()
        val video = JsonDataParser.parseVideoSource("7621495490156170200", 1, videoJson)
        assertNotNull(video)
        assertEquals(DramaProvider.MELOLO, video?.provider)
        assertEquals("1c047bf2d3182e20fd8f5d5bebf85703", video?.decryptionKey)
    }

    @Test
    fun dramaBoxParsers_handleLanguagesBrowseListDetailAndAllEpisodes() {
        val langs = DramaBoxJsonParser.parseLanguages("""[{"code":"en","name":"English"},{"code":"id","name":"Indonesian"}]""")
        assertEquals(2, langs.size)
        assertEquals("id", langs[1].first)

        val browseJson = """
            {
              "items": [
                {
                  "bookId": "42000032479",
                  "bookName": "Dendam Berujung Penyesalan",
                  "coverWap": "https://cdn.dramabox/poster.jpg",
                  "chapterCount": 50,
                  "introduction": "Sinopsis DramaBox.",
                  "tags": ["Penyesalan", "Balas Dendam"],
                  "tagV3s": [{"tagId": 1, "tagName": "Romansa"}]
                }
              ]
            }
        """.trimIndent()
        val browseList = DramaBoxJsonParser.parseDramaList(browseJson, markPopular = true)
        assertEquals(1, browseList.size)
        assertEquals(DramaProvider.DRAMABOX, browseList[0].provider)
        assertEquals("42000032479", browseList[0].id)
        assertEquals(50, browseList[0].totalEpisodes)
        assertTrue(browseList[0].isPopular)
        assertEquals(listOf("Penyesalan", "Balas Dendam", "Romansa"), browseList[0].categories)

        val detailJson = """
            {
              "bookId": "42000032479",
              "bookName": "Dendam Berujung Penyesalan",
              "coverWap": "https://cdn.dramabox/poster.jpg",
              "chapterCount": 2,
              "introduction": "Sinopsis DramaBox."
            }
        """.trimIndent()
        val allEpJson = """
            [
              {
                "chapterId": "701",
                "chapterIndex": 0,
                "chapterName": "EP 1",
                "isLocked": false,
                "videoUrl": "https://hwztakavideoto.dramaboxdb.com/ep1.720p.mp4",
                "videoUrl540": "https://hwztakavideoto.dramaboxdb.com/ep1.540p.mp4",
                "videoUrl1080": "https://hwztakavideoto.dramaboxdb.com/ep1.1080p.mp4"
              },
              {
                "chapterId": "702",
                "chapterIndex": 1,
                "chapterName": "EP 2",
                "isLocked": true,
                "videoUrl": "https://hwztakavideoto.dramaboxdb.com/ep2.720p.mp4"
              }
            ]
        """.trimIndent()

        val detail = DramaBoxJsonParser.parseDramaDetail("42000032479", detailJson, allEpJson)
        assertEquals(2, detail.episodes.size)
        assertEquals(1, detail.episodes[0].episodeNum)
        assertEquals(2, detail.episodes[1].episodeNum)

        val ep2Video = DramaBoxJsonParser.toVideoSource("42000032479", detail.episodes[1])
        assertNotNull(ep2Video)
        assertEquals(DramaProvider.DRAMABOX, ep2Video?.provider)
        assertEquals("https://hwztakavideoto.dramaboxdb.com/ep2.720p.mp4", ep2Video?.videoUrl)
    }

    // =========================================================
    // 3. MERGED CATALOG, PARALLEL SEARCH, ISOLATION, ROOM DB
    // =========================================================

    @Test
    fun catalogMerger_deduplicatesOnlyTrulyIdenticalTitlesAndPreservesProviders() {
        val m1 = Drama(id = "100", title = "Raja Naga", cover = "https://m/1.jpg", totalEpisodes = 40, provider = DramaProvider.MELOLO)
        val m2 = Drama(id = "101", title = "Cinta Abadi", cover = "https://m/2.jpg", totalEpisodes = 60, provider = DramaProvider.MELOLO)
        val d1 = Drama(id = "900", title = "Raja Naga!", cover = "https://d/1.jpg", totalEpisodes = 50, synopsis = "Lengkap", provider = DramaProvider.DRAMABOX)
        val d2 = Drama(id = "901", title = "Raja Naga (Sulih Suara)", cover = "https://d/2.jpg", totalEpisodes = 50, provider = DramaProvider.DRAMABOX)

        val merged = CatalogMerger.mergeAndDeduplicate(listOf(m1, m2), listOf(d1, d2))
        // "Raja Naga" and "Raja Naga!" collapse into 1; "Cinta Abadi" and "Raja Naga (Sulih Suara)" stay distinct
        assertEquals(3, merged.size)
        val rajaNaga = merged.find { it.title.startsWith("Raja Naga") && !it.title.contains("Sulih") }
        assertEquals(50, rajaNaga?.totalEpisodes)
    }

    @Test
    fun repository_combinedCatalogAndSearchSurviveSingleSourceFailureAndIsolateSameIds() = runTest {
        val fakeMelolo = object : MeloloApiService {
            override suspend fun getHome(lang: String, offset: Int): retrofit2.Response<ResponseBody> =
                throw IOException("Melolo offline")

            override suspend fun search(lang: String, query: String): retrofit2.Response<ResponseBody> =
                retrofit2.Response.success(
                    """{"code":0,"data":[{"id":"777","name":"Melolo Hit","cover":"https://m/c.jpg","episodes":30}]}"""
                        .toResponseBody("application/json".toMediaType())
                )

            override suspend fun getDetail(id: String, lang: String): retrofit2.Response<ResponseBody> =
                retrofit2.Response.success(
                    """{"code":0,"id":"$id","title":"Melolo Detail $id","cover":"https://m/c.jpg","episodes":1,"videos":[{"episode":1,"vid":"m1"}]}"""
                        .toResponseBody("application/json".toMediaType())
                )

            override suspend fun getVideo(id: String, ep: Int): retrofit2.Response<ResponseBody> =
                retrofit2.Response.success(
                    """{"code":200,"videoUrl":"https://melolo.cdn/video_$id.mp4"}"""
                        .toResponseBody("application/json".toMediaType())
                )
        }

        val fakeDramaBox = object : DramaBoxApiService {
            override suspend fun getLanguages() =
                retrofit2.Response.success("""[{"code":"id","name":"Indonesian"}]""".toResponseBody())

            override suspend fun getLatest(lang: String) =
                retrofit2.Response.success("""[{"bookId":"777","bookName":"DramaBox Latest","coverWap":"https://d/1.jpg","chapterCount":20}]""".toResponseBody())

            override suspend fun getTrending(lang: String) =
                retrofit2.Response.success("""[{"bookId":"888","bookName":"DramaBox Trend","coverWap":"https://d/2.jpg","chapterCount":50,"tags":["Romansa"]}]""".toResponseBody())

            override suspend fun getForYou(lang: String) =
                retrofit2.Response.success("""[]""".toResponseBody())

            override suspend fun getBrowse(page: Int, lang: String) =
                retrofit2.Response.success("""{"items":[]}""".toResponseBody())

            override suspend fun getDubbed(classify: String, page: Int, lang: String) =
                retrofit2.Response.success("""[]""".toResponseBody())

            override suspend fun search(query: String, lang: String): retrofit2.Response<ResponseBody> =
                retrofit2.Response.success("""[{"bookId":"777","bookName":"DramaBox Hit","coverWap":"https://d/1.jpg","chapterCount":20}]""".toResponseBody())

            override suspend fun getDetail(bookId: String, lang: String) =
                retrofit2.Response.success("""{"bookId":"$bookId","bookName":"DramaBox Detail $bookId","coverWap":"https://d/1.jpg","chapterCount":1}""".toResponseBody())

            override suspend fun getAllEpisodes(bookId: String) =
                retrofit2.Response.success("""[{"chapterId":"db1","chapterIndex":0,"chapterName":"EP 1","videoUrl":"https://dramabox.cdn/video_$bookId.mp4"}]""".toResponseBody())
        }

        val repo = NaraRepository(fakeMelolo, fakeDramaBox, db.naraDao())

        // Even though Melolo /home throws IOException, DramaBox catalog still loads
        val homeRes = repo.getHomeCatalog()
        assertTrue(homeRes.isSuccess)
        assertTrue(homeRes.getOrNull()!!.allCombined.any { it.provider == DramaProvider.DRAMABOX })

        // Parallel search merges both providers
        val searchRes = repo.searchDramas("hit")
        assertTrue(searchRes.isSuccess)
        val searchList = searchRes.getOrNull()!!
        assertEquals(2, searchList.size)
        assertEquals(setOf(DramaProvider.MELOLO, DramaProvider.DRAMABOX), searchList.map { it.provider }.toSet())

        // Same numeric ID "777" remains strictly isolated across Detail, Video, Favorites, and History
        val meloloDetail = repo.getDramaDetail(DramaProvider.MELOLO, "777").getOrNull()!!
        val dramaBoxDetail = repo.getDramaDetail(DramaProvider.DRAMABOX, "777").getOrNull()!!
        assertEquals("Melolo Detail 777", meloloDetail.drama.title)
        assertEquals("DramaBox Detail 777", dramaBoxDetail.drama.title)

        val meloloVid = repo.getVideoSource(DramaProvider.MELOLO, "777", 1).getOrNull()!!
        val dramaBoxVid = repo.getVideoSource(DramaProvider.DRAMABOX, "777", 1).getOrNull()!!
        assertTrue(meloloVid.videoUrl.contains("melolo.cdn"))
        assertTrue(dramaBoxVid.videoUrl.contains("dramabox.cdn"))

        // Room isolation
        repo.toggleFavorite(meloloDetail.drama, isFav = false)
        repo.toggleFavorite(dramaBoxDetail.drama, isFav = false)
        assertEquals(2, repo.getAllFavorites().first().size)
        assertTrue(repo.isFavorite(DramaProvider.MELOLO, "777").first())
        assertTrue(repo.isFavorite(DramaProvider.DRAMABOX, "777").first())

        repo.saveWatchProgress(DramaProvider.MELOLO, "777", "M", "", 3, 5000L, 10000L, 10)
        repo.saveWatchProgress(DramaProvider.DRAMABOX, "777", "D", "", 7, 8000L, 10000L, 20)
        assertEquals(3, repo.getHistory(DramaProvider.MELOLO, "777").first()?.lastEpisode)
        assertEquals(7, repo.getHistory(DramaProvider.DRAMABOX, "777").first()?.lastEpisode)
    }

    // =========================================================
    // 4. VIDEO FORMAT DETECTION & CENC DECRYPTION
    // =========================================================

    @Test
    fun videoFormatDetector_classifiesExtensionlessStreamsAccurately() {
        val urlWithoutExt = "https://v19-novel.fizzostatic.org/6823/video/tos/cn/v1/abc?expire=999"

        val cencHeader = buildMinimalMp4Bytes(includeCenc = true)
        val cencDiag = VideoFormatDetector.classifyBytes(
            url = urlWithoutExt,
            httpStatus = 206,
            contentType = "video/mp4",
            contentLength = 100000L,
            supportsRange = true,
            prefix = cencHeader,
            decryptionKey = "1c047bf2d3182e20fd8f5d5bebf85703"
        )
        assertEquals(DetectedVideoFormat.MP4_CENC, cencDiag.detectedFormat)
        assertNull(cencDiag.failureReason)

        val hlsDiag = VideoFormatDetector.classifyBytes(
            url = urlWithoutExt,
            httpStatus = 200,
            contentType = "application/octet-stream",
            contentLength = -1L,
            supportsRange = false,
            prefix = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()
        )
        assertEquals(DetectedVideoFormat.HLS, hlsDiag.detectedFormat)

        val htmlDiag = VideoFormatDetector.classifyBytes(
            url = urlWithoutExt,
            httpStatus = 200,
            contentType = "text/html",
            contentLength = 128L,
            supportsRange = false,
            prefix = "<!DOCTYPE html><html><body>Forbidden</body></html>".toByteArray()
        )
        assertEquals(DetectedVideoFormat.INVALID_RESPONSE, htmlDiag.detectedFormat)
        assertNotNull(htmlDiag.failureReason)
    }

    @Test
    fun cencMp4DataSource_patchesMoovAndDecryptsSampleBytesRandomAccess() {
        val keyHex = "1c047bf2d3182e20fd8f5d5bebf85703"
        val keyBytes = CencMp4DataSource.hexToBytes(keyHex)
        val iv8 = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())
        val plainSample = "HELLO_NARA_CENC_DECRYPTED_HEVC_PAYLOAD_0123456789ABCDEF".toByteArray(StandardCharsets.UTF_8)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        }
        val encryptedSample = ByteArray(plainSample.size)
        var offset = 0
        var blockIdx = 0L
        while (offset < plainSample.size) {
            val counter = ByteArray(16)
            System.arraycopy(iv8, 0, counter, 0, 8)
            ByteBuffer.wrap(counter, 8, 8).putLong(blockIdx)
            val ks = cipher.doFinal(counter)
            val chunk = minOf(16, plainSample.size - offset)
            for (i in 0 until chunk) {
                encryptedSample[offset + i] = (plainSample[offset + i].toInt() xor ks[i].toInt()).toByte()
            }
            offset += chunk
            blockIdx++
        }

        val ftyp = box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(StandardCharsets.US_ASCII))
        val preMoov = buildSyntheticCencMoov(encryptedSample.size, 0, iv8)
        val mdatOffset = ftyp.size + preMoov.size + 8
        val moov = buildSyntheticCencMoov(encryptedSample.size, mdatOffset, iv8)
        val mdat = box("mdat", encryptedSample)
        val fullFile = ftyp + moov + mdat

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val rangeHdr = req.header("Range") ?: "bytes=0-${fullFile.size - 1}"
                val parts = rangeHdr.removePrefix("bytes=").split("-")
                val start = parts[0].toInt()
                val end = if (parts.size > 1 && parts[1].isNotEmpty()) minOf(parts[1].toInt(), fullFile.size - 1) else fullFile.size - 1
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes $start-$end/${fullFile.size}")
                    .body(fullFile.copyOfRange(start, end + 1).toResponseBody())
                    .build()
            }
            .build()

        val ds = CencMp4DataSource.Factory(mockHttp, keyHex).createDataSource()
        ds.open(DataSpec.Builder().setUri("https://cdn.example/video").setPosition(0).build())
        val out = ByteArrayOutputStream()
        val buf = ByteArray(128)
        while (true) {
            val n = ds.read(buf, 0, buf.size)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        ds.close()

        val resultBytes = out.toByteArray()
        assertArrayEquals(plainSample, resultBytes.copyOfRange(mdatOffset, resultBytes.size))
    }

    // =========================================================
    // 5. START.IO INTERSTITIAL CONTROLLER TESTS
    // =========================================================

    @Test
    fun interstitialAdController_enforcesPreloadEndedAutoNextLocksAndCancel() {
        val context: Context = ApplicationProvider.getApplicationContext()
        var initCount = 0
        var shouldFailLoad = false
        var showCallCount = 0
        var pendingHidden: (() -> Unit)? = null
        var pendingNotDisplayed: (() -> Unit)? = null

        val fakeGateway = object : InterstitialAdGateway {
            override fun initializeSdk(context: Context, appId: String, onInitialized: () -> Unit) {
                assertEquals(InterstitialAdController.START_IO_APP_ID, appId)
                assertEquals("210355804", appId)
                initCount++
                onInitialized()
            }

            override fun loadInterstitial(
                context: Context,
                onLoaded: (AdInstanceHandle) -> Unit,
                onFailed: () -> Unit
            ) {
                if (shouldFailLoad) {
                    onFailed()
                    return
                }
                onLoaded(object : AdInstanceHandle {
                    override fun show(
                        onDisplayed: () -> Unit,
                        onHidden: () -> Unit,
                        onNotDisplayed: () -> Unit
                    ): Boolean {
                        showCallCount++
                        pendingHidden = onHidden
                        pendingNotDisplayed = onNotDisplayed
                        onDisplayed()
                        return true
                    }
                })
            }
        }

        val controller = InterstitialAdController(fakeGateway) { it.run() }
        controller.initialize(context)
        assertEquals(1, initCount)
        assertTrue(controller.isSdkInitialized)

        // 1. Preload succeeds, episode ended shows ad, double-trigger blocked, adHidden advances and preloads next
        val session1 = controller.startNewSession()
        controller.preloadIfNeeded(context)
        assertTrue(controller.isAdReady)

        var nextCount = 0
        controller.onEpisodeEnded(context, session1, hasNextEpisode = true) { nextCount++ }
        // Duplicate ended event while ad showing must be ignored
        controller.onEpisodeEnded(context, session1, hasNextEpisode = true) { nextCount++ }

        assertEquals(1, showCallCount)
        assertTrue(controller.isAdShowing)
        assertTrue(controller.isAdProcessing)
        assertEquals(0, nextCount)

        // Closing ad advances episode and immediately preloads next ad
        pendingHidden?.invoke()
        assertEquals(1, nextCount)
        assertFalse(controller.isAdShowing)
        assertFalse(controller.isAdProcessing)
        assertTrue(controller.isAdReady)

        // 2. Last episode must NEVER trigger ad or next episode
        controller.onEpisodeEnded(context, session1, hasNextEpisode = false) { nextCount++ }
        assertEquals(1, showCallCount)
        assertEquals(1, nextCount)

        // 3. Exiting / cancelling while ad is processing cancels auto-next
        val session2 = controller.startNewSession()
        controller.onEpisodeEnded(context, session2, hasNextEpisode = true) { nextCount++ }
        assertEquals(2, showCallCount)
        controller.cancelPendingTransition()
        pendingHidden?.invoke()
        assertEquals(1, nextCount) // auto-next cancelled!

        // 4. When ad fails to load / not ready, immediately proceeds to next episode without waiting
        shouldFailLoad = true
        val session3 = controller.startNewSession()
        controller.preloadIfNeeded(context)
        assertFalse(controller.isAdReady)
        controller.onEpisodeEnded(context, session3, hasNextEpisode = true) { nextCount++ }
        assertEquals(2, showCallCount) // no ad shown
        assertEquals(2, nextCount) // proceeded immediately

        // 5. If adNotDisplayed fires, proceeds cleanly
        shouldFailLoad = false
        val session4 = controller.startNewSession()
        controller.preloadIfNeeded(context)
        controller.onEpisodeEnded(context, session4, hasNextEpisode = true) { nextCount++ }
        assertEquals(3, showCallCount)
        pendingNotDisplayed?.invoke()
        assertEquals(3, nextCount)
    }

    private fun buildMinimalMp4Bytes(includeCenc: Boolean): ByteArray {
        val ftyp = box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(StandardCharsets.US_ASCII))
        return if (includeCenc) {
            ftyp + buildSyntheticCencMoov(32, 1024, ByteArray(8))
        } else {
            val avc1 = ByteArray(78) + box("avcC", ByteArray(4))
            val stsdPayload = ByteArray(8)
            stsdPayload[7] = 1
            val stsd = box("stsd", stsdPayload + box("avc1", avc1))
            ftyp + box("moov", box("trak", box("mdia", box("minf", box("stbl", stsd)))))
        }
    }

    private fun buildSyntheticCencMoov(sampleSize: Int, sampleFileOffset: Int, iv8: ByteArray): ByteArray {
        val frma = box("frma", "hvc1".toByteArray(StandardCharsets.US_ASCII))
        val schm = box("schm", ByteArray(4) + "cenc".toByteArray(StandardCharsets.US_ASCII) + ByteArray(4))
        val tencPayload = ByteArray(24)
        tencPayload[6] = 1
        tencPayload[7] = 8
        val schi = box("schi", box("tenc", tencPayload))
        val sinf = box("sinf", frma + schm + schi)
        val encv = box("encv", ByteArray(78) + box("hvcC", ByteArray(4)) + sinf)
        val stsd = box("stsd", ByteBuffer.allocate(8).putInt(0).putInt(1).array() + encv)
        val stsc = box("stsc", ByteBuffer.allocate(20).putInt(0).putInt(1).putInt(1).putInt(1).putInt(1).array())
        val stsz = box("stsz", ByteBuffer.allocate(16).putInt(0).putInt(sampleSize).putInt(1).array())
        val stco = box("stco", ByteBuffer.allocate(12).putInt(0).putInt(1).putInt(sampleFileOffset).array())
        val senc = box("senc", ByteBuffer.allocate(8 + iv8.size).putInt(0).putInt(1).put(iv8).array())
        return box("moov", box("trak", box("mdia", box("minf", box("stbl", stsd + stsc + stsz + stco + senc)))))
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val total = 8 + payload.size
        return ByteBuffer.allocate(total)
            .putInt(total)
            .put(type.toByteArray(StandardCharsets.US_ASCII), 0, 4)
            .put(payload)
            .array()
    }
}
