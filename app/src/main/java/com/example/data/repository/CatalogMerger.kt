package com.example.data.repository

import com.example.model.Drama

object CatalogMerger {

    fun normalizeTitleKey(title: String): String {
        return title.trim()
            .lowercase()
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun mergeAndDeduplicate(vararg lists: List<Drama>): List<Drama> {
        val byNormalizedTitle = LinkedHashMap<String, Drama>()
        for (list in lists) {
            for (drama in list) {
                val titleKey = normalizeTitleKey(drama.title).ifEmpty { drama.uniqueKey }
                val existing = byNormalizedTitle[titleKey]
                if (existing == null) {
                    byNormalizedTitle[titleKey] = drama
                } else {
                    byNormalizedTitle[titleKey] = chooseRicherDrama(existing, drama)
                }
            }
        }
        return byNormalizedTitle.values.toList()
    }

    fun interleaveAndDeduplicate(first: List<Drama>, second: List<Drama>): List<Drama> {
        val interleaved = ArrayList<Drama>(first.size + second.size)
        val maxLen = maxOf(first.size, second.size)
        for (i in 0 until maxLen) {
            if (i < first.size) interleaved.add(first[i])
            if (i < second.size) interleaved.add(second[i])
        }
        return mergeAndDeduplicate(interleaved)
    }

    private fun chooseRicherDrama(a: Drama, b: Drama): Drama {
        val mergedCategories = (a.categories + b.categories).distinct()
        val scoreA = richnessScore(a)
        val scoreB = richnessScore(b)
        val winner = if (scoreB > scoreA) b else a
        return winner.copy(
            cover = winner.cover.ifEmpty { if (winner == a) b.cover else a.cover },
            synopsis = winner.synopsis.ifEmpty { if (winner == a) b.synopsis else a.synopsis },
            totalEpisodes = maxOf(a.totalEpisodes, b.totalEpisodes),
            categories = mergedCategories,
            isPopular = a.isPopular || b.isPopular,
            isLatest = a.isLatest || b.isLatest
        )
    }

    private fun richnessScore(drama: Drama): Int {
        var score = 0
        if (drama.cover.isNotEmpty()) score += 4
        if (drama.synopsis.isNotEmpty()) score += 2
        if (drama.totalEpisodes > 0) score += 2
        if (drama.categories.isNotEmpty()) score += 1
        return score
    }
}
