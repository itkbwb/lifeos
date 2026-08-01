package com.lifeos.app.ui.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lifeos.app.R
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.DynamicPlanEntry
import com.lifeos.app.data.Event
import com.lifeos.app.data.PlanEntry
import com.lifeos.app.data.Project
import com.lifeos.app.ui.StaticPlanFormDialog
import com.lifeos.app.ui.theme.ProjectColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// internal (not private) so WeekGridView can share the exact same hour-row
// geometry - it draws its own multi-column grid rather than reusing
// DayTimelineContent, but must line up pixel-for-pixel with Day scale.
internal const val HOUR_ROW_HEIGHT_DP = 64
private val HOURS = 0..23
internal const val CONTENT_START_DP = 60
private const val CONTENT_END_DP = 8
private val UNFINISHED_BLOCK_HEIGHT = (UNFINISHED_FADE_MINUTES / 60f * HOUR_ROW_HEIGHT_DP).dp
private val INSTANT_ICON_SIZE = 16.dp
private val STATIC_PLAN_STROKE_WIDTH = 2.dp
private val STATIC_PLAN_DASH = 8.dp
private val STATIC_PLAN_GAP = 6.dp

// internal (not private) so unit tests can assert the Dp geometry directly without a
// Compose runtime - visibility only, no behavior change.
internal fun yOffsetFor(time: LocalTime): Dp {
    val minutes = time.hour * 60 + time.minute + time.second / 60f
    return (minutes / 60f * HOUR_ROW_HEIGHT_DP).dp
}

/** A block's rendered height for a [top, bottom] span, floored so very short intervals
 * (a 1-minute event) stay visible - this only affects pixel height, never the
 * underlying LocalTime bounds the block was computed from. */
internal fun blockHeight(top: Dp, bottom: Dp): Dp {
    val height = bottom - top
    return if (height < 2.dp) 2.dp else height
}

private fun colorFor(projects: List<Project>, projectId: Int): Color {
    val project = projects.firstOrNull { it.id == projectId }
    return ProjectColors.colorFor(project?.color ?: "gray")
}

/**
 * The vertical hour-by-hour grid with 10-minute subdivisions, rendering this day's
 * Timeline events (completed intervals, an at-most-one fading unfinished block, and
 * non-interactive INSTANT markers). Shared by Day scale and every page of Week's pager.
 */
@Composable
fun DayTimelineView(
    date: LocalDate,
    projects: List<Project>,
    serverUrl: String,
    accessClientId: String,
    accessClientSecret: String,
    onTapInterval: (Event) -> Unit = {},
    // Hoisted by the caller (one instance shared across every day DayPager
    // swipes through) so scrolling partway down a day and swiping to the
    // next one keeps that same vertical position, instead of each day's page
    // getting its own ScrollState reset to 0 - see DayPager/CalendarScreen.
    scrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
) {
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var staticPlanEntries by remember { mutableStateOf<List<PlanEntry>>(emptyList()) }
    var dynamicPlanEntries by remember { mutableStateOf<List<DynamicPlanEntry>>(emptyList()) }
    var planRefreshKey by remember { mutableStateOf(0) }
    var showPlanDialog by remember { mutableStateOf(false) }
    var planErrorMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(date, serverUrl) {
        val zone = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zone).toInstant().toString()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toString()
        withContext(Dispatchers.IO) {
            runCatching {
                ApiFactory.listEvents(serverUrl, accessClientId, accessClientSecret, from = dayStart, to = dayEnd)
            }
        }.onSuccess { events = it }
    }

    LaunchedEffect(date, serverUrl, planRefreshKey) {
        val zone = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zone).toInstant().toString()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toString()
        withContext(Dispatchers.IO) {
            runCatching {
                ApiFactory.listPlanEntries(serverUrl, accessClientId, accessClientSecret, from = dayStart, to = dayEnd)
            }
        }.onSuccess { staticPlanEntries = it }
        withContext(Dispatchers.IO) {
            runCatching {
                ApiFactory.listDynamicPlan(serverUrl, accessClientId, accessClientSecret, from = dayStart, to = dayEnd)
            }
        }.onSuccess { dynamicPlanEntries = it }
    }

    val layout = remember(events, date) { layoutDay(events, date) }
    val staticPlanBlocks = remember(staticPlanEntries, date) { layoutStaticPlan(staticPlanEntries, date) }
    val dynamicPlanBlocks = remember(dynamicPlanEntries, date) { layoutDynamicPlan(dynamicPlanEntries, date) }
    val renderItems = remember(layout, staticPlanBlocks, dynamicPlanBlocks) {
        buildDayRenderModel(layout, staticPlanBlocks, dynamicPlanBlocks)
    }
    val eventsById = remember(events) { events.associateBy { it.id } }

    Box(modifier = modifier.fillMaxSize()) {
        DayTimelineContent(
            date = date,
            renderItems = renderItems,
            projects = projects,
            eventsById = eventsById,
            onTapInterval = onTapInterval,
            scrollState = scrollState,
            modifier = Modifier.fillMaxSize(),
        )

        FloatingActionButton(
            onClick = { showPlanDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Запланировать")
        }
    }

    if (showPlanDialog) {
        StaticPlanFormDialog(
            projects = projects,
            errorMessage = planErrorMessage,
            serverUrl = serverUrl,
            accessClientId = accessClientId,
            accessClientSecret = accessClientSecret,
            onDismiss = {
                showPlanDialog = false
                planErrorMessage = ""
            },
            onConfirm = { projectId, startTime, endTime, name, subtaskId ->
                val zone = ZoneId.systemDefault()
                val startInstant = date.atTime(startTime).atZone(zone).toInstant().toString()
                val endInstant = date.atTime(endTime).atZone(zone).toInstant().toString()
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.createPlanEntry(
                                serverUrl,
                                accessClientId,
                                accessClientSecret,
                                projectId = projectId,
                                startTime = startInstant,
                                endTime = endInstant,
                                name = name,
                                subtaskId = subtaskId,
                            )
                        }
                    }.onSuccess {
                        showPlanDialog = false
                        planErrorMessage = ""
                        planRefreshKey++
                    }.onFailure {
                        planErrorMessage = "Не удалось сохранить план"
                    }
                }
            },
        )
    }
}

