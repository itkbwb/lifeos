package com.lifeos.app.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val DAY_PAGE_COUNT = 73051 // +/- ~100 years in days
private const val DAY_PAGE_CENTER = DAY_PAGE_COUNT / 2

/**
 * Day scale (chapter: infinite day swipe) - lets Day be swiped day-to-day the
 * same near-infinite way Month/Year already page, instead of only changing
 * date via Week or the scale dropdown. [content] is re-composed per visible
 * date; callers own their own network fetch per date (as [DayTimelineView]/
 * [DayEventListView] already do).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DayPager(
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (LocalDate) -> Unit,
) {
    val baseDate = remember { LocalDate.now() }
    val pagerState = rememberPagerState(
        initialPage = DAY_PAGE_CENTER + ChronoUnit.DAYS.between(baseDate, selectedDate).toInt(),
        pageCount = { DAY_PAGE_COUNT },
    )

    LaunchedEffect(pagerState, baseDate) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val date = baseDate.plusDays((page - DAY_PAGE_CENTER).toLong())
            if (date != selectedDate) onSelectDate(date)
        }
    }

    LaunchedEffect(selectedDate, baseDate) {
        val targetPage = DAY_PAGE_CENTER + ChronoUnit.DAYS.between(baseDate, selectedDate).toInt()
        if (targetPage != pagerState.currentPage) pagerState.scrollToPage(targetPage)
    }

    HorizontalPager(state = pagerState, modifier = modifier.fillMaxSize()) { page ->
        val date = baseDate.plusDays((page - DAY_PAGE_CENTER).toLong())
        content(date)
    }
}
