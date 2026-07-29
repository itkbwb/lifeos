package com.lifeos.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** The single shared "today" visual: a filled circle behind the day number. */
@Composable
fun DayNumberBadge(
    date: LocalDate,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val size = if (compact) 24.dp else 32.dp
    val fontSize = if (compact) 12.sp else 16.sp
    Box(
        modifier = modifier.size(size).let {
            if (isToday(date)) it.background(MaterialTheme.colorScheme.primary, CircleShape) else it
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            fontSize = fontSize,
            lineHeight = fontSize,
            textAlign = TextAlign.Center,
            color = if (isToday(date)) MaterialTheme.colorScheme.onPrimary else color,
        )
    }
}
