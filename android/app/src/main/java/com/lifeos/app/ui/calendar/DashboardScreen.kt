package com.lifeos.app.ui.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lifeos.app.R
import com.lifeos.app.data.ActiveProject
import com.lifeos.app.data.ActiveProjectConflictException
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.DynamicPlanEntry
import com.lifeos.app.data.Event
import com.lifeos.app.data.PlanEntry
import com.lifeos.app.data.Project
import com.lifeos.app.data.SettingsStore
import com.lifeos.app.ui.ConfirmDeleteDialog
import com.lifeos.app.ui.DashboardInstantDialog
import com.lifeos.app.ui.DynamicEntryEditDialog
import com.lifeos.app.ui.StartConflictDialog
import com.lifeos.app.ui.StartNameDialog
import com.lifeos.app.ui.theme.ProjectColors
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Dynamic Plan entry the top timer block refers to: either the one covering "now"
 * (counts down to its end) or, if there's a gap in the schedule, the nearest upcoming one
 * (counts down to its start) - null if today has no more Dynamic entries at all. */
private data class DynamicTimerTarget(
    val entry: DynamicPlanEntry,
    val targetInstant: Instant,
    val isCountingDownToEnd: Boolean,
)

private fun currentOrNextDynamicEntry(entries: List<DynamicPlanEntry>, now: Instant): DynamicTimerTarget? {
    val parsed = entries.mapNotNull { entry ->
        val start = runCatching { Instant.parse(entry.start_time) }.getOrNull() ?: return@mapNotNull null
        val end = runCatching { Instant.parse(entry.end_time) }.getOrNull() ?: return@mapNotNull null
        Triple(entry, start, end)
    }
    val covering = parsed.filter { (_, start, end) -> start <= now && now < end }.minByOrNull { it.second }
    if (covering != null) {
        return DynamicTimerTarget(covering.first, covering.third, isCountingDownToEnd = true)
    }
    val next = parsed.filter { (_, start, _) -> start > now }.minByOrNull { it.second } ?: return null
    return DynamicTimerTarget(next.first, next.second, isCountingDownToEnd = false)
}

