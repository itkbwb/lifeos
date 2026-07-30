package com.lifeos.app.ui.calendar

import com.lifeos.app.data.DynamicPlanEntry
import com.lifeos.app.data.Event
import com.lifeos.app.data.PlanEntry
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Fixed day/zone every test builds fixtures against, so nothing here depends on the
 * machine's default timezone or the current date. */
val TEST_DATE: LocalDate = LocalDate.of(2026, 7, 30)
val TEST_ZONE: ZoneOffset = ZoneOffset.UTC

private fun instantAt(time: LocalTime, date: LocalDate): String =
    date.atTime(time).atZone(TEST_ZONE).toInstant().toString()

fun startEvent(id: Int, projectId: Int, time: LocalTime, label: String? = null, date: LocalDate = TEST_DATE): Event =
    Event(id, projectId, "start", instantAt(time, date), label, instantAt(time, date), null, null, null)

fun endEvent(id: Int, projectId: Int, time: LocalTime, date: LocalDate = TEST_DATE): Event =
    Event(id, projectId, "end", instantAt(time, date), null, instantAt(time, date), null, null, null)

fun instantEvent(id: Int, projectId: Int, time: LocalTime, label: String? = null, date: LocalDate = TEST_DATE): Event =
    Event(id, projectId, "instant", instantAt(time, date), label, instantAt(time, date), null, null, null)

fun staticPlanEntry(
    id: Int,
    projectId: Int,
    start: LocalTime,
    end: LocalTime,
    name: String? = null,
    date: LocalDate = TEST_DATE,
): PlanEntry = PlanEntry(id, projectId, instantAt(start, date), instantAt(end, date), name, instantAt(start, date))

fun dynamicPlanEntry(
    id: Int,
    projectId: Int,
    start: LocalTime,
    end: LocalTime,
    name: String? = null,
    date: LocalDate = TEST_DATE,
): DynamicPlanEntry = DynamicPlanEntry(id, projectId, instantAt(start, date), instantAt(end, date), name)
