package com.lifeos.app.data

data class Event(
    val id: Int,
    val project_id: Int,
    val type: String,
    val occurred_at: String,
    val label: String?,
    val created_at: String,
    val superseded_by_id: Int?,
    val corrects_id: Int?,
    val corrected_at: String?,
)

data class ActiveProject(
    val project_id: Int,
    val event_id: Int,
    val started_at: String,
)

class ActiveProjectConflictException(
    val activeProjectId: Int,
    val activeEventId: Int,
    val startedAt: String,
) : Exception("another project is already active")
