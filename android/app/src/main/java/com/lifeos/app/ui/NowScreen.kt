package com.lifeos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeos.app.data.Block
import com.lifeos.app.data.Dashboard
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun parseTime(s: String): LocalTime = LocalTime.parse(s)

private fun currentAndNext(blocks: List<Block>, now: LocalTime): Pair<Block?, Block?> {
    val sorted = blocks.sortedBy { it.start_time }
    val current = sorted.firstOrNull { b ->
        val start = parseTime(b.start_time)
        val end = parseTime(b.end_time)
        !now.isBefore(start) && now.isBefore(end)
    }
    val next = sorted.firstOrNull { parseTime(it.start_time).isAfter(now) }
    return current to next
}

@Composable
fun NowScreen(
    dashboard: Dashboard,
    onComplete: (Int) -> Unit,
    onSkip: (Int) -> Unit,
) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1000)
        }
    }

    val (current, next) = remember(dashboard, now) { currentAndNext(dashboard.blocks, now) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = now.format(timeFormatter), fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))

        if (current != null) {
            val remainingSeconds = Duration.between(now, parseTime(current.end_time))
                .seconds
                .coerceAtLeast(0)
            val hh = remainingSeconds / 3600
            val mm = (remainingSeconds % 3600) / 60
            val ss = remainingSeconds % 60

            Text(text = (current.project_name ?: "LIFE OS").uppercase(), fontSize = 13.sp)
            Text(text = current.title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "%02d:%02d:%02d".format(hh, mm, ss),
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onComplete(current.id) }) { Text("Завершить блок") }
                OutlinedButton(onClick = { onSkip(current.id) }) { Text("Пропустить") }
            }
        } else {
            Text("Сейчас нет активного блока", fontSize = 18.sp)
        }

        Spacer(Modifier.height(32.dp))

        if (next != null) {
            Text("Дальше: ${next.title} в ${next.start_time}", fontSize = 14.sp)
        } else {
            Text("План на сегодня завершён", fontSize = 14.sp)
        }
    }
}
