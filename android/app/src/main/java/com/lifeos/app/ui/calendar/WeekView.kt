package com.lifeos.app.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifeos.app.data.Project
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val RU = Locale("ru")

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
    val weekStartDate = remember(selectedDate) { weekStart(selectedDate) }
    val pagerState = rememberPagerState(
        initialPage = ChronoUnit.DAYS.between(weekStartDate, selectedDate).toInt(),
        pageCount = { 7 },
    )

    LaunchedEffect(pagerState, weekStartDate) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val date = weekStartDate.plusDays(page.toLong())
            if (date != selectedDate) onSelectDate(date)
        }
    }

    HorizontalPager(state = pagerState, modifier = modifier.fillMaxSize()) { page ->
        val date = weekStartDate.plusDays(page.toLong())
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = date.dayOfWeek.getDisplayName(TextStyle.SHORT, RU).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.width(12.dp))
                DayNumberBadge(
                    date = date,
                    modifier = Modifier.clickable { onOpenDay(date) },
                )
            }
            DayTimelineView(
                date = date,
                projects = projects,
                serverUrl = serverUrl,
                accessClientId = accessClientId,
                accessClientSecret = accessClientSecret,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
