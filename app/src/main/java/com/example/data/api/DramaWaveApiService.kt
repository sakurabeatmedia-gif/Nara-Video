package com.example.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface DramaWaveApiService {

    @GET("api/languages")
    suspend fun getLanguages(): Response<ResponseBody>

    @GET("api/home")
    suspend fun getHome(
        @Query("lang") lang: String = "in"
    ): Response<ResponseBody>

    @GET("api/recommend")
    suspend fun getRecommend(
        @Query("lang") lang: String = "in",
        @Query("next") next: Int = 1
    ): Response<ResponseBody>

    @GET("api/anime")
    suspend fun getAnime(
        @Query("lang") lang: String = "in",
        @Query("next") next: Int = 1
    ): Response<ResponseBody>

    @GET("api/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("lang") lang: String = "in",
        @Query("next") next: Int = 1
    ): Response<ResponseBody>

    @GET("api/drama/{id}")
    suspend fun getDetail(
        @Path("id") id: String,
        @Query("lang") lang: String = "in"
    ): Response<ResponseBody>
}
