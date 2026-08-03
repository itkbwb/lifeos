package com.lifeos.app.data

data class PlanEntry(
    val id: Int,
    val project_id: Int,
    val start_time: String,
    val end_time: String,
    val name: String?,
    val created_at: String,
    val subtask_id: Int? = null,
    val recurring_plan_id: Int? = null,
)

/**
 * A recurring Static Plan template (chapter: recurring plans) - materializes into normal
 * PlanEntry rows on a rolling ~30-day window server-side (app/recurrence.py), same as this app
 * never plans anything into infinity. A simplified RRULE matching the presets Google
 * Calendar's own recurrence picker exposes: `frequency` is "daily"|"weekly"|"monthly"|"yearly",
 * `interval` is "every N {frequency}", `weekdays` (only for weekly) is a comma-separated ISO
 * weekday list (Mon=1..Sun=7), `month_mode` (only for monthly) is "day_of_month" (same
 * day-of-month as series_start_date) or "weekday_of_month" (same "Nth weekday", e.g. "the
 * third Monday"), and `max_occurrences` is an independent "after N times" end condition on top
 * of `series_end_date`.
 */
data class RecurringPlan(
    val id: Int,
    val project_id: Int,
    val subtask_id: Int?,
    val name: String?,
    val start_time_of_day: String,
    val end_time_of_day: String,
    val frequency: String,
    val interval: Int,
    val weekdays: String,
    val month_mode: String?,
    val max_occurrences: Int?,
    val timezone: String,
    val series_start_date: String,
    val series_end_date: String?,
    val created_at: String,
)

data class DynamicPlanEntry(
    val id: Int,
    val project_id: Int,
    val start_time: String,
    val end_time: String,
    val name: String?,
    val subtask_id: Int? = null,
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

data class ImportProjectResult(
    val project_id: Int,
    val project_created: Boolean,
    val subtasks_created: Int,
    val subtasks_skipped: Int = 0,
    val static_entries_created: Int,
    val errors: List<ImportRowError>,
)

/**
 * Client-side mirror of the server's recursive `ImportSubtask` (chapter:
 * project import review) - a checklist node in the file being reviewed
 * before submission. Top-level entries are "Задача", nested entries at any
 * depth are "Подзадача". Used only to parse a picked JSON file (via Gson),
 * let [ProjectImportReviewDialog] prune rejected subtrees, and re-serialize
 * the accepted tree back into the shape [ApiFactory.importProject] expects -
 * never sent/received as its own API call.
 */
data class ImportSubtaskPayload(
    val title: String,
    val done: Boolean = false,
    val is_checklist: Boolean = false,
    val subtasks: List<ImportSubtaskPayload> = emptyList(),
)

data class ImportStaticEntryPayload(
    val date: String,
    val start: String,
    val end: String,
    val name: String? = null,
    val subtask_title: String? = null,
)

data class ImportProjectPayload(
    val project_name: String,
    val color: String? = null,
    val subtasks: List<ImportSubtaskPayload> = emptyList(),
    val static_entries: List<ImportStaticEntryPayload> = emptyList(),
    val tz_offset_minutes: Int = 0,
)

data class ClearResult(
    val deleted_events: Int,
    val deleted_plan_entries: Int,
    val deleted_plan_changes: Int,
    val deleted_projects: Int = 0,
)
