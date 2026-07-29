package com.lifeos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.Project
import com.lifeos.app.ui.theme.ProjectColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LoadState { Loading, Loaded, Error }

private sealed class DialogState {
    object None : DialogState()
    object Create : DialogState()
    data class Edit(val project: Project) : DialogState()
    data class ConfirmDelete(val project: Project) : DialogState()
}

@Composable
fun ProjectsScreen(
    serverUrl: String,
    accessClientId: String,
    accessClientSecret: String,
    onOpenSettings: () -> Unit,
) {
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var loadState by remember { mutableStateOf(LoadState.Loading) }
    var refreshToken by remember { mutableStateOf(0) }
    var dialogState by remember { mutableStateOf<DialogState>(DialogState.None) }
    var dialogError by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loadState = LoadState.Loading
        withContext(Dispatchers.IO) {
            runCatching { ApiFactory.listProjects(serverUrl, accessClientId, accessClientSecret) }
        }.fold(
            onSuccess = { projects = it; loadState = LoadState.Loaded },
            onFailure = { loadState = LoadState.Error },
        )
    }

    LaunchedEffect(refreshToken, serverUrl) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Проекты") },
                actions = {
                    TextButton(onClick = onOpenSettings) { Text("Настройки") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                dialogError = ""
                dialogState = DialogState.Create
            }) { Text("+") }
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

            LoadState.Loaded -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(projects, key = { it.id }) { project ->
                    ProjectRow(project = project, onClick = {
                        dialogError = ""
                        dialogState = DialogState.Edit(project)
                    })
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
        )

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
                        onFailure = { dialogError = "Не удалось удалить проект" },
                    )
                }
            },
        )
    }
}

@Composable
private fun ProjectRow(project: Project, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(ProjectColors.colorFor(project.color)),
        )
        Spacer(Modifier.width(16.dp))
        Text(project.name, style = MaterialTheme.typography.bodyLarge)
    }
}
