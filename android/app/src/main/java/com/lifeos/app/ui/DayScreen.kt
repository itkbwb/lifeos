package com.lifeos.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

@Composable
fun DayScreen(
    plan: DayPlan,
    onStart: (Int) -> Unit,
    onPause: (Int) -> Unit,
    onResume: (Int) -> Unit,
    onComplete: (Int) -> Unit,
    onSkip: (Int) -> Unit,
) {
    var selected by remember { mutableStateOf<Block?>(null) }
    val day: LocalDate = remember(plan.date) { LocalDate.parse(plan.date) }
    val totalHeight = (TIMELINE_END_HOUR - TIMELINE_START_HOUR) * com.lifeos.app.ui.timeline.HOUR_HEIGHT_DP

    Row(
        modifier = Modifier
            .fillMaxSize()
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
                val startMin = minutesFromDayStart(block.planned_start, day)
                val endMin = minutesFromDayStart(block.planned_end, day)
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
