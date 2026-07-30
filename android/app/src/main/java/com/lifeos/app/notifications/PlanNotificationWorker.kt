package com.lifeos.app.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.SettingsStore
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val UNIQUE_WORK_NAME = "plan_notifications"
// 15 minutes is WorkManager's platform-enforced minimum periodic interval,
// not a choice made here - suggestions land within this window, not exactly
// on the minute.
private const val CHECK_INTERVAL_MINUTES = 15L
private const val START_WINDOW_MINUTES = 20L

fun schedulePlanNotifications(context: Context) {
    val request = PeriodicWorkRequestBuilder<PlanNotificationWorker>(CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
}

/**
 * Periodic check (chapter: notifications) - two independent suggestions,
 * evaluated every tick:
 *  - Start: a Dynamic Plan entry for today starts within [now, now+20min]
 *    and its project isn't already the active Timeline session.
 *  - Stop: the currently active Timeline session's project has a Dynamic
 *    Plan entry whose end_time has already passed.
 * Each is deduplicated against the last-notified id in [SettingsStore] so
 * the same suggestion doesn't repeat every 15 minutes.
 */
class PlanNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settingsStore = SettingsStore(applicationContext)
        if (!settingsStore.notificationsEnabled.first()) return Result.success()

        val serverUrl = settingsStore.serverUrl.first()
        val accessClientId = settingsStore.accessClientId.value
        val accessClientSecret = settingsStore.accessClientSecret.value

        return withContext(Dispatchers.IO) {
            runCatching {
                val zone = ZoneId.systemDefault()
                val now = ZonedDateTime.now(zone)
                val today = now.toLocalDate()
                val dayStart = today.atStartOfDay(zone).toInstant().toString()
                val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toString()

                val dynamicPlan = ApiFactory.listDynamicPlan(
                    serverUrl, accessClientId, accessClientSecret, from = dayStart, to = dayEnd,
                )
                val active = ApiFactory.getActiveProject(serverUrl, accessClientId, accessClientSecret)
                val projects = ApiFactory.listProjects(serverUrl, accessClientId, accessClientSecret)

                val startWindowEnd = now.plusMinutes(START_WINDOW_MINUTES)
                val startCandidate = dynamicPlan.firstOrNull { entry ->
                    val start = Instant.parse(entry.start_time).atZone(zone)
                    !start.isBefore(now) && start.isBefore(startWindowEnd) && active?.project_id != entry.project_id
                }
                val lastStartNotified = settingsStore.lastStartNotifiedPlanEntryId.first()
                if (startCandidate != null && startCandidate.id != lastStartNotified) {
                    val projectName = projects.firstOrNull { it.id == startCandidate.project_id }?.name ?: "проект"
                    val posted = Notifications.postStartSuggestion(
                        applicationContext, startCandidate.project_id, projectName, startCandidate.id * 2,
                    )
                    if (posted) settingsStore.setLastStartNotifiedPlanEntryId(startCandidate.id)
                }

                if (active != null) {
                    val activePlan = dynamicPlan.firstOrNull { it.project_id == active.project_id }
                    val lastStopNotified = settingsStore.lastStopNotifiedEventId.first()
                    if (activePlan != null) {
                        val end = Instant.parse(activePlan.end_time).atZone(zone)
                        if (end.isBefore(now) && active.event_id != lastStopNotified) {
                            val projectName = projects.firstOrNull { it.id == active.project_id }?.name ?: "проект"
                            val posted = Notifications.postStopSuggestion(
                                applicationContext, active.project_id, projectName, active.event_id * 2 + 1,
                            )
                            if (posted) settingsStore.setLastStopNotifiedEventId(active.event_id)
                        }
                    }
                }
            }
        }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}
