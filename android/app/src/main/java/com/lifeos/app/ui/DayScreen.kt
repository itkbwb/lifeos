package com.lifeos.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifeos.app.data.Block
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

@Composable
fun DayScreen(blocks: List<Block>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        items(blocks.sortedBy { it.planned_start }) { block ->
            ListItem(
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
}
