package com.lifeos.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lifeos.app.data.ActiveProjectConflictException
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.Project
import com.lifeos.app.data.Subtask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class ChecklistSubDialog {
    object None : ChecklistSubDialog()
    data class Conflict(
        val child: Subtask,
        val activeProjectId: Int,
        val activeEventId: Int,
        val activeProjectName: String,
    ) : ChecklistSubDialog()
}

/**
 * Full-screen "run" view for an is_checklist subtask (chapter: checklist
 * entity) - checking an item creates an Instant event named after it;
 * unchecking deletes that item's own Instant event. The first check in the
 * whole checklist also starts a project session named after the checklist
 * (reusing the exact 409 conflict shape/dialog the "Начать" button uses on
 * ProjectsScreen); the last check, or "Завершить", best-effort stops it.
 * "Завершить" resets every item to unchecked WITHOUT deleting any Instant
 * events already created, then closes this dialog.
 */
@Composable
fun ChecklistRunDialog(
    project: Project,
    container: Subtask,
    initialChildren: List<Subtask>,
    serverUrl: String,
    accessClientId: String = "",
    accessClientSecret: String = "",
    onDismiss: () -> Unit,
    onChildrenChanged: (List<Subtask>) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ChecklistRunContent(
            project = project,
            container = container,
            initialChildren = initialChildren,
            serverUrl = serverUrl,
            accessClientId = accessClientId,
            accessClientSecret = accessClientSecret,
            onClose = onDismiss,
            onChildrenChanged = onChildrenChanged,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChecklistRunContent(
    project: Project,
    container: Subtask,
    initialChildren: List<Subtask>,
    serverUrl: String,
    accessClientId: String,
    accessClientSecret: String,
    onClose: () -> Unit,
    onChildrenChanged: (List<Subtask>) -> Unit,
) {
    var children by remember(container.id) { mutableStateOf(initialChildren.sortedBy { it.position }) }
    var newTitle by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var subDialog by remember { mutableStateOf<ChecklistSubDialog>(ChecklistSubDialog.None) }
    var finishing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun applyChildren(updated: List<Subtask>) {
        children = updated
        onChildrenChanged(updated)
    }

    fun toggle(child: Subtask, checked: Boolean) {
        val previous = children
        applyChildren(children.map { if (it.id == child.id) it.copy(done = checked) else it })
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ApiFactory.updateSubtask(
                        serverUrl, accessClientId, accessClientSecret,
                        id = child.id, done = checked,
                    )
                }
            }
            result.fold(
                onSuccess = { updated ->
                    applyChildren(children.map { if (it.id == updated.id) updated else it })
                },
                onFailure = { e ->
                    applyChildren(previous)
                    if (e is ActiveProjectConflictException) {
                        val activeName = withContext(Dispatchers.IO) {
                            runCatching { ApiFactory.listProjects(serverUrl, accessClientId, accessClientSecret) }
                                .getOrNull()
                                ?.firstOrNull { it.id == e.activeProjectId }
                                ?.name
                        } ?: "проект"
                        subDialog = ChecklistSubDialog.Conflict(child, e.activeProjectId, e.activeEventId, activeName)
                    } else {
                        error = "Не удалось сохранить"
                    }
                },
            )
        }
    }

    fun addItem(title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    ApiFactory.createSubtask(
                        serverUrl, accessClientId, accessClientSecret,
                        projectId = project.id, title = trimmed, parentId = container.id,
                    )
                }
            }.fold(
                onSuccess = { created -> applyChildren(children + created) },
                onFailure = { error = "Не удалось добавить" },
            )
        }
    }

    fun finish() {
        finishing = true
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    ApiFactory.checklistReset(serverUrl, accessClientId, accessClientSecret, id = container.id)
                }
            }.fold(
                onSuccess = { updated ->
                    applyChildren(updated)
                    onClose()
                },
                onFailure = {
                    finishing = false
                    error = "Не удалось завершить"
                },
            )
        }
    }

    val doneCount = children.count { it.done }
    val accentColor = MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "${container.title} · $doneCount/${children.size}",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                    }
                },
                actions = {
                    TextButton(onClick = { finish() }, enabled = !finishing) { Text("Завершить") }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (error.isNotBlank()) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Box(modifier = Modifier.fillMaxSize().padding(top = if (error.isNotBlank()) 28.dp else 0.dp)) {
                ChecklistItemsColumn(
                    children = children,
                    accentColor = accentColor,
                    onToggle = ::toggle,
                    newTitle = newTitle,
                    onNewTitleChange = { newTitle = it },
                    onAdd = { addItem(newTitle); newTitle = "" },
                )
            }
        }
    }

    when (val sub = subDialog) {
        ChecklistSubDialog.None -> {}
        is ChecklistSubDialog.Conflict -> StartConflictDialog(
            activeProjectName = sub.activeProjectName,
            newProjectName = container.title,
            onCancel = { subDialog = ChecklistSubDialog.None },
            onFinishOnly = {
                subDialog = ChecklistSubDialog.None
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.createEvent(
                                serverUrl, accessClientId, accessClientSecret,
                                projectId = sub.activeProjectId, type = "end",
                            )
                        }
                    }.onFailure { error = "Не удалось завершить сессию" }
                }
            },
            onFinishAndStart = {
                subDialog = ChecklistSubDialog.None
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.createEvent(
                                serverUrl, accessClientId, accessClientSecret,
                                projectId = sub.activeProjectId, type = "end",
                            )
                        }
                    }.onFailure { error = "Не удалось завершить сессию" }
                }
                toggle(sub.child, true)
            },
        )
    }
}

@Composable
private fun ChecklistItemsColumn(
    children: List<Subtask>,
    accentColor: Color,
    onToggle: (Subtask, Boolean) -> Unit,
    newTitle: String,
    onNewTitleChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(bottom = 72.dp)) {
            items(children, key = { it.id }) { child ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = child.done,
                        onCheckedChange = { checked -> onToggle(child, checked) },
                        colors = CheckboxDefaults.colors(checkedColor = accentColor),
                    )
                    Text(
                        text = child.title,
                        style = MaterialTheme.typography.bodyLarge.let {
                            if (child.done) it.copy(textDecoration = TextDecoration.LineThrough) else it
                        },
                        color = if (child.done) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newTitle,
                onValueChange = onNewTitleChange,
                placeholder = { Text("Новый пункт") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onAdd, enabled = newTitle.isNotBlank()) { Text("Добавить") }
        }
    }
}
