package com.lifeos.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lifeos.app.data.ActiveProjectConflictException
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.DynamicPlanEntry
import com.lifeos.app.data.Event
import com.lifeos.app.data.PlanEntry
import com.lifeos.app.data.Project
import com.lifeos.app.ui.ConfirmDeleteDialog
import com.lifeos.app.ui.DynamicEntryEditDialog
import com.lifeos.app.ui.EventEditDialog
import com.lifeos.app.ui.PlanEntryEditDialog
import com.lifeos.app.ui.theme.ProjectColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ROW_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

private enum class SummaryTab(val label: String) {
    Timeline("Timeline"),
    Instant("Instant"),
    Dynamic("Dynamic"),
    Static("Static"),
}

private data class UndoAction(val message: String, val revert: suspend () -> Unit)

private sealed class DeleteTarget {
    data class EventTarget(val event: Event) : DeleteTarget()
    data class StaticTarget(val entry: PlanEntry) : DeleteTarget()
    data class DynamicTarget(val entry: DynamicPlanEntry) : DeleteTarget()
}

/**
 * The Day Summary (chapter 5.1/5.2) - the only place records are edited. Four
 * independent tabs, each showing only its own layer: Timeline and Instant are
 * both raw `Event` rows (chapter 5.4/5.6), Dynamic and Static are Plan layers
 * (chapter 5.7/5.8). Opened full-screen over the calendar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySummarySheet(
    date: LocalDate,
    projects: List<Project>,
    serverUrl: String,
    accessClientId: String,
    accessClientSecret: String,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DaySummaryContent(
            date = date,
            projects = projects,
            serverUrl = serverUrl,
            accessClientId = accessClientId,
            accessClientSecret = accessClientSecret,
            onClose = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaySummaryContent(
    date: LocalDate,
    projects: List<Project>,
    serverUrl: String,
    accessClientId: String,
    accessClientSecret: String,
    onClose: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    var tab by remember { mutableStateOf(SummaryTab.Timeline) }
    var refreshKey by remember { mutableIntStateOf(0) }

    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var staticEntries by remember { mutableStateOf<List<PlanEntry>>(emptyList()) }
    var dynamicEntries by remember { mutableStateOf<List<DynamicPlanEntry>>(emptyList()) }

    var editingEvent by remember { mutableStateOf<Event?>(null) }
    var editingStatic by remember { mutableStateOf<PlanEntry?>(null) }
    var editingDynamic by remember { mutableStateOf<DynamicPlanEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    var saveError by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun offerUndo(action: UndoAction) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = action.message,
                actionLabel = "Отменить",
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) { runCatching { action.revert() } }
                refreshKey++
            }
        }
    }

    LaunchedEffect(date, serverUrl, refreshKey) {
        val dayStart = date.atStartOfDay(zone).toInstant().toString()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toString()
        withContext(Dispatchers.IO) {
            runCatching {
                Triple(
                    ApiFactory.listEvents(serverUrl, accessClientId, accessClientSecret, from = dayStart, to = dayEnd),
                    ApiFactory.listPlanEntries(serverUrl, accessClientId, accessClientSecret, from = dayStart, to = dayEnd),
                    ApiFactory.listDynamicPlan(serverUrl, accessClientId, accessClientSecret, from = dayStart, to = dayEnd),
                )
            }
        }.onSuccess { (e, s, d) ->
            events = e
            staticEntries = s
            dynamicEntries = d
        }
    }

    fun projectName(id: Int) = projects.firstOrNull { it.id == id }?.name ?: "Проект"
    fun projectColor(id: Int) = ProjectColors.colorFor(projects.firstOrNull { it.id == id }?.color ?: "gray")

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        val label = date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru")))
                            .replaceFirstChar { it.titlecase(Locale("ru")) }
                        Text("$label · ${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("ru"))}")
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                        }
                    },
                )
                TabRow(selectedTabIndex = tab.ordinal) {
                    SummaryTab.entries.forEach { t ->
                        Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                SummaryTab.Timeline -> EventRowList(
                    events = events.filter { it.type == "start" || it.type == "end" }.sortedBy { Instant.parse(it.occurred_at) },
                    zone = zone,
                    projectName = ::projectName,
                    projectColor = ::projectColor,
                    onClick = { editingEvent = it },
                )

                SummaryTab.Instant -> EventRowList(
                    events = events.filter { it.type == "instant" }.sortedBy { Instant.parse(it.occurred_at) },
                    zone = zone,
                    projectName = ::projectName,
                    projectColor = ::projectColor,
                    onClick = { editingEvent = it },
                )

                SummaryTab.Dynamic -> DynamicRowList(
                    entries = dynamicEntries.sortedBy { Instant.parse(it.start_time) },
                    zone = zone,
                    projectName = ::projectName,
                    projectColor = ::projectColor,
                    onClick = { editingDynamic = it },
                )

                SummaryTab.Static -> StaticRowList(
                    entries = staticEntries.sortedBy { Instant.parse(it.start_time) },
                    zone = zone,
                    projectName = ::projectName,
                    projectColor = ::projectColor,
                    onClick = { editingStatic = it },
                )
            }
        }
    }

    editingEvent?.let { event ->
        EventEditDialog(
            event = event,
            projects = projects,
            errorMessage = saveError,
            zone = zone,
            onDismiss = { editingEvent = null; saveError = "" },
            onRequestDelete = { deleteTarget = DeleteTarget.EventTarget(event) },
            onSave = { projectId, occurredAt, label ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.correctEvent(
                                serverUrl, accessClientId, accessClientSecret,
                                eventId = event.id, projectId = projectId,
                                occurredAt = occurredAt.toString(), label = label,
                            )
                        }
                    }
                    result.onSuccess { corrected ->
                        editingEvent = null
                        saveError = ""
                        refreshKey++
                        offerUndo(
                            UndoAction("Событие изменено") {
                                ApiFactory.correctEvent(
                                    serverUrl, accessClientId, accessClientSecret,
                                    eventId = corrected.id, projectId = event.project_id,
                                    occurredAt = event.occurred_at, label = event.label ?: "",
                                )
                            },
                        )
                    }.onFailure { e ->
                        saveError = if (e is ActiveProjectConflictException) {
                            "Уже активен другой проект"
                        } else {
                            "Не удалось сохранить"
                        }
                    }
                }
            },
        )
    }

    editingStatic?.let { entry ->
        PlanEntryEditDialog(
            entry = entry,
            projects = projects,
            errorMessage = saveError,
            zone = zone,
            serverUrl = serverUrl,
            accessClientId = accessClientId,
            accessClientSecret = accessClientSecret,
            onDismiss = { editingStatic = null; saveError = "" },
            onRequestDelete = { deleteTarget = DeleteTarget.StaticTarget(entry) },
            onSave = { projectId, start, end, name, subtaskId ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.updatePlanEntry(
                                serverUrl, accessClientId, accessClientSecret,
                                id = entry.id, projectId = projectId,
                                startTime = start.toString(), endTime = end.toString(), name = name,
                                subtaskId = subtaskId, clearSubtask = subtaskId == null,
                            )
                        }
                    }
                    result.onSuccess {
                        editingStatic = null
                        saveError = ""
                        refreshKey++
                        offerUndo(
                            UndoAction("План изменён") {
                                ApiFactory.updatePlanEntry(
                                    serverUrl, accessClientId, accessClientSecret,
                                    id = entry.id, projectId = entry.project_id,
                                    startTime = entry.start_time, endTime = entry.end_time,
                                    name = entry.name ?: "",
                                    subtaskId = entry.subtask_id, clearSubtask = entry.subtask_id == null,
                                )
                            },
                        )
                    }.onFailure { saveError = "Не удалось сохранить" }
                }
            },
        )
    }

    editingDynamic?.let { entry ->
        val staticEntry = staticEntries.firstOrNull { it.id == entry.id }
        DynamicEntryEditDialog(
            entry = entry,
            projects = projects,
            errorMessage = saveError,
            zone = zone,
            serverUrl = serverUrl,
            accessClientId = accessClientId,
            accessClientSecret = accessClientSecret,
            onDismiss = { editingDynamic = null; saveError = "" },
            onRequestDelete = { deleteTarget = DeleteTarget.DynamicTarget(entry) },
            onSave = { projectId, start, end, name, subtaskId ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val timeChanged = start.toString() != entry.start_time || end.toString() != entry.end_time
                            val identityChanged = projectId != entry.project_id ||
                                name != (entry.name ?: "") ||
                                subtaskId != entry.subtask_id
                            var changeId: Int? = null
                            if (timeChanged) {
                                changeId = ApiFactory.createPlanChange(
                                    serverUrl, accessClientId, accessClientSecret,
                                    planEntryId = entry.id, changeType = "move",
                                    newStartTime = start.toString(), newEndTime = end.toString(),
                                ).id
                            }
                            if (identityChanged) {
                                ApiFactory.updatePlanEntry(
                                    serverUrl, accessClientId, accessClientSecret,
                                    id = entry.id, projectId = projectId, name = name,
                                    subtaskId = subtaskId, clearSubtask = subtaskId == null,
                                )
                            }
                            changeId
                        }
                    }
                    result.onSuccess { changeId ->
                        editingDynamic = null
                        saveError = ""
                        refreshKey++
                        offerUndo(
                            UndoAction("Dynamic план изменён") {
                                if (changeId != null) {
                                    ApiFactory.deletePlanChange(serverUrl, accessClientId, accessClientSecret, changeId)
                                }
                                val identityChanged = projectId != entry.project_id ||
                                    name != (entry.name ?: "") ||
                                    subtaskId != entry.subtask_id
                                if (staticEntry != null && identityChanged) {
                                    ApiFactory.updatePlanEntry(
                                        serverUrl, accessClientId, accessClientSecret,
                                        id = entry.id, projectId = staticEntry.project_id,
                                        name = staticEntry.name ?: "",
                                        subtaskId = staticEntry.subtask_id, clearSubtask = staticEntry.subtask_id == null,
                                    )
                                }
                            },
                        )
                    }.onFailure { saveError = "Не удалось сохранить" }
                }
            },
        )
    }

    deleteTarget?.let { target ->
        val message = when (target) {
            is DeleteTarget.EventTarget -> "Удалить событие?"
            is DeleteTarget.StaticTarget -> "Удалить запись плана?"
            is DeleteTarget.DynamicTarget -> "Удалить из Dynamic? Static-запись останется."
        }
        ConfirmDeleteDialog(
            message = message,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                scope.launch {
                    when (target) {
                        is DeleteTarget.EventTarget -> {
                            val event = target.event
                            withContext(Dispatchers.IO) {
                                runCatching { ApiFactory.deleteEvent(serverUrl, accessClientId, accessClientSecret, event.id) }
                            }.onSuccess {
                                deleteTarget = null
                                editingEvent = null
                                refreshKey++
                                offerUndo(
                                    UndoAction("Событие удалено") {
                                        ApiFactory.createEvent(
                                            serverUrl, accessClientId, accessClientSecret,
                                            projectId = event.project_id, type = event.type,
                                            occurredAt = event.occurred_at, label = event.label,
                                        )
                                    },
                                )
                            }
                        }

                        is DeleteTarget.StaticTarget -> {
                            val entry = target.entry
                            withContext(Dispatchers.IO) {
                                runCatching { ApiFactory.deletePlanEntry(serverUrl, accessClientId, accessClientSecret, entry.id) }
                            }.onSuccess {
                                deleteTarget = null
                                editingStatic = null
                                refreshKey++
                                offerUndo(
                                    UndoAction("План удалён") {
                                        ApiFactory.createPlanEntry(
                                            serverUrl, accessClientId, accessClientSecret,
                                            projectId = entry.project_id, startTime = entry.start_time,
                                            endTime = entry.end_time, name = entry.name,
                                        )
                                    },
                                )
                            }
                        }

                        is DeleteTarget.DynamicTarget -> {
                            val entry = target.entry
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    ApiFactory.createPlanChange(
                                        serverUrl, accessClientId, accessClientSecret,
                                        planEntryId = entry.id, changeType = "cancel",
                                    )
                                }
                            }.onSuccess { change ->
                                deleteTarget = null
                                editingDynamic = null
                                refreshKey++
                                offerUndo(
                                    UndoAction("Убрано из Dynamic") {
                                        ApiFactory.deletePlanChange(serverUrl, accessClientId, accessClientSecret, change.id)
                                    },
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun SummaryRow(
    time: LocalTime,
    color: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ROW_TIME_FORMAT.format(time),
            modifier = Modifier.width(52.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EventRowList(
    events: List<Event>,
    zone: ZoneId,
    projectName: (Int) -> String,
    projectColor: (Int) -> Color,
    onClick: (Event) -> Unit,
) {
    if (events.isEmpty()) {
        EmptyState("Событий нет")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(events, key = { it.id }) { event ->
            val time = Instant.parse(event.occurred_at).atZone(zone).toLocalTime()
            val title = event.label?.takeIf { it.isNotBlank() } ?: projectName(event.project_id)
            val subtitle = when (event.type) {
                "start" -> "начало"
                "end" -> "конец"
                else -> "отметка"
            }
            SummaryRow(
                time = time,
                color = projectColor(event.project_id),
                title = title,
                subtitle = subtitle,
                onClick = { onClick(event) },
            )
        }
    }
}

@Composable
private fun StaticRowList(
    entries: List<PlanEntry>,
    zone: ZoneId,
    projectName: (Int) -> String,
    projectColor: (Int) -> Color,
    onClick: (PlanEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyState("Планов нет")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.id }) { entry ->
            val start = Instant.parse(entry.start_time).atZone(zone).toLocalTime()
            val end = Instant.parse(entry.end_time).atZone(zone).toLocalTime()
            val title = entry.name?.takeIf { it.isNotBlank() } ?: projectName(entry.project_id)
            SummaryRow(
                time = start,
                color = projectColor(entry.project_id),
                title = title,
                subtitle = "до ${ROW_TIME_FORMAT.format(end)}",
                onClick = { onClick(entry) },
            )
        }
    }
}

@Composable
private fun DynamicRowList(
    entries: List<DynamicPlanEntry>,
    zone: ZoneId,
    projectName: (Int) -> String,
    projectColor: (Int) -> Color,
    onClick: (DynamicPlanEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyState("Планов нет")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.id }) { entry ->
            val start = Instant.parse(entry.start_time).atZone(zone).toLocalTime()
            val end = Instant.parse(entry.end_time).atZone(zone).toLocalTime()
            val title = entry.name?.takeIf { it.isNotBlank() } ?: projectName(entry.project_id)
            SummaryRow(
                time = start,
                color = projectColor(entry.project_id),
                title = title,
                subtitle = "до ${ROW_TIME_FORMAT.format(end)}",
                onClick = { onClick(entry) },
            )
        }
    }
}
