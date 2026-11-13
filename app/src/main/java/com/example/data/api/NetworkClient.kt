package com.example.data.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object NetworkClient {

    val authManager: ApiAuthManager by lazy {
        ApiAuthManager(ApiConstants.API_KEYS)
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(ApiShortInterceptor(authManager))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val mediaOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val meloloRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(ApiConstants.MELOLO_BASE_URL)
            .client(okHttpClient)
            .build()
    }

    private val dramaBoxRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(ApiConstants.DRAMABOX_BASE_URL)
            .client(okHttpClient)
            .build()
    }

    private val dramaWaveRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(ApiConstants.DRAMAWAVE_BASE_URL)
            .client(okHttpClient)
            .build()
    }

    val meloloService: MeloloApiService by lazy {
        meloloRetrofit.create(MeloloApiService::class.java)
    }

    val dramaBoxService: DramaBoxApiService by lazy {
        dramaBoxRetrofit.create(DramaBoxApiService::class.java)
    }

    val dramaWaveService: DramaWaveApiService by lazy {
        dramaWaveRetrofit.create(DramaWaveApiService::class.java)
    }
}
