package com.lifeos.app.ui.timeline

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeos.app.data.Block
import com.lifeos.app.ui.theme.Lavender300
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val breakPresetsMinutes = listOf(0, 5, 10, 15, 30)

fun formatLocalTime(iso: String): String =
    OffsetDateTime.parse(iso).atZoneSameInstant(SEOUL).format(timeFormatter)

val statusLabels = mapOf(
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
fun BlockActionDialog(
    block: Block,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    onReopen: () -> Unit,
    onQueue: (breakMinutes: Int) -> Unit,
) {
    var pickingBreak by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(block.title, fontWeight = FontWeight.Bold) },
        text = {
            if (pickingBreak) {
                Text("Сколько отдыха дать перед этой задачей?")
            } else {
                Column {
                    Text("${formatLocalTime(block.planned_start)}–${formatLocalTime(block.planned_end)}")
                    if (block.project_name != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(block.project_name, color = Lavender300)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Статус: ${statusLabels[block.status] ?: block.status}",
                        color = Lavender300,
                    )
                }
            }
        },
        confirmButton = {
            if (pickingBreak) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    for (minutes in breakPresetsMinutes) {
                        OutlinedButton(onClick = { onQueue(minutes) }) {
                            Text(if (minutes == 0) "Сразу" else "$minutes мин")
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (block.status) {
                        "planned", "ready" -> {
                            OutlinedButton(onClick = onSkip) { Text("Пропустить") }
                            OutlinedButton(onClick = { pickingBreak = true }) { Text("В очередь") }
                            Button(onClick = onStart) { Text("Начать") }
                        }
                        "active" -> {
                            OutlinedButton(onClick = onPause) { Text("Пауза") }
                            Button(onClick = onComplete) { Text("Завершить") }
                        }
                        "paused" -> {
                            Button(onClick = onResume) { Text("Продолжить") }
                        }
                        "completed", "skipped", "cancelled" -> {
                            OutlinedButton(onClick = onReopen) { Text("Вернуть в план") }
                        }
                        else -> {}
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}
