package com.example.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface GoodShortApiService {

    @GET("languages")
    suspend fun getLanguages(): Response<ResponseBody>

    @GET("home")
    suspend fun getHome(
        @Query("lang") lang: String = "in",
        @Query("channel") channel: Int = -1,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20
    ): Response<ResponseBody>

    @GET("nav")
    suspend fun getNavigation(
        @Query("lang") lang: String = "in"
    ): Response<ResponseBody>

    @GET("search")
    suspend fun search(
        @Query("lang") lang: String = "in",
        @Query("q") query: String,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 15
    ): Response<ResponseBody>

    @GET("hot")
    suspend fun getHotWords(
        @Query("lang") lang: String = "in"
    ): Response<ResponseBody>

    @GET("book/{id}")
    suspend fun getDetail(
        @Path("id") id: String,
        @Query("lang") lang: String = "in"
    ): Response<ResponseBody>

    @GET("chapters/{id}")
    suspend fun getChapters(
        @Path("id") id: String,
        @Query("lang") lang: String = "in"
    ): Response<ResponseBody>

    @GET("batchload/{id}")
    suspend fun getAllEpisodes(
        @Path("id") id: String,
        @Query("lang") lang: String = "in",
        @Query("q") query: String = ""
    ): Response<ResponseBody>
}
