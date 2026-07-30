package com.lifeos.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/** Handles the action buttons on start/stop suggestion notifications - always
 * dismisses the notification, and for Start/End enqueues [CreateEventWorker]
 * to make the actual API call (a BroadcastReceiver can't safely do blocking
 * network I/O itself). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val projectId = intent.getIntExtra(Notifications.EXTRA_PROJECT_ID, -1)
        val notificationId = intent.getIntExtra(Notifications.EXTRA_NOTIFICATION_ID, -1)
        if (notificationId != -1) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
        if (projectId == -1) return

        val type = when (intent.action) {
            Notifications.ACTION_START_PROJECT -> "start"
            Notifications.ACTION_END_PROJECT -> "end"
            else -> return
        }
        val request = OneTimeWorkRequestBuilder<CreateEventWorker>()
            .setInputData(CreateEventWorker.inputData(projectId, type))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