private fun formatCountdown(now: Instant, target: Instant): String {
    val remaining = Duration.between(now, target).let { if (it.isNegative) Duration.ZERO else it }
    val totalSeconds = remaining.seconds
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private sealed class DashboardActionState {
    object None : DashboardActionState()
    object StartPrompt : DashboardActionState()
    object DeleteDynamicConfirm : DashboardActionState()
    data class StartConflict(val targetProjectId: Int, val name: String, val active: ActiveProject) : DashboardActionState()
}

/**
 * The app's default/home tab (chapter: dashboard) - a circular "speedometer"
 * gauge of today's Static/Dynamic/Timeline layers (same three-layer model as
 * [DayTimelineView], just wrapped into a 24h clock face instead of a
 * vertical bar), a live countdown for the current/next Dynamic Plan entry
 * with Start/Stop/Edit controls, and an Instant button that lets the time be
 * edited (defaults to the moment the button was pressed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    serverUrl: String,
    accessClientId: String,
    accessClientSecret: String,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now() }
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }

    var dayDAnchor by remember { mutableStateOf<LocalDate?>(null) }
    var showDayDDialog by remember { mutableStateOf(false) }
    var dayDDateText by remember { mutableStateOf("") }
    var dayDError by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        settingsStore.dayDAnchorDate.collect { dayDAnchor = it }
    }

    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var staticEntries by remember { mutableStateOf<List<PlanEntry>>(emptyList()) }
    var dynamicEntries by remember { mutableStateOf<List<DynamicPlanEntry>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }

    var showInstantDialog by remember { mutableStateOf(false) }
    var instantPressTime by remember { mutableStateOf(LocalTime.now()) }
    var instantError by remember { mutableStateOf("") }

    var actionState by remember { mutableStateOf<DashboardActionState>(DashboardActionState.None) }
    var actionError by remember { mutableStateOf("") }

    var showEditDynamicDialog by remember { mutableStateOf(false) }
    var editDynamicError by remember { mutableStateOf("") }

    var nowTick by remember { mutableStateOf(Instant.now()) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowTick = Instant.now()
        }
    }

    LaunchedEffect(serverUrl) {
        withContext(Dispatchers.IO) {
            runCatching { ApiFactory.listProjects(serverUrl, accessClientId, accessClientSecret) }
        }.onSuccess { projects = it }
    }

    LaunchedEffect(serverUrl, refreshKey) {
        val dayStart = today.atStartOfDay(zone).toInstant().toString()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toString()
        withContext(Dispatchers.IO) {
            runCatching {
                DashboardFetch(
                    events = ApiFactory.listEvents(serverUrl, accessClientId, accessClientSecret, from = dayStart, to = dayEnd),
                    staticEntries = ApiFactory.listPlanEntries(serverUrl, accessClientId, accessClientSecret, from = dayStart, to = dayEnd),
                    dynamicEntries = ApiFactory.listDynamicPlan(serverUrl, accessClientId, accessClientSecret, from = dayStart, to = dayEnd),
                )
            }
        }.onSuccess { fetch ->
            events = fetch.events
            staticEntries = fetch.staticEntries
            dynamicEntries = fetch.dynamicEntries
        }
    }

    val layout = remember(events, today) { layoutDay(events, today) }
    val staticBlocks = remember(staticEntries, today) { layoutStaticPlan(staticEntries, today) }
    val dynamicBlocks = remember(dynamicEntries, today) { layoutDynamicPlan(dynamicEntries, today) }
    val renderItems = remember(layout, staticBlocks, dynamicBlocks) {
        buildDayRenderModel(layout, staticBlocks, dynamicBlocks)
    }

    val dayDLabel = dayDAnchor?.let { anchor ->
        when (val offset = ChronoUnit.DAYS.between(anchor, today)) {
            0L -> "День D"
            else -> if (offset > 0) "D+$offset" else "D$offset"
        }
    } ?: "Задать День D"

    val timerTarget = remember(dynamicEntries, nowTick) { currentOrNextDynamicEntry(dynamicEntries, nowTick) }
    val timerProjectName = timerTarget?.let { target ->
        projects.firstOrNull { it.id == target.entry.project_id }?.name ?: "проект"
    }
    val timerProgress = timerTarget?.let { target ->
        if (!target.isCountingDownToEnd) {
            0f
        } else {
            val start = Instant.parse(target.entry.start_time)
            val end = Instant.parse(target.entry.end_time)
            val totalSeconds = Duration.between(start, end).seconds.coerceAtLeast(1)
            val elapsedSeconds = Duration.between(start, nowTick).seconds.coerceIn(0, totalSeconds)
            elapsedSeconds.toFloat() / totalSeconds.toFloat()
        }
    } ?: 0f
    val timerProjectColor = timerTarget?.let { target ->
        val project = projects.firstOrNull { it.id == target.entry.project_id }
        ProjectColors.colorFor(project?.color ?: "gray")
    } ?: MaterialTheme.colorScheme.primary

    fun startTimerProject(name: String) {
        val target = timerTarget ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    ApiFactory.createEvent(
                        serverUrl, accessClientId, accessClientSecret,
                        projectId = target.entry.project_id, type = "start", label = name,
                    )
                }
            }.fold(
                onSuccess = { refreshKey++ },
                onFailure = { e ->
                    if (e is ActiveProjectConflictException) {
                        actionState = DashboardActionState.StartConflict(
                            targetProjectId = target.entry.project_id,
                            name = name,
                            active = ActiveProject(e.activeProjectId, e.activeEventId, e.startedAt),
                        )
                    } else {
                        actionError = "Не удалось начать сессию"
                    }
                },
            )
        }
    }

    fun stopProject(projectId: Int) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    ApiFactory.createEvent(serverUrl, accessClientId, accessClientSecret, projectId = projectId, type = "end")
                }
            }.fold(
                onSuccess = { refreshKey++ },
                onFailure = { actionError = "Не удалось завершить сессию" },
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Дашборд") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            // Balances the block vertically between the top bar and the bottom nav by
            // splitting whatever space is left over (after all fixed-size content below is
            // measured) equally between a Spacer here and a matching one after the date text
            // - a fixed dp value calibrated on one emulator's screen geometry doesn't scale
            // to other screen sizes (confirmed: on a real device with different dimensions,
            // the fixed-dp version pushed the date text behind the bottom nav bar entirely).
            // weight(1f) on both ends makes Compose recompute this per available height,
            // responsive to any device.
            Spacer(Modifier.weight(1f))

            Text(
                text = dayDLabel,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable {
                        dayDDateText = (dayDAnchor ?: today).format(DateTimeFormatter.ISO_LOCAL_DATE)
                        dayDError = ""
                        showDayDDialog = true
                    },
            )

            // Gap between Day D and the arc's peak tuned to match the gap between the Stop
            // button and the arc's nearest edge (88px measured on a real screenshot) - was
            // 59px with no extra spacer, so +11dp closes the 29px difference.
            Spacer(Modifier.height(11.dp))

            if (timerTarget != null) {
                val arcHeight = 117.dp
                val buttonSize = 80.dp
                // The 4 buttons sit on a circle concentric with the arc (same center,
                // radius = arc's own radius + a fixed margin) so every button is the same
                // perpendicular distance from the arc's curve - Play/Instant near the peak
                // side (220/320 degrees), Stop/Edit right at the arc's own end-cap angle
                // (180/360 degrees), matching the arc's angle convention (180=left,
                // 270=top/peak, 360=right) already used by DayGauge's needle/markers below.
                // x is clamped to stay on-screen: at 180/360 degrees the raw polar offset
                // can exceed the box width once the arc itself is already wide enough to
                // nearly touch both edges.
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().height(165.dp).padding(horizontal = 4.dp),
                ) {
                    val density = LocalDensity.current
                    val widthPx = with(density) { maxWidth.toPx() }
                    val arcHeightPx = with(density) { arcHeight.toPx() }
                    val strokeWidthPx = with(density) { 22.5.dp.toPx() }
                    val radiusPx = (minOf(widthPx, arcHeightPx * 2f) - strokeWidthPx) / 2f
                    val buttonRadiusPx = radiusPx + with(density) { 40.dp.toPx() }
                    val buttonSizePx = with(density) { buttonSize.toPx() }
                    val centerXPx = widthPx / 2f
                    val centerYPx = arcHeightPx

                    // Centering a button ON the concentric circle (the general case, used for
                    // Play/Instant) puts the arc-facing point at its middle, so at the arc's
                    // own end-cap angle (180/360 - Stop/Edit) the button's bottom edge would
                    // stick out half the button size past the arc's ends. alignBottomToArc
                    // pins the button's VISIBLE ICON's bottom edge (not the invisible 80dp
                    // touch-target box, which is bigger than the icon and centers it with
                    // padding) to the arc's own end level, still using the same radial x so
                    // it's still outside the arc by the same margin. extraOffsetPx is a small
                    // per-icon fudge on top of that: Material glyphs don't fill their nominal
                    // size box evenly (Stop's filled square sits well inside its 48dp box;
                    // Edit's pencil less so) - calibrated by cropping a real screenshot and
                    // measuring the pixel gap between the rendered icon and the arc's own
                    // pixels directly, not derived from the vector's stated bounds.
                    fun buttonOffset(
                        angleDeg: Double,
                        alignBottomToArc: Boolean = false,
                        iconSizePx: Float = 0f,
                        extraOffsetPx: Float = 0f,
                        extraRadiusPx: Float = 0f,
                        extraDxPx: Float = 0f,
                    ): IntOffset {
                        val angleRad = Math.toRadians(angleDeg)
                        val rawX = centerXPx + (buttonRadiusPx + extraRadiusPx) * cos(angleRad).toFloat() - buttonSizePx / 2f + extraDxPx
                        val x = rawX.coerceIn(0f, widthPx - buttonSizePx)
                        val y = if (alignBottomToArc) {
                            arcHeightPx - (buttonSizePx + iconSizePx) / 2f + extraOffsetPx
                        } else {
                            centerYPx + buttonRadiusPx * sin(angleRad).toFloat() - buttonSizePx / 2f
                        }
                        return IntOffset(x.roundToInt(), y.roundToInt())
                    }

                    DynamicProgressArc(
                        progress = timerProgress,
                        color = timerProjectColor,
                        modifier = Modifier.fillMaxWidth().height(arcHeight),
                    )

                    // The timer's own bottom edge lands exactly on the arc's ends; the
                    // project name is a separate line below it, outside the arc.
                    Box(modifier = Modifier.fillMaxWidth().height(arcHeight), contentAlignment = Alignment.BottomCenter) {
                        Text(
                            text = formatCountdown(nowTick, timerTarget.targetInstant),
                            style = MaterialTheme.typography.displaySmall,
                        )
                    }
                    Text(
                        text = timerProjectName ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = arcHeight + 4.dp),
                    )

                    // Start/stop are never disabled - they just log a timeline start/end
                    // event regardless of any current state; conflicts (if any) are
                    // resolved or ignored by hand later, not gated up front.
                    IconButton(
                        onClick = { actionState = DashboardActionState.StartPrompt },
                        modifier = Modifier.offset {
                            buttonOffset(220.0, extraDxPx = -with(density) { 16.dp.toPx() })
                        }.size(buttonSize),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Начать", modifier = Modifier.size(48.dp))
                    }
                    IconButton(
                        onClick = { stopProject(timerTarget.entry.project_id) },
                        modifier = Modifier.offset {
                            buttonOffset(
                                180.0,
                                alignBottomToArc = true,
                                iconSizePx = with(density) { 48.dp.toPx() },
                                extraOffsetPx = with(density) { 12.2.dp.toPx() },
                                extraRadiusPx = with(density) { 16.dp.toPx() },
                            )
                        }.size(buttonSize),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "Закончить", modifier = Modifier.size(48.dp))
                    }
                    IconButton(
                        onClick = {
                            instantPressTime = LocalTime.now()
                            instantError = ""
                            showInstantDialog = true
                        },
                        modifier = Modifier.offset {
                            buttonOffset(320.0, extraDxPx = with(density) { 16.dp.toPx() })
                        }.size(buttonSize),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_instant_sparkle),
                            contentDescription = "Инстант",
                            modifier = Modifier.size(44.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            editDynamicError = ""
                            showEditDynamicDialog = true
                        },
                        modifier = Modifier.offset {
                            buttonOffset(
                                0.0,
                                alignBottomToArc = true,
                                iconSizePx = with(density) { 44.dp.toPx() },
                                extraOffsetPx = with(density) { 5.7.dp.toPx() },
                                extraRadiusPx = with(density) { 16.dp.toPx() },
                            )
                        }.size(buttonSize),
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "Редактировать", modifier = Modifier.size(44.dp))
                    }
                }

                if (actionError.isNotBlank()) {
                    Text(
                        text = actionError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }

            Box(
                // top padding tuned so the gap between the project-name text and the gauge's
                // own Static ring matches the gap between Day D and the top bar above it
                // (target 219px) - measured via a clean single-screenshot pixel scan (text
                // bottom, ring top at the gauge's true 12-o'clock point), not eyeballed.
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 51.5.dp, bottom = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                DayGauge(
                    renderItems = renderItems,
                    projects = projects,
                    modifier = Modifier.size(280.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = today.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale("ru")))
                    .replaceFirstChar { it.titlecase(java.util.Locale("ru")) },
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
        }
    }

    if (showInstantDialog) {
        DashboardInstantDialog(
            projects = projects.filter { !it.archived },
            initialTime = instantPressTime,
            errorMessage = instantError,
            onDismiss = {
                showInstantDialog = false
                instantError = ""
            },
            onConfirm = { projectId, time, name ->
                scope.launch {
                    val occurredAt = today.atTime(time).atZone(zone).toInstant().toString()
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.createEvent(
                                serverUrl, accessClientId, accessClientSecret,
                                projectId = projectId, type = "instant",
                                occurredAt = occurredAt, label = name.ifBlank { null },
                            )
                        }
                    }.fold(
                        onSuccess = {
                            showInstantDialog = false
                            instantError = ""
                            refreshKey++
                        },
                        onFailure = { instantError = "Не удалось отметить событие" },
                    )
                }
            },
        )
    }

    if (actionState is DashboardActionState.StartPrompt && timerTarget != null && timerProjectName != null) {
        StartNameDialog(
            projectName = timerProjectName,
            initialName = timerProjectName,
            onDismiss = { actionState = DashboardActionState.None },
            onConfirm = { name ->
                actionState = DashboardActionState.None
                startTimerProject(name)
            },
        )
    }

    (actionState as? DashboardActionState.StartConflict)?.let { state ->
        val activeName = projects.firstOrNull { it.id == state.active.project_id }?.name ?: "проект"
        val newName = projects.firstOrNull { it.id == state.targetProjectId }?.name ?: "проект"
        StartConflictDialog(
            activeProjectName = activeName,
            newProjectName = newName,
            onCancel = { actionState = DashboardActionState.None },
            onFinishOnly = {
                actionState = DashboardActionState.None
                stopProject(state.active.project_id)
            },
            onFinishAndStart = {
                actionState = DashboardActionState.None
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.createEvent(
                                serverUrl, accessClientId, accessClientSecret,
                                projectId = state.active.project_id, type = "end",
                            )
                            ApiFactory.createEvent(
                                serverUrl, accessClientId, accessClientSecret,
                                projectId = state.targetProjectId, type = "start", label = state.name,
                            )
                        }
                    }.fold(
                        onSuccess = { refreshKey++ },
                        onFailure = { actionError = "Не удалось переключить проект" },
                    )
                }
            },
        )
    }

    if (showEditDynamicDialog && timerTarget != null) {
        val entry = timerTarget.entry
        DynamicEntryEditDialog(
            entry = entry,
            projects = projects.filter { !it.archived || it.id == entry.project_id },
            errorMessage = editDynamicError,
            zone = zone,
            onDismiss = {
                showEditDynamicDialog = false
                editDynamicError = ""
            },
            onRequestDelete = { actionState = DashboardActionState.DeleteDynamicConfirm },
            onSave = { projectId, start, end, name ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val timeChanged = start.toString() != entry.start_time || end.toString() != entry.end_time
                            val identityChanged = projectId != entry.project_id || name != (entry.name ?: "")
                            if (timeChanged) {
                                ApiFactory.createPlanChange(
                                    serverUrl, accessClientId, accessClientSecret,
                                    planEntryId = entry.id, changeType = "move",
                                    newStartTime = start.toString(), newEndTime = end.toString(),
                                )
                            }
                            if (identityChanged) {
                                ApiFactory.updatePlanEntry(
                                    serverUrl, accessClientId, accessClientSecret,
                                    id = entry.id, projectId = projectId, name = name,
                                )
                            }
                        }
                    }
                    result.onSuccess {
                        showEditDynamicDialog = false
                        editDynamicError = ""
                        refreshKey++
                    }.onFailure { editDynamicError = "Не удалось сохранить" }
                }
            },
        )
    }

    if (showDayDDialog) {
        val parsedDate = runCatching { LocalDate.parse(dayDDateText, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
        AlertDialog(
            onDismissRequest = { showDayDDialog = false },
            title = { Text("День Д") },
            text = {
                Column {
                    OutlinedTextField(
                        value = dayDDateText,
                        onValueChange = { dayDDateText = it },
                        label = { Text("Дата (гггг-мм-дд)") },
                        isError = dayDError.isNotBlank(),
                    )
                    if (dayDError.isNotBlank()) {
                        Text(dayDError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = parsedDate != null,
                    onClick = {
                        if (parsedDate != null) {
                            scope.launch {
                                settingsStore.setDayDAnchorDate(parsedDate)
                                showDayDDialog = false
                            }
                        } else {
                            dayDError = "Некорректная дата"
                        }
                    },
                ) { Text("Сохранить") }
            },
            dismissButton = {
                Row {
                    if (dayDAnchor != null) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    settingsStore.setDayDAnchorDate(null)
                                    showDayDDialog = false
                                }
                            },
                        ) { Text("Очистить", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { showDayDDialog = false }) { Text("Отмена") }
                }
            },
        )
    }

    if (actionState is DashboardActionState.DeleteDynamicConfirm && timerTarget != null) {
        val entry = timerTarget.entry
        ConfirmDeleteDialog(
            message = "Удалить из Dynamic? Static-запись останется.",
            onDismiss = { actionState = DashboardActionState.None },
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.createPlanChange(
                                serverUrl, accessClientId, accessClientSecret,
                                planEntryId = entry.id, changeType = "cancel",
                            )
                        }
                    }.onSuccess {
                        actionState = DashboardActionState.None
                        showEditDynamicDialog = false
                        editDynamicError = ""
                        refreshKey++
                    }.onFailure { editDynamicError = "Не удалось удалить" }
                }
            },
        )
    }
}

private data class DashboardFetch(
    val events: List<Event>,
    val staticEntries: List<PlanEntry>,
    val dynamicEntries: List<DynamicPlanEntry>,
)

private const val MINUTES_PER_DAY = 24 * 60f

/** 00:00 sits at the top (12-o'clock position), sweeping clockwise - a
 * genuine 24h clock face rather than the 12h convention. */
private fun angleForTime(time: LocalTime): Float {
    val minutes = time.hour * 60f + time.minute + time.second / 60f
    return minutes / MINUTES_PER_DAY * 360f - 90f
}

/**
 * A dome-shaped (semicircle) progress arc sitting above the countdown text -
 * elapsed/total fraction of the current Dynamic Plan block, ticking in
 * lockstep with the countdown (driven by the same `progress` value each
 * second, not its own animation loop). Empty (0f) while counting down to a
 * future entry's start rather than an in-progress one's end.
 */
@Composable
private fun DynamicProgressArc(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeWidthPx = 22.5.dp.toPx()
        val radius = (minOf(size.width, size.height * 2f) - strokeWidthPx) / 2f
        val center = Offset(size.width / 2f, size.height)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)

        drawArc(
            color = Color.White.copy(alpha = 0.1f),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Butt),
        )
        if (progress > 0f) {
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Butt),
            )
        }
    }
}

