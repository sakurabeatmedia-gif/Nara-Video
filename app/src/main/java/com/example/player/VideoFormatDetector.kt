package com.example.player

import com.example.model.DetectedVideoFormat
import com.example.model.VideoProbeDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class VideoProbeOutcome(
    val diagnostics: VideoProbeDiagnostics,
    val headerBytes: ByteArray = ByteArray(0)
)

object VideoFormatDetector {

    private const val INITIAL_PROBE_BYTES = 8192
    private const val MOOV_INSPECT_MAX_BYTES = 65536

    suspend fun probe(
        okHttpClient: OkHttpClient,
        url: String,
        decryptionKey: String = ""
    ): VideoProbeOutcome = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            return@withContext VideoProbeOutcome(
                diagnostics = VideoProbeDiagnostics(
                    originalUrl = url,
                    detectedFormat = DetectedVideoFormat.INVALID_RESPONSE,
                    failureReason = "URL video kosong."
                )
            )
        }

        try {
            val firstRead = fetchRange(okHttpClient, url, 0L, (INITIAL_PROBE_BYTES - 1).toLong())
            if (firstRead.httpStatus !in 200..299) {
                val reason = when (firstRead.httpStatus) {
                    401, 403, 404, 410 -> "Tautan video kedaluwarsa atau ditolak server media (HTTP ${firstRead.httpStatus})."
                    else -> "Server media mengembalikan HTTP ${firstRead.httpStatus}."
                }
                return@withContext VideoProbeOutcome(
                    diagnostics = VideoProbeDiagnostics(
                        originalUrl = url,
                        detectedFormat = DetectedVideoFormat.INVALID_RESPONSE,
                        contentType = firstRead.contentType,
                        httpStatus = firstRead.httpStatus,
                        contentLength = firstRead.totalLength,
                        supportsRange = firstRead.supportsRange,
                        failureReason = reason
                    )
                )
            }

            var inspectBytes = firstRead.bytes
            // If it's an MP4 starting with ftyp and moov begins immediately after, read up to 64KB
            // if the initial 8KB didn't yet capture stsd/sinf/encryption atoms.
            if (looksLikeIsoBmff(inspectBytes) && inspectBytes.size >= INITIAL_PROBE_BYTES) {
                val quickMp4 = inspectMp4Atoms(inspectBytes)
                if (!quickMp4.hasEncryptionBoxes && !quickMp4.hasCodecBoxes) {
                    val secondStart = inspectBytes.size.toLong()
                    val secondEnd = (MOOV_INSPECT_MAX_BYTES - 1).toLong()
                    if (secondEnd >= secondStart) {
                        val extra = runCatching {
                            fetchRange(okHttpClient, url, secondStart, secondEnd)
                        }.getOrNull()
                        if (extra != null && extra.httpStatus in 200..299 && extra.bytes.isNotEmpty()) {
                            inspectBytes = inspectBytes + extra.bytes
                        }
                    }
                }
            }

            val diagnostics = classifyBytes(
                url = url,
                httpStatus = firstRead.httpStatus,
                contentType = firstRead.contentType,
                contentLength = firstRead.totalLength,
                supportsRange = firstRead.supportsRange,
                prefix = inspectBytes,
                decryptionKey = decryptionKey
            )

            VideoProbeOutcome(
                diagnostics = diagnostics,
                headerBytes = firstRead.bytes
            )
        } catch (e: Exception) {
            VideoProbeOutcome(
                diagnostics = VideoProbeDiagnostics(
                    originalUrl = url,
                    detectedFormat = DetectedVideoFormat.INVALID_RESPONSE,
                    failureReason = "Gagal memeriksa header media: ${e.localizedMessage ?: "Kesalahan jaringan"}",
                    nativeError = e.message
                )
            )
        }
    }

    fun classifyBytes(
        url: String,
        httpStatus: Int?,
        contentType: String,
        contentLength: Long,
        supportsRange: Boolean,
        prefix: ByteArray,
        decryptionKey: String = ""
    ): VideoProbeDiagnostics {
        val hexMagic = prefix.take(16).joinToString(" ") { "%02X".format(it) }.ifEmpty { "-" }

        if (prefix.isEmpty()) {
            return VideoProbeDiagnostics(
                originalUrl = url,
                detectedFormat = DetectedVideoFormat.INVALID_RESPONSE,
                contentType = contentType,
                httpStatus = httpStatus,
                contentLength = contentLength,
                supportsRange = supportsRange,
                magicBytesHex = hexMagic,
                failureReason = "Server media mengembalikan respons kosong (0 byte)."
            )
        }

        val ctLower = contentType.lowercase()
        val pathLower = url.substringBefore("?").lowercase()
        val textPreview = extractTrimmedAsciiPreview(prefix)

        // 1. Check text-based manifests & non-media error pages FIRST
        if (textPreview.startsWith("#EXTM3U") ||
            (ctLower.contains("mpegurl") && textPreview.contains("#EXT"))
        ) {
            return VideoProbeDiagnostics(
                originalUrl = url,
                detectedFormat = DetectedVideoFormat.HLS,
                contentType = contentType,
                httpStatus = httpStatus,
                contentLength = contentLength,
                supportsRange = supportsRange,
                codecInfo = "HLS Playlist (M3U8)",
                playerType = "ExoPlayer HlsMediaSource",
                magicBytesHex = hexMagic
            )
        }

        if (textPreview.startsWith("<MPD", ignoreCase = true) ||
            (textPreview.startsWith("<?xml", ignoreCase = true) && textPreview.contains("<MPD", ignoreCase = true)) ||
            ctLower.contains("dash+xml")
        ) {
            return VideoProbeDiagnostics(
                originalUrl = url,
                detectedFormat = DetectedVideoFormat.DASH,
                contentType = contentType,
                httpStatus = httpStatus,
                contentLength = contentLength,
                supportsRange = supportsRange,
                codecInfo = "MPEG-DASH Manifest (MPD)",
                playerType = "ExoPlayer DashMediaSource",
                magicBytesHex = hexMagic
            )
        }

        if (textPreview.startsWith("<!DOCTYPE", ignoreCase = true) ||
            textPreview.startsWith("<html", ignoreCase = true) ||
            textPreview.startsWith("{") ||
            (textPreview.startsWith("[") && textPreview.contains("\"")) ||
            ctLower.contains("text/html") ||
            ctLower.contains("application/json")
        ) {
            return VideoProbeDiagnostics(
                originalUrl = url,
                detectedFormat = DetectedVideoFormat.INVALID_RESPONSE,
                contentType = contentType,
                httpStatus = httpStatus,
                contentLength = contentLength,
                supportsRange = supportsRange,
                codecInfo = "-",
                playerType = "-",
                magicBytesHex = hexMagic,
                failureReason = "Respons server berupa HTML/JSON ($contentType), bukan file/aliran video."
            )
        }

        // 2. Check ISO BMFF / MP4 signature ('ftyp' at offset 4)
        if (looksLikeIsoBmff(prefix)) {
            val mp4Info = inspectMp4Atoms(prefix)
            val codecsLabel = buildList {
                if (mp4Info.videoCodec.isNotEmpty()) add(mp4Info.videoCodec)
                if (mp4Info.audioCodec.isNotEmpty()) add(mp4Info.audioCodec)
            }.joinToString(" + ").ifEmpty {
                if (mp4Info.hasEncryptionBoxes) "CENC Encrypted MP4" else "ISO BMFF / MP4"
            }

            if (mp4Info.hasEncryptionBoxes) {
                val missingKeyReason = if (decryptionKey.isBlank()) {
                    "Video MP4 terenkripsi CENC (${codecsLabel}), tetapi kunci dekripsi tidak tersedia dari API."
                } else {
                    null
                }
                return VideoProbeDiagnostics(
                    originalUrl = url,
                    detectedFormat = DetectedVideoFormat.MP4_CENC,
                    contentType = contentType,
                    httpStatus = httpStatus,
                    contentLength = contentLength,
                    supportsRange = supportsRange,
                    codecInfo = "$codecsLabel (CENC)",
                    playerType = "CencMp4DataSource + ExoPlayer Mp4Extractor",
                    failureReason = missingKeyReason,
                    magicBytesHex = hexMagic
                )
            }

            return VideoProbeDiagnostics(
                originalUrl = url,
                detectedFormat = DetectedVideoFormat.MP4_PLAIN,
                contentType = contentType,
                httpStatus = httpStatus,
                contentLength = contentLength,
                supportsRange = supportsRange,
                codecInfo = codecsLabel,
                playerType = "ExoPlayer ProgressiveMediaSource (Mp4Extractor)",
                magicBytesHex = hexMagic
            )
        }

        // 3. Check MPEG-TS sync byte 0x47 repeating every 188 bytes
        if (looksLikeMpegTs(prefix) || ctLower.contains("mp2t") || pathLower.endsWith(".ts")) {
            return VideoProbeDiagnostics(
                originalUrl = url,
                detectedFormat = DetectedVideoFormat.MPEG_TS,
                contentType = contentType,
                httpStatus = httpStatus,
                contentLength = contentLength,
                supportsRange = supportsRange,
                codecInfo = "MPEG-TS Stream",
                playerType = "ExoPlayer ProgressiveMediaSource (TsExtractor)",
                magicBytesHex = hexMagic
            )
        }

        // 4. Content-Type / URL fallback ONLY when Content-Type explicitly names a streaming manifest
        if (ctLower.contains("mpegurl") || pathLower.endsWith(".m3u8")) {
            return VideoProbeDiagnostics(
                originalUrl = url,
                detectedFormat = DetectedVideoFormat.HLS,
                contentType = contentType,
                httpStatus = httpStatus,
                contentLength = contentLength,
                supportsRange = supportsRange,
                codecInfo = "HLS",
                playerType = "ExoPlayer HlsMediaSource",
                magicBytesHex = hexMagic
            )
        }
        if (pathLower.endsWith(".mpd")) {
            return VideoProbeDiagnostics(
                originalUrl = url,
                detectedFormat = DetectedVideoFormat.DASH,
                contentType = contentType,
                httpStatus = httpStatus,
                contentLength = contentLength,
                supportsRange = supportsRange,
                codecInfo = "MPEG-DASH",
                playerType = "ExoPlayer DashMediaSource",
                magicBytesHex = hexMagic
            )
        }

        return VideoProbeDiagnostics(
            originalUrl = url,
            detectedFormat = DetectedVideoFormat.UNKNOWN,
            contentType = contentType,
            httpStatus = httpStatus,
            contentLength = contentLength,
            supportsRange = supportsRange,
            codecInfo = "-",
            playerType = "-",
            magicBytesHex = hexMagic,
            failureReason = "Format video tidak dikenali dari magic bytes ($hexMagic) dan Content-Type ($contentType)."
        )
    }

    internal fun looksLikeIsoBmff(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
    }

    internal fun looksLikeMpegTs(bytes: ByteArray): Boolean {
        if (bytes.size >= 377) {
            return bytes[0] == 0x47.toByte() &&
                bytes[188] == 0x47.toByte() &&
                bytes[376] == 0x47.toByte()
        }
        if (bytes.size >= 189) {
            return bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte()
        }
        return false
    }

    internal data class Mp4Inspection(
        val hasEncryptionBoxes: Boolean = false,
        val hasCodecBoxes: Boolean = false,
        val videoCodec: String = "",
        val audioCodec: String = ""
    )

    internal fun inspectMp4Atoms(bytes: ByteArray): Mp4Inspection {
        var hasEncryption = false
        var videoCodec = ""
        var audioCodec = ""

        fun containsFourCc(tag: String): Boolean {
            if (bytes.size < 4) return false
            val b0 = tag[0].code.toByte()
            val b1 = tag[1].code.toByte()
            val b2 = tag[2].code.toByte()
            val b3 = tag[3].code.toByte()
            for (i in 0..(bytes.size - 4)) {
                if (bytes[i] == b0 && bytes[i + 1] == b1 && bytes[i + 2] == b2 && bytes[i + 3] == b3) {
                    return true
                }
            }
            return false
        }

        if (containsFourCc("encv") || containsFourCc("enca") ||
            containsFourCc("sinf") || containsFourCc("senc") ||
            containsFourCc("cenc") || containsFourCc("tenc")
        ) {
            hasEncryption = true
        }

        videoCodec = when {
            containsFourCc("hvc1") || containsFourCc("hev1") || containsFourCc("hvcC") -> "HEVC (H.265)"
            containsFourCc("avc1") || containsFourCc("avc3") || containsFourCc("avcC") -> "AVC (H.264)"
            containsFourCc("vp09") -> "VP9"
            containsFourCc("av01") -> "AV1"
            else -> ""
        }
        audioCodec = when {
            containsFourCc("mp4a") || containsFourCc("esds") -> "AAC"
            containsFourCc("Opus") -> "Opus"
            containsFourCc("ac-3") -> "AC-3"
            else -> ""
        }

        return Mp4Inspection(
            hasEncryptionBoxes = hasEncryption,
            hasCodecBoxes = videoCodec.isNotEmpty() || audioCodec.isNotEmpty(),
            videoCodec = videoCodec,
            audioCodec = audioCodec
        )
    }

    private fun extractTrimmedAsciiPreview(bytes: ByteArray): String {
        var offset = 0
        // Strip UTF-8 BOM if present
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            offset = 3
        }
        val len = (bytes.size - offset).coerceAtMost(2048)
        if (len <= 0) return ""
        return String(bytes, offset, len, StandardCharsets.UTF_8).trimStart()
    }

    private data class RangeFetchResult(
        val httpStatus: Int,
        val contentType: String,
        val totalLength: Long,
        val supportsRange: Boolean,
        val bytes: ByteArray
    )

    private fun fetchRange(
        okHttpClient: OkHttpClient,
        url: String,
        start: Long,
        endInclusive: Long
    ): RangeFetchResult {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$endInclusive")
            .header("Accept", "*/*")
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val status = response.code
            val contentType = response.header("Content-Type") ?: "-"
            val contentRange = response.header("Content-Range")
            val totalFromRange = contentRange?.substringAfterLast("/")?.toLongOrNull() ?: -1L
            val contentLengthHeader = response.header("Content-Length")?.toLongOrNull() ?: -1L
            val totalLength = when {
                totalFromRange > 0 -> totalFromRange
                status == 200 && contentLengthHeader > 0 -> contentLengthHeader
                else -> -1L
            }
            val supportsRange = (status == 206) ||
                (response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true)

            val maxToRead = (endInclusive - start + 1).toInt().coerceAtMost(256 * 1024)
            val bodyStream = response.body?.byteStream()
            val out = ByteArrayOutputStream()
            if (bodyStream != null) {
                val buf = ByteArray(8192)
                var totalRead = 0
                while (totalRead < maxToRead) {
                    val toRead = minOf(buf.size, maxToRead - totalRead)
                    val n = bodyStream.read(buf, 0, toRead)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    totalRead += n
                }
            }
            return RangeFetchResult(
                httpStatus = status,
                contentType = contentType,
                totalLength = totalLength,
                supportsRange = supportsRange,
                bytes = out.toByteArray()
            )
        }
    }
}
