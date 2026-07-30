package com.lifeos.app.data

data class PlanEntry(
    val id: Int,
    val project_id: Int,
    val start_time: String,
    val end_time: String,
    val name: String?,
    val created_at: String,
)

data class DynamicPlanEntry(
    val id: Int,
    val project_id: Int,
    val start_time: String,
    val end_time: String,
    val name: String?,
)
