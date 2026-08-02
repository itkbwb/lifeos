package com.lifeos.app.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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

    // This effect's keys (pagerState, baseDate) never change across the pager's
    // lifetime, so Compose starts it exactly once and never restarts it -
    // reading `selectedDate`/`onSelectDate` directly here would freeze them at
    // whatever they were on that first composition (a classic stale-closure
    // bug): every subsequent page change would keep comparing against that
    // FIRST selectedDate forever, never the current one. Concretely, that made
    // the page landing back on "today" (selectedDate's initial value) silently
    // fail to update the header, in only one swipe direction, no matter how
    // long the pager had been in use - rememberUpdatedState keeps this
    // effect's view of both always current without needing to restart it.
    val currentSelectedDate by rememberUpdatedState(selectedDate)
    val currentOnSelectDate by rememberUpdatedState(onSelectDate)

    LaunchedEffect(pagerState, baseDate) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val date = baseDate.plusDays((page - DAY_PAGE_CENTER).toLong())
            if (date != currentSelectedDate) currentOnSelectDate(date)
        }
    }

    LaunchedEffect(selectedDate, baseDate) {
        val targetPage = DAY_PAGE_CENTER + ChronoUnit.DAYS.between(baseDate, selectedDate).toInt()
        if (targetPage != pagerState.currentPage) pagerState.scrollToPage(targetPage)
    }

    // No touch-slop tweaking needed here (previously required when the page
    // content had its own verticalScroll competing with this horizontal
    // Pager for the same touch stream, at the same tree depth) - the caller
    // now wraps this whole pager in a single ancestor verticalScroll instead
    // of putting one inside each page, and Compose's nested-scroll dispatch
    // already disambiguates cleanly between two DIFFERENT-orientation
    // scrollables at different tree depths, no slop hack required.
    HorizontalPager(state = pagerState, modifier = modifier) { page ->
        val date = baseDate.plusDays((page - DAY_PAGE_CENTER).toLong())
        content(date)
    }
}
