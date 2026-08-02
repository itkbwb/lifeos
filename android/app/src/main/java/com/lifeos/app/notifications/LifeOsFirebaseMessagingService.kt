package com.lifeos.app.notifications

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives the server-decided start/stop suggestions (chapter: notifications,
 * server-pushed) via FCM - the server's own scheduler (server/app/scheduler.py)
 * now owns deciding *when*, replacing the client-side PlanNotificationWorker
 * that used to poll every 15 minutes and could be deferred for hours by
 * Android's Doze/App Standby. This service only turns a push into the same
 * on-device notification UI [Notifications] already built (channels, action
 * buttons), and re-registers this device's token on rotation.
 */
class LifeOsFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { registerToken(applicationContext, token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (data["type"]) {
            "start", "end" -> handleSuggestion(data)
            "reminder" -> handleReminder(data)
        }
    }

    private fun handleSuggestion(data: Map<String, String>) {
        val type = data["type"] ?: return
        val projectId = data["project_id"]?.toIntOrNull() ?: return
        val projectName = data["project_name"] ?: "проект"
        val notificationId = projectId * 2 + if (type == "end") 1 else 0

        when (type) {
            "start" -> Notifications.postStartSuggestion(applicationContext, projectId, projectName, notificationId)
            "end" -> Notifications.postStopSuggestion(applicationContext, projectId, projectName, notificationId)
        }
    }

    private fun handleReminder(data: Map<String, String>) {
        val reminderId = data["reminder_id"]?.toIntOrNull() ?: return
        val message = data["message"] ?: return
        // Offset well clear of the projectId*2(+1) IDs handleSuggestion uses, so a reminder
        // notification can never collide with a start/stop suggestion's.
        Notifications.postReminder(applicationContext, message, notificationId = 1_000_000 + reminderId)
        // Also surfaced as an in-app snackbar (with its own close "x") when the app is open.
        ReminderEvents.emit(message)
    }

    companion object {
        /** Also called once from MainActivity on launch - onNewToken only fires
         * when the token is first created or rotates, not on every app start,
         * so a token that existed before the server URL was configured (or
         * before this device had ever synced) would otherwise never register. */
        suspend fun registerToken(context: Context, token: String) {
            val settingsStore = SettingsStore(context)
            val serverUrl = settingsStore.serverUrl.first()
            withContext(Dispatchers.IO) {
                runCatching {
                    ApiFactory.registerDeviceToken(
                        serverUrl,
                        settingsStore.accessClientId.value,
                        settingsStore.accessClientSecret.value,
                        token,
                    )
                }
            }
        }

        /** Called when the Settings "notifications" toggle is turned off - lets
         * the server stop pushing to this device rather than relying on the
         * token eventually expiring on its own. */
        suspend fun unregisterToken(context: Context, token: String) {
            val settingsStore = SettingsStore(context)
            val serverUrl = settingsStore.serverUrl.first()
            withContext(Dispatchers.IO) {
                runCatching {
                    ApiFactory.unregisterDeviceToken(
                        serverUrl,
                        settingsStore.accessClientId.value,
                        settingsStore.accessClientSecret.value,
                        token,
                    )
                }
            }
        }
    }
}
