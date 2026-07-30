package com.lifeos.app.ui.calendar

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lifeos.app.R
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.Event
import com.lifeos.app.data.Project
import com.lifeos.app.ui.theme.ProjectColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val HOUR_ROW_HEIGHT_DP = 64
private val HOURS = 0..23
private const val CONTENT_START_DP = 60
private const val CONTENT_END_DP = 8
private const val UNFINISHED_BLOCK_MINUTES = 20
private val UNFINISHED_BLOCK_HEIGHT = (UNFINISHED_BLOCK_MINUTES / 60f * HOUR_ROW_HEIGHT_DP).dp
private val INSTANT_ICON_SIZE = 16.dp

private fun yOffsetFor(time: LocalTime): Dp {
    val minutes = time.hour * 60 + time.minute + time.second / 60f
    return (minutes / 60f * HOUR_ROW_HEIGHT_DP).dp
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

    BoxWithConstraints(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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

        layout.intervals.forEach { block ->
            val top = yOffsetFor(block.startTime)
            val height = (yOffsetFor(block.endTime) - top).let { if (it < 2.dp) 2.dp else it }
            val color = colorFor(projects, block.projectId)
            Box(
                modifier = Modifier
                    .padding(start = CONTENT_START_DP.dp, end = CONTENT_END_DP.dp)
                    .offset(y = top)
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = 0.85f))
                    .clickable { onTapInterval(block.event) },
            ) {
                if (!block.name.isNullOrBlank()) {
                    Text(
                        text = block.name,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = ProjectColors.contrastingTextColor(color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        layout.unfinished?.let { block ->
            val top = yOffsetFor(block.startTime)
            val color = colorFor(projects, block.projectId)
            Box(
                modifier = Modifier
                    .padding(start = CONTENT_START_DP.dp, end = CONTENT_END_DP.dp)
                    .offset(y = top)
                    .fillMaxWidth()
                    .height(UNFINISHED_BLOCK_HEIGHT)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0f)))),
            ) {
                if (!block.name.isNullOrBlank()) {
                    Text(
                        text = block.name,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = ProjectColors.contrastingTextColor(color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        layout.instants.forEach { marker ->
            val top = yOffsetFor(marker.time)
            val color = colorFor(projects, marker.projectId)
            HorizontalDivider(
                modifier = Modifier
                    .padding(start = CONTENT_START_DP.dp, end = CONTENT_END_DP.dp)
                    .offset(y = top),
                thickness = 2.dp,
                color = color,
            )
            val xFraction = pseudoRandomFraction(marker.event.id)
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
    }
}
