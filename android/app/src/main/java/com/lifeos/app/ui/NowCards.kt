package com.lifeos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeos.app.data.Block
import com.lifeos.app.ui.theme.Lavender200
import com.lifeos.app.ui.theme.Lavender300
import com.lifeos.app.ui.theme.Lavender500
import com.lifeos.app.ui.theme.SurfaceContainerHighDark

private fun formatHms(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return "%02d:%02d:%02d".format(h, m, sec)
}

@Composable
private fun BlockHeading(block: Block) {
    Text(text = (block.project_name ?: "LIFE OS").uppercase(), fontSize = 13.sp, color = Lavender300)
    Text(text = block.title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun AccentedCard(
    accent: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerHighDark),
    ) {
        Row {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxSize()
                    .background(accent),
            )
            Column(Modifier.padding(20.dp), content = content)
        }
    }
}

@Composable
private fun StateLabel(text: String, color: Color = Lavender500) {
    Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 1.sp)
}

@Composable
fun ReadyCard(
    block: Block,
    minutesLate: Int,
    onStart: () -> Unit,
    onReschedule: () -> Unit,
    onSkip: () -> Unit,
) {
    AccentedCard(accent = Lavender500) {
        StateLabel("ПОРА НАЧИНАТЬ")
        Spacer(Modifier.height(8.dp))
        BlockHeading(block)
        if (minutesLate > 0) {
            Spacer(Modifier.height(8.dp))
            Text("Опоздание: $minutesLate мин", color = Lavender200)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Lavender500, contentColor = Color(0xFF09080D)),
            ) { Text("Начать сейчас") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onReschedule) { Text("Перенести") }
            OutlinedButton(onClick = onSkip) { Text("Пропустить") }
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
    AccentedCard(accent = Lavender300) {
        StateLabel("В РАБОТЕ", color = Lavender300)
        Spacer(Modifier.height(8.dp))
        BlockHeading(block)
        Spacer(Modifier.height(20.dp))
        Text(
            text = formatHms(elapsedSeconds),
            fontSize = 52.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(text = "из запланированных ${formatHms(plannedSeconds)}", fontSize = 13.sp, color = Lavender300)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onPause) { Text("Пауза") }
            Button(
                onClick = onComplete,
                colors = ButtonDefaults.buttonColors(containerColor = Lavender500, contentColor = Color(0xFF09080D)),
            ) { Text("Завершить") }
        }
    }
}

@Composable
fun PausedCard(
    block: Block,
    pausedSeconds: Int,
    onResume: () -> Unit,
) {
    AccentedCard(accent = Lavender200) {
        StateLabel("НА ПАУЗЕ", color = Lavender200)
        Spacer(Modifier.height(8.dp))
        BlockHeading(block)
        Spacer(Modifier.height(12.dp))
        Text(text = formatHms(pausedSeconds), fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onResume,
            colors = ButtonDefaults.buttonColors(containerColor = Lavender500, contentColor = Color(0xFF09080D)),
        ) { Text("Продолжить") }
    }
}

@Composable
fun CompletedCard(block: Block) {
    AccentedCard(accent = SurfaceContainerHighDark) {
        StateLabel("ВЫПОЛНЕНО", color = Lavender300)
        Spacer(Modifier.height(8.dp))
        BlockHeading(block)
    }
}

@Composable
fun IdleCard(nextBlock: Block?) {
    AccentedCard(accent = SurfaceContainerHighDark) {
        Text("Сейчас нет активного блока", fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        if (nextBlock != null) {
            Text("Дальше: ${nextBlock.title}", color = Lavender300)
        } else {
            Text("План на сегодня завершён", color = Lavender300)
        }
    }
}
