package com.example.data.api

import com.example.BuildConfig

object ApiConstants {

    const val MELOLO_BASE_URL =
        "https://apishort.com/v1/melolo/api/"

    const val DRAMABITE_BASE_URL =
        "https://apishort.com/v1/dramabite/"

    const val GOODSHORT_BASE_URL =
        "https://apishort.com/v1/goodshort/"

    // URL lama dipertahankan sementara agar file legacy
    // tidak menyebabkan compilation error.
    const val DRAMABOX_BASE_URL =
        "https://apishort.com/v1/dramabox/api/dramabox/"

    const val DRAMAWAVE_BASE_URL =
        "https://apishort.com/v1/dramawave/"

    const val REELSHORT_BASE_URL =
        "https://apishort.com/v1/reelshort/"

    val API_KEY: String
        get() = BuildConfig.API_SHORT_KEY.trim()

    // Kompatibilitas sementara dengan kode lama.
    val API_KEYS: List<String>
        get() = listOf(API_KEY).filter { it.isNotBlank() }

    const val HEADER_API_KEY = "X-API-Key"
    const val HEADER_ACCEPT = "Accept"
    const val ACCEPT_JSON = "application/json"

    const val DEFAULT_LANG = "id"
}
