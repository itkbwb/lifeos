package com.lifeos.app.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.Project
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
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
    // Hoisted above DayPager (not inside its per-date content lambda) so
    // swiping from one day to the next keeps the same vertical scroll
    // position instead of each day starting back at the top - see
    // DayTimelineView/DayEventListView's scrollState/listState params.
    val dayTimelineScrollState = rememberScrollState()
    val dayListState = rememberLazyListState()

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
            CalendarScale.Day -> DayPager(
                selectedDate = selectedDate,
                onSelectDate = { selectedDate = it },
                modifier = Modifier.padding(padding),
            ) { date ->
                if (dayListMode) {
                    DayEventListView(
                        date = date,
                        projects = projects,
                        serverUrl = serverUrl,
                        accessClientId = accessClientId,
                        accessClientSecret = accessClientSecret,
                        listState = dayListState,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    DayTimelineView(
                        date = date,
                        projects = projects,
                        serverUrl = serverUrl,
                        accessClientId = accessClientId,
                        accessClientSecret = accessClientSecret,
                        scrollState = dayTimelineScrollState,
                        modifier = Modifier.fillMaxSize(),
                    )
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
