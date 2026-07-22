package com.lifeos.app.data

data class Block(
    val id: Int,
    val project_id: Int?,
    val project_name: String?,
    val block_type: String,
    val title: String,
    val description: String?,
    val planned_start: String,
    val planned_end: String,
    val planned_duration_minutes: Int,
    val stored_status: String,
    val status: String,
    val overdue: Boolean,
    val created_at: String,
    val updated_at: String,
    val completed_at: String?,
    val skipped_at: String?,
    val cancelled_at: String?,
    val rescheduled_from_id: Int?,
)

data class WorkSessionInfo(
    val id: Int,
    val schedule_block_id: Int,
    val started_at: String,
    val ended_at: String?,
    val status: String,
    val elapsed_seconds: Int,
    val paused_seconds: Int,
)

data class DayDeviation(
    val minutes_late_starting: Int,
    val minutes_over_under_planned: Int,
)

data class NowResponse(
    val date: String,
    val current_block: Block?,
    val active_work_session: WorkSessionInfo?,
    val next_block: Block?,
    val day_deviation: DayDeviation,
)

data class DayPlan(
    val date: String,
    val plan_end_date: String,
    val blocks: List<Block>,
)

data class WeekDay(
    val date: String,
    val weekday: String,
    val total_minutes: Int,
    val productive_minutes: Int,
    val completed_minutes: Int,
    val block_count: Int,
)

data class WeekPlan(
    val days: List<WeekDay>,
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

data class BlockCreateRequest(
    val title: String,
    val description: String? = null,
    val block_type: String = "work",
    val project_id: Int? = null,
    val planned_start: String,
    val planned_end: String,
)

data class BlockUpdateRequest(
    val title: String? = null,
    val description: String? = null,
    val block_type: String? = null,
    val project_id: Int? = null,
    val planned_start: String? = null,
    val planned_end: String? = null,
)

data class RescheduleRequest(
    val planned_start: String,
    val planned_end: String,
)
