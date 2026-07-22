package com.lifeos.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeos.app.data.DayDeviation
import com.lifeos.app.ui.theme.Lavender100
import com.lifeos.app.ui.theme.SurfaceVariantDark
import kotlinx.coroutines.delay

@Composable
fun NowScreen(
    state: NowUiState,
    dayDeviation: DayDeviation,
    fetchedAtMillis: Long,
    onStart: (Int) -> Unit,
    onPause: (Int) -> Unit,
    onResume: (Int) -> Unit,
    onComplete: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    onReschedule: (Int) -> Unit,
    onRestart: (Int) -> Unit,
    onSwitchTask: () -> Unit,
) {
    var tickMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var countdown by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        while (true) {
            delay(1000)
            tickMillis = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        if (dayDeviation.minutes_over_under_planned != 0) {
            DeviationBanner(dayDeviation)
            Spacer(Modifier.height(16.dp))
        }

        when (state) {
            is NowUiState.Ready -> ReadyCard(
                block = state.block,
                minutesLate = state.minutesLate,
                onStart = { onStart(state.block.id) },
                onReschedule = { onReschedule(state.block.id) },
                onSkip = { onSkip(state.block.id) },
            )

            is NowUiState.Active -> {
                val elapsedNow = state.session.elapsed_seconds +
                    ((tickMillis - fetchedAtMillis) / 1000).toInt().coerceAtLeast(0)
                ActiveCard(
                    block = state.block,
                    elapsedSeconds = elapsedNow,
                    plannedSeconds = state.block.planned_duration_minutes * 60,
                    countdown = countdown,
                    onToggleCountdown = { countdown = !countdown },
                    onPause = { onPause(state.block.id) },
                    onComplete = { onComplete(state.block.id) },
                    onRestart = { onRestart(state.block.id) },
                    onSwitchTask = onSwitchTask,
                )
            }

            is NowUiState.Paused -> PausedCard(
                block = state.block,
                pausedSeconds = state.session.elapsed_seconds,
                onResume = { onResume(state.block.id) },
                onRestart = { onRestart(state.block.id) },
                onSwitchTask = onSwitchTask,
            )

            is NowUiState.Completed -> CompletedCard(state.block)

            is NowUiState.Idle -> IdleCard(state.nextBlock)
        }
    }
}

@Composable
private fun DeviationBanner(deviation: DayDeviation) {
    val minutes = deviation.minutes_over_under_planned
    val text = if (minutes > 0) {
        "Ты потратил на ${minutes} мин больше плана"
    } else {
        "Отставание от плана: ${-minutes} мин"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Lavender100,
        )
    }
}
