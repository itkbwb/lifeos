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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.Project
import com.lifeos.app.ui.theme.ProjectColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun ColorPicker(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProjectColors.palette.forEach { (id, color) ->
            val isSelected = id == selected
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(id) },
            )
        }
    }
}

@Composable
fun ProjectFormDialog(
    title: String,
    initialName: String,
    initialColor: String,
    confirmLabel: String,
    errorMessage: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Название") },
                )
                Spacer(Modifier.height(16.dp))
                ColorPicker(selected = color, onSelect = { color = it })
                if (errorMessage.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(errorMessage, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, color) }, enabled = name.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
fun ProjectEditDialog(
    project: Project,
    errorMessage: String,
    serverUrl: String,
    accessClientId: String = "",
    accessClientSecret: String = "",
    onDismiss: () -> Unit,
    onSave: (name: String, color: String) -> Unit,
    onRequestDelete: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    var name by remember(project.id) { mutableStateOf(project.name) }
    var color by remember(project.id) { mutableStateOf(project.color) }
    // Independent of the name/color "Сохранить" flow - notes save immediately
    // on their own, without needing this dialog's own confirm button.
    var notes by remember(project.id) { mutableStateOf(project.notes ?: "") }
    var showNotesEditor by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showNotesEditor) {
        NotesEditorDialog(
            title = "Заметка · ${project.name}",
            initialNotes = notes,
            onDismiss = { showNotesEditor = false },
            onSave = { newNotes ->
                notes = newNotes
                showNotesEditor = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.updateProject(
                                serverUrl, accessClientId, accessClientSecret,
                                id = project.id, notes = newNotes,
                            )
                        }
                    }
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить проект") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Название") },
                )
                Spacer(Modifier.height(16.dp))
                ColorPicker(selected = color, onSelect = { color = it })
                if (errorMessage.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(errorMessage, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showNotesEditor = true }) {
                        Text(if (notes.isBlank()) "Заметка" else "Заметка ✓")
                    }
                    TextButton(onClick = onToggleArchive) {
                        Text(if (project.archived) "Восстановить" else "Архивировать")
                    }
                    TextButton(onClick = onRequestDelete) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, color) }, enabled = name.isNotBlank()) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
fun StartNameDialog(
    projectName: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Начать «$projectName»") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Название события") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("Начать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
fun InstantNameDialog(
    projectName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Отметить «$projectName»") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Название (необязательно)") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("Отметить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
fun StartConflictDialog(
    activeProjectName: String,
    newProjectName: String,
    onCancel: () -> Unit,
    onFinishOnly: () -> Unit,
    onFinishAndStart: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Уже активен «$activeProjectName»") },
        text = {
            Column {
                Text("Нельзя начать «$newProjectName», пока идёт другой проект.")
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onFinishOnly) {
                    Text("Завершить «$activeProjectName»")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onFinishAndStart) {
                Text("Завершить и начать «$newProjectName»")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Отмена") }
        },
    )
}

@Composable
fun DeleteProjectConfirmDialog(
    projectName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text("Удалить проект «$projectName»?") },
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

private sealed class RestoreConflictStep {
    object Choose : RestoreConflictStep()
    object Rename : RestoreConflictStep()
    object ConfirmMerge : RestoreConflictStep()
    object ConfirmReplace : RestoreConflictStep()
}

/**
 * Shown when restoring an archived project fails because an active project
 * already has that name ([com.lifeos.app.data.ProjectNameConflictException])
 * - a small internal wizard (not three separate dialogs the caller juggles)
 * offering rename / merge / replace, per chapter: archive restore collision.
 */
@Composable
fun RestoreConflictDialog(
    archivedProjectName: String,
    conflictingProjectName: String,
    onCancel: () -> Unit,
    onRename: (newName: String) -> Unit,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
) {
    var step by remember { mutableStateOf<RestoreConflictStep>(RestoreConflictStep.Choose) }

    when (step) {
        RestoreConflictStep.Choose -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Уже есть активный «$conflictingProjectName»") },
            text = { Text("Нельзя восстановить «$archivedProjectName» под этим именем, пока есть активный проект с таким же названием.") },
            confirmButton = {
                TextButton(onClick = { step = RestoreConflictStep.Rename }) { Text("Переименовать") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { step = RestoreConflictStep.ConfirmMerge }) { Text("Объединить") }
                    TextButton(onClick = { step = RestoreConflictStep.ConfirmReplace }) { Text("Заменить") }
                    TextButton(onClick = onCancel) { Text("Отмена") }
                }
            },
        )

        RestoreConflictStep.Rename -> {
            var name by remember { mutableStateOf(archivedProjectName) }
            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text("Новое имя") },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Название") },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { onRename(name) },
                        enabled = name.isNotBlank() && name != conflictingProjectName,
                    ) { Text("Восстановить") }
                },
                dismissButton = {
                    TextButton(onClick = onCancel) { Text("Отмена") }
                },
            )
        }

        RestoreConflictStep.ConfirmMerge -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Объединить с «$conflictingProjectName»?") },
            text = { Text("Все задачи и события «$archivedProjectName» перейдут в «$conflictingProjectName». Архивный проект «$archivedProjectName» исчезнет.") },
            confirmButton = {
                TextButton(onClick = onMerge) { Text("Объединить") }
            },
            dismissButton = {
                TextButton(onClick = onCancel) { Text("Отмена") }
            },
        )

        RestoreConflictStep.ConfirmReplace -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Заменить «$conflictingProjectName»?") },
            text = { Text("«$conflictingProjectName» будет заархивирован, а «$archivedProjectName» восстановлен под тем же именем.") },
            confirmButton = {
                TextButton(onClick = onReplace) {
                    Text("Заменить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onCancel) { Text("Отмена") }
            },
        )
    }
}

/**
 * Shown when deleting a project fails because it has Events or Plan entries
 * ([com.lifeos.app.data.ProjectHasRecordsException]) - offers archiving
 * (keeps the project and all its records, just hides it from active
 * pickers) as an alternative to force-deleting everything.
 */
@Composable
fun DeleteOrArchiveDialog(
    projectName: String,
    onArchive: () -> Unit,
    onDeleteAll: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("У «$projectName» есть события или планы") },
        text = { Text("Удалить проект вместе со всеми его событиями и планами, или архивировать его — тогда всё сохранится, но проект уйдёт из списка активных.") },
        confirmButton = {
            TextButton(onClick = onDeleteAll) {
                Text("Удалить всё", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onArchive) { Text("Архивировать") }
                TextButton(onClick = onCancel) { Text("Отмена") }
            }
        },
    )
}
