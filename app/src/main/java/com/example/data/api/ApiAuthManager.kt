package com.example.data.api

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicInteger

class ApiAuthManager(
    private val keys: List<String> = ApiConstants.API_KEYS
) {
    private val activeIndex = AtomicInteger(0)

    init {
        require(keys.isNotEmpty()) { "API keys configuration must not be empty" }
    }

    fun getCurrentKeyIndex(): Int = activeIndex.get().coerceIn(0, keys.lastIndex)

    fun getKeyAt(index: Int): String = keys[index.coerceIn(0, keys.lastIndex)]

    fun keyCount(): Int = keys.size

    @Synchronized
    fun nextKeyIndexOnAuthFailure(failedIndex: Int, triedIndices: Set<Int>): Int? {
        if (triedIndices.size >= keys.size) return null

        // Advance shared active index if it still points to the failed key
        if (activeIndex.get() == failedIndex) {
            val candidate = (failedIndex + 1) % keys.size
            activeIndex.set(candidate)
        }

        // Prefer current activeIndex if untried in this request chain
        val current = activeIndex.get()
        if (current !in triedIndices) return current

        // Otherwise scan linearly from (failedIndex + 1) for an untried key
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
        var rateLimitRetried = false
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

            // Rotate ONLY when authentication is rejected (HTTP 401 / 403), at most once per key
            if (code == 401 || code == 403) {
                val nextIndex = authManager.nextKeyIndexOnAuthFailure(keyIndex, triedKeyIndices)
                if (nextIndex != null) {
                    response.close()
                    keyIndex = nextIndex
                    continue
                }
                return response
            }

            // Respect HTTP 429 rate limit using Retry-After without rotating API keys
            if (code == 429 && !rateLimitRetried) {
                rateLimitRetried = true
                val retryAfterSec = response.header("Retry-After")?.trim()?.toLongOrNull()
                val delayMs = if (retryAfterSec != null && retryAfterSec > 0) {
                    (retryAfterSec * 1000L).coerceIn(500L, 4000L)
                } else {
                    1500L
                }
                response.close()
                sleepFn(delayMs)
                continue
            }

            // Do NOT rotate keys on HTTP 502 / 503 / 504; perform at most one brief retry with the same key
            if ((code == 502 || code == 503 || code == 504) && !gatewayRetried) {
                gatewayRetried = true
                response.close()
                sleepFn(800L)
                continue
            }

            return response
        }
    }
}
