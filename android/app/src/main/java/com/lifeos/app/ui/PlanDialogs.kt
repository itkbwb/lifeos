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
import com.lifeos.app.data.Project
import com.lifeos.app.ui.theme.ProjectColors
import java.time.LocalTime

@Composable
private fun ProjectPicker(projects: List<Project>, selectedId: Int?, onSelect: (Int) -> Unit) {
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
    onConfirm: (projectId: Int, startTime: LocalTime, endTime: LocalTime) -> Unit,
) {
    var selectedProjectId by remember { mutableStateOf(projects.firstOrNull()?.id) }
    var startText by remember { mutableStateOf("09:00") }
    var endText by remember { mutableStateOf("10:00") }

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
                onClick = { onConfirm(selectedProjectId!!, startTime!!, endTime!!) },
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
