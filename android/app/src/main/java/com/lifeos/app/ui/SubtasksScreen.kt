package com.lifeos.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Checklist for one project (chapter: project subtasks / nested subtasks) - a
 * dedicated screen (not inline on the project tile) so a project with a long
 * list doesn't balloon the Projects list itself. Completed items stay in
 * place, struck-through, rather than sorting to the bottom or disappearing -
 * the point is to see what's left AND what's done without an extra toggle.
 *
 * Subtasks nest to unlimited depth via `parent_id`. The flat list from the
 * server is turned into a depth-first-ordered, collapse-aware visible list
 * ([buildVisibleRows]) for rendering; reordering is scoped to one sibling
 * group at a time (drag never crosses a parent boundary), and moving a
 * subtask to a different parent happens via explicit indent/outdent actions
 * rather than cross-level drag.
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
    var collapsedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var addingChildTo by remember { mutableStateOf<Subtask?>(null) }
    var addingChecklistChildTo by remember { mutableStateOf<Subtask?>(null) }
    var editingNotesFor by remember { mutableStateOf<Subtask?>(null) }
    var openChecklistFor by remember { mutableStateOf<Subtask?>(null) }
    var schedulingSubtask by remember { mutableStateOf<Subtask?>(null) }
    var scheduleError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(project.id, serverUrl) {
        withContext(Dispatchers.IO) {
            runCatching { ApiFactory.listSubtasks(serverUrl, accessClientId, accessClientSecret, project.id) }
        }.fold(
            onSuccess = { subtasks = it; loaded = true },
            onFailure = { error = "Не удалось загрузить задачи"; loaded = true },
        )
    }

    fun addSubtask(title: String, parentId: Int?, isChecklist: Boolean = false) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    ApiFactory.createSubtask(
                        serverUrl, accessClientId, accessClientSecret,
                        project.id, trimmed, parentId = parentId, isChecklist = isChecklist,
                    )
                }
            }.fold(
                onSuccess = { created -> subtasks = subtasks + created },
                onFailure = { error = "Не удалось добавить" },
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
        // CASCADE on the server removes the whole subtree - mirror that
        // locally so a parent's now-orphaned children don't briefly flash.
        val previous = subtasks
        val toRemove = generateSequence(setOf(subtask.id)) { ids ->
            val next = subtasks.filter { it.parent_id in ids }.map { it.id }.toSet()
            next.ifEmpty { null }
        }.flatten().toSet()
        subtasks = subtasks.filter { it.id !in toRemove }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ApiFactory.deleteSubtask(serverUrl, accessClientId, accessClientSecret, subtask.id) }
            }.onFailure {
                error = "Не удалось удалить"
                subtasks = previous
            }
        }
    }

    fun reparent(subtask: Subtask, newParentId: Int?) {
        val previous = subtasks
        subtasks = subtasks.map { if (it.id == subtask.id) it.copy(parent_id = newParentId) else it }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    ApiFactory.updateSubtask(
                        serverUrl, accessClientId, accessClientSecret,
                        id = subtask.id, parentId = newParentId, clearParent = newParentId == null,
                    )
                }
            }.onFailure {
                error = "Не удалось переместить"
                subtasks = previous
            }
        }
    }

    fun persistOrder(parentId: Int?, orderedIds: List<Int>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    ApiFactory.reorderSubtasks(
                        serverUrl, accessClientId, accessClientSecret,
                        projectId = project.id, orderedIds = orderedIds, parentId = parentId,
                    )
                }
            }.fold(
                // Reconciles just this sibling group with the server's canonical
                // order rather than trusting the optimistic client-built order -
                // other groups in `subtasks` are left untouched.
                onSuccess = { updatedGroup ->
                    val updatedIds = updatedGroup.map { it.id }.toSet()
                    subtasks = subtasks.filter { it.id !in updatedIds } + updatedGroup
                },
                onFailure = { error = "Не удалось сохранить порядок" },
            )
        }
    }

    val accentColor = ProjectColors.colorFor(project.color)

    if (addingChildTo != null) {
        val parent = addingChildTo!!
        var childTitle by remember(parent.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingChildTo = null },
            title = { Text("Подзадача для «${parent.title}»") },
            text = {
                OutlinedTextField(
                    value = childTitle,
                    onValueChange = { childTitle = it },
                    placeholder = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { addSubtask(childTitle, parent.id); addingChildTo = null },
                    enabled = childTitle.isNotBlank(),
                ) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { addingChildTo = null }) { Text("Отмена") }
            },
        )
    }

    if (addingChecklistChildTo != null) {
        val parent = addingChecklistChildTo!!
        var checklistTitle by remember(parent.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingChecklistChildTo = null },
            title = { Text("Чек-лист для «${parent.title}»") },
            text = {
                OutlinedTextField(
                    value = checklistTitle,
                    onValueChange = { checklistTitle = it },
                    placeholder = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { addSubtask(checklistTitle, parent.id, isChecklist = true); addingChecklistChildTo = null },
                    enabled = checklistTitle.isNotBlank(),
                ) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { addingChecklistChildTo = null }) { Text("Отмена") }
            },
        )
    }

    openChecklistFor?.let { container ->
        ChecklistRunDialog(
            project = project,
            container = container,
            initialChildren = subtasks.filter { it.parent_id == container.id }.sortedBy { it.position },
            serverUrl = serverUrl,
            accessClientId = accessClientId,
            accessClientSecret = accessClientSecret,
            onDismiss = { openChecklistFor = null },
            onChildrenChanged = { updated ->
                val updatedIds = updated.map { it.id }.toSet()
                subtasks = subtasks.filter { it.id !in updatedIds } + updated
            },
        )
    }

    editingNotesFor?.let { target ->
        NotesEditorDialog(
            title = "Заметка · ${target.title}",
            initialNotes = target.notes ?: "",
            onDismiss = { editingNotesFor = null },
            onSave = { newNotes ->
                editingNotesFor = null
                subtasks = subtasks.map { if (it.id == target.id) it.copy(notes = newNotes) else it }
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.updateSubtask(
                                serverUrl, accessClientId, accessClientSecret,
                                id = target.id, notes = newNotes,
                            )
                        }
                    }.onFailure { error = "Не удалось сохранить заметку" }
                }
            },
        )
    }

    schedulingSubtask?.let { target ->
        StaticPlanFormDialog(
            projects = listOf(project),
            errorMessage = scheduleError,
            serverUrl = serverUrl,
            accessClientId = accessClientId,
            accessClientSecret = accessClientSecret,
            initialName = target.title,
            initialSubtaskId = target.id,
            onDismiss = { schedulingSubtask = null; scheduleError = "" },
            onConfirm = { projectId, date, startTime, endTime, name, subtaskId ->
                val zone = ZoneId.systemDefault()
                val startInstant = date.atTime(startTime).atZone(zone).toInstant().toString()
                val endInstant = date.atTime(endTime).atZone(zone).toInstant().toString()
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.createPlanEntry(
                                serverUrl, accessClientId, accessClientSecret,
                                projectId = projectId, startTime = startInstant, endTime = endInstant,
                                name = name, subtaskId = subtaskId,
                            )
                        }
                    }.onSuccess {
                        schedulingSubtask = null
                        scheduleError = ""
                    }.onFailure { scheduleError = "Не удалось запланировать" }
                }
            },
            onConfirmRecurring = { projectId, date, startTime, endTime, name, subtaskId, recurrence ->
                val api = recurrence.toApiParams()
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.createRecurringPlan(
                                serverUrl, accessClientId, accessClientSecret,
                                projectId = projectId,
                                startTimeOfDay = startTime.toString(),
                                endTimeOfDay = endTime.toString(),
                                frequency = api.frequency,
                                interval = api.interval,
                                weekdays = api.weekdays,
                                monthMode = api.monthMode,
                                maxOccurrences = api.maxOccurrences,
                                timezone = ZoneId.systemDefault().id,
                                seriesStartDate = date.toString(),
                                seriesEndDate = api.seriesEndDate,
                                name = name,
                                subtaskId = subtaskId,
                            )
                        }
                    }.onSuccess {
                        schedulingSubtask = null
                        scheduleError = ""
                    }.onFailure { scheduleError = "Не удалось сохранить повтор" }
                }
            },
        )
    }

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
                    Text("Нет задач", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> SubtaskTree(
                    subtasks = subtasks,
                    collapsedIds = collapsedIds,
                    accentColor = accentColor,
                    onToggleCollapse = { id ->
                        collapsedIds = if (id in collapsedIds) collapsedIds - id else collapsedIds + id
                    },
                    onReorder = ::persistOrder,
                    onToggleDone = ::toggleDone,
                    onDelete = ::deleteSubtask,
                    onAddChild = { addingChildTo = it },
                    onAddChecklistChild = { addingChecklistChildTo = it },
                    onOpenChecklist = { openChecklistFor = it },
                    onEditNotes = { editingNotesFor = it },
                    onSchedule = { schedulingSubtask = it; scheduleError = "" },
                    onIndent = { subtask, newParentId -> reparent(subtask, newParentId) },
                    onOutdent = { subtask, newParentId -> reparent(subtask, newParentId) },
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
                    placeholder = { Text("Новая задача") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { addSubtask(newTitle, null, isChecklist = true); newTitle = "" },
                    enabled = newTitle.isNotBlank(),
                ) {
                    Icon(Icons.Filled.Checklist, contentDescription = "Добавить чек-лист")
                }
                Button(
                    onClick = { addSubtask(newTitle, null); newTitle = "" },
                    enabled = newTitle.isNotBlank(),
                ) { Text("Добавить") }
            }
        }
    }
}

