package com.example.data.api

import com.example.model.Drama
import com.example.model.DramaDetail
import com.example.model.DramaProvider
import com.example.model.Episode
import com.example.model.VideoQualityOption
import com.example.model.VideoSource
import org.json.JSONArray
import org.json.JSONObject

object DramaBoxJsonParser {

    fun parseLanguages(jsonStr: String): List<Pair<String, String>> {
        val trimmed = jsonStr.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        return try {
            val arr = JSONArray(trimmed)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val code = cleanStr(obj.optString("code"))
                    val name = cleanStr(obj.optString("name"))
                    if (code.isNotEmpty()) add(code to name.ifEmpty { code })
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseDramaList(
        jsonStr: String,
        markPopular: Boolean = false,
        markLatest: Boolean = false
    ): List<Drama> {
        val trimmed = jsonStr.trim()
        if (trimmed.isEmpty()) return emptyList()

        val result = mutableListOf<Drama>()
        try {
            val array: JSONArray? = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    val candidateKeys = listOf("items", "data", "list", "results", "books", "dramas")
                    var found: JSONArray? = null
                    for (key in candidateKeys) {
                        val opt = root.opt(key)
                        if (opt is JSONArray) {
                            found = opt
                            break
                        }
                    }
                    found
                }
                else -> null
            }

            if (array != null) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    parseSingleDrama(obj, markPopular, markLatest)?.let { result.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    fun parseSingleDrama(
        obj: JSONObject,
        markPopular: Boolean = false,
        markLatest: Boolean = false
    ): Drama? {
        val id = firstNonEmpty(
            obj.optString("bookId"),
            obj.optString("id")
        )
        if (id.isEmpty()) return null

        val title = firstNonEmpty(
            obj.optString("bookName"),
            obj.optString("title"),
            obj.optString("name")
        ).ifEmpty { "Drama $id" }

        val cover = firstNonEmpty(
            obj.optString("coverWap"),
            obj.optString("cover"),
            obj.optString("poster"),
            obj.optString("thumb_url")
        )

        var totalEpisodes = obj.optInt("chapterCount", 0)
        if (totalEpisodes <= 0) totalEpisodes = obj.optInt("episodes", 0)
        if (totalEpisodes <= 0) totalEpisodes = obj.optInt("totalEpisodes", 0)

        val synopsis = firstNonEmpty(
            obj.optString("introduction"),
            obj.optString("description"),
            obj.optString("intro"),
            obj.optString("summary")
        )

        val categories = mutableListOf<String>()
        val tagsArray = obj.optJSONArray("tags")
        if (tagsArray != null) {
            for (i in 0 until tagsArray.length()) {
                val tag = cleanStr(tagsArray.optString(i))
                if (tag.isNotEmpty() && !categories.contains(tag)) {
                    categories.add(tag)
                }
            }
        }
        val tagV3Array = obj.optJSONArray("tagV3s")
        if (tagV3Array != null) {
            for (i in 0 until tagV3Array.length()) {
                val tObj = tagV3Array.optJSONObject(i) ?: continue
                val tagName = cleanStr(tObj.optString("tagName"))
                if (tagName.isNotEmpty() && !categories.contains(tagName)) {
                    categories.add(tagName)
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
            isPopular = markPopular,
            isLatest = markLatest,
            provider = DramaProvider.DRAMABOX
        )
    }

    fun parseDramaDetail(
        bookId: String,
        detailJsonStr: String,
        allEpisodesJsonStr: String? = null
    ): DramaDetail {
        val trimmed = detailJsonStr.trim()
        val obj = if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            root.optJSONObject("data") ?: root
        } else {
            throw IllegalStateException("Drama tidak tersedia.")
        }

        val drama = parseSingleDrama(obj) ?: Drama(
            id = bookId,
            title = "Drama",
            cover = "",
            provider = DramaProvider.DRAMABOX
        )

        val parsedEpisodes = if (!allEpisodesJsonStr.isNullOrBlank()) {
            parseAllEpisodes(allEpisodesJsonStr)
        } else {
            emptyList()
        }

        val episodes = if (parsedEpisodes.isNotEmpty()) {
            parsedEpisodes
        } else if (drama.totalEpisodes > 0) {
            (1..drama.totalEpisodes).map { Episode(episodeNum = it) }
        } else {
            emptyList()
        }

        if (episodes.isEmpty() && drama.cover.isEmpty() && drama.synopsis.isEmpty()) {
            throw IllegalStateException("Drama tidak tersedia.")
        }

        return DramaDetail(
            drama = drama.copy(totalEpisodes = if (drama.totalEpisodes > 0) drama.totalEpisodes else episodes.size),
            episodes = episodes,
            channel = ""
        )
    }

    fun parseAllEpisodes(jsonStr: String): List<Episode> {
        val trimmed = jsonStr.trim()
        if (trimmed.isEmpty()) return emptyList()

        val array: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("data")
                    ?: root.optJSONArray("episodes")
                    ?: root.optJSONArray("items")
                    ?: root.optJSONArray("list")
                    ?: return emptyList()
            }
            else -> return emptyList()
        }

        val episodes = mutableListOf<Episode>()
        for (i in 0 until array.length()) {
            val epObj = array.optJSONObject(i) ?: continue
            val chapterIdx = if (epObj.has("chapterIndex") && !epObj.isNull("chapterIndex")) {
                epObj.optInt("chapterIndex", i) + 1
            } else {
                epObj.optInt("episode", 0).takeIf { it > 0 } ?: (i + 1)
            }
            val chapterId = firstNonEmpty(
                epObj.optString("chapterId"),
                epObj.optString("id")
            )
            val title = firstNonEmpty(
                epObj.optString("chapterName"),
                epObj.optString("title")
            ).ifEmpty { "Episode $chapterIdx" }

            val url1080 = cleanStr(epObj.optString("videoUrl1080"))
            val url720 = cleanStr(epObj.optString("videoUrl720"))
            val url540 = cleanStr(epObj.optString("videoUrl540"))
            val defaultUrl = firstNonEmpty(
                epObj.optString("videoUrl"),
                url720,
                url1080,
                url540
            )

            val qualities = buildList {
                if (url1080.isNotEmpty()) add(VideoQualityOption(label = "1080p", url = url1080))
                if (url720.isNotEmpty()) add(VideoQualityOption(label = "720p", url = url720))
                if (url540.isNotEmpty()) add(VideoQualityOption(label = "540p", url = url540))
                if (isEmpty() && defaultUrl.isNotEmpty()) {
                    add(VideoQualityOption(label = "HD", url = defaultUrl))
                }
            }

            episodes.add(
                Episode(
                    episodeNum = chapterIdx,
                    videoId = chapterId,
                    title = title,
                    durationSeconds = epObj.optInt("duration", 0),
                    videoUrl = defaultUrl,
                    qualities = qualities
                )
            )
        }
        return episodes.sortedBy { it.episodeNum }
    }

    fun toVideoSource(bookId: String, episode: Episode): VideoSource? {
        val url = episode.videoUrl.trim()
        if (url.isEmpty()) return null
        val matchedQuality = episode.qualities.find { it.url == url }?.label ?: "720p"
        val isHls = url.substringBefore("?").lowercase().endsWith(".m3u8") ||
            url.contains(".m3u8", ignoreCase = true)
        return VideoSource(
            dramaId = bookId,
            episodeNum = episode.episodeNum,
            videoUrl = url,
            quality = matchedQuality,
            isHls = isHls,
            durationMs = if (episode.durationSeconds > 0) episode.durationSeconds * 1000L else 0L,
            qualities = episode.qualities,
            provider = DramaProvider.DRAMABOX
        )
    }

    private fun cleanStr(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.equals("null", true) || trimmed.equals("undefined", true)) "" else trimmed
    }

    private fun firstNonEmpty(vararg values: String?): String {
        for (v in values) {
            val c = cleanStr(v)
            if (c.isNotEmpty()) return c
        }
        return ""
    }
}
