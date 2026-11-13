package com.example.data.api

import okhttp3.Interceptor
import okhttp3.Response

class ApiAuthManager(
    private val keys: List<String> = ApiConstants.API_KEYS
) {
    private val primaryKey: String

    init {
        primaryKey = keys.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "API key belum dikonfigurasi."
            )
    }

    fun getCurrentKeyIndex(): Int = 0

    fun getKeyAt(index: Int): String = primaryKey

    fun keyCount(): Int = 1

    @Synchronized
    fun nextKeyIndexOnAuthFailure(
        failedIndex: Int,
        triedIndices: Set<Int>
    ): Int? = null

    fun resetToPrimary() = Unit
}

class ApiShortInterceptor(
    private val authManager: ApiAuthManager,
    private val sleepFn: (Long) -> Unit = { ms ->
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val signedRequest = originalRequest.newBuilder()
            .header(
                ApiConstants.HEADER_API_KEY,
                authManager.getKeyAt(0)
            )
            .header(
                ApiConstants.HEADER_ACCEPT,
                ApiConstants.ACCEPT_JSON
            )
            .build()

        var response = chain.proceed(signedRequest)

        if (response.code == 502 ||
            response.code == 503 ||
            response.code == 504
        ) {
            response.close()
            sleepFn(800L)
            response = chain.proceed(signedRequest)
        }

        return response
    }
}
