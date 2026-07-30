package com.lifeos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import com.lifeos.app.data.Event
import com.lifeos.app.data.Project
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val EVENT_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE
private val EVENT_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Edits a single Timeline or Instant event (chapter 5.4/5.6 - both are just
 * `Event` rows, editable via the same fields). Save goes through the server's
 * `/correct` endpoint (chapter 3.7 - events are immutable historical facts;
 * an edit inserts a replacement row rather than mutating this one).
 */
@Composable
fun EventEditDialog(
    event: Event,
    projects: List<Project>,
    errorMessage: String,
    zone: ZoneId = ZoneId.systemDefault(),
    onDismiss: () -> Unit,
    onSave: (projectId: Int, occurredAt: Instant, label: String) -> Unit,
    onRequestDelete: () -> Unit,
) {
    var projectId by remember(event.id) { mutableStateOf<Int?>(event.project_id) }
    var label by remember(event.id) { mutableStateOf(event.label ?: "") }
    val zoned = remember(event.id) { Instant.parse(event.occurred_at).atZone(zone) }
    var dateText by remember(event.id) { mutableStateOf(EVENT_DATE_FORMAT.format(zoned.toLocalDate())) }
    var timeText by remember(event.id) { mutableStateOf(EVENT_TIME_FORMAT.format(zoned.toLocalTime())) }

    val date = runCatching { LocalDate.parse(dateText, EVENT_DATE_FORMAT) }.getOrNull()
    val time = runCatching { LocalTime.parse(timeText, EVENT_TIME_FORMAT) }.getOrNull()
    val isValid = projectId != null && date != null && time != null

    val isDirty = projectId != event.project_id ||
        label != (event.label ?: "") ||
        dateText != EVENT_DATE_FORMAT.format(zoned.toLocalDate()) ||
        timeText != EVENT_TIME_FORMAT.format(zoned.toLocalTime())
    var showDiscardConfirm by remember(event.id) { mutableStateOf(false) }
    val requestDismiss = { if (isDirty) showDiscardConfirm = true else onDismiss() }

    if (showDiscardConfirm) {
        DiscardChangesDialog(onDiscard = onDismiss, onKeepEditing = { showDiscardConfirm = false })
    }

    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text(if (event.type == "instant") "Изменить отметку" else "Изменить событие") },
        text = {
            Column {
                ProjectPicker(projects, projectId, onSelect = { projectId = it })
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
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
                enabled = isValid,
                onClick = {
                    onSave(
                        projectId!!,
                        date!!.atTime(time!!).atZone(zone).toInstant(),
                        label.trim(),
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRequestDelete) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = requestDismiss) { Text("Отмена") }
            }
        },
    )
}

/** Closing an edit dialog with unsaved changes asks for confirmation first (chapter 5.3). */
@Composable
fun DiscardChangesDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        text = { Text("Отменить несохранённые изменения?") },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text("Отменить изменения", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) { Text("Продолжить редактирование") }
        },
    )
}

/** Generic one-action confirmation, reused for delete confirmations across Day Summary. */
@Composable
fun ConfirmDeleteDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/**
 * The Dashboard's Instant button (chapter: dashboard) - project, name, and
 * time, with time pre-filled to the moment the button was pressed but still
 * editable (e.g. logging something a few minutes after it actually happened).
 */
@Composable
fun DashboardInstantDialog(
    projects: List<Project>,
    initialTime: LocalTime,
    errorMessage: String,
    onDismiss: () -> Unit,
    onConfirm: (projectId: Int, time: LocalTime, name: String) -> Unit,
) {
    var projectId by remember { mutableStateOf(projects.firstOrNull()?.id) }
    var name by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf(EVENT_TIME_FORMAT.format(initialTime)) }

    val time = runCatching { LocalTime.parse(timeText, EVENT_TIME_FORMAT) }.getOrNull()
    val isValid = projectId != null && time != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Инстант") },
        text = {
            Column {
                ProjectPicker(projects, projectId, onSelect = { projectId = it })
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
                enabled = isValid,
                onClick = { onConfirm(projectId!!, time!!, name.trim()) },
            ) { Text("Отметить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
