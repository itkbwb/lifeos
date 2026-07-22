package com.lifeos.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.lifeos.app.ui.theme.Lavender300
import com.lifeos.app.ui.theme.Lavender500
import com.lifeos.app.ui.timeline.BlockActionDialog
import com.lifeos.app.ui.timeline.HOUR_HEIGHT_DP
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

private val dayHeaderFormatter = DateTimeFormatter.ofPattern("d MMM")
private const val VISIBLE_DAYS = 1.75f

@Composable
fun WeekScreen(
    plans: List<DayPlan>,
    onStart: (Int) -> Unit,
    onPause: (Int) -> Unit,
    onResume: (Int) -> Unit,
    onComplete: (Int) -> Unit,
    onSkip: (Int) -> Unit,
) {
    if (plans.isEmpty()) return

    var selected by remember { mutableStateOf<Block?>(null) }
    val hScrollState = rememberScrollState()
    val vScrollState = rememberScrollState()
    val totalHeight = (TIMELINE_END_HOUR - TIMELINE_START_HOUR) * HOUR_HEIGHT_DP
    val today = remember { LocalDate.now() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gutterWidth = 36.dp
        val dayColumnWidth = ((maxWidth - gutterWidth) / VISIBLE_DAYS)

        Column(modifier = Modifier.fillMaxSize()) {
            // Day header row, horizontally synced with the timeline below.
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.width(gutterWidth))
                Row(modifier = Modifier.horizontalScroll(hScrollState)) {
                    for (plan in plans) {
                        val date = LocalDate.parse(plan.date)
                        val isToday = date == today
                        Column(
                            modifier = Modifier
                                .width(dayColumnWidth)
                                .padding(vertical = 6.dp),
                        ) {
                            Text(
                                text = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("ru")),
                                fontSize = 11.sp,
                                color = if (isToday) Lavender500 else Lavender300,
                            )
                            Text(
                                text = date.format(dayHeaderFormatter),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isToday) Lavender500 else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(vScrollState),
            ) {
                HourGutter(TIMELINE_START_HOUR, TIMELINE_END_HOUR, modifier = Modifier.padding(top = 0.dp))
                Row(modifier = Modifier.horizontalScroll(hScrollState)) {
                    for (plan in plans) {
                        val day = LocalDate.parse(plan.date)
                        Box(
                            modifier = Modifier
                                .width(dayColumnWidth)
                                .height(totalHeight.dp)
                                .padding(horizontal = 2.dp),
                        ) {
                            for (block in plan.blocks) {
                                val startMin = minutesFromDayStart(block.planned_start, day)
                                val endMin = minutesFromDayStart(block.planned_end, day)
                                val topDp = dpForMinutes(startMin)
                                val heightDp = dpForMinutes((endMin - startMin).coerceAtLeast(1))
                                TimelineBlockCard(
                                    block = block,
                                    heightDp = heightDp,
                                    compact = true,
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
        )
    }
}