/**
 * The circular "speedometer": three concentric rings mirroring
 * [DayTimelineView]'s layer language (Static outermost/dashed, Dynamic
 * middle/translucent, Timeline innermost/solid), plus a needle for the
 * current time. Stateless and Compose-only - all the actual layout math
 * (LocalTime bounds, layer order) is already done by [buildDayRenderModel].
 */
@Composable
private fun DayGauge(
    renderItems: List<DayRenderItem>,
    projects: List<Project>,
    modifier: Modifier = Modifier,
) {
    fun colorFor(projectId: Int): Color {
        val project = projects.firstOrNull { it.id == projectId }
        return ProjectColors.colorFor(project?.color ?: "gray")
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = minOf(size.width, size.height) / 2f
        val ringGap = maxRadius * 0.14f
        val staticRadius = maxRadius - ringGap * 0.5f
        val dynamicRadius = maxRadius - ringGap * 1.6f
        val timelineRadius = maxRadius - ringGap * 2.7f
        val ringWidth = ringGap * 0.9f

        // Faint background tracks so empty rings are still visible.
        listOf(staticRadius, dynamicRadius, timelineRadius).forEach { radius ->
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = radius,
                center = center,
                style = Stroke(width = ringWidth),
            )
        }

        renderItems.forEach { item ->
            if (item.layerType == RenderLayerType.TIMELINE_INSTANT) return@forEach
            val color = colorFor(item.projectId)
            val effectiveEnd = if (item.isFadeGradient) {
                item.startTime.plusMinutes(UNFINISHED_FADE_MINUTES.toLong())
            } else {
                item.endTime
            }
            val startAngle = angleForTime(item.startTime)
            var sweep = angleForTime(effectiveEnd) - startAngle
            if (sweep <= 0f) sweep += 360f
            sweep = sweep.coerceIn(1.5f, 359f)

            when (item.layerType) {
                RenderLayerType.STATIC_PLAN -> drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - staticRadius, center.y - staticRadius),
                    size = androidx.compose.ui.geometry.Size(staticRadius * 2, staticRadius * 2),
                    style = Stroke(
                        width = ringWidth * 0.55f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    ),
                )

                RenderLayerType.DYNAMIC_PLAN -> drawArc(
                    color = color.copy(alpha = item.fillAlpha ?: DYNAMIC_PLAN_FILL_ALPHA),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - dynamicRadius, center.y - dynamicRadius),
                    size = androidx.compose.ui.geometry.Size(dynamicRadius * 2, dynamicRadius * 2),
                    style = Stroke(width = ringWidth),
                )

                RenderLayerType.TIMELINE_INTERVAL, RenderLayerType.TIMELINE_UNFINISHED -> drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - timelineRadius, center.y - timelineRadius),
                    size = androidx.compose.ui.geometry.Size(timelineRadius * 2, timelineRadius * 2),
                    alpha = item.fillAlpha ?: TIMELINE_FILL_ALPHA,
                    style = Stroke(width = ringWidth),
                )

                RenderLayerType.TIMELINE_INSTANT -> {}
            }
        }

        // INSTANT markers: small dots on the Timeline ring.
        renderItems.filter { it.layerType == RenderLayerType.TIMELINE_INSTANT }.forEach { marker ->
            val angle = Math.toRadians(angleForTime(marker.startTime).toDouble())
            val dot = Offset(
                center.x + timelineRadius * cos(angle).toFloat(),
                center.y + timelineRadius * sin(angle).toFloat(),
            )
            drawCircle(color = colorFor(marker.projectId), radius = ringWidth * 0.4f, center = dot)
        }

        // Current-time needle.
        val nowAngle = Math.toRadians(angleForTime(LocalTime.now()).toDouble())
        val needleEnd = Offset(
            center.x + maxRadius * cos(nowAngle).toFloat(),
            center.y + maxRadius * sin(nowAngle).toFloat(),
        )
        drawLine(color = Color.White, start = center, end = needleEnd, strokeWidth = 3.dp.toPx())
        drawCircle(color = Color.White, radius = 5.dp.toPx(), center = center)
    }
}
