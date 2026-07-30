package com.lifeos.app.ui.calendar

import com.lifeos.app.data.Event
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

data class IntervalBlockData(
    val projectId: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val name: String?,
    val event: Event,
)

data class UnfinishedBlockData(
    val projectId: Int,
    val startTime: LocalTime,
    val name: String?,
    val event: Event,
)

data class InstantMarkerData(
    val projectId: Int,
    val time: LocalTime,
    val name: String?,
    val event: Event,
)

data class DayLayout(
    val intervals: List<IntervalBlockData>,
    val unfinished: UnfinishedBlockData?,
    val instants: List<InstantMarkerData>,
)

/**
 * A stable pseudo-random position in [0f, 1f) derived from an event id - same
 * value every time the app runs (no persisted state needed), used to spread
 * INSTANT icons along their line so consecutive events don't stack on top of
 * each other.
 */
fun pseudoRandomFraction(seed: Int): Float = Random(seed.toLong()).nextFloat()

private fun zonedDate(event: Event, zone: ZoneId): LocalDate =
    Instant.parse(event.occurred_at).atZone(zone).toLocalDate()

private fun zonedTime(event: Event, zone: ZoneId): LocalTime =
    Instant.parse(event.occurred_at).atZone(zone).toLocalTime()

/**
 * Turns a flat event list into renderable day-timeline pieces (pure, no Compose):
 * matched START/END pairs (clipped to this calendar day, carrying the START's
 * name), at most one trailing unfinished START, and INSTANT markers.
 */
fun layoutDay(events: List<Event>, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DayLayout {
    val byProject = events.filter { it.type == "start" || it.type == "end" }.groupBy { it.project_id }

    val intervals = mutableListOf<IntervalBlockData>()
    var unfinished: UnfinishedBlockData? = null

    byProject.forEach { (projectId, projectEvents) ->
        val sorted = projectEvents.sortedBy { Instant.parse(it.occurred_at) }
        var pendingStart: Event? = null
        for (e in sorted) {
            when (e.type) {
                "start" -> pendingStart = e
                "end" -> {
                    val start = pendingStart
                    if (start != null) {
                        val startDate = zonedDate(start, zone)
                        val endDate = zonedDate(e, zone)
                        if (startDate <= date && endDate >= date) {
                            val startTime = if (startDate == date) zonedTime(start, zone) else LocalTime.MIDNIGHT
                            val endTime = if (endDate == date) zonedTime(e, zone) else LocalTime.MAX
                            intervals += IntervalBlockData(projectId, startTime, endTime, start.label, e)
                        }
                        pendingStart = null
                    }
                }
            }
        }
        // A trailing unmatched START only renders on the day it actually happened -
        // it never extends into later days (see Chapter 3 scope notes).
        pendingStart?.let { start ->
            if (zonedDate(start, zone) == date) {
                unfinished = UnfinishedBlockData(projectId, zonedTime(start, zone), start.label, start)
            }
        }
    }

    val instants = events
        .filter { it.type == "instant" && zonedDate(it, zone) == date }
        .map { InstantMarkerData(it.project_id, zonedTime(it, zone), it.label, it) }

    return DayLayout(intervals, unfinished, instants)
}
