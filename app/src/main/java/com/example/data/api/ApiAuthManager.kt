
package com.example.data.api

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicInteger

class ApiAuthManager(
    private val keys: List<String> = ApiConstants.API_KEYS
) {
    private val activeIndex = AtomicInteger(0)

    init {
        require(keys.isNotEmpty()) {
            "API keys configuration must not be empty"
        }
    }

    fun getCurrentKeyIndex(): Int =
        activeIndex.get().coerceIn(0, keys.lastIndex)

    fun getKeyAt(index: Int): String =
        keys[index.coerceIn(0, keys.lastIndex)]

    fun keyCount(): Int = keys.size

    @Synchronized
    fun nextKeyIndexOnAuthFailure(
        failedIndex: Int,
        triedIndices: Set<Int>
    ): Int? {
        if (triedIndices.size >= keys.size) return null

        if (activeIndex.get() == failedIndex) {
            activeIndex.set((failedIndex + 1) % keys.size)
        }

        val current = activeIndex.get()

        if (current !in triedIndices) return current

        for (step in 1..keys.size) {
            val candidate = (failedIndex + step) % keys.size

            if (candidate !in triedIndices) {
                activeIndex.set(candidate)
                return candidate
            }
        }

        return null
    }

    fun resetToPrimary() {
        activeIndex.set(0)
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
        val triedKeyIndices = HashSet<Int>(authManager.keyCount())

        var keyIndex = authManager.getCurrentKeyIndex()
        var gatewayRetried = false

        while (true) {
            triedKeyIndices.add(keyIndex)

            val apiKey = authManager.getKeyAt(keyIndex)

            val signedRequest = originalRequest.newBuilder()
                .header(ApiConstants.HEADER_API_KEY, apiKey)
                .header(ApiConstants.HEADER_ACCEPT, ApiConstants.ACCEPT_JSON)
                .build()

            val response = chain.proceed(signedRequest)
            val code = response.code

            // Ganti key jika autentikasi gagal atau kuota key terkena limit.
            if (code == 401 || code == 403 || code == 429) {
                val nextIndex = authManager.nextKeyIndexOnAuthFailure(
                    keyIndex,
                    triedKeyIndices
                )

                if (nextIndex != null) {
                    response.close()
                    keyIndex = nextIndex
                    continue
                }

                // Semua key sudah dicoba.
                return response
            }

            // Gangguan gateway: coba sekali lagi dengan key yang sama.
            if (
                (code == 502 || code == 503 || code == 504) &&
                !gatewayRetried
            ) {
                gatewayRetried = true
                response.close()
                sleepFn(800L)
                continue
            }

            return response
        }
    }
}
