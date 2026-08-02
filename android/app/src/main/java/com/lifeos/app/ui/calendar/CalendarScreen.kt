package com.lifeos.app.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.Project
import com.lifeos.app.ui.StaticPlanFormDialog
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    serverUrl: String,
    accessClientId: String,
    accessClientSecret: String,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableStateOf(CalendarScale.Day) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var scaleMenuExpanded by remember { mutableStateOf(false) }
    var dayListMode by remember { mutableStateOf(false) }
    var showDaySummary by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    // Day scale's "+" (plan a Static entry) used to live inside DayTimelineView,
    // per DayPager page - which meant it scrolled away with the content and, once
    // scrolling was hoisted out, would only render at the bottom of a 1536dp-tall
    // grid instead of staying fixed on screen. Hoisted here instead, keyed off
    // selectedDate (kept in sync by DayPager) rather than a per-page date.
    var showPlanDialog by remember { mutableStateOf(false) }
    var planErrorMessage by remember { mutableStateOf("") }
    var planRefreshKey by remember { mutableStateOf(0) }
    // The ONE vertical ScrollState for Day scale's timeline grid, wrapping the
    // whole DayPager instead of living inside each page's content - see
    // DayTimelineView's doc comment for why per-page scroll state was broken
    // (each swipe reset position, and briefly having two pages' ScrollState
    // alive at once during the drag corrupted the pager's own page<->date sync).
    val dayTimelineScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(serverUrl) {
        withContext(Dispatchers.IO) {
            runCatching { ApiFactory.listProjects(serverUrl, accessClientId, accessClientSecret) }
        }.onSuccess { projects = it }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = periodLabel(scale, selectedDate),
                        modifier = Modifier.clickable { scaleMenuExpanded = true },
                    )
                    ScaleSwitcher(
                        current = scale,
                        expanded = scaleMenuExpanded,
                        onExpandedChange = { scaleMenuExpanded = it },
                        onSelect = { scale = it },
                    )
                },
                actions = {
                    if (scale == CalendarScale.Day) {
                        IconButton(onClick = { dayListMode = !dayListMode }) {
                            Icon(
                                imageVector = if (dayListMode) Icons.Filled.DateRange else Icons.AutoMirrored.Filled.List,
                                contentDescription = if (dayListMode) "Показать таймлайн" else "Показать списком",
                            )
                        }
                        IconButton(onClick = { showDaySummary = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Редактировать день")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (scale) {
            CalendarScale.Day -> Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (dayListMode) {
                    DayPager(
                        selectedDate = selectedDate,
                        onSelectDate = { selectedDate = it },
                        modifier = Modifier.fillMaxSize(),
                    ) { date ->
                        DayEventListView(
                            date = date,
                            projects = projects,
                            serverUrl = serverUrl,
                            accessClientId = accessClientId,
                            accessClientSecret = accessClientSecret,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    // The ONE scrollable ancestor for the whole timeline grid - DayPager
                    // itself gets a fixed height (the grid's true content height, taller
                    // than the viewport) instead of fillMaxSize, which is what makes this
                    // Box's height (the actual viewport) scrollable at all.
                    Box(modifier = Modifier.fillMaxSize().verticalScroll(dayTimelineScrollState)) {
                        DayPager(
                            selectedDate = selectedDate,
                            onSelectDate = { selectedDate = it },
                            modifier = Modifier.fillMaxWidth().height((HOUR_ROW_HEIGHT_DP * 24).dp),
                        ) { date ->
                            DayTimelineView(
                                date = date,
                                projects = projects,
                                serverUrl = serverUrl,
                                accessClientId = accessClientId,
                                accessClientSecret = accessClientSecret,
                                planRefreshKey = planRefreshKey,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    FloatingActionButton(
                        onClick = { showPlanDialog = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Запланировать")
                    }
                }
            }

            CalendarScale.Week -> WeekView(
                selectedDate = selectedDate,
                onSelectDate = { selectedDate = it },
                onOpenDay = { date ->
                    selectedDate = date
                    scale = CalendarScale.Day
                },
                projects = projects,
                serverUrl = serverUrl,
                accessClientId = accessClientId,
                accessClientSecret = accessClientSecret,
                modifier = Modifier.padding(padding),
            )

            CalendarScale.Month -> MonthView(
                selectedYearMonth = YearMonth.from(selectedDate),
                onYearMonthChange = { ym -> selectedDate = ym.atDay(1) },
                onOpenWeek = { date ->
                    selectedDate = date
                    scale = CalendarScale.Week
                },
                modifier = Modifier.padding(padding),
            )

            CalendarScale.Year -> YearView(
                selectedYear = selectedDate.year,
                onYearChange = { year -> selectedDate = withYearClamped(selectedDate, year) },
                onOpenMonth = { yearMonth ->
                    selectedDate = yearMonth.atDay(1)
                    scale = CalendarScale.Month
                },
                modifier = Modifier.padding(padding),
            )
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
                val startInstant = selectedDate.atTime(startTime).atZone(zone).toInstant().toString()
                val endInstant = selectedDate.atTime(endTime).atZone(zone).toInstant().toString()
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

    if (showDaySummary) {
        DaySummarySheet(
            date = selectedDate,
            projects = projects,
            serverUrl = serverUrl,
            accessClientId = accessClientId,
            accessClientSecret = accessClientSecret,
            onDismiss = { showDaySummary = false },
        )
    }
}