private data class VisibleRow(val subtask: Subtask, val depth: Int, val hasChildren: Boolean)

/** Depth-first flattening of the subtask tree, skipping the children of any
 * id in [collapsedIds] - children of the same parent always end up
 * contiguous in the result, which [SubtaskTree]'s drag-reorder relies on to
 * detect sibling-group boundaries. Thin wrapper around [buildVisibleRowsGeneric]
 * - this shape (flat list + parent_id) just needs grouping-by-parent and
 * position-sorting before the shared depth-first walk. */
private fun buildVisibleRows(subtasks: List<Subtask>, collapsedIds: Set<Int>): List<VisibleRow> {
    val childrenByParent = subtasks.groupBy { it.parent_id }
    fun childrenOf(s: Subtask): List<Subtask> = childrenByParent[s.id]?.sortedBy { it.position } ?: emptyList()
    val roots = childrenByParent[null]?.sortedBy { it.position } ?: emptyList()
    return buildVisibleRowsGeneric(roots, ::childrenOf, { it.id }, collapsedIds)
        .map { (s, depth) -> VisibleRow(s, depth, childrenOf(s).isNotEmpty()) }
}

/**
 * Renders the whole tree as one flat, indented [LazyColumn] (rather than
 * nested LazyColumns, which Compose doesn't support well) built from
 * [buildVisibleRows]. Drag-to-reorder (long-press the handle) only swaps
 * adjacent rows that share the same `parent_id`, so a drag can never
 * silently cross into a different parent's children - moving between
 * parents is the explicit indent/outdent menu action instead.
 */
