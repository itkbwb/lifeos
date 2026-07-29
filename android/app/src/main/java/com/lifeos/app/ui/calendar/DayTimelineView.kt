package com.lifeos.app.ui.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate

private const val HOUR_ROW_HEIGHT_DP = 64
private val HOURS = 0..23

/**
 * The vertical hour-by-hour grid, empty for now but sized/laid out exactly as it will need to be
 * once events render inside it. Shared by Day scale and every page of Week's pager.
 */
@Composable
fun DayTimelineView(date: LocalDate, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(HOURS.toList()) { hour ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HOUR_ROW_HEIGHT_DP.dp),
            ) {
                Text(
                    text = "%02d:00".format(hour),
                    modifier = Modifier.width(56.dp).padding(start = 8.dp, top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}
