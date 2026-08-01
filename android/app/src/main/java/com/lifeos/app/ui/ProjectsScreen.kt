package com.lifeos.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Restore
import com.lifeos.app.R
import com.lifeos.app.data.ActiveProject
import com.lifeos.app.data.ActiveProjectConflictException
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.Project
import com.lifeos.app.data.ProjectHasRecordsException
import com.lifeos.app.ui.theme.ProjectColors
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LoadState { Loading, Loaded, Error }

private sealed class DialogState {
    object None : DialogState()
    object Create : DialogState()
    data class Edit(val project: Project) : DialogState()
    data class ConfirmDelete(val project: Project) : DialogState()
    data class DeleteOrArchive(val project: Project) : DialogState()
    data class StartPrompt(val project: Project) : DialogState()
    data class InstantPrompt(val project: Project) : DialogState()
    data class StartConflict(val newProject: Project, val name: String, val active: ActiveProject) : DialogState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    serverUrl: String,
    accessClientId: String,
    accessClientSecret: String,
    onOpenSettings: () -> Unit,
) {
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var activeProject by remember { mutableStateOf<ActiveProject?>(null) }
    var loadState by remember { mutableStateOf(LoadState.Loading) }
    var refreshToken by remember { mutableStateOf(0) }
    var dialogState by remember { mutableStateOf<DialogState>(DialogState.None) }
    var dialogError by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    var openSubtasksFor by remember { mutableStateOf<Project?>(null) }
    var importStatus by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Whole-project import (chapter: project import) - a self-contained JSON file
    // (project + subtasks + optionally Static entries) picked from disk, distinct
    // from the flat CSV importer in Settings which only ever creates Static entries.
    val importProjectLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importStatus = "Импорт…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("не удалось открыть файл")
                    ApiFactory.importProject(serverUrl, accessClientId, accessClientSecret, json)
                }
            }
            result.fold(
                onSuccess = { r ->
                    importStatus = buildString {
                        append(if (r.project_created) "Проект создан" else "Проект обновлён")
                        append(", подзадач: ${r.subtasks_created}, записей плана: ${r.static_entries_created}")
                        if (r.errors.isNotEmpty()) {
                            append(", ошибок: ${r.errors.size} (строка ${r.errors.first().row}: ${r.errors.first().message})")
                        }
                    }
                    refreshToken++
                },
                onFailure = { importStatus = "Не удалось импортировать проект" },
            )
        }
    }

    suspend fun reload() {
        loadState = LoadState.Loading
        withContext(Dispatchers.IO) {
            runCatching {
                val list = ApiFactory.listProjects(serverUrl, accessClientId, accessClientSecret)
                val active = ApiFactory.getActiveProject(serverUrl, accessClientId, accessClientSecret)
                list to active
            }
        }.fold(
            onSuccess = { (list, active) ->
                projects = list
                activeProject = active
                loadState = LoadState.Loaded
            },
            onFailure = { loadState = LoadState.Error },
        )
    }

    fun startProject(project: Project, name: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    ApiFactory.createEvent(
                        serverUrl, accessClientId, accessClientSecret,
                        projectId = project.id, type = "start", label = name,
                    )
                }
            }.fold(
                onSuccess = { refreshToken++ },
                onFailure = { e ->
                    if (e is ActiveProjectConflictException) {
                        dialogState = DialogState.StartConflict(
                            newProject = project,
                            name = name,
                            active = ActiveProject(e.activeProjectId, e.activeEventId, e.startedAt),
                        )
                    } else {
                        dialogError = "Не удалось начать сессию"
                    }
                },
            )
        }
    }

    fun endProject(projectId: Int) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    ApiFactory.createEvent(serverUrl, accessClientId, accessClientSecret, projectId = projectId, type = "end")
                }
            }.fold(
                onSuccess = { refreshToken++ },
                onFailure = { dialogError = "Не удалось завершить сессию" },
            )
        }
    }

    fun logInstant(project: Project, name: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    ApiFactory.createEvent(
                        serverUrl, accessClientId, accessClientSecret,
                        projectId = project.id, type = "instant", label = name.ifBlank { null },
                    )
                }
            }.fold(
                onSuccess = { refreshToken++ },
                onFailure = { dialogError = "Не удалось отметить событие" },
            )
        }
    }

    LaunchedEffect(refreshToken, serverUrl) { reload() }

    val subtasksTarget = openSubtasksFor
    if (subtasksTarget != null) {
        SubtasksScreen(
            project = subtasksTarget,
            serverUrl = serverUrl,
            accessClientId = accessClientId,
            accessClientSecret = accessClientSecret,
            onBack = { openSubtasksFor = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Проекты") })
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(
                    onClick = { importProjectLauncher.launch(arrayOf("application/json", "*/*")) },
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = "Импортировать проект")
                }
                FloatingActionButton(onClick = {
                    dialogError = ""
                    dialogState = DialogState.Create
                }) { Text("+") }
            }
        },
    ) { padding ->
        when (loadState) {
            LoadState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            LoadState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Не удалось подключиться к серверу.")
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { scope.launch { reload() } }) { Text("Повторить") }
                    OutlinedButton(onClick = onOpenSettings) { Text("Настройки") }
                }
            }

            LoadState.Loaded -> {
                val activeProjects = projects.filter { !it.archived }
                val archivedProjects = projects.filter { it.archived }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (importStatus.isNotBlank()) {
                        item(key = "import-status") {
                            Text(importStatus, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(activeProjects, key = { it.id }) { project ->
                        ProjectTile(
                            project = project,
                            isActive = activeProject?.project_id == project.id,
                            onClick = { openSubtasksFor = project },
                            onEdit = {
                                dialogError = ""
                                dialogState = DialogState.Edit(project)
                            },
                            onStart = {
                                dialogError = ""
                                dialogState = DialogState.StartPrompt(project)
                            },
                            onStop = { endProject(project.id) },
                            onInstant = {
                                dialogError = ""
                                dialogState = DialogState.InstantPrompt(project)
                            },
                        )
                    }

                    if (archivedProjects.isNotEmpty()) {
                        item(key = "archive-toggle") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showArchived = !showArchived }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Архив (${archivedProjects.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = if (showArchived) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                )
                            }
                        }
                        if (showArchived) {
                            items(archivedProjects, key = { it.id }) { project ->
                                ArchivedProjectRow(
                                    project = project,
                                    onClick = {
                                        dialogError = ""
                                        dialogState = DialogState.Edit(project)
                                    },
                                    onRestore = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                runCatching {
                                                    ApiFactory.updateProject(
                                                        serverUrl, accessClientId, accessClientSecret,
                                                        id = project.id, archived = false,
                                                    )
                                                }
                                            }.onSuccess { refreshToken++ }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when (val state = dialogState) {
        DialogState.None -> {}

        DialogState.Create -> ProjectFormDialog(
            title = "Новый проект",
            initialName = "",
            initialColor = "lavender",
            confirmLabel = "Создать",
            errorMessage = dialogError,
            onDismiss = { dialogState = DialogState.None },
            onConfirm = { name, color ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.createProject(serverUrl, accessClientId, accessClientSecret, name, color)
                        }
                    }.fold(
                        onSuccess = {
                            dialogState = DialogState.None
                            refreshToken++
                        },
                        onFailure = { dialogError = "Не удалось создать проект" },
                    )
                }
            },
        )

        is DialogState.Edit -> ProjectEditDialog(
            project = state.project,
            errorMessage = dialogError,
            onDismiss = { dialogState = DialogState.None },
            onSave = { name, color ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.updateProject(
                                serverUrl,
                                accessClientId,
                                accessClientSecret,
                                id = state.project.id,
                                name = name,
                                color = color,
                            )
                        }
                    }.fold(
                        onSuccess = {
                            dialogState = DialogState.None
                            refreshToken++
                        },
                        onFailure = { dialogError = "Не удалось сохранить проект" },
                    )
                }
            },
            onRequestDelete = { dialogState = DialogState.ConfirmDelete(state.project) },
            onToggleArchive = {
                val target = state.project
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.updateProject(
                                serverUrl, accessClientId, accessClientSecret,
                                id = target.id, archived = !target.archived,
                            )
                        }
                    }.fold(
                        onSuccess = {
                            dialogState = DialogState.None
                            refreshToken++
                        },
                        onFailure = { dialogError = "Не удалось изменить статус архива" },
                    )
                }
            },
        )

        is DialogState.StartPrompt -> StartNameDialog(
            projectName = state.project.name,
            initialName = state.project.name,
            onDismiss = { dialogState = DialogState.None },
            onConfirm = { name ->
                dialogState = DialogState.None
                startProject(state.project, name)
            },
        )

        is DialogState.InstantPrompt -> InstantNameDialog(
            projectName = state.project.name,
            onDismiss = { dialogState = DialogState.None },
            onConfirm = { name ->
                dialogState = DialogState.None
                logInstant(state.project, name)
            },
        )

        is DialogState.StartConflict -> {
            val activeName = projects.firstOrNull { it.id == state.active.project_id }?.name ?: "проект"
            StartConflictDialog(
                activeProjectName = activeName,
                newProjectName = state.newProject.name,
                onCancel = { dialogState = DialogState.None },
                onFinishOnly = {
                    dialogState = DialogState.None
                    endProject(state.active.project_id)
                },
                onFinishAndStart = {
                    dialogState = DialogState.None
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                ApiFactory.createEvent(
                                    serverUrl, accessClientId, accessClientSecret,
                                    projectId = state.active.project_id, type = "end",
                                )
                                ApiFactory.createEvent(
                                    serverUrl, accessClientId, accessClientSecret,
                                    projectId = state.newProject.id, type = "start", label = state.name,
                                )
                            }
                        }.fold(
                            onSuccess = { refreshToken++ },
                            onFailure = { dialogError = "Не удалось переключить проект" },
                        )
                    }
                },
            )
        }

        is DialogState.ConfirmDelete -> DeleteProjectConfirmDialog(
            projectName = state.project.name,
            onDismiss = { dialogState = DialogState.Edit(state.project) },
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.deleteProject(serverUrl, accessClientId, accessClientSecret, state.project.id)
                        }
                    }.fold(
                        onSuccess = {
                            dialogState = DialogState.None
                            refreshToken++
                        },
                        onFailure = { e ->
                            if (e is ProjectHasRecordsException) {
                                dialogState = DialogState.DeleteOrArchive(state.project)
                            } else {
                                dialogError = "Не удалось удалить проект"
                            }
                        },
                    )
                }
            },
        )

        is DialogState.DeleteOrArchive -> DeleteOrArchiveDialog(
            projectName = state.project.name,
            onCancel = { dialogState = DialogState.Edit(state.project) },
            onArchive = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.updateProject(
                                serverUrl, accessClientId, accessClientSecret,
                                id = state.project.id, archived = true,
                            )
                        }
                    }.fold(
                        onSuccess = {
                            dialogState = DialogState.None
                            refreshToken++
                        },
                        onFailure = { dialogError = "Не удалось архивировать проект" },
                    )
                }
            },
            onDeleteAll = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.deleteProject(
                                serverUrl, accessClientId, accessClientSecret,
                                id = state.project.id, force = true,
                            )
                        }
                    }.fold(
                        onSuccess = {
                            dialogState = DialogState.None
                            refreshToken++
                        },
                        onFailure = { dialogError = "Не удалось удалить проект" },
                    )
                }
            },
        )
    }
}

