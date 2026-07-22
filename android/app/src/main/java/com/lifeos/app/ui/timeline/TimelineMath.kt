package com.lifeos.app.ui.timeline

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

const val HOUR_HEIGHT_DP = 64
const val TIMELINE_START_HOUR = 0
const val TIMELINE_END_HOUR = 24

/** Minutes from local midnight of [day], clamped to [0, 24*60]. A block that
 * starts the day before or ends the day after is clipped at the boundary -
 * the tap dialog still shows its real, un-clipped times. */
fun minutesFromDayStart(iso: String, day: LocalDate): Int {
    val zdt = OffsetDateTime.parse(iso).atZoneSameInstant(SEOUL)
    val dayStart = day.atStartOfDay(SEOUL)
    val minutes = java.time.Duration.between(dayStart, zdt).toMinutes()
    return minutes.coerceIn(0, (24 * 60).toLong()).toInt()
}

fun localDate(iso: String): LocalDate = OffsetDateTime.parse(iso).atZoneSameInstant(SEOUL).toLocalDate()

fun nowMinutesFromDayStart(day: LocalDate): Int {
    val now = ZonedDateTime.now(SEOUL)
    if (now.toLocalDate() != day) return -1
    val dayStart = day.atStartOfDay(SEOUL)
    return java.time.Duration.between(dayStart, now).toMinutes().toInt()
}

fun dpForMinutes(minutes: Int): Int = (minutes * HOUR_HEIGHT_DP) / 60
