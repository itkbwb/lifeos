package com.lifeos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.DynamicPlanEntry
import com.lifeos.app.data.PlanEntry
import com.lifeos.app.data.Project
import com.lifeos.app.data.Subtask
import com.lifeos.app.ui.theme.ProjectColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ProjectPicker(projects: List<Project>, selectedId: Int?, onSelect: (Int) -> Unit) {
    // Archived projects don't belong among active pickers (chapter: archiving) -
    // except the one already selected, so editing an old record referencing an
    // archived project never loses its current selection.
    val pickable = projects.filter { !it.archived || it.id == selectedId }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        pickable.forEach { project ->
            val isSelected = project.id == selectedId
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(ProjectColors.colorFor(project.color).copy(alpha = 0.85f))
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(16.dp))
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(project.id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(project.name, color = ProjectColors.contrastingTextColor(ProjectColors.colorFor(project.color)))
            }
        }
    }
}

/**
 * Subtask equivalent of [ProjectPicker] (chapter: planning subtasks) - lets a
 * scheduled block optionally target one checklist item of the selected
 * project. "Без подзадачи" (null) is always the first chip since linking is
 * optional - most Static/Dynamic entries won't reference a subtask at all.
 */
@Composable
internal fun SubtaskPicker(subtasks: List<Subtask>, selectedId: Int?, onSelect: (Int?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf<Subtask?>(null).plus(subtasks).forEach { subtask ->
            val isSelected = subtask?.id == selectedId
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(16.dp))
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(subtask?.id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(subtask?.title ?: "Без подзадачи", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val WEEKDAY_SHORT_LABELS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс") // index0=Mon(ISO 1)..index6=Sun(ISO 7)

/**
 * Repeat picker for [StaticPlanFormDialog] (chapter: recurring plans). Preset chips
 * (не повторять/каждый день/по будням/по выходным) just pre-fill the per-weekday toggle row
 * below them - that row stays editable regardless of which preset was tapped, so "каждый день,
 * кроме среды" is one extra tap away rather than needing a 5th preset.
 */
@Composable
private fun RepeatPicker(
    selectedWeekdays: Set<Int>,
    onWeekdaysChange: (Set<Int>) -> Unit,
    seriesEndText: String,
    onSeriesEndChange: (String) -> Unit,
) {
    val everyDay = (1..7).toSet()
    val weekdaysOnly = (1..5).toSet()
    val weekendOnly = setOf(6, 7)
    Column {
        Text("Повтор", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RepeatChip("Не повторять", selectedWeekdays.isEmpty()) { onWeekdaysChange(emptySet()) }
            RepeatChip("Каждый день", selectedWeekdays == everyDay) { onWeekdaysChange(everyDay) }
            RepeatChip("По будням", selectedWeekdays == weekdaysOnly) { onWeekdaysChange(weekdaysOnly) }
            RepeatChip("По выходным", selectedWeekdays == weekendOnly) { onWeekdaysChange(weekendOnly) }
        }
        if (selectedWeekdays.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WEEKDAY_SHORT_LABELS.forEachIndexed { index, label ->
                    val iso = index + 1
                    val isOn = iso in selectedWeekdays
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .clickable {
                                onWeekdaysChange(if (isOn) selectedWeekdays - iso else selectedWeekdays + iso)
                            }
                            .size(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            fontSize = 11.sp,
                            color = if (isOn) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = seriesEndText,
                onValueChange = onSeriesEndChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("До какой даты") },
                placeholder = { Text("Не ограничено") },
            )
        }
    }
}

@Composable
private fun RepeatChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Creates a Static Plan entry (chapter 4.5/4.6) for a fixed day: pick a project (required -
 * a plan cannot exist without one) and a start/end time. The entry is immutable once
 * created; rescheduling later goes through a PlanChange, not this dialog.
 *
 * Optionally repeating (chapter: recurring plans) - picking any weekday in [RepeatPicker]
 * switches the confirm action from [onConfirm] (a single entry) to [onConfirmRecurring] (a
 * RecurringPlan template, materialized server-side on a rolling window rather than planned
 * into infinity).
 */
@Composable
fun StaticPlanFormDialog(
    projects: List<Project>,
    errorMessage: String,
    serverUrl: String,
    accessClientId: String = "",
    accessClientSecret: String = "",
    onDismiss: () -> Unit,
    onConfirm: (projectId: Int, startTime: LocalTime, endTime: LocalTime, name: String?, subtaskId: Int?) -> Unit,
    onConfirmRecurring: (
        projectId: Int,
        startTime: LocalTime,
        endTime: LocalTime,
        name: String?,
        subtaskId: Int?,
        weekdays: Set<Int>,
        seriesEndDate: LocalDate?,
    ) -> Unit,
) {
    var selectedProjectId by remember { mutableStateOf(projects.firstOrNull()?.id) }
    var startText by remember { mutableStateOf("09:00") }
    var endText by remember { mutableStateOf("10:00") }
    var name by remember { mutableStateOf("") }
    var subtasks by remember { mutableStateOf<List<Subtask>>(emptyList()) }
    var selectedSubtaskId by remember { mutableStateOf<Int?>(null) }
    var selectedWeekdays by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var seriesEndText by remember { mutableStateOf("") }

    LaunchedEffect(selectedProjectId, serverUrl) {
        val projectId = selectedProjectId
        subtasks = if (projectId == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                runCatching { ApiFactory.listSubtasks(serverUrl, accessClientId, accessClientSecret, projectId) }
            }.getOrDefault(emptyList())
        }
        if (subtasks.none { it.id == selectedSubtaskId }) selectedSubtaskId = null
    }

    val startTime = runCatching { LocalTime.parse(startText) }.getOrNull()
    val endTime = runCatching { LocalTime.parse(endText) }.getOrNull()
    val seriesEndDate = seriesEndText.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val seriesEndValid = seriesEndText.isBlank() || seriesEndDate != null
    val isValid = selectedProjectId != null && startTime != null && endTime != null &&
        endTime > startTime && seriesEndValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Запланировать") },
        text = {
            Column {
                ProjectPicker(projects, selectedProjectId, onSelect = { selectedProjectId = it })
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название") },
                    placeholder = { Text("Необязательно") },
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        modifier = Modifier.fillMaxWidth(0.5f),
                        label = { Text("Начало") },
                        placeholder = { Text("09:00") },
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Конец") },
                        placeholder = { Text("10:00") },
                    )
                }
                if (subtasks.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    SubtaskPicker(subtasks, selectedSubtaskId, onSelect = { selectedSubtaskId = it })
                }
                Spacer(Modifier.height(16.dp))
                RepeatPicker(
                    selectedWeekdays = selectedWeekdays,
                    onWeekdaysChange = { selectedWeekdays = it },
                    seriesEndText = seriesEndText,
                    onSeriesEndChange = { seriesEndText = it },
                )
                if (errorMessage.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(errorMessage, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedWeekdays.isEmpty()) {
                        onConfirm(selectedProjectId!!, startTime!!, endTime!!, name.ifBlank { null }, selectedSubtaskId)
                    } else {
                        onConfirmRecurring(
                            selectedProjectId!!, startTime!!, endTime!!, name.ifBlank { null }, selectedSubtaskId,
                            selectedWeekdays, seriesEndDate,
                        )
                    }
                },
                enabled = isValid,
            ) {
                Text(if (selectedWeekdays.isEmpty()) "Запланировать" else "Добавить повтор")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

private val PLAN_DATE_FORMAT = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
private val PLAN_TIME_FORMAT = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun PlanEntryFields(
    projects: List<Project>,
    projectId: Int?,
    onProjectSelect: (Int) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    dateText: String,
    onDateChange: (String) -> Unit,
    startText: String,
    onStartChange: (String) -> Unit,
    endText: String,
    onEndChange: (String) -> Unit,
    subtasks: List<Subtask>,
    subtaskId: Int?,
    onSubtaskSelect: (Int?) -> Unit,
    errorMessage: String,
) {
    Column {
        ProjectPicker(projects, projectId, onSelect = onProjectSelect)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Название") },
            placeholder = { Text("Необязательно") },
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = dateText,
            onValueChange = onDateChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Дата") },
            placeholder = { Text("2026-08-01") },
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = startText,
                onValueChange = onStartChange,
                modifier = Modifier.fillMaxWidth(0.5f),
                label = { Text("Начало") },
                placeholder = { Text("09:00") },
            )
            OutlinedTextField(
                value = endText,
                onValueChange = onEndChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Конец") },
                placeholder = { Text("10:00") },
            )
        }
        if (subtasks.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SubtaskPicker(subtasks, subtaskId, onSelect = onSubtaskSelect)
        }
        if (errorMessage.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Fetches `projectId`'s subtasks, refetching whenever the project changes -
 * shared by [PlanEntryEditDialog] and [DynamicEntryEditDialog]. Clears the
 * current subtask selection if it no longer belongs to the newly-loaded list
 * (e.g. the user switched to a different project). */
@Composable
private fun rememberProjectSubtasks(
    projectId: Int?,
    serverUrl: String,
    accessClientId: String,
    accessClientSecret: String,
    currentSubtaskId: Int?,
    onSubtaskInvalid: () -> Unit,
): List<Subtask> {
    var subtasks by remember { mutableStateOf<List<Subtask>>(emptyList()) }
    LaunchedEffect(projectId, serverUrl) {
        subtasks = if (projectId == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                runCatching { ApiFactory.listSubtasks(serverUrl, accessClientId, accessClientSecret, projectId) }
            }.getOrDefault(emptyList())
        }
        if (currentSubtaskId != null && subtasks.none { it.id == currentSubtaskId }) onSubtaskInvalid()
    }
    return subtasks
}

/**
 * Edits a Static Plan entry directly (chapter 5.7) - unlike rescheduling via a
 * PlanChange, this mutates the record itself (a data-entry correction, not a
 * real-world reschedule).
 */
@Composable
fun PlanEntryEditDialog(
    entry: PlanEntry,
    projects: List<Project>,
    errorMessage: String,
    zone: ZoneId = ZoneId.systemDefault(),
    serverUrl: String,
    accessClientId: String = "",
    accessClientSecret: String = "",
    onDismiss: () -> Unit,
    onSave: (projectId: Int, start: Instant, end: Instant, name: String, subtaskId: Int?) -> Unit,
    onRequestDelete: () -> Unit,
) {
    var projectId by remember(entry.id) { mutableStateOf<Int?>(entry.project_id) }
    var name by remember(entry.id) { mutableStateOf(entry.name ?: "") }
    var subtaskId by remember(entry.id) { mutableStateOf(entry.subtask_id) }
    val startZoned = remember(entry.id) { Instant.parse(entry.start_time).atZone(zone) }
    val endZoned = remember(entry.id) { Instant.parse(entry.end_time).atZone(zone) }
    var dateText by remember(entry.id) { mutableStateOf(PLAN_DATE_FORMAT.format(startZoned.toLocalDate())) }
    var startText by remember(entry.id) { mutableStateOf(PLAN_TIME_FORMAT.format(startZoned.toLocalTime())) }
    var endText by remember(entry.id) { mutableStateOf(PLAN_TIME_FORMAT.format(endZoned.toLocalTime())) }

    val subtasks = rememberProjectSubtasks(
        projectId, serverUrl, accessClientId, accessClientSecret, subtaskId,
        onSubtaskInvalid = { subtaskId = null },
    )

    val date = runCatching { LocalDate.parse(dateText, PLAN_DATE_FORMAT) }.getOrNull()
    val startTime = runCatching { LocalTime.parse(startText, PLAN_TIME_FORMAT) }.getOrNull()
    val endTime = runCatching { LocalTime.parse(endText, PLAN_TIME_FORMAT) }.getOrNull()
    val isValid = projectId != null && date != null && startTime != null && endTime != null && endTime > startTime

    val isDirty = projectId != entry.project_id ||
        name != (entry.name ?: "") ||
        subtaskId != entry.subtask_id ||
        dateText != PLAN_DATE_FORMAT.format(startZoned.toLocalDate()) ||
        startText != PLAN_TIME_FORMAT.format(startZoned.toLocalTime()) ||
        endText != PLAN_TIME_FORMAT.format(endZoned.toLocalTime())
    var showDiscardConfirm by remember(entry.id) { mutableStateOf(false) }
    val requestDismiss = { if (isDirty) showDiscardConfirm = true else onDismiss() }

    if (showDiscardConfirm) {
        DiscardChangesDialog(onDiscard = onDismiss, onKeepEditing = { showDiscardConfirm = false })
    }

    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text("Изменить план") },
        text = {
            PlanEntryFields(
                projects = projects,
                projectId = projectId,
                onProjectSelect = { projectId = it },
                name = name,
                onNameChange = { name = it },
                dateText = dateText,
                onDateChange = { dateText = it },
                startText = startText,
                onStartChange = { startText = it },
                endText = endText,
                onEndChange = { endText = it },
                subtasks = subtasks,
                subtaskId = subtaskId,
                onSubtaskSelect = { subtaskId = it },
                errorMessage = errorMessage,
            )
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    onSave(
                        projectId!!,
                        date!!.atTime(startTime!!).atZone(zone).toInstant(),
                        date.atTime(endTime!!).atZone(zone).toInstant(),
                        name.trim(),
                        subtaskId,
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRequestDelete) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = requestDismiss) { Text("Отмена") }
            }
        },
    )
}

