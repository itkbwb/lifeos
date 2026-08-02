package com.lifeos.app.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val RU = Locale("ru")
private const val MONTH_PAGE_COUNT = 2401 // +/- 100 years
private const val MONTH_PAGE_CENTER = MONTH_PAGE_COUNT / 2

private fun monthPageToYearMonth(base: YearMonth, page: Int): YearMonth =
    base.plusMonths((page - MONTH_PAGE_CENTER).toLong())

private fun yearMonthToMonthPage(base: YearMonth, yearMonth: YearMonth): Int =
    MONTH_PAGE_CENTER + ChronoUnit.MONTHS.between(base, yearMonth).toInt()

/**
 * The real calendar grid (6 fixed rows x 7 days), shared by Month scale (each day independently
 * clickable) and Year scale's compact per-month blocks (the whole block is one click target).
 */
@Composable
fun MonthGrid(
    yearMonth: YearMonth,
    compact: Boolean,
    onDayClick: ((java.time.LocalDate) -> Unit)? = null,
    onMonthClick: (() -> Unit)? = null,
    reminderDates: Set<LocalDate> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val dates = remember(yearMonth) { monthGridDates(yearMonth) }
    val weekdayHeaders = remember { (0 until 7).map { DayOfWeek.MONDAY.plus(it.toLong()) } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onMonthClick != null) it.clickable(onClick = onMonthClick) else it },
    ) {
        if (compact) {
            Text(
                text = yearMonth.month.getDisplayName(TextStyle.FULL_STANDALONE, RU)
                    .replaceFirstChar { it.uppercase() },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                weekdayHeaders.forEach { dow ->
                    Text(
                        text = dow.getDisplayName(TextStyle.SHORT, RU),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        dates.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    val inMonth = date.month == yearMonth.month && date.year == yearMonth.year
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .let {
                                if (!compact && onDayClick != null) it.clickable { onDayClick(date) } else it
                            },
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            // Year's compact mini-months show only their own days (Google
                            // Calendar style); Month scale keeps adjacent-month days for a
                            // stable 6-row grid.
                            if (inMonth || !compact) {
                                DayNumberBadge(
                                    date = date,
                                    compact = compact,
                                    color = if (inMonth) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                        // Only in Month scale (not Year's compact mini-months, too small for it)
                        // and only for days that actually belong to this month.
                        if (!compact && inMonth && date in reminderDates) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = "Есть напоминание",
                                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(10.dp),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MonthView(
    selectedYearMonth: YearMonth,
    onYearMonthChange: (YearMonth) -> Unit,
    onOpenWeek: (java.time.LocalDate) -> Unit,
    reminderDates: Set<LocalDate> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val baseYearMonth = remember { YearMonth.now() }
    val pagerState = rememberPagerState(
        initialPage = yearMonthToMonthPage(baseYearMonth, selectedYearMonth),
        pageCount = { MONTH_PAGE_COUNT },
    )

    // Same stale-closure trap as DayPager (see its doc comment): this effect's
    // keys never change, so it runs once and never restarts.
    val currentSelectedYearMonth by rememberUpdatedState(selectedYearMonth)
    val currentOnYearMonthChange by rememberUpdatedState(onYearMonthChange)

    LaunchedEffect(pagerState, baseYearMonth) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val ym = monthPageToYearMonth(baseYearMonth, page)
            if (ym != currentSelectedYearMonth) currentOnYearMonthChange(ym)
        }
    }

    LaunchedEffect(selectedYearMonth, baseYearMonth) {
        val targetPage = yearMonthToMonthPage(baseYearMonth, selectedYearMonth)
        if (targetPage != pagerState.currentPage) pagerState.scrollToPage(targetPage)
    }

    HorizontalPager(state = pagerState, modifier = modifier.fillMaxSize()) { page ->
        val ym = monthPageToYearMonth(baseYearMonth, page)
        MonthGrid(
            yearMonth = ym,
            compact = false,
            onDayClick = onOpenWeek,
            reminderDates = reminderDates,
            modifier = Modifier.padding(8.dp),
        )
    }
}
