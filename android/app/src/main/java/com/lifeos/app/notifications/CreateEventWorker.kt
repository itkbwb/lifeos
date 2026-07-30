package com.lifeos.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val KEY_PROJECT_ID = "project_id"
private const val KEY_TYPE = "type"

/** Performs the actual start/end API call after a notification action button
 * is tapped - runs off a BroadcastReceiver's short-lived callback, so the
 * network I/O has to be handed off to WorkManager rather than done inline. */
class CreateEventWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val projectId = inputData.getInt(KEY_PROJECT_ID, -1)
        val type = inputData.getString(KEY_TYPE)
        if (projectId == -1 || type == null) return Result.failure()

        val settingsStore = SettingsStore(applicationContext)
        val serverUrl = settingsStore.serverUrl.first()
        val accessClientId = settingsStore.accessClientId.value
        val accessClientSecret = settingsStore.accessClientSecret.value

        return withContext(Dispatchers.IO) {
            runCatching {
                ApiFactory.createEvent(serverUrl, accessClientId, accessClientSecret, projectId = projectId, type = type)
            }
        }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        fun inputData(projectId: Int, type: String) = androidx.work.Data.Builder()
            .putInt(KEY_PROJECT_ID, projectId)
            .putString(KEY_TYPE, type)
            .build()
    }
}
