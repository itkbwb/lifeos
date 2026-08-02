package com.lifeos.app.data

data class Project(
    val id: Int,
    val name: String,
    val color: String,
    val created_at: String,
    val archived: Boolean = false,
    val notes: String? = null,
)

data class ProjectMergeResult(
    val target_project_id: Int,
    val subtasks_moved: Int,
    val events_moved: Int,
    val plan_entries_moved: Int,
)
