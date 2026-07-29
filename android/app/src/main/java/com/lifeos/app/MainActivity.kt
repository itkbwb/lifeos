package com.lifeos.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.SettingsStore
import com.lifeos.app.ui.SettingsScreen
import com.lifeos.app.ui.theme.LifeOsTheme
import com.lifeos.app.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val updateChecker by lazy { UpdateChecker(this, BuildConfig.UPDATE_REPO) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleProvisioningIntent(intent)
        setContent {
            LifeOsTheme {
                LifeOsRoot(updateChecker, ::ensureInstallPermission)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleProvisioningIntent(intent)
    }

    /**
     * Lets `adb shell am start ... --es cf_client_id X --es cf_client_secret Y` provision
     * Cloudflare Access service token credentials without them ever passing through chat.
     */
    private fun handleProvisioningIntent(intent: Intent) {
        val clientId = intent.getStringExtra("cf_client_id")
        val clientSecret = intent.getStringExtra("cf_client_secret")
        if (!clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()) {
            SettingsStore(application).setAccessCredentials(clientId, clientSecret)
        }
    }

    private fun ensureInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                ),
            )
            return false
        }
        return true
    }
}

@Composable
private fun LifeOsRoot(
    updateChecker: UpdateChecker,
    ensureInstallPermission: () -> Boolean,
) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    val serverUrl by settingsStore.serverUrl.collectAsState(initial = SettingsStore.DEFAULT_URL)
    val accessClientId by settingsStore.accessClientId.collectAsState()
    val accessClientSecret by settingsStore.accessClientSecret.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf("") }
    var connectionStatus by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        settingsStore.discardLegacyPlaintextCredentials()
    }

    suspend fun downloadAndInstall(info: UpdateChecker.UpdateInfo) {
        updateStatus = "Скачиваю версию ${info.version}…"
        val uri = withContext(Dispatchers.IO) {
            runCatching { updateChecker.downloadApk(info) }.getOrNull()
        }
        if (uri != null) {
            updateStatus = "Версия ${info.version} готова к установке"
            if (ensureInstallPermission()) {
                context.startActivity(updateChecker.installIntent(uri))
            }
        } else {
            updateStatus = "Не удалось скачать обновление"
        }
    }

    LaunchedEffect(Unit) {
        val info = withContext(Dispatchers.IO) {
            runCatching { updateChecker.checkLatest(BuildConfig.VERSION_NAME) }.getOrNull()
        } ?: return@LaunchedEffect
        downloadAndInstall(info)
    }

    Scaffold { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showSettings) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TextButton(onClick = { showSettings = false }) {
                        Text("← Назад")
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        SettingsScreen(
                            currentUrl = serverUrl,
                            hasAccessCredentials = accessClientId.isNotBlank() && accessClientSecret.isNotBlank(),
                            accessClientSecretMasked = settingsStore.accessClientSecretMasked(),
                            connectionStatus = connectionStatus,
                            onSave = { url -> scope.launch { settingsStore.setServerUrl(url) } },
                            onSaveAccessCredentials = { id, secret -> settingsStore.setAccessCredentials(id, secret) },
                            onTestConnection = {
                                scope.launch {
                                    connectionStatus = "Проверка…"
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching {
                                            ApiFactory.create(serverUrl, accessClientId, accessClientSecret).getNow()
                                        }.isSuccess
                                    }
                                    connectionStatus = if (ok) "Сервер доступен" else "Сервер недоступен"
                                }
                            },
                            onCheckUpdate = {
                                scope.launch {
                                    updateStatus = "Проверка…"
                                    val info = withContext(Dispatchers.IO) {
                                        runCatching { updateChecker.checkLatest(BuildConfig.VERSION_NAME) }.getOrNull()
                                    }
                                    updateStatus = info?.let { "Доступна версия ${it.version}" }
                                        ?: "Установлена последняя версия"
                                }
                            },
                            onUpdateNow = {
                                scope.launch {
                                    updateStatus = "Проверка…"
                                    val info = withContext(Dispatchers.IO) {
                                        runCatching { updateChecker.checkLatest(BuildConfig.VERSION_NAME) }.getOrNull()
                                    }
                                    if (info != null) {
                                        downloadAndInstall(info)
                                    } else {
                                        updateStatus = "Установлена последняя версия"
                                    }
                                }
                            },
                            updateStatus = updateStatus,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Life OS", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Версия ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (updateStatus.isNotBlank()) {
                        Text(updateStatus, modifier = Modifier.padding(top = 16.dp))
                    }
                    TextButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.padding(top = 24.dp),
                    ) {
                        Text("Настройки")
                    }
                }
            }
        }
    }
}
