package com.example.data.api

import com.example.model.Drama
import com.example.model.DramaDetail
import com.example.model.Episode
import com.example.model.VideoQualityOption
import com.example.model.VideoSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

object JsonDataParser {

    fun checkApiError(jsonStr: String): Pair<Int, String>? {
        val trimmed = jsonStr.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            val root = JSONObject(trimmed)
            if (root.has("code") && !root.isNull("code")) {
                val code = root.optInt("code", 0)
                if (code != 0 && code != 200) {
                    val msg = firstNonEmpty(
                        root.optString("msg"),
                        root.optString("message"),
                        root.optString("error")
                    ).ifEmpty { "Server mengembalikan kode status $code" }
                    Pair(code, msg)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseDramaList(jsonStr: String): List<Drama> {
        val dramas = mutableListOf<Drama>()
        val trimmed = jsonStr.trim()
        if (trimmed.isEmpty()) return dramas

        try {
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    parseSingleDrama(obj)?.let { dramas.add(it) }
                }
            } else if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)

                val candidateKeys = listOf("data", "collections", "items", "dramas", "list", "results", "feed", "content")
                var foundArray: JSONArray? = null

                for (key in candidateKeys) {
                    val opt = root.opt(key)
                    if (opt is JSONArray) {
                        foundArray = opt
                        break
                    }
                }

                if (foundArray != null) {
                    for (i in 0 until foundArray.length()) {
                        val obj = foundArray.optJSONObject(i) ?: continue
                        parseSingleDrama(obj)?.let { drama ->
                            // Skip query-echo stubs (no cover and 0 episodes) returned by search
                            if (drama.cover.isNotEmpty() || drama.totalEpisodes > 0) {
                                dramas.add(drama)
                            }
                        }
                    }
                } else {
                    parseSingleDrama(root)?.let { drama ->
                        if (drama.cover.isNotEmpty() || drama.totalEpisodes > 0) {
                            dramas.add(drama)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return dramas
    }

    fun parseSingleDrama(obj: JSONObject): Drama? {
        val id = firstNonEmpty(
            obj.optString("id"),
            obj.optString("collection_id"),
            obj.optString("drama_id"),
            obj.optString("book_id")
        )
        if (id.isEmpty()) return null

        val title = firstNonEmpty(
            obj.optString("title"),
            obj.optString("name"),
            obj.optString("drama_name"),
            obj.optString("book_name")
        ).ifEmpty { "Drama $id" }

        val cover = firstNonEmpty(
            obj.optString("cover"),
            obj.optString("poster"),
            obj.optString("cover_url"),
            obj.optString("thumb_url"),
            obj.optString("image"),
            obj.optString("channel_avatar")
        )

        var totalEpisodes = obj.optInt("episodes", 0)
        if (totalEpisodes <= 0) totalEpisodes = obj.optInt("total_episodes", 0)
        if (totalEpisodes <= 0) totalEpisodes = obj.optInt("totalEpisodes", 0)
        if (totalEpisodes <= 0) totalEpisodes = obj.optInt("episode_count", 0)
        if (totalEpisodes <= 0) {
            val label = firstNonEmpty(obj.optString("episode_label"))
            if (label.isNotEmpty()) {
                val matcher = Pattern.compile("(\\d+)").matcher(label)
                if (matcher.find()) {
                    totalEpisodes = matcher.group(1)?.toIntOrNull() ?: 0
                }
            }
        }
        if (totalEpisodes <= 0) {
            val epsArray = obj.optJSONArray("videos") ?: obj.optJSONArray("episodes")
            if (epsArray != null) totalEpisodes = epsArray.length()
        }

        val synopsis = firstNonEmpty(
            obj.optString("description"),
            obj.optString("summary"),
            obj.optString("intro"),
            obj.optString("synopsis")
        )

        val categories = mutableListOf<String>()
        val catStr = firstNonEmpty(obj.optString("categories"))
        if (catStr.isNotEmpty()) {
            catStr.split(",").forEach {
                val clean = it.trim()
                if (clean.isNotEmpty()) categories.add(clean)
            }
        }
        val tagsArray = obj.optJSONArray("tags") ?: obj.optJSONArray("genre")
        if (tagsArray != null) {
            for (i in 0 until tagsArray.length()) {
                val tag = firstNonEmpty(tagsArray.optString(i))
                if (tag.isNotEmpty() && !categories.contains(tag)) {
                    categories.add(tag)
                }
            }
        }

        return Drama(
            id = id,
            title = title,
            cover = cover,
            totalEpisodes = totalEpisodes,
            synopsis = synopsis,
            categories = categories,
            isPopular = obj.optBoolean("is_popular", false) || obj.optBoolean("popular", false),
            isLatest = obj.optBoolean("is_latest", false) || obj.optBoolean("latest", false)
        )
    }

    fun parseDramaDetail(id: String, jsonStr: String): DramaDetail {
        val root = JSONObject(jsonStr)

        if (root.has("code") && !root.isNull("code")) {
            val code = root.optInt("code", 0)
            if (code != 0 && code != 200) {
                val msg = firstNonEmpty(root.optString("msg"), root.optString("message"))
                    .ifEmpty { "Detail drama tidak tersedia (kode $code)." }
                throw IllegalStateException(msg)
            }
        }

        val dramaObj = root.optJSONObject("data") ?: root
        val drama = parseSingleDrama(dramaObj) ?: Drama(id = id, title = "Drama #$id", cover = "")

        val episodes = mutableListOf<Episode>()
        val episodesArray = dramaObj.optJSONArray("videos")
            ?: root.optJSONArray("videos")
            ?: dramaObj.optJSONArray("episodes")
            ?: dramaObj.optJSONArray("episode_list")
            ?: root.optJSONArray("episodes")

        if (episodesArray != null && episodesArray.length() > 0) {
            for (i in 0 until episodesArray.length()) {
                val epObj = episodesArray.optJSONObject(i)
                if (epObj != null) {
                    val epNum = epObj.optInt("episode", 0)
                        .takeIf { it > 0 }
                        ?: epObj.optInt("episode_num", 0).takeIf { it > 0 }
                        ?: epObj.optInt("ep", 0).takeIf { it > 0 }
                        ?: (i + 1)
                    val videoId = firstNonEmpty(
                        epObj.optString("vid"),
                        epObj.optString("video_id"),
                        epObj.optString("id")
                    )
                    val title = firstNonEmpty(epObj.optString("title")).ifEmpty { "Episode $epNum" }
                    val durationSec = epObj.optInt("duration", 0)
                    episodes.add(
                        Episode(
                            episodeNum = epNum,
                            videoId = videoId,
                            title = title,
                            durationSeconds = durationSec
                        )
                    )
                } else {
                    episodes.add(Episode(episodeNum = i + 1))
                }
            }
        } else if (drama.totalEpisodes > 0) {
            for (i in 1..drama.totalEpisodes) {
                episodes.add(Episode(episodeNum = i))
            }
        }

        if (episodes.isEmpty() && drama.cover.isEmpty() && drama.synopsis.isEmpty()) {
            throw IllegalStateException("Data detail drama tidak ditemukan dari server.")
        }

        val channel = firstNonEmpty(dramaObj.optString("channel"))

        return DramaDetail(
            drama = drama.copy(totalEpisodes = if (drama.totalEpisodes > 0) drama.totalEpisodes else episodes.size),
            episodes = episodes,
            channel = channel
        )
    }

    fun parseVideoSource(dramaId: String, episodeNum: Int, jsonStr: String): VideoSource? {
        val trimmed = jsonStr.trim()
        if (trimmed.isEmpty()) return null

        val root = JSONObject(trimmed)

        if (root.has("code") && !root.isNull("code")) {
            val code = root.optInt("code", 0)
            if (code != 0 && code != 200) {
                val msg = firstNonEmpty(root.optString("msg"), root.optString("message"))
                    .ifEmpty { "Sumber video belum tersedia (kode $code)." }
                throw IllegalStateException(msg)
            }
        }

        val dataObj = root.optJSONObject("data") ?: root

        if (dataObj.optBoolean("locked", false) || root.optBoolean("locked", false)) {
            throw IllegalStateException("Episode $episodeNum saat ini terkunci di server.")
        }

        // Parse qualityList first so we can match decryption keys & labels
        val qualities = mutableListOf<VideoQualityOption>()
        val qualityArray = dataObj.optJSONArray("qualityList")
            ?: root.optJSONArray("qualityList")
            ?: dataObj.optJSONArray("qualities")
            ?: root.optJSONArray("qualities")

        if (qualityArray != null) {
            for (i in 0 until qualityArray.length()) {
                val qObj = qualityArray.optJSONObject(i) ?: continue
                val qUrl = firstNonEmpty(
                    qObj.optString("url"),
                    qObj.optString("videoUrl"),
                    qObj.optString("video_url"),
                    qObj.optString("play_url")
                )
                if (qUrl.isNotEmpty()) {
                    val label = firstNonEmpty(
                        qObj.optString("label"),
                        qObj.optString("quality"),
                        qObj.optString("name")
                    ).ifEmpty { "HD" }
                    val key = firstNonEmpty(
                        qObj.optString("key"),
                        qObj.optString("decryption_key"),
                        qObj.optString("aes_key")
                    )
                    val kid = firstNonEmpty(
                        qObj.optString("kid"),
                        qObj.optString("key_id")
                    )
                    qualities.add(VideoQualityOption(label = label, url = qUrl, key = key, kid = kid))
                }
            }
        }

        val videoUrl = firstNonEmpty(
            dataObj.optString("videoUrl"),
            dataObj.optString("best_url"),
            dataObj.optString("url"),
            dataObj.optString("video_url"),
            dataObj.optString("play_url"),
            dataObj.optString("hls_url"),
            dataObj.optString("stream_url"),
            root.optString("videoUrl"),
            root.optString("best_url"),
            root.optString("url"),
            root.optString("video_url"),
            qualities.firstOrNull()?.url ?: ""
        )

        if (videoUrl.isEmpty()) return null

        val matchedQuality = qualities.find { it.url == videoUrl }
            ?: qualities.find { it.url.substringBefore("?") == videoUrl.substringBefore("?") }

        val quality = firstNonEmpty(
            dataObj.optString("quality"),
            matchedQuality?.label ?: ""
        ).ifEmpty { "HD" }

        val decryptionKey = firstNonEmpty(
            matchedQuality?.key ?: "",
            dataObj.optString("key"),
            root.optString("key")
        )
        val decryptionKid = firstNonEmpty(
            matchedQuality?.kid ?: "",
            dataObj.optString("kid"),
            root.optString("kid")
        )

        var durationMs = dataObj.optLong("duration_ms", 0L)
        if (durationMs <= 0L) {
            val sec = dataObj.optLong("duration", 0L).takeIf { it > 0L } ?: root.optLong("duration", 0L)
            if (sec > 0L) durationMs = sec * 1000L
        }

        val pathPart = videoUrl.substringBefore("?").lowercase()
        val isHls = pathPart.endsWith(".m3u8") || videoUrl.contains(".m3u8", ignoreCase = true)

        return VideoSource(
            dramaId = dramaId,
            episodeNum = episodeNum,
            videoUrl = videoUrl,
            quality = quality,
            isHls = isHls,
            durationMs = durationMs,
            decryptionKey = decryptionKey,
            decryptionKid = decryptionKid,
            qualities = qualities
        )
    }

    private fun firstNonEmpty(vararg values: String): String {
        for (v in values) {
            val trimmed = v.trim()
            if (trimmed.isNotEmpty() && !trimmed.equals("null", ignoreCase = true) && !trimmed.equals("undefined", ignoreCase = true)) {
                return trimmed
            }
        }
        return ""
    }
}
