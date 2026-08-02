package com.lifeos.app.ui.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.DynamicPlanEntry
import com.lifeos.app.data.Event
import com.lifeos.app.data.PlanEntry
import com.lifeos.app.data.Project
import com.lifeos.app.ui.theme.ProjectColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val RU = Locale("ru")
private const val WEEK_PAGE_COUNT = 10453 // +/- ~100 years in weeks
private const val WEEK_PAGE_CENTER = WEEK_PAGE_COUNT / 2
private const val WEEK_GUTTER_DP = 36

/**
 * Week scale (chapter: Google-Calendar-style week grid) - a real 7-column grid
 * sharing one hour axis, wrapped in its own near-infinite pager so weeks swipe
 * independently of Day's pager. Tapping a day's header number still opens Day
 * scale ([onOpenDay]), same as before.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WeekView(
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    projects: List<Project>,
    serverUrl: String,
    accessClientId: String,
    accessClientSecret: String,
    modifier: Modifier = Modifier,
) {
    val baseWeekStart = remember { weekStart(LocalDate.now()) }
    val selectedWeekStart = remember(selectedDate) { weekStart(selectedDate) }
    val pagerState = rememberPagerState(
        initialPage = WEEK_PAGE_CENTER + ChronoUnit.WEEKS.between(baseWeekStart, selectedWeekStart).toInt(),
        pageCount = { WEEK_PAGE_COUNT },
    )

    // Same stale-closure trap as DayPager (see its doc comment): this effect's
    // keys never change, so it runs once and never restarts - reading
    // selectedWeekStart/selectedDate/onSelectDate directly would freeze them
    // at their very first values forever, making the pager silently fail to
    // report landing back on that first week in one swipe direction.
    val currentSelectedWeekStart by rememberUpdatedState(selectedWeekStart)
    val currentSelectedDate by rememberUpdatedState(selectedDate)
    val currentOnSelectDate by rememberUpdatedState(onSelectDate)

    LaunchedEffect(pagerState, baseWeekStart) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val pageWeekStart = baseWeekStart.plusWeeks((page - WEEK_PAGE_CENTER).toLong())
            if (pageWeekStart != currentSelectedWeekStart) {
                // Preserve which weekday was selected, not just "Monday of the new week".
                val weekdayOffset = ChronoUnit.DAYS.between(currentSelectedWeekStart, currentSelectedDate)
                currentOnSelectDate(pageWeekStart.plusDays(weekdayOffset))
            }
        }
    }

    LaunchedEffect(selectedWeekStart, baseWeekStart) {
        val targetPage = WEEK_PAGE_CENTER + ChronoUnit.WEEKS.between(baseWeekStart, selectedWeekStart).toInt()
        if (targetPage != pagerState.currentPage) pagerState.scrollToPage(targetPage)
    }

    HorizontalPager(state = pagerState, modifier = modifier.fillMaxSize()) { page ->
        val pageWeekStart = baseWeekStart.plusWeeks((page - WEEK_PAGE_CENTER).toLong())
        WeekGrid(
            weekStartDate = pageWeekStart,
            projects = projects,
            serverUrl = serverUrl,
            accessClientId = accessClientId,
            accessClientSecret = accessClientSecret,
            onOpenDay = onOpenDay,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun WeekGrid(
    weekStartDate: LocalDate,
    projects: List<Project>,
    serverUrl: String,
    accessClientId: String,
    accessClientSecret: String,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(weekStartDate) { (0..6).map { weekStartDate.plusDays(it.toLong()) } }

    var events by remember(weekStartDate) { mutableStateOf<List<Event>>(emptyList()) }
    var staticEntries by remember(weekStartDate) { mutableStateOf<List<PlanEntry>>(emptyList()) }
    var dynamicEntries by remember(weekStartDate) { mutableStateOf<List<DynamicPlanEntry>>(emptyList()) }

    LaunchedEffect(weekStartDate, serverUrl) {
        val from = weekStartDate.atStartOfDay(zone).toInstant().toString()
        val to = weekStartDate.plusDays(7).atStartOfDay(zone).toInstant().toString()
        withContext(Dispatchers.IO) {
            runCatching {
                Triple(
                    ApiFactory.listEvents(serverUrl, accessClientId, accessClientSecret, from = from, to = to),
                    ApiFactory.listPlanEntries(serverUrl, accessClientId, accessClientSecret, from = from, to = to),
                    ApiFactory.listDynamicPlan(serverUrl, accessClientId, accessClientSecret, from = from, to = to),
                )
            }
        }.onSuccess { (e, s, d) ->
            events = e
            staticEntries = s
            dynamicEntries = d
        }
    }

    // One batched fetch for the whole week (not 7x per-day calls) - layoutDay/
    // layoutStaticPlan/layoutDynamicPlan are pure filters, cheap to run 7x locally.
    val renderItemsByDay = remember(events, staticEntries, dynamicEntries, days) {
        days.associateWith { day ->
            buildDayRenderModel(
                layoutDay(events, day),
                layoutStaticPlan(staticEntries, day),
                layoutDynamicPlan(dynamicEntries, day),
            )
        }
    }

    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Box(modifier = Modifier.width(WEEK_GUTTER_DP.dp))
            days.forEach { day ->
                Column(
                    modifier = Modifier.weight(1f).clickable { onOpenDay(day) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = day.dayOfWeek.getDisplayName(TextStyle.SHORT, RU).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    DayNumberBadge(date = day, compact = true)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        Row(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(modifier = Modifier.width(WEEK_GUTTER_DP.dp)) {
                (0..23).forEach { hour ->
                    Box(modifier = Modifier.fillMaxWidth().height(HOUR_ROW_HEIGHT_DP.dp)) {
                        Text(
                            text = hour.toString(),
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).height((HOUR_ROW_HEIGHT_DP * 24).dp)) {
                // Shared hour gridlines, drawn once behind every column.
                Column(modifier = Modifier.fillMaxSize()) {
                    (0..23).forEach { _ ->
                        HorizontalDivider(
                            modifier = Modifier.height(HOUR_ROW_HEIGHT_DP.dp).fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    days.forEach { day ->
                        DayColumn(
                            date = day,
                            renderItems = renderItemsByDay[day].orEmpty(),
                            projects = projects,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/** One day's worth of blocks in a narrow grid column - same three-layer visual
 * language as [DayTimelineView] (solid Timeline, translucent Dynamic, dashed
 * Static outline), just without text labels (no room at this width, matching
 * how dense a real Google Calendar week column reads too). */
