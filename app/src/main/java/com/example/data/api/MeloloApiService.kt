package com.example.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface MeloloApiService {

    @GET("home")
    suspend fun getHome(
        @Query("lang") lang: String = ApiConstants.DEFAULT_LANG,
        @Query("offset") offset: Int = 0
    ): Response<ResponseBody>

    @GET("search")
    suspend fun search(
        @Query("lang") lang: String = ApiConstants.DEFAULT_LANG,
        @Query("q") query: String
    ): Response<ResponseBody>

    @GET("detail/{id}")
    suspend fun getDetail(
        @Path("id") id: String,
        @Query("lang") lang: String = ApiConstants.DEFAULT_LANG
    ): Response<ResponseBody>

    @GET("video")
    suspend fun getVideo(
        @Query("id") id: String,
        @Query("ep") ep: Int
    ): Response<ResponseBody>
}
