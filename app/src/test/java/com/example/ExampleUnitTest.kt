package com.example

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.ApiConstants
import com.example.data.api.ApiKeyStore
import com.example.data.api.JsonDataParser
import com.example.model.DetectedVideoFormat
import com.example.player.CencMp4DataSource
import com.example.player.VideoFormatDetector
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(UnstableApi::class)
class ExampleUnitTest {

    @Test
    fun parseDramaList_filtersStubSearchResultAndParsesMeloloSchema() {
        val searchJson = """
            {
              "code": 0,
              "count": 2,
              "data": [
                {
                  "id": "1757306532031237470",
                  "name": "Cinta",
                  "cover": "",
                  "episodes": 0,
                  "intro": ""
                },
                {
                  "id": "7621495490156170200",
                  "name": "Dia Ketagihan",
                  "cover": "https://example.com/poster.jpg",
                  "episodes": 85,
                  "intro": "Drama romantis populer."
                }
              ],
              "has_more": true,
              "query": "cinta"
            }
        """.trimIndent()

        val dramas = JsonDataParser.parseDramaList(searchJson)
        assertEquals(1, dramas.size)
        assertEquals("7621495490156170200", dramas[0].id)
        assertEquals("Dia Ketagihan", dramas[0].title)
        assertEquals(85, dramas[0].totalEpisodes)
    }

    @Test
    fun parseDramaDetail_readsVideosArray() {
        val detailJson = """
            {
              "code": 0,
              "id": "7621495490156170200",
              "title": "Dia Ketagihan",
              "cover": "https://example.com/cover.jpg",
              "episodes": 2,
              "intro": "Sinopsis lengkap.",
              "videos": [
                {"duration": 159, "episode": 1, "vid": "7621495490156170293"},
                {"duration": 142, "episode": 2, "vid": "7621495490156170294"}
              ]
            }
        """.trimIndent()

        val detail = JsonDataParser.parseDramaDetail("7621495490156170200", detailJson)
        assertEquals("Dia Ketagihan", detail.drama.title)
        assertEquals(2, detail.episodes.size)
        assertEquals(1, detail.episodes[0].episodeNum)
        assertEquals("7621495490156170293", detail.episodes[0].videoId)
        assertEquals(159, detail.episodes[0].durationSeconds)
    }

    @Test
    fun parseVideoSource_extractsCamelCaseUrlAndCencKeysFromQualityList() {
        val videoJson = """
            {
              "code": 200,
              "episodeNumber": 1,
              "locked": false,
              "msg": "SUCCESS",
              "title": "Dia Ketagihan",
              "totalEpisodes": 85,
              "videoUrl": "https://v19-novel.fizzostatic.org/obj/stream/abc?token=xyz",
              "qualityList": [
                {
                  "key": "1c047bf2d3182e20fd8f5d5bebf85703",
                  "kid": "68674af2f8818bcd08ac15e20010508b",
                  "label": "720p",
                  "url": "https://v19-novel.fizzostatic.org/obj/stream/abc?token=xyz"
                }
              ]
            }
        """.trimIndent()

        val source = JsonDataParser.parseVideoSource("7621495490156170200", 1, videoJson)
        assertNotNull(source)
        assertEquals("https://v19-novel.fizzostatic.org/obj/stream/abc?token=xyz", source?.videoUrl)
        assertEquals("720p", source?.quality)
        assertEquals("1c047bf2d3182e20fd8f5d5bebf85703", source?.decryptionKey)
        assertEquals("68674af2f8818bcd08ac15e20010508b", source?.decryptionKid)
        assertFalse(source?.isHls ?: true)
    }

