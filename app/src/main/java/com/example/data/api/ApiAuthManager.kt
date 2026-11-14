package com.example.data.api

import okhttp3.Interceptor
import okhttp3.Response

class ApiAuthManager(
    private val keys: List<String> = ApiConstants.API_KEYS
) {
    private val apiKeys = keys
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    @Volatile
    private var currentKeyIndex = 0

    init {
        require(apiKeys.isNotEmpty()) {
            "API key belum dikonfigurasi."
        }
    }

    @Synchronized
    fun getCurrentKeyIndex(): Int = currentKeyIndex

    fun getKeyAt(index: Int): String {
        return apiKeys.getOrElse(index) {
            throw IndexOutOfBoundsException("API key index tidak valid: $index")
        }
    }

    fun keyCount(): Int = apiKeys.size

    @Synchronized
    fun nextKeyIndexOnAuthFailure(
        failedIndex: Int,
        triedIndices: Set<Int>
    ): Int? {
        if (apiKeys.isEmpty()) return null

        for (offset in 1..apiKeys.size) {
            val nextIndex = (failedIndex + offset) % apiKeys.size

            if (nextIndex !in triedIndices) {
                currentKeyIndex = nextIndex
                return nextIndex
            }
        }

        return null
    }

    @Synchronized
    fun resetToPrimary() {
        currentKeyIndex = 0
    }
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

        val triedIndices = mutableSetOf<Int>()
        var keyIndex = authManager.getCurrentKeyIndex()

        while (true) {
            triedIndices.add(keyIndex)

            val signedRequest = originalRequest.newBuilder()
                .header(
                    ApiConstants.HEADER_API_KEY,
                    authManager.getKeyAt(keyIndex)
                )
                .header(
                    ApiConstants.HEADER_ACCEPT,
                    ApiConstants.ACCEPT_JSON
                )
                .build()

            var response = chain.proceed(signedRequest)

            var serverRetries = 0

            while (
                response.code in listOf(502, 503, 504) &&
                serverRetries < 1
            ) {
                response.close()
                sleepFn(800L)
                serverRetries++

                response = chain.proceed(signedRequest)
            }

            if (response.code == 401 || response.code == 403) {
                val nextIndex = authManager.nextKeyIndexOnAuthFailure(
                    failedIndex = keyIndex,
                    triedIndices = triedIndices
                )

                if (nextIndex == null) {
                    return response
                }

                response.close()
                keyIndex = nextIndex
                continue
            }

            if (response.isSuccessful) {
                authManager.resetToPrimary()
            }

            return response
        }
    }
}
