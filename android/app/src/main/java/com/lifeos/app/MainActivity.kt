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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lifeos.app.ui.theme.LifeOsTheme
import com.lifeos.app.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val updateChecker by lazy { UpdateChecker(this, BuildConfig.UPDATE_REPO) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LifeOsTheme {
                LifeOsRoot(updateChecker, ::ensureInstallPermission)
            }
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
    var updateStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
                Button(
                    onClick = {
                        scope.launch {
                            val info = withContext(Dispatchers.IO) {
                                runCatching { updateChecker.checkLatest(BuildConfig.VERSION_NAME) }.getOrNull()
                            }
                            if (info != null) downloadAndInstall(info) else updateStatus = "Установлена последняя версия"
                        }
                    },
                    modifier = Modifier.padding(top = 24.dp),
                ) {
                    Text("Проверить обновление")
                }
                if (updateStatus.isNotBlank()) {
                    Text(updateStatus, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }
}
