package com.example.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface DramaBiteApiService {

    @GET("languages")
    suspend fun getLanguages(): Response<ResponseBody>

    @GET("home")
    suspend fun getHome(
        @Query("lang") lang: String = "id"
    ): Response<ResponseBody>

    @GET("module")
    suspend fun getModule(
        @Query("page") page: Int = 1,
        @Query("lang") lang: String = "id"
    ): Response<ResponseBody>

    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("lang") lang: String = "id"
    ): Response<ResponseBody>

    @GET("drama/{id}")
    suspend fun getDetail(
        @Path("id") id: String,
        @Query("lang") lang: String = "id"
    ): Response<ResponseBody>

    @GET("episodes/{id}")
    suspend fun getEpisodes(
        @Path("id") id: String,
        @Query("lang") lang: String = "id"
    ): Response<ResponseBody>

    @GET("play/{id}/{episode}")
    suspend fun getVideo(
        @Path("id") id: String,
        @Path("episode") episode: Int,
        @Query("lang") lang: String = "id"
    ): Response<ResponseBody>
}
