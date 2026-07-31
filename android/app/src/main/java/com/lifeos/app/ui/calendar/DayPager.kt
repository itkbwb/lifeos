package com.lifeos.app.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
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

    // A real finger's "vertical" drag is never perfectly straight - a diagonal
    // component during the first few pixels can make the Pager's own
    // horizontal-orientation touch-slop check win the ambiguous gesture before
    // the page content's verticalScroll gets a chance, "eating" slow vertical
    // drags (only a hard flick has enough single-axis velocity to reliably
    // tip the race the other way). Raising ONLY the Pager's touch slop makes
    // it require a much more clearly-horizontal gesture before it claims a
    // drag as a page-swipe, without touching the inner verticalScroll's own
    // (normal) slop - restored via a second CompositionLocalProvider around
    // the actual page content, which sits inside this one.
    val originalViewConfiguration = LocalViewConfiguration.current
    val pagerViewConfiguration = remember(originalViewConfiguration) {
        object : ViewConfiguration by originalViewConfiguration {
            override val touchSlop: Float = originalViewConfiguration.touchSlop * 3f
        }
    }

    CompositionLocalProvider(LocalViewConfiguration provides pagerViewConfiguration) {
        HorizontalPager(state = pagerState, modifier = modifier.fillMaxSize()) { page ->
            CompositionLocalProvider(LocalViewConfiguration provides originalViewConfiguration) {
                val date = baseDate.plusDays((page - DAY_PAGE_CENTER).toLong())
                content(date)
            }
        }
    }
}
