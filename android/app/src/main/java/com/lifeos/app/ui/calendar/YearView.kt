package com.lifeos.app.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Month
import java.time.Year
import java.time.YearMonth

private const val YEAR_PAGE_COUNT = 201 // +/- 100 years
private const val YEAR_PAGE_CENTER = YEAR_PAGE_COUNT / 2

private fun yearPageToYear(base: Int, page: Int): Int = base + (page - YEAR_PAGE_CENTER)

private fun yearToYearPage(base: Int, year: Int): Int = YEAR_PAGE_CENTER + (year - base)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YearView(
    selectedYear: Int,
    onYearChange: (Int) -> Unit,
    onOpenMonth: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseYear = remember { Year.now().value }
    val pagerState = rememberPagerState(
        initialPage = yearToYearPage(baseYear, selectedYear),
        pageCount = { YEAR_PAGE_COUNT },
    )

    LaunchedEffect(pagerState, baseYear) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val year = yearPageToYear(baseYear, page)
            if (year != selectedYear) onYearChange(year)
        }
    }

    LaunchedEffect(selectedYear, baseYear) {
        val targetPage = yearToYearPage(baseYear, selectedYear)
        if (targetPage != pagerState.currentPage) pagerState.scrollToPage(targetPage)
    }

    HorizontalPager(state = pagerState, modifier = modifier.fillMaxSize()) { page ->
        val year = yearPageToYear(baseYear, page)
        val months = remember(year) { Month.values().map { YearMonth.of(year, it) } }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(months) { yearMonth ->
                MonthGrid(
                    yearMonth = yearMonth,
                    compact = true,
                    onMonthClick = { onOpenMonth(yearMonth) },
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}
