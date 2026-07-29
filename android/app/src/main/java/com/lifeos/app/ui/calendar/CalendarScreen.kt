package com.lifeos.app.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(modifier: Modifier = Modifier) {
    var scale by remember { mutableStateOf(CalendarScale.Day) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var scaleMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = periodLabel(scale, selectedDate),
                        modifier = Modifier.clickable { scaleMenuExpanded = true },
                    )
                    ScaleSwitcher(
                        current = scale,
                        expanded = scaleMenuExpanded,
                        onExpandedChange = { scaleMenuExpanded = it },
                        onSelect = { scale = it },
                    )
                },
            )
        },
    ) { padding ->
        when (scale) {
            CalendarScale.Day -> DayTimelineView(
                date = selectedDate,
                modifier = Modifier.padding(padding),
            )

            CalendarScale.Week -> WeekView(
                selectedDate = selectedDate,
                onSelectDate = { selectedDate = it },
                onOpenDay = { date ->
                    selectedDate = date
                    scale = CalendarScale.Day
                },
                modifier = Modifier.padding(padding),
            )

            CalendarScale.Month -> MonthView(
                selectedYearMonth = YearMonth.from(selectedDate),
                onYearMonthChange = { ym -> selectedDate = ym.atDay(1) },
                onOpenWeek = { date ->
                    selectedDate = date
                    scale = CalendarScale.Week
                },
                modifier = Modifier.padding(padding),
            )

            CalendarScale.Year -> YearView(
                selectedYear = selectedDate.year,
                onYearChange = { year -> selectedDate = withYearClamped(selectedDate, year) },
                onOpenMonth = { yearMonth ->
                    selectedDate = yearMonth.atDay(1)
                    scale = CalendarScale.Month
                },
                modifier = Modifier.padding(padding),
            )
        }
    }
}
