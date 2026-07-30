package com.lifeos.app.ui.calendar

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineLayoutTest {

    @Test
    fun `matched start-end pair becomes one interval`() {
        val events = listOf(
            startEvent(1, projectId = 1, time = LocalTime.of(9, 0)),
            endEvent(2, projectId = 1, time = LocalTime.of(10, 0)),
        )
        val layout = layoutDay(events, TEST_DATE, TEST_ZONE)

        assertEquals(1, layout.intervals.size)
        assertEquals(LocalTime.of(9, 0), layout.intervals[0].startTime)
        assertEquals(LocalTime.of(10, 0), layout.intervals[0].endTime)
        assertNull(layout.unfinished)
    }

    @Test
    fun `unmatched trailing start becomes unfinished not an interval`() {
        val events = listOf(startEvent(1, projectId = 1, time = LocalTime.of(9, 0)))
        val layout = layoutDay(events, TEST_DATE, TEST_ZONE)

        assertTrue(layout.intervals.isEmpty())
        assertEquals(1, layout.unfinished?.projectId)
        assertEquals(LocalTime.of(9, 0), layout.unfinished?.startTime)
    }

    @Test
    fun `interval spanning into the next day clips end to LocalTime MAX`() {
        val events = listOf(
            startEvent(1, projectId = 1, time = LocalTime.of(23, 0)),
            endEvent(2, projectId = 1, time = LocalTime.of(1, 0), date = TEST_DATE.plusDays(1)),
        )
        val layout = layoutDay(events, TEST_DATE, TEST_ZONE)

        assertEquals(1, layout.intervals.size)
        assertEquals(LocalTime.MAX, layout.intervals[0].endTime)
    }

    @Test
    fun `interval spanning from the previous day clips start to MIDNIGHT`() {
        val events = listOf(
            startEvent(1, projectId = 1, time = LocalTime.of(23, 0), date = TEST_DATE.minusDays(1)),
            endEvent(2, projectId = 1, time = LocalTime.of(1, 0)),
        )
        val layout = layoutDay(events, TEST_DATE, TEST_ZONE)

        assertEquals(1, layout.intervals.size)
        assertEquals(LocalTime.MIDNIGHT, layout.intervals[0].startTime)
    }

    @Test
    fun `very short intervals keep their exact time bounds`() {
        val events = listOf(
            startEvent(1, projectId = 1, time = LocalTime.of(9, 0)),
            endEvent(2, projectId = 1, time = LocalTime.of(9, 1)),
            startEvent(3, projectId = 2, time = LocalTime.of(10, 0)),
            endEvent(4, projectId = 2, time = LocalTime.of(10, 5)),
            startEvent(5, projectId = 3, time = LocalTime.of(11, 0)),
            endEvent(6, projectId = 3, time = LocalTime.of(11, 10)),
        )
        val layout = layoutDay(events, TEST_DATE, TEST_ZONE)

        assertEquals(3, layout.intervals.size)
        val byProject = layout.intervals.associateBy { it.projectId }
        assertEquals(LocalTime.of(9, 1), byProject.getValue(1).endTime)
        assertEquals(LocalTime.of(10, 5), byProject.getValue(2).endTime)
        assertEquals(LocalTime.of(11, 10), byProject.getValue(3).endTime)
    }

    @Test
    fun `instants are independent of plan data - layoutDay never takes plan input`() {
        val events = listOf(instantEvent(1, projectId = 1, time = LocalTime.of(9, 20)))
        val layoutA = layoutDay(events, TEST_DATE, TEST_ZONE)
        val layoutB = layoutDay(events, TEST_DATE, TEST_ZONE)

        // Same call twice, nothing plan-related can influence this - proves instants are
        // computed purely from events, never from Static/Dynamic plan entries.
        assertEquals(layoutA.instants, layoutB.instants)
        assertEquals(1, layoutA.instants.size)
        assertEquals(LocalTime.of(9, 20), layoutA.instants[0].time)
    }

    @Test
    fun `layoutStaticPlan clips to day boundaries the same way intervals do`() {
        // start() and end() default to the same TEST_DATE, so a span crossing midnight
        // needs its end anchored to the next day explicitly.
        val spanning = staticPlanEntry(
            1,
            projectId = 1,
            start = LocalTime.of(23, 0),
            end = LocalTime.of(1, 0),
            date = TEST_DATE,
        ).let { it.copy(end_time = TEST_DATE.plusDays(1).atTime(1, 0).atZone(TEST_ZONE).toInstant().toString()) }

        val blocks = layoutStaticPlan(listOf(spanning), TEST_DATE, TEST_ZONE)

        assertEquals(1, blocks.size)
        assertEquals(LocalTime.of(23, 0), blocks[0].startTime)
        assertEquals(LocalTime.MAX, blocks[0].endTime)
    }

    @Test
    fun `layoutStaticPlan and layoutDynamicPlan handle 1, 5 and 10 minute spans without distortion`() {
        val statics = listOf(
            staticPlanEntry(1, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(9, 1)),
            staticPlanEntry(2, projectId = 1, start = LocalTime.of(10, 0), end = LocalTime.of(10, 5)),
            staticPlanEntry(3, projectId = 1, start = LocalTime.of(11, 0), end = LocalTime.of(11, 10)),
        )
        val blocks = layoutStaticPlan(statics, TEST_DATE, TEST_ZONE)

        assertEquals(3, blocks.size)
        assertEquals(LocalTime.of(9, 1), blocks[0].endTime)
        assertEquals(LocalTime.of(10, 5), blocks[1].endTime)
        assertEquals(LocalTime.of(11, 10), blocks[2].endTime)
    }

    @Test
    fun `many consecutive short static entries keep distinct non-merged bounds`() {
        val entries = (0 until 15).map { i ->
            val start = LocalTime.of(8, 0).plusMinutes(i * 10L)
            staticPlanEntry(i, projectId = 1, start = start, end = start.plusMinutes(5))
        }
        val blocks = layoutStaticPlan(entries, TEST_DATE, TEST_ZONE)

        assertEquals(15, blocks.size)
        assertEquals(15, blocks.map { it.id }.toSet().size)
        blocks.forEachIndexed { index, block ->
            assertEquals(entries[index].start_time, TEST_DATE.atTime(block.startTime).atZone(TEST_ZONE).toInstant().toString())
        }
    }

    @Test
    fun `dynamic plan reflects entries independently of static`() {
        val dynamics = listOf(dynamicPlanEntry(1, projectId = 2, start = LocalTime.of(11, 0), end = LocalTime.of(12, 0)))
        val blocks = layoutDynamicPlan(dynamics, TEST_DATE, TEST_ZONE)

        assertEquals(1, blocks.size)
        assertEquals(2, blocks[0].projectId)
        assertEquals(LocalTime.of(11, 0), blocks[0].startTime)
        assertEquals(LocalTime.of(12, 0), blocks[0].endTime)
    }
}
