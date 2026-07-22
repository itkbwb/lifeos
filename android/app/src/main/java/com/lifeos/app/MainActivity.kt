package com.lifeos.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lifeos.app.ui.DayScreen
import com.lifeos.app.ui.NowScreen
import com.lifeos.app.ui.SettingsScreen
import com.lifeos.app.ui.theme.LifeOsTheme
import com.lifeos.app.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val updateChecker by lazy { UpdateChecker(this, BuildConfig.UPDATE_REPO) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleProvisioningIntent(intent)
        setContent {
            LifeOsTheme {
                LifeOsRoot(viewModel, updateChecker, ::ensureInstallPermission)
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
            viewModel.provisionAccessCredentials(clientId, clientSecret)
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
    viewModel: MainViewModel,
    updateChecker: UpdateChecker,
    ensureInstallPermission: () -> Boolean,
) {
    val navController = rememberNavController()
    val state by viewModel.state.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val accessClientId by viewModel.accessClientId.collectAsState()
    val accessClientSecret by viewModel.accessClientSecret.collectAsState()
    var updateStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val info = withContext(Dispatchers.IO) {
            runCatching { updateChecker.checkLatest(BuildConfig.VERSION_NAME) }.getOrNull()
        } ?: return@LaunchedEffect

        updateStatus = "Скачиваю версию ${info.version}…"
        val uri = withContext(Dispatchers.IO) {
            runCatching { updateChecker.downloadApk(info) }.getOrNull()
        }
        if (uri != null) {
            updateStatus = "Версия ${info.version} готова к установке"
            if (ensureInstallPermission()) {
                context.startActivity(updateChecker.installIntent(uri))
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                NavigationBarItem(
                    selected = currentRoute == "now",
                    onClick = { navController.navigate("now") },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Сейчас") },
                )
                NavigationBarItem(
                    selected = currentRoute == "day",
                    onClick = { navController.navigate("day") },
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    label = { Text("День") },
                )
                NavigationBarItem(
                    selected = currentRoute == "settings",
                    onClick = { navController.navigate("settings") },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Настройки") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            NavHost(navController = navController, startDestination = "now") {
                composable("now") {
                    when (val s = state) {
                        is LoadState.Loaded -> NowScreen(
                            dashboard = s.dashboard,
                            onComplete = viewModel::completeBlock,
                            onSkip = viewModel::skipBlock,
                        )
                        is LoadState.Error -> Text("Ошибка: ${s.message}")
                        is LoadState.Loading -> CircularProgressIndicator()
                    }
                }
                composable("day") {
                    when (val s = state) {
                        is LoadState.Loaded -> DayScreen(s.dashboard.blocks)
                        else -> CircularProgressIndicator()
                    }
                }
                composable("settings") {
                    SettingsScreen(
                        currentUrl = serverUrl,
                        currentAccessClientId = accessClientId,
                        currentAccessClientSecret = accessClientSecret,
                        onSave = viewModel::updateServerUrl,
                        onSaveAccessCredentials = viewModel::provisionAccessCredentials,
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
                        updateStatus = updateStatus,
                    )
                }
            }
        }
    }
}
