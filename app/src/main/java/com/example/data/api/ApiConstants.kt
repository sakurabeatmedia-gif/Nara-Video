
package com.example.data.api

object ApiConstants {
    const val MELOLO_BASE_URL = "https://apishort.com/v1/melolo/api/"
    const val DRAMABOX_BASE_URL = "https://apishort.com/v1/dramabox/api/dramabox/"

    val API_KEYS: List<String> = listOf(
        "vbs_9ed0b6f5c576190b1f7bf32de09f705559933d757685b968",
        "vbs_18bcfc98d4f1e813ffbcedeb62ec23b6b9743605e96a7520",
        "vbs_776c1aa51441d41d189b4e0e5bcf0551ac3ef616f853e6ef"
    )

    const val HEADER_API_KEY = "X-API-Key"
    const val HEADER_ACCEPT = "Accept"
    const val ACCEPT_JSON = "application/json"
    const val DEFAULT_LANG = "id"
}