/**
 * Edits a Dynamic Plan entry (chapter 5.8). A start/end change is a real-world
 * reschedule, so it creates a PlanChange (move) against the underlying Static
 * entry rather than mutating it; project/name changes correct the Static
 * entry itself (chapter 4.7 - color is project identity, not a schedule
 * fact). Deleting creates a PlanChange (cancel), which per 5.9 never touches
 * the Static record.
 */
@Composable
fun DynamicEntryEditDialog(
    entry: DynamicPlanEntry,
    projects: List<Project>,
    errorMessage: String,
    zone: ZoneId = ZoneId.systemDefault(),
    serverUrl: String,
    accessClientId: String = "",
    accessClientSecret: String = "",
    onDismiss: () -> Unit,
    onSave: (projectId: Int, start: Instant, end: Instant, name: String, subtaskId: Int?) -> Unit,
    onRequestDelete: () -> Unit,
) {
    var projectId by remember(entry.id) { mutableStateOf<Int?>(entry.project_id) }
    var name by remember(entry.id) { mutableStateOf(entry.name ?: "") }
    var subtaskId by remember(entry.id) { mutableStateOf(entry.subtask_id) }
    val startZoned = remember(entry.id) { Instant.parse(entry.start_time).atZone(zone) }
    val endZoned = remember(entry.id) { Instant.parse(entry.end_time).atZone(zone) }
    var dateText by remember(entry.id) { mutableStateOf(PLAN_DATE_FORMAT.format(startZoned.toLocalDate())) }
    var startText by remember(entry.id) { mutableStateOf(PLAN_TIME_FORMAT.format(startZoned.toLocalTime())) }
    var endText by remember(entry.id) { mutableStateOf(PLAN_TIME_FORMAT.format(endZoned.toLocalTime())) }

    val subtasks = rememberProjectSubtasks(
        projectId, serverUrl, accessClientId, accessClientSecret, subtaskId,
        onSubtaskInvalid = { subtaskId = null },
    )

    val date = runCatching { LocalDate.parse(dateText, PLAN_DATE_FORMAT) }.getOrNull()
    val startTime = runCatching { LocalTime.parse(startText, PLAN_TIME_FORMAT) }.getOrNull()
    val endTime = runCatching { LocalTime.parse(endText, PLAN_TIME_FORMAT) }.getOrNull()
    val isValid = projectId != null && date != null && startTime != null && endTime != null && endTime > startTime

    val isDirty = projectId != entry.project_id ||
        name != (entry.name ?: "") ||
        subtaskId != entry.subtask_id ||
        dateText != PLAN_DATE_FORMAT.format(startZoned.toLocalDate()) ||
        startText != PLAN_TIME_FORMAT.format(startZoned.toLocalTime()) ||
        endText != PLAN_TIME_FORMAT.format(endZoned.toLocalTime())
    var showDiscardConfirm by remember(entry.id) { mutableStateOf(false) }
    val requestDismiss = { if (isDirty) showDiscardConfirm = true else onDismiss() }

    if (showDiscardConfirm) {
        DiscardChangesDialog(onDiscard = onDismiss, onKeepEditing = { showDiscardConfirm = false })
    }

    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text("Изменить (Dynamic)") },
        text = {
            PlanEntryFields(
                projects = projects,
                projectId = projectId,
                onProjectSelect = { projectId = it },
                name = name,
                onNameChange = { name = it },
                dateText = dateText,
                onDateChange = { dateText = it },
                startText = startText,
                onStartChange = { startText = it },
                endText = endText,
                onEndChange = { endText = it },
                subtasks = subtasks,
                subtaskId = subtaskId,
                onSubtaskSelect = { subtaskId = it },
                errorMessage = errorMessage,
            )
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    onSave(
                        projectId!!,
                        date!!.atTime(startTime!!).atZone(zone).toInstant(),
                        date.atTime(endTime!!).atZone(zone).toInstant(),
                        name.trim(),
                        subtaskId,
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRequestDelete) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = requestDismiss) { Text("Отмена") }
            }
        },
    )
}