@Composable
private fun DayColumn(
    date: LocalDate,
    renderItems: List<DayRenderItem>,
    projects: List<Project>,
    modifier: Modifier = Modifier,
) {
    fun colorFor(projectId: Int): Color {
        val project = projects.firstOrNull { it.id == projectId }
        return ProjectColors.colorFor(project?.color ?: "gray")
    }

    Box(modifier = modifier) {
        renderItems.forEach { item ->
            if (item.layerType == RenderLayerType.TIMELINE_INSTANT) return@forEach
            val top = yOffsetFor(item.startTime)
            val color = colorFor(item.projectId)
            val height = if (item.layerType == RenderLayerType.TIMELINE_UNFINISHED) {
                (UNFINISHED_FADE_MINUTES / 60f * HOUR_ROW_HEIGHT_DP).dp
            } else {
                blockHeight(top, yOffsetFor(item.endTime))
            }

            when (item.layerType) {
                RenderLayerType.STATIC_PLAN -> {
                    Canvas(
                        modifier = Modifier
                            .padding(horizontal = 1.dp)
                            .offset(y = top)
                            .fillMaxWidth()
                            .height(height),
                    ) {
                        val strokeWidthPx = 1.5.dp.toPx()
                        drawRoundRect(
                            color = ProjectColors.staticPlanOutlineColor(color),
                            topLeft = Offset(strokeWidthPx / 2, strokeWidthPx / 2),
                            size = Size(size.width - strokeWidthPx, size.height - strokeWidthPx),
                            style = Stroke(
                                width = strokeWidthPx,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
                            ),
                        )
                    }
                }

                RenderLayerType.DYNAMIC_PLAN -> Box(
                    modifier = Modifier
                        .padding(horizontal = 1.dp)
                        .offset(y = top)
                        .fillMaxWidth()
                        .height(height)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color.copy(alpha = item.fillAlpha ?: DYNAMIC_PLAN_FILL_ALPHA)),
                )

                RenderLayerType.TIMELINE_INTERVAL -> Box(
                    modifier = Modifier
                        .padding(horizontal = 1.dp)
                        .offset(y = top)
                        .fillMaxWidth()
                        .height(height)
                        .clip(RoundedCornerShape(3.dp))
                        .background(ProjectColors.timelineBlockColor(color)),
                )

                RenderLayerType.TIMELINE_UNFINISHED -> Box(
                    modifier = Modifier
                        .padding(horizontal = 1.dp)
                        .offset(y = top)
                        .fillMaxWidth()
                        .height(height)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0f)))),
                )

                RenderLayerType.TIMELINE_INSTANT -> {}
            }
        }

        renderItems.filter { it.layerType == RenderLayerType.TIMELINE_INSTANT }.forEach { marker ->
            val top = yOffsetFor(marker.startTime)
            Box(
                modifier = Modifier
                    .offset(y = top - 3.dp)
                    .align(Alignment.TopCenter)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colorFor(marker.projectId)),
            )
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