@Composable
private fun SubtaskTree(
    subtasks: List<Subtask>,
    collapsedIds: Set<Int>,
    accentColor: Color,
    onToggleCollapse: (Int) -> Unit,
    onReorder: (parentId: Int?, orderedIds: List<Int>) -> Unit,
    onToggleDone: (Subtask) -> Unit,
    onDelete: (Subtask) -> Unit,
    onAddChild: (Subtask) -> Unit,
    onAddChecklistChild: (Subtask) -> Unit,
    onOpenChecklist: (Subtask) -> Unit,
    onEditNotes: (Subtask) -> Unit,
    onSchedule: (Subtask) -> Unit,
    onIndent: (Subtask, newParentId: Int?) -> Unit,
    onOutdent: (Subtask, newParentId: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val itemHeight = 56.dp
    val itemHeightPx = with(density) { itemHeight.toPx() }

    var localOrder by remember(subtasks, collapsedIds) { mutableStateOf(buildVisibleRows(subtasks, collapsedIds)) }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }

    fun siblingsOf(subtask: Subtask): List<Subtask> =
        subtasks.filter { it.parent_id == subtask.parent_id }.sortedBy { it.position }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        itemsIndexed(localOrder, key = { _, row -> row.subtask.id }) { index, row ->
            val subtask = row.subtask
            val isDragged = index == draggedIndex
            var menuExpanded by remember { mutableStateOf(false) }

            val siblings = siblingsOf(subtask)
            val siblingIndex = siblings.indexOfFirst { it.id == subtask.id }
            val canIndent = siblingIndex > 0
            val canOutdent = subtask.parent_id != null

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                    .padding(start = (16 + row.depth * 20).dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.hasChildren) {
                    IconButton(onClick = { onToggleCollapse(subtask.id) }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = if (subtask.id in collapsedIds) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                            contentDescription = if (subtask.id in collapsedIds) "Развернуть" else "Свернуть",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.size(24.dp))
                }
                if (subtask.is_checklist) {
                    IconButton(onClick = { onOpenChecklist(subtask) }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Checklist,
                            contentDescription = "Открыть чек-лист",
                            tint = accentColor,
                        )
                    }
                } else {
                    Checkbox(
                        checked = subtask.done,
                        onCheckedChange = { onToggleDone(subtask) },
                        colors = CheckboxDefaults.colors(checkedColor = accentColor),
                    )
                }
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
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                        .let { if (subtask.is_checklist) it.clickable { onOpenChecklist(subtask) } else it },
                )
                IconButton(onClick = { onEditNotes(subtask) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Notes,
                        contentDescription = if (subtask.notes.isNullOrBlank()) "Добавить заметку" else "Заметка",
                        tint = if (subtask.notes.isNullOrBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        } else {
                            accentColor
                        },
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Ещё")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Запланировать") },
                            leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                            onClick = { menuExpanded = false; onSchedule(subtask) },
                        )
                        DropdownMenuItem(
                            text = { Text("Добавить подзадачу") },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            onClick = { menuExpanded = false; onAddChild(subtask) },
                        )
                        DropdownMenuItem(
                            text = { Text("Добавить чек-лист") },
                            leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                            onClick = { menuExpanded = false; onAddChecklistChild(subtask) },
                        )
                        DropdownMenuItem(
                            text = { Text("Сделать вложенной") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            enabled = canIndent,
                            onClick = {
                                menuExpanded = false
                                onIndent(subtask, siblings[siblingIndex - 1].id)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Поднять на уровень") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null) },
                            enabled = canOutdent,
                            onClick = {
                                val currentParent = subtasks.firstOrNull { it.id == subtask.parent_id }
                                menuExpanded = false
                                onOutdent(subtask, currentParent?.parent_id)
                            },
                        )
                    }
                }
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
                                    draggedIndex = localOrder.indexOfFirst { it.subtask.id == subtask.id }
                                    dragOffset = 0f
                                },
                                onDragEnd = {
                                    draggedIndex = null
                                    dragOffset = 0f
                                    val group = localOrder.filter { it.subtask.parent_id == subtask.parent_id }
                                    onReorder(subtask.parent_id, group.map { it.subtask.id })
                                },
                                onDragCancel = {
                                    draggedIndex = null
                                    dragOffset = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    var idx = draggedIndex ?: return@detectDragGesturesAfterLongPress
                                    dragOffset += dragAmount.y
                                    while (dragOffset > itemHeightPx / 2 && idx + 1 < localOrder.size &&
                                        localOrder[idx + 1].subtask.parent_id == localOrder[idx].subtask.parent_id
                                    ) {
                                        localOrder = localOrder.toMutableList().apply { add(idx, removeAt(idx + 1)) }
                                        idx += 1
                                        dragOffset -= itemHeightPx
                                    }
                                    while (dragOffset < -itemHeightPx / 2 && idx - 1 >= 0 &&
                                        localOrder[idx - 1].subtask.parent_id == localOrder[idx].subtask.parent_id
                                    ) {
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