@Composable
private fun ProjectTile(
    project: Project,
    isActive: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onInstant: () -> Unit,
) {
    val color = ProjectColors.colorFor(project.color)
    val onColor = ProjectColors.contrastingTextColor(color)
    // Dimmed (not just "different icon") so it's visually obvious which of
    // the two is actually usable right now - Stop only ever "lights up" for
    // the one tile that is truly the active project (chapter: separate
    // play/stop buttons), never for every tile at once.
    val disabledTint = onColor.copy(alpha = 0.35f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = project.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = onColor,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onStart) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Начать",
                tint = if (isActive) disabledTint else onColor,
            )
        }
        IconButton(onClick = onStop) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = "Завершить",
                tint = if (isActive) onColor else disabledTint,
            )
        }
        IconButton(onClick = onInstant) {
            Icon(
                painter = painterResource(R.drawable.ic_instant_sparkle),
                contentDescription = "Отметить",
                tint = onColor,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Редактировать",
                tint = onColor,
            )
        }
    }
}

@Composable
private fun ArchivedProjectRow(
    project: Project,
    onClick: () -> Unit,
    onRestore: () -> Unit,
) {
    val color = ProjectColors.colorFor(project.color)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        Text(
            text = project.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        IconButton(onClick = onRestore) {
            Icon(
                imageVector = Icons.Filled.Restore,
                contentDescription = "Восстановить",
            )
        }
    }
}
