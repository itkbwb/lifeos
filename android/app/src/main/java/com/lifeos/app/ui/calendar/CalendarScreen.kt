package com.lifeos.app.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.lifeos.app.data.ActiveProjectConflictException
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.Project
import com.lifeos.app.ui.InstantFormDialog
import com.lifeos.app.ui.StaticPlanFormDialog
import com.lifeos.app.ui.TimelineFormDialog
import java.time.Instant
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
    // Dates with a special reminder (chapter: special reminders) - drawn as a small star on
    // Day/Week/Month, including future dates. Fetched once per serverUrl (the reminder list is
    // small for a personal app) rather than re-fetched per visible range.
    var reminderDates by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    // Day scale's "+" (plan a Static entry) used to live inside DayTimelineView,
    // per DayPager page - which meant it scrolled away with the content and, once
    // scrolling was hoisted out, would only render at the bottom of a 1536dp-tall
    // grid instead of staying fixed on screen. Hoisted here instead, keyed off
    // selectedDate (kept in sync by DayPager) rather than a per-page date.
    var showAddMenu by remember { mutableStateOf(false) }
    var showPlanDialog by remember { mutableStateOf(false) }
    var planErrorMessage by remember { mutableStateOf("") }
    var planRefreshKey by remember { mutableStateOf(0) }
    // Timeline/Instant siblings of the Static Plan dialog above - same "+" menu, each with
    // its own date field (unlike the live Play/Instant buttons on Dashboard/Projects, which
    // always use "now").
    var showTimelineDialog by remember { mutableStateOf(false) }
    var timelineErrorMessage by remember { mutableStateOf("") }
    var showInstantDialog by remember { mutableStateOf(false) }
    var instantErrorMessage by remember { mutableStateOf("") }
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

    LaunchedEffect(serverUrl) {
        val zone = ZoneId.systemDefault()
        withContext(Dispatchers.IO) {
            runCatching { ApiFactory.listReminders(serverUrl, accessClientId, accessClientSecret) }
        }.onSuccess { reminders ->
            reminderDates = reminders.map { Instant.parse(it.remind_at).atZone(zone).toLocalDate() }.toSet()
        }
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
                    if (scale == CalendarScale.Day && selectedDate in reminderDates) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Есть напоминание",
                            modifier = Modifier.size(16.dp).padding(start = 4.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
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

                    Box(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                        FloatingActionButton(onClick = { showAddMenu = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Добавить")
                        }
                        DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("План") },
                                onClick = { showAddMenu = false; showPlanDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Таймлайн") },
                                onClick = { showAddMenu = false; showTimelineDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Мгновенное") },
                                onClick = { showAddMenu = false; showInstantDialog = true },
                            )
                        }
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
                reminderDates = reminderDates,
                modifier = Modifier.padding(padding),
            )

            CalendarScale.Month -> MonthView(
                selectedYearMonth = YearMonth.from(selectedDate),
                onYearMonthChange = { ym -> selectedDate = ym.atDay(1) },
                onOpenWeek = { date ->
                    selectedDate = date
                    scale = CalendarScale.Week
                },
                reminderDates = reminderDates,
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

    if (showTimelineDialog) {
        TimelineFormDialog(
            projects = projects,
            initialDate = selectedDate,
            errorMessage = timelineErrorMessage,
            onDismiss = {
                showTimelineDialog = false
                timelineErrorMessage = ""
            },
            onConfirm = { projectId, date, startTime, endTime, name ->
                val zone = ZoneId.systemDefault()
                val startInstant = date.atTime(startTime).atZone(zone).toInstant().toString()
                val endInstant = date.atTime(endTime).atZone(zone).toInstant().toString()
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.createEvent(
                                serverUrl, accessClientId, accessClientSecret,
                                projectId = projectId, type = "start", occurredAt = startInstant, label = name,
                            )
                            ApiFactory.createEvent(
                                serverUrl, accessClientId, accessClientSecret,
                                projectId = projectId, type = "end", occurredAt = endInstant, label = name,
                            )
                        }
                    }.onSuccess {
                        showTimelineDialog = false
                        timelineErrorMessage = ""
                        planRefreshKey++
                    }.onFailure { e ->
                        timelineErrorMessage = if (e is ActiveProjectConflictException) {
                            "Уже есть активный проект — сначала завершите его"
                        } else {
                            "Не удалось сохранить таймлайн"
                        }
                    }
                }
            },
        )
    }

    if (showInstantDialog) {
        InstantFormDialog(
            projects = projects,
            initialDate = selectedDate,
            errorMessage = instantErrorMessage,
            onDismiss = {
                showInstantDialog = false
                instantErrorMessage = ""
            },
            onConfirm = { projectId, date, time, name ->
                val zone = ZoneId.systemDefault()
                val occurredAt = date.atTime(time).atZone(zone).toInstant().toString()
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ApiFactory.createEvent(
                                serverUrl, accessClientId, accessClientSecret,
                                projectId = projectId, type = "instant", occurredAt = occurredAt, label = name,
                            )
                        }
                    }.onSuccess {
                        showInstantDialog = false
                        instantErrorMessage = ""
                        planRefreshKey++
                    }.onFailure {
                        instantErrorMessage = "Не удалось сохранить событие"
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
