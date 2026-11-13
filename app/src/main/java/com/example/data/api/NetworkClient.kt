package com.example.data.api

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object NetworkClient {

    private val authInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val activeApiKey = ApiKeyStore.getActiveApiKey()

        val newRequest = originalRequest.newBuilder()
            .header(ApiConstants.HEADER_API_KEY, activeApiKey)
            .header(ApiConstants.HEADER_ACCEPT, ApiConstants.ACCEPT_JSON)
            .build()

        chain.proceed(newRequest)
    }

    private val retryOnTransientServerError = Interceptor { chain ->
        val request = chain.request()
        var response = chain.proceed(request)
        var tryCount = 0
        val maxRetries = 2

        while (!response.isSuccessful && (response.code == 502 || response.code == 504) && tryCount < maxRetries) {
            tryCount++
            response.close()
            try {
                Thread.sleep(1000L * tryCount)
            } catch (_: InterruptedException) {
                break
            }
            response = chain.proceed(request)
        }

        response
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(retryOnTransientServerError)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // Separate OkHttpClient for video CDN probing and streaming (never leaks API Key to CDN)
    val mediaOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(ApiConstants.API_BASE_URL)
            .client(okHttpClient)
            .build()
    }

    val meloloService: MeloloApiService by lazy {
        retrofit.create(MeloloApiService::class.java)
    }
}

sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class Error(val code: Int, val message: String, val isServerError: Boolean = false) : NetworkResult<Nothing>()
    data class Exception(val throwable: Throwable) : NetworkResult<Nothing>()
}
