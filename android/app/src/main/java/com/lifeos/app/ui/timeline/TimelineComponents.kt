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
import com.lifeos.app.ui.theme.Lavender200
import com.lifeos.app.ui.theme.Lavender300
import com.lifeos.app.ui.theme.Lavender500

/** Stable per-project/type pastel hues so blocks stay recognizable at a
 * glance across the day and week views, independent of their status. */
private val CategoryPalette = listOf(
    Color(0xFFB8E0D2), // mint
    Color(0xFFFFD6A5), // apricot
    Color(0xFFFFADAD), // coral
    Color(0xFFA0C4FF), // sky
    Color(0xFFCAB8FF), // lilac
    Color(0xFFFDFFB6), // butter
    Color(0xFF9BF6FF), // cyan
    Color(0xFFFFC6FF), // pink
)

private fun categoryPastel(block: Block): Color {
    val key = block.project_name ?: block.block_type
    val index = (key.hashCode() and 0x7fffffff) % CategoryPalette.size
    return CategoryPalette[index]
}

/** Picks readable text over an arbitrary background instead of hardcoding
 * dark/light per branch - this is what previously let a status fall through
 * to a near-black background with near-black text. */
private fun contrastingText(bg: Color): Color {
    val luminance = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    return if (luminance > 0.55f) Color(0xFF1B1626) else Color(0xFFF5F2FF)
}

private data class BlockPalette(val background: Color, val text: Color)

private fun paletteFor(block: Block): BlockPalette = when (block.status) {
    "active", "paused" -> BlockPalette(Lavender500, Color(0xFF09080D))
    "completed" -> BlockPalette(Color(0xFF4A465A).copy(alpha = 0.35f), Lavender200)
    "skipped", "cancelled", "rescheduled" -> BlockPalette(Color(0xFF2A2638).copy(alpha = 0.35f), Lavender200)
    else -> { // "planned", "ready", or any status not yet modeled - always legible
        val pastel = categoryPastel(block)
        BlockPalette(pastel, contrastingText(pastel))
    }
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
    val palette = paletteFor(block)
    Box(
        modifier = modifier
            .height(heightDp.dp.coerceAtLeastDp(18.dp))
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Column {
            Text(
                text = block.title,
                fontSize = if (compact) 10.sp else 13.sp,
                fontWeight = FontWeight.Bold,
                color = palette.text,
                maxLines = if (heightDp < 40) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact && heightDp >= 40) {
                Text(
                    text = "${formatLocalTime(block.planned_start)}–${formatLocalTime(block.planned_end)}",
                    fontSize = 10.sp,
                    color = palette.text.copy(alpha = 0.75f),
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
