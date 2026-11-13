package com.example.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ReelShortApiService {

    @GET("languages")
    suspend fun getLanguages(): Response<ResponseBody>

    @GET("trending")
    suspend fun getTrending(
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @GET("home")
    suspend fun getHome(
        @Query("tab") tab: String = "populer",
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @GET("detail/{id}")
    suspend fun getDetail(
        @Path("id") id: String,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @GET("chapters/{id}")
    suspend fun getChapters(
        @Path("id") id: String,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @GET("allepisodes/{id}")
    suspend fun getAllEpisodes(
        @Path("id") id: String,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>
}
