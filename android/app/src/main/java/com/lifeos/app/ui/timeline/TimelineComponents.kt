package com.lifeos.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeos.app.data.Block
import com.lifeos.app.ui.theme.Lavender100
import com.lifeos.app.ui.theme.Lavender200
import com.lifeos.app.ui.theme.Lavender300
import com.lifeos.app.ui.theme.Lavender500
import com.lifeos.app.ui.theme.SurfaceContainerHighDark
import com.lifeos.app.ui.theme.SurfaceVariantDark

private fun blockColor(status: String): Color = when (status) {
    "active", "paused" -> Lavender500
    "ready" -> Lavender300
    "completed" -> Color(0xFF4A465A)
    "skipped", "cancelled" -> Color(0xFF2A2638)
    else -> SurfaceVariantDark
}

/** One time-positioned block. Caller supplies absolute offsetY/height in dp
 * from a shared Box; this composable just draws the card content. */
@Composable
fun TimelineBlockCard(
    block: Block,
    heightDp: Int,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = blockColor(block.status)
    val muted = block.status in setOf("completed", "skipped", "cancelled", "rescheduled")
    Box(
        modifier = modifier
            .height(heightDp.dp.coerceAtLeastDp(18.dp))
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg.copy(alpha = if (muted) 0.35f else 0.9f))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Column {
            Text(
                text = block.title,
                fontSize = if (compact) 10.sp else 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (muted) Lavender200 else Color(0xFF09080D),
                maxLines = if (heightDp < 40) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact && heightDp >= 40) {
                Text(
                    text = "${formatLocalTime(block.planned_start)}–${formatLocalTime(block.planned_end)}",
                    fontSize = 10.sp,
                    color = if (muted) Lavender200 else Color(0xFF09080D).copy(alpha = 0.7f),
                )
            }
        }
    }
}

private fun androidx.compose.ui.unit.Dp.coerceAtLeastDp(min: androidx.compose.ui.unit.Dp) =
    if (this < min) min else this

@Composable
fun HourGutter(startHour: Int, endHour: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(36.dp)) {
        for (hour in startHour until endHour) {
            Box(Modifier.height(HOUR_HEIGHT_DP.dp)) {
                Text(
                    text = "%02d:00".format(hour),
                    fontSize = 10.sp,
                    color = Lavender300,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
    }
}

@Composable
fun NowLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(Lavender500),
    )
}