/**
 * The actual hour grid + three-layer drawing, with no network/state of its own - takes an
 * already-built [DayRenderItem] list and draws it. Split out from [DayTimelineView] so it
 * can be exercised directly (Paparazzi screenshot tests, previews) without a server.
 */
@Composable
internal fun DayTimelineContent(
    date: LocalDate,
    renderItems: List<DayRenderItem>,
    projects: List<Project>,
    eventsById: Map<Int, Event>,
    onTapInterval: (Event) -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.verticalScroll(scrollState)) {
        val contentWidth = maxWidth - CONTENT_START_DP.dp - CONTENT_END_DP.dp

        Column(modifier = Modifier.fillMaxWidth()) {
            HOURS.forEach { hour ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HOUR_ROW_HEIGHT_DP.dp),
                ) {
                    Text(
                        text = "%02d:00".format(hour),
                        modifier = Modifier.width(56.dp).padding(start = 8.dp, top = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                    )
                    for (tick in 0 until 6) {
                        HorizontalDivider(
                            modifier = Modifier
                                .padding(start = CONTENT_START_DP.dp)
                                .offset(y = (HOUR_ROW_HEIGHT_DP / 6f * tick).dp),
                            color = if (tick == 0) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            },
                        )
                    }
                }
            }
        }

        // Chapter 4.6 layer order (Dynamic bottom, Timeline middle, Static top) lives in
        // buildDayRenderModel's list order - this loop just draws each item by type,
        // it doesn't decide stacking.
        renderItems.forEach { item ->
            val top = yOffsetFor(item.startTime)
            val color = colorFor(projects, item.projectId)
            when (item.layerType) {
                RenderLayerType.DYNAMIC_PLAN -> {
                    val height = blockHeight(top, yOffsetFor(item.endTime))
                    Box(
                        modifier = Modifier
                            .padding(start = CONTENT_START_DP.dp, end = CONTENT_END_DP.dp)
                            .offset(y = top)
                            .fillMaxWidth()
                            .height(height)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color.copy(alpha = item.fillAlpha ?: DYNAMIC_PLAN_FILL_ALPHA)),
                        contentAlignment = Alignment.TopEnd,
                    ) {
                        item.label?.let { label ->
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }

                RenderLayerType.TIMELINE_INTERVAL -> {
                    val height = blockHeight(top, yOffsetFor(item.endTime))
                    Box(
                        modifier = Modifier
                            .padding(start = CONTENT_START_DP.dp, end = CONTENT_END_DP.dp)
                            .offset(y = top)
                            .fillMaxWidth()
                            .height(height)
                            .clip(RoundedCornerShape(4.dp))
                            // Opaque pre-blended color, not a semi-transparent overlay - see
                            // ProjectColors.timelineBlockColor: Dynamic Plan draws directly
                            // underneath, so an actually-translucent fill would ghost its
                            // label/wash through wherever the two layers coincide.
                            .background(ProjectColors.timelineBlockColor(color))
                            .clickable { eventsById[item.sourceId]?.let(onTapInterval) },
                    )
                    // Label is drawn in a later pass (see below), on top of INSTANT
                    // markers - an INSTANT's full-width line is allowed to cross this
                    // block's fill (and Dynamic's, underneath), but never this label text.
                }

                RenderLayerType.TIMELINE_UNFINISHED -> {
                    Box(
                        modifier = Modifier
                            .padding(start = CONTENT_START_DP.dp, end = CONTENT_END_DP.dp)
                            .offset(y = top)
                            .fillMaxWidth()
                            .height(UNFINISHED_BLOCK_HEIGHT)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0f)))),
                    )
                    // Label drawn later, same reasoning as TIMELINE_INTERVAL above.
                }

                RenderLayerType.TIMELINE_INSTANT -> {
                    HorizontalDivider(
                        modifier = Modifier
                            .padding(start = CONTENT_START_DP.dp, end = CONTENT_END_DP.dp)
                            .offset(y = top),
                        thickness = 2.dp,
                        color = color,
                    )
                    val xFraction = pseudoRandomFraction(item.sourceId)
                    val iconX = CONTENT_START_DP.dp + (contentWidth - INSTANT_ICON_SIZE) * xFraction
                    Icon(
                        painter = painterResource(R.drawable.ic_instant_sparkle),
                        contentDescription = null,
                        tint = color,
                        // Lowered so the line crosses through the middle of the icon's bottom
                        // ray, rather than merging with its horizontal side spikes.
                        modifier = Modifier
                            .offset(x = iconX, y = top - INSTANT_ICON_SIZE * 3 / 4)
                            .size(INSTANT_ICON_SIZE),
                    )
                }

                RenderLayerType.STATIC_PLAN -> {
                    val height = blockHeight(top, yOffsetFor(item.endTime))
                    val outlineColor = ProjectColors.staticPlanOutlineColor(color)
                    Canvas(
                        modifier = Modifier
                            .padding(start = CONTENT_START_DP.dp, end = CONTENT_END_DP.dp)
                            .offset(y = top)
                            .fillMaxWidth()
                            .height(height),
                    ) {
                        val strokeWidthPx = STATIC_PLAN_STROKE_WIDTH.toPx()
                        drawRoundRect(
                            color = outlineColor,
                            topLeft = Offset(strokeWidthPx / 2, strokeWidthPx / 2),
                            size = Size(size.width - strokeWidthPx, size.height - strokeWidthPx),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                            style = Stroke(
                                width = strokeWidthPx,
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(STATIC_PLAN_DASH.toPx(), STATIC_PLAN_GAP.toPx()),
                                ),
                            ),
                        )
                    }
                }
            }
        }

        // Timeline's own labels (interval + unfinished), redrawn as a final pass on top
        // of everything above - including INSTANT markers, whose full-width line is
        // otherwise allowed to cross this row (see the two branches above). Positioned
        // identically to where each block already draws its fill, so it lines up exactly.
        renderItems.forEach { item ->
            if (item.layerType != RenderLayerType.TIMELINE_INTERVAL && item.layerType != RenderLayerType.TIMELINE_UNFINISHED) {
                return@forEach
            }
            val label = item.label ?: return@forEach
            val top = yOffsetFor(item.startTime)
            val color = colorFor(projects, item.projectId)
            Box(
                modifier = Modifier
                    .padding(start = CONTENT_START_DP.dp, end = CONTENT_END_DP.dp)
                    .offset(y = top)
                    .fillMaxWidth()
                    .height(
                        if (item.layerType == RenderLayerType.TIMELINE_UNFINISHED) {
                            UNFINISHED_BLOCK_HEIGHT
                        } else {
                            blockHeight(top, yOffsetFor(item.endTime))
                        },
                    ),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = ProjectColors.contrastingTextColor(color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isToday(date)) {
            val nowTop = yOffsetFor(LocalTime.now())
            Box(
                modifier = Modifier
                    .offset(y = nowTop)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.error),
            )
        }
    }
}
