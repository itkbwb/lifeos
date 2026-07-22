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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lifeos.app.ui.DayScreen
import com.lifeos.app.ui.NowScreen
import com.lifeos.app.ui.ProjectsScreen
import com.lifeos.app.ui.SettingsScreen
import com.lifeos.app.ui.theme.LifeOsTheme
import com.lifeos.app.ui.toUiState
import com.lifeos.app.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime

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
        android.util.Log.e("LifeOS", "handleProvisioningIntent id_len=${clientId?.length} secret_len=${clientSecret?.length}")
        if (!clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()) {
            viewModel.provisionAccessCredentials(clientId, clientSecret)
            android.util.Log.e("LifeOS", "provisionAccessCredentials called")
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LifeOsRoot(
    viewModel: MainViewModel,
    updateChecker: UpdateChecker,
    ensureInstallPermission: () -> Boolean,
) {
    val navController = rememberNavController()
    val nowState by viewModel.nowState.collectAsState()
    val dayPlan by viewModel.dayPlan.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val accessClientId by viewModel.accessClientId.collectAsState()
    val accessClientSecret by viewModel.accessClientSecret.collectAsState()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Life OS") },
                actions = {
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
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
                    selected = currentRoute == "plan",
                    onClick = {
                        navController.navigate("plan")
                        viewModel.refreshDayPlan()
                    },
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    label = { Text("План") },
                )
                NavigationBarItem(
                    selected = currentRoute == "projects",
                    onClick = {
                        navController.navigate("projects")
                        viewModel.refreshProjects()
                    },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    label = { Text("Проекты") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            NavHost(navController = navController, startDestination = "now") {
                composable("now") {
                    when (val s = nowState) {
                        is ConnectionState.Loaded -> NowScreen(
                            state = s.now.toUiState(),
                            dayDeviation = s.now.day_deviation,
                            fetchedAtMillis = s.fetchedAtMillis,
                            onStart = viewModel::startBlock,
                            onPause = viewModel::pauseBlock,
                            onResume = viewModel::resumeBlock,
                            onComplete = viewModel::completeBlock,
                            onSkip = viewModel::skipBlock,
                            onReschedule = { id ->
                                val block = s.now.current_block
                                if (block != null) {
                                    val start = OffsetDateTime.parse(block.planned_start).plusDays(1)
                                    val end = OffsetDateTime.parse(block.planned_end).plusDays(1)
                                    viewModel.rescheduleBlock(id, start.toString(), end.toString())
                                }
                            },
                        )
                        is ConnectionState.NoConnection -> CenteredMessage("Нет соединения")
                        is ConnectionState.ServerUnavailable -> CenteredMessage("Сервер недоступен")
                        is ConnectionState.Loading -> CenteredProgress()
                    }
                }
                composable("plan") {
                    val plan = dayPlan
                    if (plan != null) DayScreen(plan.blocks) else CenteredProgress()
                }
                composable("projects") {
                    ProjectsScreen(projects)
                }
                composable("settings") {
                    SettingsScreen(
                        currentUrl = serverUrl,
                        hasAccessCredentials = accessClientId.isNotBlank() && accessClientSecret.isNotBlank(),
                        accessClientSecretMasked = run {
                            val secret = accessClientSecret
                            if (secret.length < 4) "" else "••••••••" + secret.takeLast(4)
                        },
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
        }
    }
}

@Composable
private fun CenteredProgress() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center)) {
        Text(text)
    }
}
