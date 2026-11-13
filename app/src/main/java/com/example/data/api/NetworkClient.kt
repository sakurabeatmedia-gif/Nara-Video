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

    private fun createRetrofit(baseUrl: String): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .build()
    }

    private val meloloRetrofit by lazy {
        createRetrofit(ApiConstants.MELOLO_BASE_URL)
    }

    private val dramaBoxRetrofit by lazy {
        createRetrofit(ApiConstants.DRAMABOX_BASE_URL)
    }

    private val dramaWaveRetrofit by lazy {
        createRetrofit(ApiConstants.DRAMAWAVE_BASE_URL)
    }

    private val reelShortRetrofit by lazy {
        createRetrofit(ApiConstants.REELSHORT_BASE_URL)
    }

    private val dramaBiteRetrofit by lazy {
        createRetrofit(ApiConstants.DRAMABITE_BASE_URL)
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

    val reelShortService: ReelShortApiService by lazy {
        reelShortRetrofit.create(ReelShortApiService::class.java)
    }

    val dramaBiteService: DramaBiteApiService by lazy {
        dramaBiteRetrofit.create(DramaBiteApiService::class.java)
    }
}
