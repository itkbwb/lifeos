package com.lifeos.app.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.unit.dp
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.Reminder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val RU = Locale("ru")
private val REMINDER_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE
private val REMINDER_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val REMINDER_ROW_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", RU)

/**
 * Standalone "special reminders" tab (chapter: special reminders) - a one-off "remind me at
 * this date+time" note, distinct from Static/Dynamic Plan (which schedule project work). Firing
 * is entirely server-driven (see server/app/scheduler.py); this screen is just CRUD + a list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    serverUrl: String,
    accessClientId: String,
    accessClientSecret: String,
    modifier: Modifier = Modifier,
) {
    var reminders by remember { mutableStateOf<List<Reminder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createErrorMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(serverUrl, refreshKey) {
        loading = true
        withContext(Dispatchers.IO) {
            runCatching { ApiFactory.listReminders(serverUrl, accessClientId, accessClientSecret) }
        }.fold(
            onSuccess = { reminders = it; loadError = false },
            onFailure = { loadError = true },
        )
        loading = false
    }

    fun deleteReminder(id: Int) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ApiFactory.deleteReminder(serverUrl, accessClientId, accessClientSecret, id = id) }
            }.onSuccess { refreshKey++ }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Напоминания") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                createErrorMessage = ""
                showCreateDialog = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить напоминание")
            }
        },
    ) { padding ->
        when {
            loading && reminders.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            loadError -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text("Не удалось загрузить напоминания") }

            reminders.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text("Напоминаний пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant) }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(reminders, key = { it.id }) { reminder ->
                    val zoned = remember(reminder.remind_at) {
                        Instant.parse(reminder.remind_at).atZone(ZoneId.systemDefault())
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(REMINDER_ROW_FORMAT.format(zoned), style = MaterialTheme.typography.labelMedium)
                                Text(reminder.message, style = MaterialTheme.typography.bodyLarge)
                            }
                            IconButton(onClick = { deleteReminder(reminder.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Удалить напоминание",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var dateText by remember { mutableStateOf(REMINDER_DATE_FORMAT.format(LocalDate.now())) }
        var timeText by remember { mutableStateOf(REMINDER_TIME_FORMAT.format(LocalTime.now())) }
        var message by remember { mutableStateOf("") }

        val date = runCatching { LocalDate.parse(dateText, REMINDER_DATE_FORMAT) }.getOrNull()
        val time = runCatching { LocalTime.parse(timeText, REMINDER_TIME_FORMAT) }.getOrNull()
        val isValid = date != null && time != null && message.isNotBlank()

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Новое напоминание") },
            text = {
                Column {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Текст") },
                        placeholder = { Text("Осталось 10 дней до дедлайна") },
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
                    if (createErrorMessage.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(createErrorMessage, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = isValid,
                    onClick = {
                        val remindAt = date!!.atTime(time!!).atZone(ZoneId.systemDefault()).toInstant().toString()
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    ApiFactory.createReminder(
                                        serverUrl, accessClientId, accessClientSecret,
                                        remindAt = remindAt, message = message.trim(),
                                    )
                                }
                            }.onSuccess {
                                showCreateDialog = false
                                refreshKey++
                            }.onFailure {
                                createErrorMessage = "Не удалось сохранить напоминание"
                            }
                        }
                    },
                ) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Отмена") }
            },
        )
    }
}
