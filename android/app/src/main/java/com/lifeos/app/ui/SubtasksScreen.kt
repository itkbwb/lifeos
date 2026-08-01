package com.lifeos.app.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.Project
import com.lifeos.app.data.Subtask
import com.lifeos.app.ui.theme.ProjectColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Checklist for one project (chapter: project subtasks) - a dedicated screen
 * (not inline on the project tile) so a project with a long list doesn't
 * balloon the Projects list itself. Completed items stay in place,
 * struck-through, rather than sorting to the bottom or disappearing - the
 * point is to see what's left AND what's done without an extra toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtasksScreen(
    project: Project,
    serverUrl: String,
    accessClientId: String,
    accessClientSecret: String,
    onBack: () -> Unit,
) {
    var subtasks by remember { mutableStateOf<List<Subtask>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(project.id, serverUrl) {
        withContext(Dispatchers.IO) {
            runCatching { ApiFactory.listSubtasks(serverUrl, accessClientId, accessClientSecret, project.id) }
        }.fold(
            onSuccess = { subtasks = it; loaded = true },
            onFailure = { error = "Не удалось загрузить подзадачи"; loaded = true },
        )
    }

    fun addSubtask() {
        val title = newTitle.trim()
        if (title.isBlank()) return
        newTitle = ""
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ApiFactory.createSubtask(serverUrl, accessClientId, accessClientSecret, project.id, title) }
            }.fold(
                onSuccess = { created -> subtasks = subtasks + created },
                onFailure = { error = "Не удалось добавить подзадачу" },
            )
        }
    }

    fun toggleDone(subtask: Subtask) {
        val previous = subtasks
        subtasks = subtasks.map { if (it.id == subtask.id) it.copy(done = !it.done) else it }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    ApiFactory.updateSubtask(
                        serverUrl, accessClientId, accessClientSecret,
                        id = subtask.id, done = !subtask.done,
                    )
                }
            }.onFailure {
                error = "Не удалось сохранить"
                subtasks = previous
            }
        }
    }

    fun deleteSubtask(subtask: Subtask) {
        val previous = subtasks
        subtasks = subtasks.filter { it.id != subtask.id }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ApiFactory.deleteSubtask(serverUrl, accessClientId, accessClientSecret, subtask.id) }
            }.onFailure {
                error = "Не удалось удалить"
                subtasks = previous
            }
        }
    }

    fun persistOrder(ordered: List<Subtask>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    ApiFactory.reorderSubtasks(
                        serverUrl, accessClientId, accessClientSecret,
                        projectId = project.id, orderedIds = ordered.map { it.id },
                    )
                }
            }.fold(
                // Reconciles with the server's canonical list rather than trusting the
                // optimistic client-built order as final - cheap correctness net in case
                // a concurrent edit (e.g. a toggle mid-drag) raced the reorder call.
                onSuccess = { subtasks = it },
                onFailure = { error = "Не удалось сохранить порядок" },
            )
        }
    }

    val accentColor = ProjectColors.colorFor(project.color)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (error.isNotBlank()) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when {
                !loaded -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                subtasks.isEmpty() -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Нет подзадач", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> ReorderableSubtaskList(
                    subtasks = subtasks,
                    accentColor = accentColor,
                    onReorder = { newOrder ->
                        subtasks = newOrder
                        persistOrder(newOrder)
                    },
                    onToggle = ::toggleDone,
                    onDelete = ::deleteSubtask,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    placeholder = { Text("Новая подзадача") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { addSubtask() }, enabled = newTitle.isNotBlank()) {
                    Text("Добавить")
                }
            }
        }
    }
}

/**
 * A drag-handle-driven reorderable list (long-press the handle, drag up/down)
 * - not the whole row, so dragging never fights with tapping the checkbox or
 * the title. Reordering is optimistic: [localOrder] updates live as items
 * cross each other's midpoint, [onReorder] (which persists to the server)
 * only fires once, on drag release.
 */
@Composable
private fun ReorderableSubtaskList(
    subtasks: List<Subtask>,
    accentColor: Color,
    onReorder: (List<Subtask>) -> Unit,
    onToggle: (Subtask) -> Unit,
    onDelete: (Subtask) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val itemHeight = 56.dp
    val itemHeightPx = with(density) { itemHeight.toPx() }

    var localOrder by remember(subtasks) { mutableStateOf(subtasks) }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        itemsIndexed(localOrder, key = { _, item -> item.id }) { index, subtask ->
            val isDragged = index == draggedIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = subtask.done,
                    onCheckedChange = { onToggle(subtask) },
                    colors = CheckboxDefaults.colors(checkedColor = accentColor),
                )
                Text(
                    text = subtask.title,
                    style = MaterialTheme.typography.bodyLarge.let {
                        if (subtask.done) it.copy(textDecoration = TextDecoration.LineThrough) else it
                    },
                    color = if (subtask.done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                IconButton(onClick = { onDelete(subtask) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Удалить")
                }
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "Перетащить",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .pointerInput(subtask.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggedIndex = localOrder.indexOfFirst { it.id == subtask.id }
                                    dragOffset = 0f
                                },
                                onDragEnd = {
                                    draggedIndex = null
                                    dragOffset = 0f
                                    onReorder(localOrder)
                                },
                                onDragCancel = {
                                    draggedIndex = null
                                    dragOffset = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    var idx = draggedIndex ?: return@detectDragGesturesAfterLongPress
                                    dragOffset += dragAmount.y
                                    while (dragOffset > itemHeightPx / 2 && idx + 1 < localOrder.size) {
                                        localOrder = localOrder.toMutableList().apply { add(idx, removeAt(idx + 1)) }
                                        idx += 1
                                        dragOffset -= itemHeightPx
                                    }
                                    while (dragOffset < -itemHeightPx / 2 && idx - 1 >= 0) {
                                        localOrder = localOrder.toMutableList().apply { add(idx, removeAt(idx - 1)) }
                                        idx -= 1
                                        dragOffset += itemHeightPx
                                    }
                                    draggedIndex = idx
                                },
                            )
                        },
                )
            }
        }
    }
}
