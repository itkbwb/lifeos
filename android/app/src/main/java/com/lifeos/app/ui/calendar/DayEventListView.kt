package com.lifeos.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.Event
import com.lifeos.app.data.Project
import com.lifeos.app.ui.theme.ProjectColors
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

private sealed class DayEventRow(open val time: LocalTime) {
    data class Interval(
        override val time: LocalTime,
        val endTime: LocalTime,
        val projectId: Int,
        val name: String?,
        val key: Int,
    ) : DayEventRow(time)

    data class Unfinished(
        override val time: LocalTime,
        val projectId: Int,
        val name: String?,
        val key: Int,
    ) : DayEventRow(time)

    data class Instant(
        override val time: LocalTime,
        val projectId: Int,
        val name: String?,
        val key: Int,
    ) : DayEventRow(time)
}

private fun durationLabel(start: LocalTime, end: LocalTime): String {
    val minutes = Duration.between(start, if (end == LocalTime.MAX) LocalTime.of(23, 59) else end).toMinutes()
        .coerceAtLeast(0)
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours == 0L -> "$remainder мин"
        remainder == 0L -> "$hours ч"
        else -> "$hours ч $remainder мин"
    }
}

private fun projectName(projects: List<Project>, projectId: Int): String =
    projects.firstOrNull { it.id == projectId }?.name ?: "Проект"

private fun colorForProject(projects: List<Project>, projectId: Int): Color =
    ProjectColors.colorFor(projects.firstOrNull { it.id == projectId }?.color ?: "gray")

/**
 * A chronological list of a day's events (intervals, the trailing unfinished block, and
 * INSTANT markers) - the reading companion to DayTimelineView's graphical grid. Exists
 * because a very short interval or a dense cluster of INSTANTs renders too small on the
 * grid to show its name at all, even though the data has one.
 */
@Composable
fun DayEventListView(
    date: LocalDate,
    projects: List<Project>,
    serverUrl: String,
    accessClientId: String,
    accessClientSecret: String,
    // Hoisted by the caller (one instance shared across every day DayPager
    // swipes through) so the list's scroll position carries over day-to-day,
    // same reasoning as DayTimelineView's scrollState - see CalendarScreen.
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
) {
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }

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

    val layout = remember(events, date) { layoutDay(events, date) }
    val rows = remember(layout) {
        buildList {
            layout.intervals.forEach { add(DayEventRow.Interval(it.startTime, it.endTime, it.projectId, it.name, it.event.id)) }
            layout.unfinished?.let { add(DayEventRow.Unfinished(it.startTime, it.projectId, it.name, it.event.id)) }
            layout.instants.forEach { add(DayEventRow.Instant(it.time, it.projectId, it.name, it.event.id)) }
        }.sortedBy { it.time }
    }

    if (rows.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Событий нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        items(rows, key = { "${it::class.simpleName}-${it.key()}" }) { row ->
            DayEventRowView(row, projects)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        }
    }
}

private fun DayEventRow.key(): Int = when (this) {
    is DayEventRow.Interval -> key
    is DayEventRow.Unfinished -> key
    is DayEventRow.Instant -> key
}

@Composable
private fun DayEventRowView(row: DayEventRow, projects: List<Project>) {
    val projectId = when (row) {
        is DayEventRow.Interval -> row.projectId
        is DayEventRow.Unfinished -> row.projectId
        is DayEventRow.Instant -> row.projectId
    }
    val name = when (row) {
        is DayEventRow.Interval -> row.name
        is DayEventRow.Unfinished -> row.name
        is DayEventRow.Instant -> row.name
    }
    val color = colorForProject(projects, projectId)
    val title = name?.takeIf { it.isNotBlank() } ?: projectName(projects, projectId)

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = TIME_FORMAT.format(row.time),
            modifier = Modifier.width(52.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            val subtitle = when (row) {
                is DayEventRow.Interval -> "до ${TIME_FORMAT.format(row.endTime)} · ${durationLabel(row.time, row.endTime)}"
                is DayEventRow.Unfinished -> "идёт сейчас"
                is DayEventRow.Instant -> "отметка"
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
