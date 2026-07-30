package com.lifeos.app.ui.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lifeos.app.R
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.DynamicPlanEntry
import com.lifeos.app.data.Event
import com.lifeos.app.data.PlanEntry
import com.lifeos.app.data.Project
import com.lifeos.app.ui.DashboardInstantDialog
import com.lifeos.app.ui.theme.ProjectColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's default/home tab (chapter: dashboard) - a circular "speedometer"
 * gauge of today's Static/Dynamic/Timeline layers (same three-layer model as
 * [DayTimelineView], just wrapped into a 24h clock face instead of a
 * vertical bar), plus an Instant button that lets the time be edited
 * (defaults to the moment the button was pressed).
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

    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var staticEntries by remember { mutableStateOf<List<PlanEntry>>(emptyList()) }
    var dynamicEntries by remember { mutableStateOf<List<DynamicPlanEntry>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }

    var showInstantDialog by remember { mutableStateOf(false) }
    var instantPressTime by remember { mutableStateOf(LocalTime.now()) }
    var instantError by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

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
                Triple(
                    ApiFactory.listEvents(serverUrl, accessClientId, accessClientSecret, from = dayStart, to = dayEnd),
                    ApiFactory.listPlanEntries(serverUrl, accessClientId, accessClientSecret, from = dayStart, to = dayEnd),
                    ApiFactory.listDynamicPlan(serverUrl, accessClientId, accessClientSecret, from = dayStart, to = dayEnd),
                )
            }
        }.onSuccess { (e, s, d) ->
            events = e
            staticEntries = s
            dynamicEntries = d
        }
    }

    val layout = remember(events, today) { layoutDay(events, today) }
    val staticBlocks = remember(staticEntries, today) { layoutStaticPlan(staticEntries, today) }
    val dynamicBlocks = remember(dynamicEntries, today) { layoutDynamicPlan(dynamicEntries, today) }
    val renderItems = remember(layout, staticBlocks, dynamicBlocks) {
        buildDayRenderModel(layout, staticBlocks, dynamicBlocks)
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Дашборд") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    instantPressTime = LocalTime.now()
                    instantError = ""
                    showInstantDialog = true
                },
                icon = {
                    Icon(painter = painterResource(R.drawable.ic_instant_sparkle), contentDescription = null)
                },
                text = { Text("Инстант") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                DayGauge(
                    renderItems = renderItems,
                    projects = projects,
                    modifier = Modifier.size(280.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = today.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale("ru")))
                    .replaceFirstChar { it.titlecase(java.util.Locale("ru")) },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
}

private const val MINUTES_PER_DAY = 24 * 60f

/** 00:00 sits at the top (12-o'clock position), sweeping clockwise - a
 * genuine 24h clock face rather than the 12h convention. */
private fun angleForTime(time: LocalTime): Float {
    val minutes = time.hour * 60f + time.minute + time.second / 60f
    return minutes / MINUTES_PER_DAY * 360f - 90f
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
