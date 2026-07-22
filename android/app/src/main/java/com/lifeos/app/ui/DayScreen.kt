package com.lifeos.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeos.app.data.Block
import com.lifeos.app.data.DayPlan
import com.lifeos.app.ui.timeline.BlockActionDialog
import com.lifeos.app.ui.timeline.HourGutter
import com.lifeos.app.ui.timeline.NowLine
import com.lifeos.app.ui.timeline.TIMELINE_END_HOUR
import com.lifeos.app.ui.timeline.TIMELINE_START_HOUR
import com.lifeos.app.ui.timeline.TimelineBlockCard
import com.lifeos.app.ui.timeline.dpForMinutes
import com.lifeos.app.ui.timeline.minutesFromDayStart
import com.lifeos.app.ui.timeline.nowMinutesFromDayStart
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val dayTitleFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale("ru"))

@Composable
fun DayScreen(
    plan: DayPlan,
    onStart: (Int) -> Unit,
    onPause: (Int) -> Unit,
    onResume: (Int) -> Unit,
    onComplete: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    onReopen: (Int) -> Unit,
    onQueue: (Int, Int) -> Unit,
    onUpdateTime: (Int, String, String) -> Unit,
    onNavigateDate: (String) -> Unit,
) {
    var selected by remember { mutableStateOf<Block?>(null) }
    val day: LocalDate = remember(plan.date) { LocalDate.parse(plan.date) }
    val totalHeight = (TIMELINE_END_HOUR - TIMELINE_START_HOUR) * com.lifeos.app.ui.timeline.HOUR_HEIGHT_DP

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onNavigateDate(day.minusDays(1).toString()) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Предыдущий день")
            }
            val weekday = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("ru"))
                .replaceFirstChar { it.uppercase() }
            Text(
                text = "$weekday, ${day.format(dayTitleFormatter)}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            IconButton(onClick = { onNavigateDate(day.plusDays(1).toString()) }) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Следующий день")
            }
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            HourGutter(TIMELINE_START_HOUR, TIMELINE_END_HOUR)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeight.dp)
                    .padding(start = 6.dp),
            ) {
                for (block in plan.blocks) {
                    val startMin = minutesFromDayStart(block.display_start, day)
                    val endMin = minutesFromDayStart(block.display_end, day)
                    val topDp = dpForMinutes(startMin)
                    val heightDp = dpForMinutes((endMin - startMin).coerceAtLeast(1))
                    TimelineBlockCard(
                        block = block,
                        heightDp = heightDp,
                        compact = false,
                        onClick = { selected = block },
                        modifier = Modifier.offset(y = topDp.dp),
                    )
                }
                val nowMin = nowMinutesFromDayStart(day)
                if (nowMin in 0..(24 * 60)) {
                    NowLine(modifier = Modifier.offset(y = dpForMinutes(nowMin).dp))
                }
            }
        }
    }

    val block = selected
    if (block != null) {
        BlockActionDialog(
            block = block,
            onDismiss = { selected = null },
            onStart = { onStart(block.id); selected = null },
            onPause = { onPause(block.id); selected = null },
            onResume = { onResume(block.id); selected = null },
            onComplete = { onComplete(block.id); selected = null },
            onSkip = { onSkip(block.id); selected = null },
            onReopen = { onReopen(block.id); selected = null },
            onQueue = { minutes -> onQueue(block.id, minutes); selected = null },
            onUpdateTime = { start, end -> onUpdateTime(block.id, start, end); selected = null },
        )
    }
}
