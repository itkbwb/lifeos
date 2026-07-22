package com.lifeos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeos.app.data.Block
import com.lifeos.app.ui.theme.Lavender300
import com.lifeos.app.ui.theme.Lavender500
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val seoul = ZoneId.of("Asia/Seoul")

private fun formatLocalTime(iso: String): String =
    OffsetDateTime.parse(iso).atZoneSameInstant(seoul).format(timeFormatter)

private val statusLabels = mapOf(
    "ready" to "Пора начинать",
    "active" to "Выполняется",
    "paused" to "На паузе",
    "completed" to "Выполнено",
    "skipped" to "Пропущено",
    "cancelled" to "Отменено",
    "rescheduled" to "Перенесено",
    "planned" to "Запланировано",
)

private fun statusDotColor(status: String): Color = when (status) {
    "active", "paused" -> Lavender500
    "ready" -> Lavender300
    "completed" -> Color(0xFF5A5568)
    else -> Color(0xFF3A3550)
}

@Composable
fun DayScreen(
    blocks: List<Block>,
    onStart: (Int) -> Unit,
    onPause: (Int) -> Unit,
    onResume: (Int) -> Unit,
    onComplete: (Int) -> Unit,
    onSkip: (Int) -> Unit,
) {
    var selected by remember { mutableStateOf<Block?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        items(blocks.sortedBy { it.planned_start }) { block ->
            ListItem(
                modifier = Modifier.clickable { selected = block },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(statusDotColor(block.status), CircleShape),
                    )
                },
                headlineContent = { Text(block.title) },
                supportingContent = {
                    val start = formatLocalTime(block.planned_start)
                    val end = formatLocalTime(block.planned_end)
                    val status = statusLabels[block.status] ?: block.status
                    Text("$start–$end · $status")
                },
            )
            Divider()
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

@Composable
private fun BlockActionDialog(
    block: Block,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(block.title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("${formatLocalTime(block.planned_start)}–${formatLocalTime(block.planned_end)}")
                if (block.project_name != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(block.project_name, color = Lavender300)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (block.status) {
                    "planned", "ready" -> {
                        OutlinedButton(onClick = onSkip) { Text("Пропустить") }
                        Button(onClick = onStart) { Text("Начать") }
                    }
                    "active" -> {
                        OutlinedButton(onClick = onPause) { Text("Пауза") }
                        Button(onClick = onComplete) { Text("Завершить") }
                    }
                    "paused" -> {
                        Button(onClick = onResume) { Text("Продолжить") }
                    }
                    else -> {}
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}
