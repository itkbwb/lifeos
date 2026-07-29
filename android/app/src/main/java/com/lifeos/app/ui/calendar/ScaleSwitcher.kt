package com.lifeos.app.ui.calendar

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

private val SCALE_LABELS = mapOf(
    CalendarScale.Day to "Day",
    CalendarScale.Week to "Week",
    CalendarScale.Month to "Month",
    CalendarScale.Year to "Year",
)

@Composable
fun ScaleSwitcher(
    current: CalendarScale,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (CalendarScale) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
        listOf(CalendarScale.Day, CalendarScale.Week, CalendarScale.Month, CalendarScale.Year).forEach { scale ->
            DropdownMenuItem(
                text = { Text(if (scale == current) "${SCALE_LABELS[scale]} ✓" else SCALE_LABELS[scale].orEmpty()) },
                onClick = {
                    onExpandedChange(false)
                    onSelect(scale)
                },
            )
        }
    }
}
