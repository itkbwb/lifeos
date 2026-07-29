package com.lifeos.app.ui.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class CalendarScale { Day, Week, Month, Year }

private val RU = Locale("ru")
private val DAY_LABEL_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)
private val WEEK_EDGE_FORMAT = DateTimeFormatter.ofPattern("d MMM", RU)
private val MONTH_LABEL_FORMAT = DateTimeFormatter.ofPattern("LLLL yyyy", RU)

fun weekStart(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** Always 42 dates (6 weeks x 7 days), including leading/trailing days of adjacent months. */
fun monthGridDates(yearMonth: YearMonth): List<LocalDate> {
    val start = weekStart(yearMonth.atDay(1))
    return (0 until 42).map { start.plusDays(it.toLong()) }
}

fun isToday(date: LocalDate): Boolean = date == LocalDate.now()

/** Like `withYear`, but clamps Feb 29 -> Feb 28 instead of throwing on non-leap target years. */
fun withYearClamped(date: LocalDate, year: Int): LocalDate {
    val ym = YearMonth.of(year, date.month)
    return ym.atDay(minOf(date.dayOfMonth, ym.lengthOfMonth()))
}

fun formatDayLabel(date: LocalDate): String = date.format(DAY_LABEL_FORMAT)

fun formatWeekLabel(start: LocalDate, end: LocalDate): String =
    "${start.format(WEEK_EDGE_FORMAT)} – ${end.format(WEEK_EDGE_FORMAT)}"

fun formatMonthLabel(yearMonth: YearMonth): String = yearMonth.format(MONTH_LABEL_FORMAT)

fun formatYearLabel(year: Int): String = year.toString()

fun periodLabel(scale: CalendarScale, selectedDate: LocalDate): String = when (scale) {
    CalendarScale.Day -> formatDayLabel(selectedDate)
    CalendarScale.Week -> {
        val start = weekStart(selectedDate)
        formatWeekLabel(start, start.plusDays(6))
    }
    CalendarScale.Month -> formatMonthLabel(YearMonth.from(selectedDate))
    CalendarScale.Year -> formatYearLabel(selectedDate.year)
}
