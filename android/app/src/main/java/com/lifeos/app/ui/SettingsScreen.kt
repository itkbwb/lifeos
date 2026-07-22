package com.lifeos.app.ui

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
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
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

@Composable
fun SettingsScreen(
    currentUrl: String,
    hasAccessCredentials: Boolean,
    accessClientSecretMasked: String,
    onSave: (String) -> Unit,
    onSaveAccessCredentials: (String, String) -> Unit,
    onCheckUpdate: () -> Unit,
    onUpdateNow: () -> Unit,
    updateStatus: String,
) {
    var connectionExpanded by remember { mutableStateOf(false) }
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
        Text("Сервер подключён")
        Spacer(Modifier.height(4.dp))
        Text(if (hasAccessCredentials) "Синхронизация работает" else "Нужен токен доступа")
        Spacer(Modifier.height(4.dp))
        Text("Версия актуальна")

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { connectionExpanded = !connectionExpanded }) {
            Text(if (connectionExpanded) "Скрыть подключение" else "Подключение")
        }

        AnimatedVisibility(visible = connectionExpanded) {
            Column {
                Divider()
                Spacer(Modifier.height(16.dp))

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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onUpdateNow) { Text("Обновить") }
                    OutlinedButton(onClick = onCheckUpdate) { Text("Проверить") }
                }
                Spacer(Modifier.height(8.dp))
                Text(updateStatus)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
