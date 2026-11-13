package com.example.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface DramaBoxApiService {

    @GET("languages")
    suspend fun getLanguages(): Response<ResponseBody>

    @GET("latest")
    suspend fun getLatest(
        @Query("lang") lang: String = ApiConstants.DEFAULT_LANG
    ): Response<ResponseBody>

    @GET("trending")
    suspend fun getTrending(
        @Query("lang") lang: String = ApiConstants.DEFAULT_LANG
    ): Response<ResponseBody>

    @GET("foryou")
    suspend fun getForYou(
        @Query("lang") lang: String = ApiConstants.DEFAULT_LANG
    ): Response<ResponseBody>

    @GET("browse")
    suspend fun getBrowse(
        @Query("page") page: Int = 1,
        @Query("lang") lang: String = ApiConstants.DEFAULT_LANG
    ): Response<ResponseBody>

    @GET("dubbed")
    suspend fun getDubbed(
        @Query("classify") classify: String = "terpopuler",
        @Query("page") page: Int = 1,
        @Query("lang") lang: String = ApiConstants.DEFAULT_LANG
    ): Response<ResponseBody>

    @GET("search")
    suspend fun search(
        @Query("query") query: String,
        @Query("lang") lang: String = ApiConstants.DEFAULT_LANG
    ): Response<ResponseBody>

    @GET("detail")
    suspend fun getDetail(
        @Query("bookId") bookId: String,
        @Query("lang") lang: String = ApiConstants.DEFAULT_LANG
    ): Response<ResponseBody>

    @GET("allepisode")
    suspend fun getAllEpisodes(
        @Query("bookId") bookId: String
    ): Response<ResponseBody>
}
