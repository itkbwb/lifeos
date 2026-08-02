package com.lifeos.app.notifications

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fire-and-forget bridge from [LifeOsFirebaseMessagingService] (which has no view of the UI) to
 * [com.lifeos.app.MainActivity]'s Snackbar (chapter: special reminders). `tryEmit` never
 * suspends and never fails - if nothing is collecting (app fully backgrounded), the message is
 * just dropped, which is fine since the system notification already covers that case.
 */
object ReminderEvents {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun emit(message: String) {
        _messages.tryEmit(message)
    }
}
