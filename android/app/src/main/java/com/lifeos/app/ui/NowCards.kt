package com.lifeos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeos.app.data.Block

private fun formatHms(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return "%02d:%02d:%02d".format(h, m, sec)
}

@Composable
private fun BlockHeading(block: Block) {
    Text(text = (block.project_name ?: "LIFE OS").uppercase(), fontSize = 13.sp)
    Text(text = block.title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun ReadyCard(
    block: Block,
    minutesLate: Int,
    onStart: () -> Unit,
    onReschedule: () -> Unit,
    onSkip: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("ПОРА НАЧИНАТЬ", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            BlockHeading(block)
            if (minutesLate > 0) {
                Spacer(Modifier.height(8.dp))
                Text("Опоздание: $minutesLate мин")
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStart) { Text("Начать сейчас") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onReschedule) { Text("Перенести") }
                OutlinedButton(onClick = onSkip) { Text("Пропустить") }
            }
        }
    }
}

@Composable
fun ActiveCard(
    block: Block,
    elapsedSeconds: Int,
    plannedSeconds: Int,
    onPause: () -> Unit,
    onComplete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("В РАБОТЕ", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            BlockHeading(block)
            Spacer(Modifier.height(16.dp))
            Text(text = formatHms(elapsedSeconds), fontSize = 48.sp, fontWeight = FontWeight.Bold)
            Text(text = "из запланированных ${formatHms(plannedSeconds)}", fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onPause) { Text("Пауза") }
                Button(onClick = onComplete) { Text("Завершить") }
            }
        }
    }
}

@Composable
fun PausedCard(
    block: Block,
    pausedSeconds: Int,
    onResume: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("НА ПАУЗЕ", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            BlockHeading(block)
            Spacer(Modifier.height(8.dp))
            Text(text = formatHms(pausedSeconds), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onResume) { Text("Продолжить") }
        }
    }
}

@Composable
fun CompletedCard(block: Block) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("ВЫПОЛНЕНО", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            BlockHeading(block)
        }
    }
}

@Composable
fun IdleCard(nextBlock: Block?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Сейчас нет активного блока", fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            if (nextBlock != null) {
                Text("Дальше: ${nextBlock.title}")
            } else {
                Text("План на сегодня завершён")
            }
        }
    }
}
