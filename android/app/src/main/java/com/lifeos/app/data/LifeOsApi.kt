package com.lifeos.app.data

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface LifeOsApi {
    @GET("api/now")
    suspend fun getNow(): NowResponse

    @GET("api/plan/day")
    suspend fun getDayPlan(@Query("plan_date") planDate: String? = null): DayPlan

    @GET("api/plan/week")
    suspend fun getWeekPlan(@Query("plan_date") planDate: String? = null): WeekPlan

    @GET("api/projects")
    suspend fun getProjects(): List<ProjectStat>

    @POST("api/blocks")
    suspend fun createBlock(@Body body: BlockCreateRequest): Block

    @PATCH("api/blocks/{id}")
    suspend fun updateBlock(@Path("id") id: Int, @Body body: BlockUpdateRequest): Block

    @DELETE("api/blocks/{id}")
    suspend fun deleteBlock(@Path("id") id: Int)

    @POST("api/blocks/{id}/start")
    suspend fun startBlock(@Path("id") id: Int): Block

    @POST("api/blocks/{id}/restart")
    suspend fun restartBlock(@Path("id") id: Int): Block

    @POST("api/blocks/{id}/pause")
    suspend fun pauseBlock(@Path("id") id: Int): Block

    @POST("api/blocks/{id}/resume")
    suspend fun resumeBlock(@Path("id") id: Int): Block

    @POST("api/blocks/{id}/complete")
    suspend fun completeBlock(@Path("id") id: Int): Block

    @POST("api/blocks/{id}/skip")
    suspend fun skipBlock(@Path("id") id: Int): Block

    @POST("api/blocks/{id}/cancel")
    suspend fun cancelBlock(@Path("id") id: Int): Block

    @POST("api/blocks/{id}/reschedule")
    suspend fun rescheduleBlock(@Path("id") id: Int, @Body body: RescheduleRequest): Block
}
