package com.lifeos.app.data

data class Block(
    val id: Int,
    val block_date: String,
    val start_time: String,
    val end_time: String,
    val duration_minutes: Int,
    val title: String,
    val notes: String?,
    val block_type: String,
    val status: String,
    val project_id: Int?,
    val project_name: String?,
)

data class WeekDay(
    val date: String,
    val weekday: String,
    val total_minutes: Int,
    val productive_minutes: Int,
    val completed_minutes: Int,
    val block_count: Int,
)

data class ProjectStat(
    val id: Int,
    val name: String,
    val priority: Int,
    val category: String,
    val scheduled_minutes: Int,
    val completed_minutes: Int,
    val block_count: Int,
)

data class Dashboard(
    val date: String,
    val plan_end_date: String,
    val blocks: List<Block>,
    val week: List<WeekDay>,
    val projects: List<ProjectStat>,
)

data class ActionResult(val ok: Boolean, val status: String)
