package com.lifeos.app.data

data class Reminder(
    val id: Int,
    val remind_at: String,
    val message: String,
    val notified: Boolean,
    val created_at: String,
)