    @Test
    fun videoFormatDetector_classifiesExtensionlessContainersAccurately() {
        val urlWithoutExt = "https://v19-novel.fizzostatic.org/6823/video/tos/cn/v1/abc?expire=999"

        // 1. CENC MP4 with HEVC + AAC
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
        assertTrue(cencDiag.codecInfo.contains("HEVC"))

        // 2. CENC MP4 missing key -> failureReason populated
        val cencMissingKey = VideoFormatDetector.classifyBytes(
            url = urlWithoutExt,
            httpStatus = 206,
            contentType = "video/mp4",
            contentLength = 100000L,
            supportsRange = true,
            prefix = cencHeader,
            decryptionKey = ""
        )
        assertEquals(DetectedVideoFormat.MP4_CENC, cencMissingKey.detectedFormat)
        assertNotNull(cencMissingKey.failureReason)

        // 3. Plain MP4
        val plainHeader = buildMinimalMp4Bytes(includeCenc = false)
        val plainDiag = VideoFormatDetector.classifyBytes(
            url = urlWithoutExt,
            httpStatus = 200,
            contentType = "application/octet-stream",
            contentLength = 50000L,
            supportsRange = false,
            prefix = plainHeader
        )
        assertEquals(DetectedVideoFormat.MP4_PLAIN, plainDiag.detectedFormat)

        // 4. HLS manifest
        val hlsDiag = VideoFormatDetector.classifyBytes(
            url = urlWithoutExt,
            httpStatus = 200,
            contentType = "application/octet-stream",
            contentLength = -1L,
            supportsRange = false,
            prefix = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()
        )
        assertEquals(DetectedVideoFormat.HLS, hlsDiag.detectedFormat)

        // 5. DASH manifest
        val dashDiag = VideoFormatDetector.classifyBytes(
            url = urlWithoutExt,
            httpStatus = 200,
            contentType = "text/xml",
            contentLength = -1L,
            supportsRange = false,
            prefix = "<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"></MPD>".toByteArray()
        )
        assertEquals(DetectedVideoFormat.DASH, dashDiag.detectedFormat)

        // 6. MPEG-TS
        val tsBytes = ByteArray(400)
        tsBytes[0] = 0x47
        tsBytes[188] = 0x47
        tsBytes[376] = 0x47
        val tsDiag = VideoFormatDetector.classifyBytes(
            url = urlWithoutExt,
            httpStatus = 200,
            contentType = "application/octet-stream",
            contentLength = -1L,
            supportsRange = false,
            prefix = tsBytes
        )
        assertEquals(DetectedVideoFormat.MPEG_TS, tsDiag.detectedFormat)

        // 7. HTML error page must NEVER be treated as MP4
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

        // Encrypt plainSample using AES-CTR (8-byte IV + 8-byte block counter)
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

        // Build complete MP4: ftyp + moov + mdat(8B header + encryptedSample)
        val ftyp = box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(StandardCharsets.US_ASCII))
        val mdatPayloadOffsetPlaceholder = 0 // computed after moov size is known
        val preMoov = buildSyntheticCencMoov(
            sampleSize = encryptedSample.size,
            sampleFileOffset = mdatPayloadOffsetPlaceholder,
            iv8 = iv8
        )
        val mdatOffset = ftyp.size + preMoov.size + 8
        val moov = buildSyntheticCencMoov(
            sampleSize = encryptedSample.size,
            sampleFileOffset = mdatOffset,
            iv8 = iv8
        )
        assertEquals(preMoov.size, moov.size)
        val mdat = box("mdat", encryptedSample)
        val fullFile = ftyp + moov + mdat

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val rangeHdr = req.header("Range") ?: "bytes=0-${fullFile.size - 1}"
                val parts = rangeHdr.removePrefix("bytes=").split("-")
                val start = parts[0].toInt()
                val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                    minOf(parts[1].toInt(), fullFile.size - 1)
                } else {
                    fullFile.size - 1
                }
                val slice = fullFile.copyOfRange(start, end + 1)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes $start-$end/${fullFile.size}")
                    .header("Content-Type", "video/mp4")
                    .body(slice.toResponseBody())
                    .build()
            }
            .build()

        val ds = CencMp4DataSource.Factory(mockHttp, keyHex).createDataSource()
        // Read entire virtual file starting from offset 0
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
        assertEquals(fullFile.size, resultBytes.size)
        // Verify moov was patched: encv -> hvc1, sinf -> free, senc -> free
        val patchedMoovText = String(resultBytes, ftyp.size, moov.size, StandardCharsets.US_ASCII)
        assertTrue(patchedMoovText.contains("hvc1"))
        assertFalse(patchedMoovText.contains("encv"))
        assertFalse(patchedMoovText.contains("sinf"))
        assertFalse(patchedMoovText.contains("senc"))

        // Verify mdat payload matches original plaintext sample byte-for-byte
        val decryptedPayload = resultBytes.copyOfRange(mdatOffset, resultBytes.size)
        assertArrayEquals(plainSample, decryptedPayload)

        // Verify mid-sample random-access seek (e.g., byte 20 of encrypted sample) decrypts cleanly
        val seekOffset = mdatOffset + 20L
        ds.open(DataSpec.Builder().setUri("https://cdn.example/video").setPosition(seekOffset).build())
        val tailBuf = ByteArray(plainSample.size - 20)
        var totalRead = 0
        while (totalRead < tailBuf.size) {
            val n = ds.read(tailBuf, totalRead, tailBuf.size - totalRead)
            if (n <= 0) break
            totalRead += n
        }
        ds.close()
        assertArrayEquals(plainSample.copyOfRange(20, plainSample.size), tailBuf)
    }

    @Test
    fun apiKeyStore_savesMasksAndClearsCustomKey() {
        val context: Context = ApplicationProvider.getApplicationContext()
        ApiKeyStore.init(context)
        ApiKeyStore.clearCustomApiKey()

        assertFalse(ApiKeyStore.hasCustomApiKey())
        assertEquals(ApiConstants.API_KEY, ApiKeyStore.getActiveApiKey())

        ApiKeyStore.saveCustomApiKey("custom_key_12345678")
        assertTrue(ApiKeyStore.hasCustomApiKey())
        assertEquals("custom_key_12345678", ApiKeyStore.getActiveApiKey())
        assertEquals("cust••••••••5678", ApiKeyStore.getMaskedCustomApiKey())

        ApiKeyStore.clearCustomApiKey()
        assertFalse(ApiKeyStore.hasCustomApiKey())
        assertEquals(ApiConstants.API_KEY, ApiKeyStore.getActiveApiKey())
    }

    private fun buildMinimalMp4Bytes(includeCenc: Boolean): ByteArray {
        val ftyp = box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(StandardCharsets.US_ASCII))
        return if (includeCenc) {
            ftyp + buildSyntheticCencMoov(sampleSize = 32, sampleFileOffset = 1024, iv8 = ByteArray(8))
        } else {
            val avc1 = ByteArray(78) + box("avcC", ByteArray(4))
            val stsdPayload = ByteArray(8)
            stsdPayload[7] = 1
            val stsd = box("stsd", stsdPayload + box("avc1", avc1))
            val stbl = box("stbl", stsd)
            val moov = box("moov", box("trak", box("mdia", box("minf", stbl))))
            ftyp + moov
        }
    }

    private fun buildSyntheticCencMoov(
        sampleSize: Int,
        sampleFileOffset: Int,
        iv8: ByteArray
    ): ByteArray {
        val frma = box("frma", "hvc1".toByteArray(StandardCharsets.US_ASCII))
        val schm = box("schm", ByteArray(4) + "cenc".toByteArray(StandardCharsets.US_ASCII) + ByteArray(4))
        val tencPayload = ByteArray(24)
        tencPayload[6] = 1
        tencPayload[7] = 8
        val tenc = box("tenc", tencPayload)
        val schi = box("schi", tenc)
        val sinf = box("sinf", frma + schm + schi)

        // VisualSampleEntry fixed part = 78 bytes after 8B header, followed by hvcC + sinf
        val encvBody = ByteArray(78) + box("hvcC", ByteArray(4)) + sinf
        val encv = box("encv", encvBody)
        val stsdHdr = ByteBuffer.allocate(8).putInt(0).putInt(1).array()
        val stsd = box("stsd", stsdHdr + encv)

        val stsc = box("stsc", ByteBuffer.allocate(20).putInt(0).putInt(1).putInt(1).putInt(1).putInt(1).array())
        val stsz = box("stsz", ByteBuffer.allocate(16).putInt(0).putInt(sampleSize).putInt(1).array())
        val stco = box("stco", ByteBuffer.allocate(12).putInt(0).putInt(1).putInt(sampleFileOffset).array())
        val senc = box("senc", ByteBuffer.allocate(8 + iv8.size).putInt(0).putInt(1).put(iv8).array())

        val stbl = box("stbl", stsd + stsc + stsz + stco + senc)
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        val trak = box("trak", mdia)
        return box("moov", trak)
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
