package com.example.data.api

import com.example.BuildConfig

object ApiConstants {
    const val MELOLO_BASE_URL =
        "https://apishort.com/v1/melolo/api/"

    const val DRAMABOX_BASE_URL =
        "https://apishort.com/v1/dramabox/api/dramabox/"

    val API_KEYS: List<String>
        get() = listOf(BuildConfig.API_SHORT_KEY)
            .filter { it.isNotBlank() }

    const val HEADER_API_KEY = "X-API-Key"
    const val HEADER_ACCEPT = "Accept"
    const val ACCEPT_JSON = "application/json"
    const val DEFAULT_LANG = "id"
}
