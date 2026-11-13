package com.example.model

data class Drama(
    val id: String,
    val title: String,
    val cover: String,
    val totalEpisodes: Int = 0,
    val synopsis: String = "",
    val categories: List<String> = emptyList(),
    val isPopular: Boolean = false,
    val isLatest: Boolean = false,
    val rating: String = "9.8"
)

data class Episode(
    val episodeNum: Int,
    val videoId: String = "",
    val title: String = "Episode $episodeNum",
    val durationSeconds: Int = 0
)

data class DramaDetail(
    val drama: Drama,
    val episodes: List<Episode> = emptyList(),
    val channel: String = "",
    val shareUrl: String = ""
)

data class VideoQualityOption(
    val label: String,
    val url: String,
    val key: String = "",
    val kid: String = ""
)

data class VideoSource(
    val dramaId: String,
    val episodeNum: Int,
    val videoUrl: String,
    val quality: String = "HD",
    val isHls: Boolean = false,
    val durationMs: Long = 0L,
    val decryptionKey: String = "",
    val decryptionKid: String = "",
    val qualities: List<VideoQualityOption> = emptyList()
)

enum class DetectedVideoFormat(val displayName: String) {
    MP4_PLAIN("MP4"),
    MP4_CENC("MP4 (CENC Terenkripsi)"),
    HLS("HLS (M3U8)"),
    DASH("MPEG-DASH (MPD)"),
    MPEG_TS("MPEG-TS"),
    INVALID_RESPONSE("Respons Bukan Media"),
    UNKNOWN("Format Tidak Dikenal")
}

data class VideoProbeDiagnostics(
    val originalUrl: String = "",
    val detectedFormat: DetectedVideoFormat = DetectedVideoFormat.UNKNOWN,
    val contentType: String = "-",
    val httpStatus: Int? = null,
    val contentLength: Long = -1L,
    val supportsRange: Boolean = false,
    val codecInfo: String = "-",
    val playerType: String = "-",
    val failureReason: String? = null,
    val nativeError: String? = null,
    val magicBytesHex: String = "-"
)
