package com.example.carrotnavi

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

data class KakaoSearchResponse(
    val documents: List<KakaoDocument>
)

data class KakaoDocument(
    val place_name: String,
    val road_address_name: String,
    val address_name: String,
    val x: String, // longitude
    val y: String  // latitude
)

interface KakaoSearchApi {
    @GET("v2/local/search/keyword.json")
    fun searchKeyword(
        @Header("Authorization") apiKey: String,
        @Query("query") query: String,
        @Query("sort") sort: String? = null,
        @Query("x") x: String? = null,
        @Query("y") y: String? = null,
        @Query("radius") radius: Int? = null
    ): Call<KakaoSearchResponse>
}
