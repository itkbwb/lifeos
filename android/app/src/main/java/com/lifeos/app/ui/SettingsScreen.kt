package com.lifeos.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lifeos.app.data.ApiFactory
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    currentUrl: String,
    hasAccessCredentials: Boolean,
    accessClientSecretMasked: String,
    connectionStatus: String,
    appVersion: String,
    serverUrl: String,
    accessClientId: String,
    accessClientSecret: String,
    onSave: (String) -> Unit,
    onSaveAccessCredentials: (String, String) -> Unit,
    onTestConnection: () -> Unit,
    onCheckUpdate: () -> Unit,
    onUpdateNow: () -> Unit,
    updateStatus: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importStatus by remember { mutableStateOf("") }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importStatus = "Импорт…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val csvText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("не удалось открыть файл")
                    val tzOffsetMinutes = ZonedDateTime.now().offset.totalSeconds / 60
                    ApiFactory.importCsv(serverUrl, accessClientId, accessClientSecret, csvText, tzOffsetMinutes)
                }
            }
            result.fold(
                onSuccess = { r ->
                    importStatus = buildString {
                        append("Импортировано записей: ${r.created}")
                        if (r.projects_created.isNotEmpty()) append(", новых проектов: ${r.projects_created.size}")
                        if (r.errors.isNotEmpty()) append(", ошибок: ${r.errors.size} (строка ${r.errors.first().row}: ${r.errors.first().message})")
                    }
                },
                onFailure = { importStatus = "Не удалось импортировать" },
            )
        }
    }
    var clearStatus by remember { mutableStateOf("") }
    var clearConfirmScope by remember { mutableStateOf<String?>(null) }

    var editingToken by remember { mutableStateOf(false) }

    var url by remember(currentUrl) { mutableStateOf(currentUrl) }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var tokenSavedFeedback by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Настройки")

        Spacer(Modifier.height(24.dp))
        Text("Адрес сервера")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://life-os.vip") },
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSave(url) }) { Text("Сохранить") }

        Spacer(Modifier.height(32.dp))
        Text("Cloudflare Access Service Token")
        Spacer(Modifier.height(8.dp))

        if (!editingToken) {
            Text(if (hasAccessCredentials) accessClientSecretMasked else "Не задан")
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { editingToken = true }) { Text("Изменить токен") }
        } else {
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("CF-Access-Client-Id") },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = clientSecret,
                onValueChange = { clientSecret = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("CF-Access-Client-Secret") },
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        onSaveAccessCredentials(clientId, clientSecret)
                        clientId = ""
                        clientSecret = ""
                        editingToken = false
                        tokenSavedFeedback = true
                    },
                ) { Text("Сохранить токен") }
                OutlinedButton(onClick = { editingToken = false }) { Text("Отмена") }
            }
            if (tokenSavedFeedback) {
                Spacer(Modifier.height(8.dp))
                Text("Сохранено")
            }
        }

        Spacer(Modifier.height(32.dp))
        Divider()
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onTestConnection) { Text("Проверить подключение") }
        if (connectionStatus.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(connectionStatus)
        }

        Spacer(Modifier.height(32.dp))
        Divider()
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onUpdateNow) { Text("Обновить") }
            OutlinedButton(onClick = onCheckUpdate) { Text("Проверить обновление") }
        }
        Spacer(Modifier.height(8.dp))
        Text(updateStatus)

        Spacer(Modifier.height(32.dp))
        Divider()
        Spacer(Modifier.height(16.dp))
        Text("Импорт (chapter 5.11)")
        Spacer(Modifier.height(8.dp))
        Text("CSV с колонками: project,date,start,end,title")
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*", "text/csv", "*/*")) }) {
            Text("Выбрать CSV")
        }
        if (importStatus.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(importStatus)
        }

        Spacer(Modifier.height(32.dp))
        Divider()
        Spacer(Modifier.height(16.dp))
        Text("Очистка данных", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text("Необратимо удаляет записи с сервера. Проекты не затрагиваются.")
        Spacer(Modifier.height(12.dp))
        CLEAR_SCOPES.forEach { (scope, label) ->
            OutlinedButton(
                onClick = { clearConfirmScope = scope },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Очистить: $label") }
            Spacer(Modifier.height(8.dp))
        }
        if (clearStatus.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(clearStatus)
        }

        Spacer(Modifier.height(32.dp))
        Divider()
        Spacer(Modifier.height(16.dp))
        Text("Версия $appVersion")
        Spacer(Modifier.height(24.dp))
    }

    clearConfirmScope?.let { targetScope ->
        val label = CLEAR_SCOPES.first { it.first == targetScope }.second
        ConfirmDeleteDialog(
            message = "Удалить всё из «$label»? Действие необратимо.",
            onDismiss = { clearConfirmScope = null },
            onConfirm = {
                clearConfirmScope = null
                scope.launch {
                    clearStatus = "Очистка…"
                    val result = withContext(Dispatchers.IO) {
                        runCatching { ApiFactory.clearData(serverUrl, accessClientId, accessClientSecret, targetScope) }
                    }
                    result.fold(
                        onSuccess = { r ->
                            clearStatus = "Удалено: событий ${r.deleted_events}, " +
                                "записей плана ${r.deleted_plan_entries}, изменений плана ${r.deleted_plan_changes}"
                        },
                        onFailure = { clearStatus = "Не удалось очистить" },
                    )
                }
            },
        )
    }
}

private val CLEAR_SCOPES = listOf(
    "static" to "Static",
    "dynamic" to "Dynamic",
    "timeline" to "Timeline",
    "instant" to "Instant",
    "static_and_dynamic" to "Static и Dynamic",
    "all" to "всё",
)
