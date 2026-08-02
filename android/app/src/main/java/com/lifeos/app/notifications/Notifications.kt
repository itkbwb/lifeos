package com.lifeos.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lifeos.app.MainActivity
import com.lifeos.app.R

/**
 * Two separate notification channels/kinds (chapter: notifications) - a
 * suggestion to start a project whose Dynamic Plan entry is starting soon,
 * and a separate suggestion to end the currently active one once its
 * planned end time has passed. Deliberately kept apart end to end (channel,
 * action, dedup state) rather than one generic "plan reminder" type.
 */
object Notifications {
    const val CHANNEL_START = "start_suggestions"
    const val CHANNEL_STOP = "stop_suggestions"
    const val CHANNEL_REMINDER = "reminders"

    const val ACTION_START_PROJECT = "com.lifeos.app.action.START_PROJECT"
    const val ACTION_END_PROJECT = "com.lifeos.app.action.END_PROJECT"
    const val ACTION_DISMISS = "com.lifeos.app.action.DISMISS"
    const val EXTRA_PROJECT_ID = "project_id"
    const val EXTRA_NOTIFICATION_ID = "notification_id"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_START, "Предложения начать", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STOP, "Предложения закончить", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "Напоминания", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun hasPostPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Returns whether the notification was actually posted - callers must only
     * advance their dedup state (e.g. [SettingsStore.setLastStopNotifiedEventId])
     * when this is true, or a suggestion silently dropped for lack of
     * permission would be marked "already notified" and never retried once
     * permission is later granted. */
    fun postStartSuggestion(context: Context, projectId: Int, projectName: String, notificationId: Int): Boolean {
        ensureChannels(context)
        if (!hasPostPermission(context)) return false
        val notification = NotificationCompat.Builder(context, CHANNEL_START)
            .setSmallIcon(R.drawable.ic_instant_sparkle)
            .setContentTitle("Начать «$projectName»?")
            .setContentText("Скоро время по плану")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, notificationId))
            .addAction(0, "Начать", actionIntent(context, ACTION_START_PROJECT, projectId, notificationId))
            .addAction(0, "Игнорировать", actionIntent(context, ACTION_DISMISS, projectId, notificationId))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        return true
    }

    fun postStopSuggestion(context: Context, projectId: Int, projectName: String, notificationId: Int): Boolean {
        ensureChannels(context)
        if (!hasPostPermission(context)) return false
        val notification = NotificationCompat.Builder(context, CHANNEL_STOP)
            .setSmallIcon(R.drawable.ic_instant_sparkle)
            .setContentTitle("Закончить «$projectName»?")
            .setContentText("Запланированное время истекло")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, notificationId))
            .addAction(0, "Закончить", actionIntent(context, ACTION_END_PROJECT, projectId, notificationId))
            .addAction(0, "Игнорировать", actionIntent(context, ACTION_DISMISS, projectId, notificationId))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        return true
    }

    /** No action buttons, unlike start/stop suggestions - a reminder is just informational,
     * tapping it (or the body) simply opens the app. */
    fun postReminder(context: Context, message: String, notificationId: Int): Boolean {
        ensureChannels(context)
        if (!hasPostPermission(context)) return false
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_instant_sparkle)
            .setContentTitle("Напоминание")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, notificationId))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        return true
    }

    /** Tapping the notification body (not one of its action buttons) just opens
     * the app - there's no deep link to a specific screen, MainActivity's own
     * default (Dashboard) is enough context for the user to act on the
     * suggestion themselves. */
    private fun openAppIntent(context: Context, notificationId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // Same request-code scheme as actionIntent (notificationId * 10 + a small
        // offset) but offset 9 - actionIntent only ever uses 0-2, so this can't
        // collide with either of a given notification's two action buttons.
        return PendingIntent.getActivity(
            context, notificationId * 10 + 9, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(context: Context, action: String, projectId: Int, notificationId: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_PROJECT_ID, projectId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val requestCode = notificationId * 10 + (action.hashCode() and 0x7).let { it % 3 }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
