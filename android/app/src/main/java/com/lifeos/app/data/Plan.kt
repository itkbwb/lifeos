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

data class PlanChange(
    val id: Int,
    val plan_entry_id: Int,
    val change_type: String,
    val new_start_time: String?,
    val new_end_time: String?,
    val created_at: String,
)

data class ImportRowError(
    val row: Int,
    val message: String,
)

data class ImportResult(
    val created: Int,
    val projects_created: List<String>,
    val errors: List<ImportRowError>,
)

data class ClearResult(
    val deleted_events: Int,
    val deleted_plan_entries: Int,
    val deleted_plan_changes: Int,
)
