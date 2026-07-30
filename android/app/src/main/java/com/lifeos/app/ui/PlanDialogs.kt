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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lifeos.app.data.DynamicPlanEntry
import com.lifeos.app.data.PlanEntry
import com.lifeos.app.data.Project
import com.lifeos.app.ui.theme.ProjectColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
internal fun ProjectPicker(projects: List<Project>, selectedId: Int?, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        projects.forEach { project ->
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
 * Creates a Static Plan entry (chapter 4.5/4.6) for a fixed day: pick a project (required -
 * a plan cannot exist without one) and a start/end time. The entry is immutable once
 * created; rescheduling later goes through a PlanChange, not this dialog.
 */
@Composable
fun StaticPlanFormDialog(
    projects: List<Project>,
    errorMessage: String,
    onDismiss: () -> Unit,
    onConfirm: (projectId: Int, startTime: LocalTime, endTime: LocalTime, name: String?) -> Unit,
) {
    var selectedProjectId by remember { mutableStateOf(projects.firstOrNull()?.id) }
    var startText by remember { mutableStateOf("09:00") }
    var endText by remember { mutableStateOf("10:00") }
    var name by remember { mutableStateOf("") }

    val startTime = runCatching { LocalTime.parse(startText) }.getOrNull()
    val endTime = runCatching { LocalTime.parse(endText) }.getOrNull()
    val isValid = selectedProjectId != null && startTime != null && endTime != null && endTime > startTime

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
                if (errorMessage.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(errorMessage, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedProjectId!!, startTime!!, endTime!!, name.ifBlank { null }) },
                enabled = isValid,
            ) {
                Text("Запланировать")
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
        if (errorMessage.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
    }
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
    onDismiss: () -> Unit,
    onSave: (projectId: Int, start: Instant, end: Instant, name: String) -> Unit,
    onRequestDelete: () -> Unit,
) {
    var projectId by remember(entry.id) { mutableStateOf<Int?>(entry.project_id) }
    var name by remember(entry.id) { mutableStateOf(entry.name ?: "") }
    val startZoned = remember(entry.id) { Instant.parse(entry.start_time).atZone(zone) }
    val endZoned = remember(entry.id) { Instant.parse(entry.end_time).atZone(zone) }
    var dateText by remember(entry.id) { mutableStateOf(PLAN_DATE_FORMAT.format(startZoned.toLocalDate())) }
    var startText by remember(entry.id) { mutableStateOf(PLAN_TIME_FORMAT.format(startZoned.toLocalTime())) }
    var endText by remember(entry.id) { mutableStateOf(PLAN_TIME_FORMAT.format(endZoned.toLocalTime())) }

    val date = runCatching { LocalDate.parse(dateText, PLAN_DATE_FORMAT) }.getOrNull()
    val startTime = runCatching { LocalTime.parse(startText, PLAN_TIME_FORMAT) }.getOrNull()
    val endTime = runCatching { LocalTime.parse(endText, PLAN_TIME_FORMAT) }.getOrNull()
    val isValid = projectId != null && date != null && startTime != null && endTime != null && endTime > startTime

    val isDirty = projectId != entry.project_id ||
        name != (entry.name ?: "") ||
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
    onDismiss: () -> Unit,
    onSave: (projectId: Int, start: Instant, end: Instant, name: String) -> Unit,
    onRequestDelete: () -> Unit,
) {
    var projectId by remember(entry.id) { mutableStateOf<Int?>(entry.project_id) }
    var name by remember(entry.id) { mutableStateOf(entry.name ?: "") }
    val startZoned = remember(entry.id) { Instant.parse(entry.start_time).atZone(zone) }
    val endZoned = remember(entry.id) { Instant.parse(entry.end_time).atZone(zone) }
    var dateText by remember(entry.id) { mutableStateOf(PLAN_DATE_FORMAT.format(startZoned.toLocalDate())) }
    var startText by remember(entry.id) { mutableStateOf(PLAN_TIME_FORMAT.format(startZoned.toLocalTime())) }
    var endText by remember(entry.id) { mutableStateOf(PLAN_TIME_FORMAT.format(endZoned.toLocalTime())) }

    val date = runCatching { LocalDate.parse(dateText, PLAN_DATE_FORMAT) }.getOrNull()
    val startTime = runCatching { LocalTime.parse(startText, PLAN_TIME_FORMAT) }.getOrNull()
    val endTime = runCatching { LocalTime.parse(endText, PLAN_TIME_FORMAT) }.getOrNull()
    val isValid = projectId != null && date != null && startTime != null && endTime != null && endTime > startTime

    val isDirty = projectId != entry.project_id ||
        name != (entry.name ?: "") ||
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
