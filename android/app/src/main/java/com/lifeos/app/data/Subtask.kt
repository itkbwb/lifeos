package com.lifeos.app.data

data class Subtask(
    val id: Int,
    val project_id: Int,
    val title: String,
    val done: Boolean,
    val position: Int,
    val created_at: String,
    val parent_id: Int? = null,
    val notes: String? = null,
)
