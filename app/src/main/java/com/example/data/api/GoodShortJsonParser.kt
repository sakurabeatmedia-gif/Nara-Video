package com.example.data.api

import com.example.model.Drama
import com.example.model.DramaDetail
import com.example.model.DramaProvider
import com.example.model.Episode
import com.example.model.VideoQualityOption
import com.example.model.VideoSource
import org.json.JSONObject

object GoodShortJsonParser {

    fun parseDramaList(json: String): List<Drama> {
        return JsonDataParser.parseDramaList(json).map {
            it.copy(provider = DramaProvider.GOODSHORT)
        }
    }

    fun parseDramaDetail(
        dramaId: String,
        json: String
    ): DramaDetail {
        val detail = JsonDataParser.parseDramaDetail(
            dramaId,
            json
        )

        return detail.copy(
            drama = detail.drama.copy(
                provider = DramaProvider.GOODSHORT
            )
        )
    }

    fun parseAllEpisodes(json: String): List<Episode> {
        val root = JSONObject(json)
        val data = root.optJSONObject("data")

        val array = root.optJSONArray("data")
            ?: root.optJSONArray("episodes")
            ?: root.optJSONArray("chapters")
            ?: root.optJSONArray("items")
            ?: root.optJSONArray("list")
            ?: data?.optJSONArray("episodes")
            ?: data?.optJSONArray("chapters")
            ?: data?.optJSONArray("items")
            ?: data?.optJSONArray("list")
            ?: return emptyList()

        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue

                val chapterIndex = obj.optInt("chapterIndex", -1)

                val episodeNum =
                    obj.optInt("episode", 0).takeIf { it > 0 }
                        ?: obj.optInt("episodeNum", 0).takeIf { it > 0 }
                        ?: obj.optInt("ep", 0).takeIf { it > 0 }
                        ?: if (chapterIndex >= 0) chapterIndex + 1
                        else i + 1

                val videoUrl = firstNonEmpty(
                    obj.optString("videoUrl"),
                    obj.optString("video_url"),
                    obj.optString("url"),
                    obj.optString("play_url"),
                    obj.optString("hls_url"),
                    obj.optString("videoUrl720"),
                    obj.optString("videoUrl1080")
                )

                val qualities = mutableListOf<VideoQualityOption>()

                val qualityArray =
                    obj.optJSONArray("qualityList")
                        ?: obj.optJSONArray("qualities")

                if (qualityArray != null) {
                    for (q in 0 until qualityArray.length()) {
                        val quality = qualityArray.optJSONObject(q)
                            ?: continue

                        val url = firstNonEmpty(
                            quality.optString("url"),
                            quality.optString("videoUrl"),
                            quality.optString("video_url")
                        )

                        if (url.isNotEmpty()) {
                            qualities.add(
                                VideoQualityOption(
                                    label = firstNonEmpty(
                                        quality.optString("label"),
                                        quality.optString("quality")
                                    ).ifEmpty { "HD" },
                                    url = url
                                )
                            )
                        }
                    }
                }

                if (qualities.isEmpty() && videoUrl.isNotEmpty()) {
                    qualities.add(
                        VideoQualityOption(
                            label = "HD",
                            url = videoUrl
                        )
                    )
                }

                add(
                    Episode(
                        episodeNum = episodeNum,
                        videoId = firstNonEmpty(
                            obj.optString("chapterId"),
                            obj.optString("id")
                        ),
                        title = firstNonEmpty(
                            obj.optString("chapterName"),
                            obj.optString("title")
                        ).ifEmpty { "Episode $episodeNum" },
                        durationSeconds = obj.optInt("duration", 0),
                        videoUrl = videoUrl,
                        qualities = qualities
                    )
                )
            }
        }.sortedBy { it.episodeNum }
    }

    fun toVideoSource(
        dramaId: String,
        episode: Episode
    ): VideoSource? {

        val url = episode.videoUrl.ifEmpty {
            episode.qualities.firstOrNull()?.url.orEmpty()
        }

        if (url.isBlank()) return null

        return VideoSource(
            dramaId = dramaId,
            episodeNum = episode.episodeNum,
            videoUrl = url,
            quality = episode.qualities.firstOrNull()?.label ?: "HD",
            isHls = url.contains(".m3u8", ignoreCase = true),
            durationMs = episode.durationSeconds * 1000L,
            qualities = episode.qualities,
            provider = DramaProvider.GOODSHORT
        )
    }

    fun checkApiError(json: String): Pair<Int, String>? {
        return JsonDataParser.checkApiError(json)
    }

    private fun firstNonEmpty(vararg values: String): String {
        return values.firstOrNull {
            it.isNotBlank() &&
                !it.equals("null", true) &&
                !it.equals("undefined", true)
        }?.trim().orEmpty()
    }
}
