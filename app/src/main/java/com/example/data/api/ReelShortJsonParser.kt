package com.example.data.api

import com.example.model.Drama
import com.example.model.DramaDetail
import com.example.model.DramaProvider
import org.json.JSONArray
import org.json.JSONObject

object ReelShortJsonParser {

    fun parseDramaList(json: String): List<Drama> {
        val root = runCatching {
            JSONObject(json)
        }.getOrNull() ?: return emptyList()

        val data = root.optJSONObject("data") ?: root

        val arrays = listOfNotNull(
            data.optJSONArray("books"),
            data.optJSONArray("items"),
            data.optJSONArray("list"),
            data.optJSONArray("dramas"),
            data.optJSONArray("results"),
            data.optJSONArray("records")
        )

        val result = mutableListOf<Drama>()

        arrays.forEach { array ->
            result.addAll(parseBooksArray(array))
        }

        if (result.isEmpty()) {
            val generic = JsonDataParser.parseDramaList(json)
            result.addAll(
                generic.map {
                    it.copy(provider = DramaProvider.REELSHORT)
                }
            )
        }

        return result
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
    }

    fun parseShelf(json: String): List<Drama> {
        val root = runCatching {
            JSONObject(json)
        }.getOrNull() ?: return emptyList()

        val error = checkApiError(json)
        if (error != null) {
            throw IllegalStateException(error.second)
        }

        val data = root.optJSONObject("data") ?: root
        val books = data.optJSONArray("books") ?: JSONArray()

        return parseBooksArray(books)
            .distinctBy { it.id }
    }

    private fun parseBooksArray(array: JSONArray): List<Drama> {
        val result = mutableListOf<Drama>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue

            val id = firstValue(
                item.optString("id"),
                item.optString("book_id"),
                item.optString("drama_id")
            )

            if (id.isBlank()) continue

            val title = firstValue(
                item.optString("title"),
                item.optString("name")
            ).ifBlank { "Drama $id" }

            val cover = firstValue(
                item.optString("cover_url"),
                item.optString("cover"),
                item.optString("poster"),
                item.optString("image")
            )

            val description = firstValue(
                item.optString("description"),
                item.optString("summary"),
                item.optString("intro")
            )

            val episodes = item.optInt(
                "episodes",
                item.optInt("total_episodes", 0)
            )

            val categories = mutableListOf<String>()
            val tags = item.optJSONArray("tags")
                ?: item.optJSONArray("genre")

            if (tags != null) {
                for (i in 0 until tags.length()) {
                    val tag = tags.optString(i).trim()
                    if (tag.isNotBlank() && !categories.contains(tag)) {
                        categories.add(tag)
                    }
                }
            }

            result.add(
                Drama(
                    id = id,
                    title = title,
                    cover = cover,
                    totalEpisodes = episodes,
                    synopsis = description,
                    categories = categories,
                    isPopular = item.optLong("plays", 0L) > 0L,
                    isLatest = false,
                    provider = DramaProvider.REELSHORT
                )
            )
        }

        return result
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
                provider = DramaProvider.REELSHORT
            )
        )
    }

    fun checkApiError(json: String): Pair<Int, String>? {
        return JsonDataParser.checkApiError(json)
    }

    private fun firstValue(vararg values: String): String {
        return values.firstOrNull {
            it.isNotBlank() && it != "null"
        }?.trim().orEmpty()
    }
}
