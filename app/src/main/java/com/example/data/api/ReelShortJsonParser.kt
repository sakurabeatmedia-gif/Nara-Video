package com.example.data.api

import com.example.model.Drama
import com.example.model.DramaDetail
import com.example.model.DramaProvider

object ReelShortJsonParser {

    fun parseDramaList(json: String): List<Drama> {
        return JsonDataParser.parseDramaList(json).map { drama ->
            drama.copy(
                provider = DramaProvider.REELSHORT
            )
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
                provider = DramaProvider.REELSHORT
            )
        )
    }

    fun checkApiError(json: String): Pair<Int, String>? {
        return JsonDataParser.checkApiError(json)
    }
}