/**
 * Retroactively logs a Timeline interval - a start/end Event pair sharing one label - for an
 * arbitrary date (chapter: calendar "+" button). Unlike the live "Play"/"Stop" buttons, which
 * always use "now", this always requires an explicit date and both times up front. Both events
 * are created with the same label so the calendar never shows a mismatched name at start vs
 * finish (see the label fix on endProject/stopProject).
 */
@Composable
fun TimelineFormDialog(
    projects: List<Project>,
    initialDate: LocalDate,
    errorMessage: String,
    onDismiss: () -> Unit,
    onConfirm: (
        projectId: Int,
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
        name: String,
    ) -> Unit,
) {
    var selectedProjectId by remember { mutableStateOf(projects.firstOrNull()?.id) }
    var name by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(PLAN_DATE_FORMAT.format(initialDate)) }
    val now = remember { LocalTime.now() }
    var startText by remember { mutableStateOf(PLAN_TIME_FORMAT.format(now)) }
    var endText by remember { mutableStateOf(PLAN_TIME_FORMAT.format(now.plusHours(1))) }

    val date = runCatching { LocalDate.parse(dateText, PLAN_DATE_FORMAT) }.getOrNull()
    val startTime = runCatching { LocalTime.parse(startText, PLAN_TIME_FORMAT) }.getOrNull()
    val endTime = runCatching { LocalTime.parse(endText, PLAN_TIME_FORMAT) }.getOrNull()
    val isValid = selectedProjectId != null && date != null && startTime != null && endTime != null &&
        endTime > startTime && name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить таймлайн") },
        text = {
            Column {
                ProjectPicker(projects, selectedProjectId, onSelect = { selectedProjectId = it })
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название") },
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Дата") },
                    placeholder = { Text("2026-08-01") },
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        modifier = Modifier.fillMaxWidth(0.5f),
                        label = { Text("Начало") },
                        placeholder = { Text("09:00") },
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Конец") },
                        placeholder = { Text("10:00") },
                    )
                }
                if (errorMessage.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(errorMessage, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(selectedProjectId!!, date!!, startTime!!, endTime!!, name.trim())
                },
                enabled = isValid,
            ) { Text("Добавить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/**
 * Retroactively logs an Instant event for an arbitrary date/time (chapter: calendar "+" button).
 * Unlike the live "Instant" button on Dashboard/Projects, which always uses "now", this always
 * requires an explicit date and time up front.
 */
@Composable
fun InstantFormDialog(
    projects: List<Project>,
    initialDate: LocalDate,
    errorMessage: String,
    onDismiss: () -> Unit,
    onConfirm: (projectId: Int, date: LocalDate, time: LocalTime, name: String?) -> Unit,
) {
    var selectedProjectId by remember { mutableStateOf(projects.firstOrNull()?.id) }
    var name by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(PLAN_DATE_FORMAT.format(initialDate)) }
    var timeText by remember { mutableStateOf(PLAN_TIME_FORMAT.format(LocalTime.now())) }

    val date = runCatching { LocalDate.parse(dateText, PLAN_DATE_FORMAT) }.getOrNull()
    val time = runCatching { LocalTime.parse(timeText, PLAN_TIME_FORMAT) }.getOrNull()
    val isValid = selectedProjectId != null && date != null && time != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить мгновенное") },
        text = {
            Column {
                ProjectPicker(projects, selectedProjectId, onSelect = { selectedProjectId = it })
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название") },
                    placeholder = { Text("Необязательно") },
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Дата") },
                    placeholder = { Text("2026-08-01") },
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Время") },
                    placeholder = { Text("09:00") },
                )
                if (errorMessage.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(errorMessage, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedProjectId!!, date!!, time!!, name.ifBlank { null }) },
                enabled = isValid,
            ) { Text("Добавить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/**
 * Google-Calendar-style "this / this and following / all" scope choice (chapter: recurring
 * plans) - shown before actually saving/deleting an edit on a PlanEntry that belongs to a
 * RecurringPlan, since which occurrences that edit/delete should apply to isn't otherwise
 * unambiguous.
 */
@Composable
fun RecurrenceScopeDialog(
    title: String,
    onDismiss: () -> Unit,
    onThisOnly: () -> Unit,
    onThisAndFollowing: () -> Unit,
    onAll: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TextButton(onClick = onThisOnly, modifier = Modifier.fillMaxWidth()) {
                    Text("Только этот день", modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onThisAndFollowing, modifier = Modifier.fillMaxWidth()) {
                    Text("Этот и все следующие", modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onAll, modifier = Modifier.fillMaxWidth()) {
                    Text("Все", modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
