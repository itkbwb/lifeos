package com.lifeos.app.data

import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface LifeOsApi {
    @GET("api/dashboard")
    suspend fun getDashboard(@Query("block_date") blockDate: String? = null): Dashboard

    @FormUrlEncoded
    @POST("api/blocks/{id}/action")
    suspend fun blockAction(@Path("id") id: Int, @Field("action") action: String): ActionResult
}
