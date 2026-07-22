package com.lifeos.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
    currentAccessClientId: String,
    currentAccessClientSecret: String,
    onSave: (String) -> Unit,
    onSaveAccessCredentials: (String, String) -> Unit,
    onCheckUpdate: () -> Unit,
    updateStatus: String,
) {
    var url by remember(currentUrl) { mutableStateOf(currentUrl) }
    var clientId by remember(currentAccessClientId) { mutableStateOf(currentAccessClientId) }
    var clientSecret by remember(currentAccessClientSecret) { mutableStateOf(currentAccessClientSecret) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text("Адрес сервера")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("http://192.168.x.x:8000") },
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSave(url) }) { Text("Сохранить") }

        Spacer(Modifier.height(32.dp))
        Text("Cloudflare Access Service Token")
        Spacer(Modifier.height(8.dp))
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
        Button(onClick = { onSaveAccessCredentials(clientId, clientSecret) }) { Text("Сохранить токен") }

        Spacer(Modifier.height(32.dp))
        Button(onClick = onCheckUpdate) { Text("Проверить обновления") }
        Spacer(Modifier.height(8.dp))
        Text(updateStatus)
    }
}
