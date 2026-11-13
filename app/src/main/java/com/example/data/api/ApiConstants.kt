package com.example.data.api

object ApiConstants {
    const val MELOLO_BASE_URL = "https://apishort.com/v1/melolo/api/"
    const val DRAMABOX_BASE_URL = "https://apishort.com/v1/dramabox/api/dramabox/"

    val API_KEYS: List<String> = listOf(
        "vbs_cbb6e561d49018a8ed3f1f55bfd0e6662f157f753f7ca08a",
        "vbs_7dd91a0c2f823aac18c1b08883cd6d0c5cced4e903b7cc62",
        "vbs_0d7e1b255475439d9d808f700e60d87182faef0054d21020"
    )

    const val HEADER_API_KEY = "X-API-Key"
    const val HEADER_ACCEPT = "Accept"
    const val ACCEPT_JSON = "application/json"
    const val DEFAULT_LANG = "id"
}
