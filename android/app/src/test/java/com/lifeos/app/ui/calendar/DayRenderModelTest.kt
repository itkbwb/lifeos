package com.lifeos.app.ui.calendar

import com.lifeos.app.data.DynamicPlanEntry
import com.lifeos.app.data.Event
import com.lifeos.app.data.PlanEntry
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers chapter 4.6's three-layer contract (Dynamic bottom, Timeline middle, Static
 * top; color = project, type = draw style) at the [buildDayRenderModel] level - pure
 * JVM, no Compose runtime, so bounds/ordering/fill/stroke can be asserted directly
 * instead of through pixel coordinates.
 */
class DayRenderModelTest {

    private fun model(
        events: List<Event> = emptyList(),
        statics: List<PlanEntry> = emptyList(),
        dynamics: List<DynamicPlanEntry> = emptyList(),
    ): List<DayRenderItem> {
        val dayLayout = layoutDay(events, TEST_DATE, TEST_ZONE)
        val staticBlocks = layoutStaticPlan(statics, TEST_DATE, TEST_ZONE)
        val dynamicBlocks = layoutDynamicPlan(dynamics, TEST_DATE, TEST_ZONE)
        return buildDayRenderModel(dayLayout, staticBlocks, dynamicBlocks)
    }

    // --- Scenario 1: full overlap of all three layers ---------------------------------

    @Test
    fun `scenario 1 - full overlap keeps three distinct items in Dynamic, Timeline, Static order`() {
        val items = model(
            events = listOf(startEvent(1, projectId = 1, time = LocalTime.of(9, 0)), endEvent(2, projectId = 1, time = LocalTime.of(10, 0))),
            statics = listOf(staticPlanEntry(10, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
            dynamics = listOf(dynamicPlanEntry(20, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
        )

        assertEquals(3, items.size)
        // list order is draw order: Dynamic first (bottom), Static last (top).
        assertEquals(RenderLayerType.DYNAMIC_PLAN, items[0].layerType)
        assertEquals(RenderLayerType.TIMELINE_INTERVAL, items[1].layerType)
        assertEquals(RenderLayerType.STATIC_PLAN, items[2].layerType)
        assertTrue(items[0].zIndex < items[1].zIndex)
        assertTrue(items[1].zIndex < items[2].zIndex)

        // Timeline stays fully opaque (its own constant, unaffected by Dynamic's).
        assertEquals(TIMELINE_FILL_ALPHA, items[1].fillAlpha)
        assertEquals(false, items[1].isDashedStroke)
        // Static has no fill at all.
        assertNull(items[2].fillAlpha)
        assertTrue(items[2].isDashedStroke)
        // Dynamic uses its own centralized alpha, distinct from Timeline's.
        assertEquals(DYNAMIC_PLAN_FILL_ALPHA, items[0].fillAlpha)

        // Same project on all three - color agrees, no layer overwrote another's data.
        assertTrue(items.all { it.projectId == 1 })
        // No coalescing: three separate source ids survive (event/static/dynamic ids differ).
        // The Timeline interval's sourceId is the END event's id (layoutDay pairs a
        // start/end span under the closing event), so it's 2 here, not the start's 1.
        assertEquals(setOf(2, 10, 20), items.map { it.sourceId }.toSet())
    }

    // --- Scenario 2: Timeline shorter than the plan ------------------------------------

    @Test
    fun `scenario 2 - timeline ending early does not shrink or move the plan layers`() {
        val items = model(
            events = listOf(startEvent(1, projectId = 1, time = LocalTime.of(9, 0)), endEvent(2, projectId = 1, time = LocalTime.of(9, 30))),
            statics = listOf(staticPlanEntry(10, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
            dynamics = listOf(dynamicPlanEntry(20, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
        )

        val timeline = items.single { it.layerType == RenderLayerType.TIMELINE_INTERVAL }
        val static = items.single { it.layerType == RenderLayerType.STATIC_PLAN }
        val dynamic = items.single { it.layerType == RenderLayerType.DYNAMIC_PLAN }

        assertEquals(LocalTime.of(9, 30), timeline.endTime)
        assertEquals(LocalTime.of(10, 0), static.endTime)
        assertEquals(LocalTime.of(10, 0), dynamic.endTime)
    }

    // --- Scenario 3: Timeline starting later -------------------------------------------

    @Test
    fun `scenario 3 - timeline starting late leaves the plan layers visible from 09-00`() {
        val items = model(
            events = listOf(startEvent(1, projectId = 1, time = LocalTime.of(9, 20)), endEvent(2, projectId = 1, time = LocalTime.of(10, 0))),
            statics = listOf(staticPlanEntry(10, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
            dynamics = listOf(dynamicPlanEntry(20, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
        )

        assertEquals(LocalTime.of(9, 20), items.single { it.layerType == RenderLayerType.TIMELINE_INTERVAL }.startTime)
        assertEquals(LocalTime.of(9, 0), items.single { it.layerType == RenderLayerType.STATIC_PLAN }.startTime)
        assertEquals(LocalTime.of(9, 0), items.single { it.layerType == RenderLayerType.DYNAMIC_PLAN }.startTime)
    }

    // --- Scenario 4: Dynamic differs from Static, no Timeline --------------------------

    @Test
    fun `scenario 4 - dynamic diverging from static shows both independently, no timeline`() {
        val items = model(
            statics = listOf(staticPlanEntry(10, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
            dynamics = listOf(dynamicPlanEntry(20, projectId = 1, start = LocalTime.of(11, 0), end = LocalTime.of(12, 0))),
        )

        assertEquals(2, items.size)
        assertTrue(items.none { it.layerType.name.startsWith("TIMELINE") })
        val static = items.single { it.layerType == RenderLayerType.STATIC_PLAN }
        val dynamic = items.single { it.layerType == RenderLayerType.DYNAMIC_PLAN }
        assertEquals(LocalTime.of(9, 0) to LocalTime.of(10, 0), static.startTime to static.endTime)
        assertEquals(LocalTime.of(11, 0) to LocalTime.of(12, 0), dynamic.startTime to dynamic.endTime)
        assertTrue(static.isDashedStroke)
        assertEquals(DYNAMIC_PLAN_FILL_ALPHA, dynamic.fillAlpha)
    }

    // --- Scenario 5: fact matches Dynamic but not Static --------------------------------

    @Test
    fun `scenario 5 - static remains visible where timeline only covers dynamic`() {
        val items = model(
            events = listOf(startEvent(1, projectId = 1, time = LocalTime.of(11, 0)), endEvent(2, projectId = 1, time = LocalTime.of(12, 0))),
            statics = listOf(staticPlanEntry(10, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
            dynamics = listOf(dynamicPlanEntry(20, projectId = 1, start = LocalTime.of(11, 0), end = LocalTime.of(12, 0))),
        )

        val static = items.single { it.layerType == RenderLayerType.STATIC_PLAN }
        assertEquals(LocalTime.of(9, 0), static.startTime)
        assertEquals(LocalTime.of(10, 0), static.endTime)
        // Timeline and Dynamic coincide at 11-12, independently of Static's 9-10 span.
        val timeline = items.single { it.layerType == RenderLayerType.TIMELINE_INTERVAL }
        val dynamic = items.single { it.layerType == RenderLayerType.DYNAMIC_PLAN }
        assertEquals(timeline.startTime, dynamic.startTime)
        assertEquals(timeline.endTime, dynamic.endTime)
    }

    // --- Scenario 6: three different projects in the same range -------------------------

    @Test
    fun `scenario 6 - three different projects in the same range keep their own project id`() {
        val items = model(
            events = listOf(startEvent(1, projectId = 3, time = LocalTime.of(9, 0)), endEvent(2, projectId = 3, time = LocalTime.of(10, 0))),
            statics = listOf(staticPlanEntry(10, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
            dynamics = listOf(dynamicPlanEntry(20, projectId = 2, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
        )

        assertEquals(1, items.single { it.layerType == RenderLayerType.STATIC_PLAN }.projectId)
        assertEquals(2, items.single { it.layerType == RenderLayerType.DYNAMIC_PLAN }.projectId)
        assertEquals(3, items.single { it.layerType == RenderLayerType.TIMELINE_INTERVAL }.projectId)
        // No coalescing by time: three items, three project ids, not one merged record.
        assertEquals(setOf(1, 2, 3), items.map { it.projectId }.toSet())
    }

    // --- Scenario 7: partial overlap ----------------------------------------------------

    @Test
    fun `scenario 7 - partial overlap keeps each layer's own bounds`() {
        val items = model(
            events = listOf(startEvent(1, projectId = 1, time = LocalTime.of(10, 0)), endEvent(2, projectId = 1, time = LocalTime.of(12, 0))),
            statics = listOf(staticPlanEntry(10, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(11, 0))),
            dynamics = listOf(dynamicPlanEntry(20, projectId = 1, start = LocalTime.of(9, 30), end = LocalTime.of(10, 30))),
        )

        val static = items.single { it.layerType == RenderLayerType.STATIC_PLAN }
        val dynamic = items.single { it.layerType == RenderLayerType.DYNAMIC_PLAN }
        val timeline = items.single { it.layerType == RenderLayerType.TIMELINE_INTERVAL }

        assertEquals(LocalTime.of(9, 0) to LocalTime.of(11, 0), static.startTime to static.endTime)
        assertEquals(LocalTime.of(9, 30) to LocalTime.of(10, 30), dynamic.startTime to dynamic.endTime)
        assertEquals(LocalTime.of(10, 0) to LocalTime.of(12, 0), timeline.startTime to timeline.endTime)
    }

    // --- Scenario 8: many consecutive short plan intervals -------------------------------

    @Test
    fun `scenario 8 - 12 consecutive short static and dynamic entries stay distinct`() {
        val statics = (0 until 12).map { i ->
            val start = LocalTime.of(8, 0).plusMinutes(i * 15L)
            staticPlanEntry(100 + i, projectId = 1, start = start, end = start.plusMinutes(5))
        }
        val dynamics = (0 until 12).map { i ->
            val start = LocalTime.of(8, 0).plusMinutes(i * 15L)
            dynamicPlanEntry(200 + i, projectId = 1, start = start, end = start.plusMinutes(5))
        }
        val items = model(statics = statics, dynamics = dynamics)

        val staticItems = items.filter { it.layerType == RenderLayerType.STATIC_PLAN }
        val dynamicItems = items.filter { it.layerType == RenderLayerType.DYNAMIC_PLAN }
        assertEquals(12, staticItems.size)
        assertEquals(12, dynamicItems.size)
        // Distinct source ids - none collapsed into a shared interval.
        assertEquals(12, staticItems.map { it.sourceId }.toSet().size)
        assertEquals(12, dynamicItems.map { it.sourceId }.toSet().size)
        // Each item's bounds match its own 5-minute span exactly - no merging into a
        // wider interval, no rounding drift across neighbors.
        staticItems.forEachIndexed { i, item ->
            val expectedStart = LocalTime.of(8, 0).plusMinutes(i * 15L)
            assertEquals(expectedStart, item.startTime)
            assertEquals(expectedStart.plusMinutes(5), item.endTime)
        }
    }

    // --- Scenario 9: very short intervals -------------------------------------------------

    @Test
    fun `scenario 9 - 1, 5 and 10 minute intervals keep exact LocalTime bounds`() {
        val statics = listOf(
            staticPlanEntry(1, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(9, 1)),
            staticPlanEntry(2, projectId = 1, start = LocalTime.of(10, 0), end = LocalTime.of(10, 5)),
            staticPlanEntry(3, projectId = 1, start = LocalTime.of(11, 0), end = LocalTime.of(11, 10)),
        )
        val items = model(statics = statics).filter { it.layerType == RenderLayerType.STATIC_PLAN }

        assertEquals(LocalTime.of(9, 0) to LocalTime.of(9, 1), items[0].startTime to items[0].endTime)
        assertEquals(LocalTime.of(10, 0) to LocalTime.of(10, 5), items[1].startTime to items[1].endTime)
        assertEquals(LocalTime.of(11, 0) to LocalTime.of(11, 10), items[2].startTime to items[2].endTime)

        // The Dp floor is a rendering-only affordance - it never touches the time model,
        // and even a 1-minute span still resolves to a drawable (non-zero, non-negative) height.
        items.forEach { item ->
            val height = blockHeight(yOffsetFor(item.startTime), yOffsetFor(item.endTime))
            assertTrue("height must stay drawable for ${item.startTime}-${item.endTime}", height.value >= 2f)
        }
    }

    // --- Scenario 10: INSTANT inside/at the boundary of a plan interval -------------------

    @Test
    fun `scenario 10 - INSTANT stays a separate timeline item, unaffected by plan layers`() {
        val events = listOf(
            instantEvent(1, projectId = 1, time = LocalTime.of(9, 30)), // inside
            instantEvent(2, projectId = 1, time = LocalTime.of(10, 0)), // on the boundary
        )
        val withoutPlan = model(events = events)
        val withPlan = model(
            events = events,
            statics = listOf(staticPlanEntry(10, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
            dynamics = listOf(dynamicPlanEntry(20, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
        )

        val instantsWithoutPlan = withoutPlan.filter { it.layerType == RenderLayerType.TIMELINE_INSTANT }
        val instantsWithPlan = withPlan.filter { it.layerType == RenderLayerType.TIMELINE_INSTANT }

        assertEquals(2, instantsWithoutPlan.size)
        // Identical INSTANT items whether or not plan layers exist - plan data cannot
        // influence INSTANT geometry, its deterministic offset seed (sourceId), or count.
        assertEquals(instantsWithoutPlan, instantsWithPlan)
        assertTrue(instantsWithPlan.all { it.fillAlpha == null && it.label == null })
    }

    // --- Scenario 11: open (unfinished) timeline activity ---------------------------------

    @Test
    fun `scenario 11 - open activity keeps its fixed fade geometry regardless of plan layers`() {
        val events = listOf(startEvent(1, projectId = 1, time = LocalTime.of(9, 30)))
        val withoutPlan = model(events = events)
        val withPlan = model(
            events = events,
            statics = listOf(staticPlanEntry(10, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
            dynamics = listOf(dynamicPlanEntry(20, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
        )

        val unfinishedWithoutPlan = withoutPlan.single { it.layerType == RenderLayerType.TIMELINE_UNFINISHED }
        val unfinishedWithPlan = withPlan.single { it.layerType == RenderLayerType.TIMELINE_UNFINISHED }

        assertEquals(unfinishedWithoutPlan, unfinishedWithPlan)
        assertTrue(unfinishedWithPlan.isFadeGradient)
        assertEquals(unfinishedWithPlan.startTime, unfinishedWithPlan.endTime) // open-ended, no real end time
        // Static (topmost) still drawn on top, Dynamic (bottom) still drawn under it -
        // the unfinished item's own zIndex band is unaffected by their presence.
        assertEquals(1, unfinishedWithPlan.zIndex)
        assertTrue(withPlan.single { it.layerType == RenderLayerType.STATIC_PLAN }.zIndex > unfinishedWithPlan.zIndex)
        assertTrue(withPlan.single { it.layerType == RenderLayerType.DYNAMIC_PLAN }.zIndex < unfinishedWithPlan.zIndex)
    }

    // --- Scenario 13: Dynamic opacity is centralized --------------------------------------

    @Test
    fun `scenario 13 - dynamic fill alpha comes from one constant, never applied to timeline or static`() {
        val items = model(
            events = listOf(startEvent(1, projectId = 1, time = LocalTime.of(9, 0)), endEvent(2, projectId = 1, time = LocalTime.of(10, 0))),
            statics = listOf(staticPlanEntry(10, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
            dynamics = listOf(dynamicPlanEntry(20, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
        )

        assertEquals(DYNAMIC_PLAN_FILL_ALPHA, items.single { it.layerType == RenderLayerType.DYNAMIC_PLAN }.fillAlpha)
        assertEquals(TIMELINE_FILL_ALPHA, items.single { it.layerType == RenderLayerType.TIMELINE_INTERVAL }.fillAlpha)
        assertNull(items.single { it.layerType == RenderLayerType.STATIC_PLAN }.fillAlpha)
        assertTrue(DYNAMIC_PLAN_FILL_ALPHA != TIMELINE_FILL_ALPHA)
    }

    // --- Scenario 14: label alignment ------------------------------------------------------

    @Test
    fun `scenario 14 - timeline labels align start, dynamic labels align end, static has none`() {
        val longName = "A".repeat(200)
        val items = model(
            events = listOf(startEvent(1, projectId = 1, time = LocalTime.of(9, 0), label = longName), endEvent(2, projectId = 1, time = LocalTime.of(10, 0))),
            statics = listOf(staticPlanEntry(10, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0), name = "static name is ignored")),
            dynamics = listOf(dynamicPlanEntry(20, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0), name = longName)),
        )

        val timeline = items.single { it.layerType == RenderLayerType.TIMELINE_INTERVAL }
        val dynamic = items.single { it.layerType == RenderLayerType.DYNAMIC_PLAN }
        val static = items.single { it.layerType == RenderLayerType.STATIC_PLAN }

        assertEquals(LabelAlignment.START, timeline.labelAlignment)
        assertEquals(LabelAlignment.END, dynamic.labelAlignment)
        assertEquals(LabelAlignment.NONE, static.labelAlignment)
        assertNull(static.label) // Static never carries a label, regardless of plan_entries.name
        assertEquals(longName, timeline.label)
        assertEquals(longName, dynamic.label)
        // A long label doesn't touch geometry - bounds stay exactly what was scheduled.
        assertEquals(LocalTime.of(9, 0) to LocalTime.of(10, 0), timeline.startTime to timeline.endTime)
        assertEquals(LocalTime.of(9, 0) to LocalTime.of(10, 0), dynamic.startTime to dynamic.endTime)
    }

    @Test
    fun `scenario 14 - blank or missing names normalize to null, not an empty label block`() {
        val items = model(dynamics = listOf(dynamicPlanEntry(1, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0), name = "   ")))
        assertNull(items.single().label)
    }

    // --- Scenario 15: empty-state combinations ---------------------------------------------

    @Test
    fun `scenario 15 - only static`() {
        val items = model(statics = listOf(staticPlanEntry(1, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))))
        assertEquals(listOf(RenderLayerType.STATIC_PLAN), items.map { it.layerType })
    }

    @Test
    fun `scenario 15 - only dynamic`() {
        val items = model(dynamics = listOf(dynamicPlanEntry(1, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))))
        assertEquals(listOf(RenderLayerType.DYNAMIC_PLAN), items.map { it.layerType })
    }

    @Test
    fun `scenario 15 - only timeline`() {
        val items = model(events = listOf(startEvent(1, projectId = 1, time = LocalTime.of(9, 0)), endEvent(2, projectId = 1, time = LocalTime.of(10, 0))))
        assertEquals(listOf(RenderLayerType.TIMELINE_INTERVAL), items.map { it.layerType })
    }

    @Test
    fun `scenario 15 - static plus dynamic`() {
        val items = model(
            statics = listOf(staticPlanEntry(1, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
            dynamics = listOf(dynamicPlanEntry(2, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
        )
        assertEquals(setOf(RenderLayerType.STATIC_PLAN, RenderLayerType.DYNAMIC_PLAN), items.map { it.layerType }.toSet())
    }

    @Test
    fun `scenario 15 - static plus timeline`() {
        val items = model(
            events = listOf(startEvent(1, projectId = 1, time = LocalTime.of(9, 0)), endEvent(2, projectId = 1, time = LocalTime.of(10, 0))),
            statics = listOf(staticPlanEntry(1, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
        )
        assertEquals(setOf(RenderLayerType.STATIC_PLAN, RenderLayerType.TIMELINE_INTERVAL), items.map { it.layerType }.toSet())
    }

    @Test
    fun `scenario 15 - dynamic plus timeline`() {
        val items = model(
            events = listOf(startEvent(1, projectId = 1, time = LocalTime.of(9, 0)), endEvent(2, projectId = 1, time = LocalTime.of(10, 0))),
            dynamics = listOf(dynamicPlanEntry(1, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))),
        )
        assertEquals(setOf(RenderLayerType.DYNAMIC_PLAN, RenderLayerType.TIMELINE_INTERVAL), items.map { it.layerType }.toSet())
    }

    @Test
    fun `scenario 15 - nothing at all`() {
        assertTrue(model().isEmpty())
    }

    // --- Scenario 17: rebuilding the model is deterministic, no duplicates ----------------

    @Test
    fun `scenario 17 - rebuilding from identical input yields an equal, non-duplicated list`() {
        val events = listOf(startEvent(1, projectId = 1, time = LocalTime.of(9, 0)), endEvent(2, projectId = 1, time = LocalTime.of(10, 0)))
        val statics = listOf(staticPlanEntry(10, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0)))
        val dynamics = listOf(dynamicPlanEntry(20, projectId = 1, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0)))

        val first = model(events, statics, dynamics)
        val second = model(events, statics, dynamics)

        assertEquals(first, second)
        assertEquals(3, first.size)
    }

    // --- Scenario 18: performance smoke (correctness at scale, not a benchmark) -----------

    @Test
    fun `scenario 18 - 140 mixed items build correctly and quickly`() {
        val events = mutableListOf<Event>()
        var eventId = 1
        repeat(30) { i ->
            val start = LocalTime.of(0, 0).plusMinutes(i * 20L)
            events += startEvent(eventId++, projectId = 1, time = start)
            events += endEvent(eventId++, projectId = 1, time = start.plusMinutes(10))
        }
        repeat(50) { i ->
            events += instantEvent(eventId++, projectId = 1, time = LocalTime.of(0, 5).plusMinutes(i * 15L))
        }
        val statics = (0 until 30).map { i ->
            val start = LocalTime.of(0, 0).plusMinutes(i * 20L)
            staticPlanEntry(1000 + i, projectId = 1, start = start, end = start.plusMinutes(10))
        }
        val dynamics = (0 until 30).map { i ->
            val start = LocalTime.of(0, 0).plusMinutes(i * 20L)
            dynamicPlanEntry(2000 + i, projectId = 1, start = start, end = start.plusMinutes(10))
        }

        val startedAt = System.nanoTime()
        val items = model(events, statics, dynamics)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(30 + 30 + 50 + 30, items.size) // 30 intervals + 30 static + 50 instant + 30 dynamic
        assertEquals(30 + 30 + 50 + 30, items.map { it.layerType to it.sourceId to it.startTime }.toSet().size)
        // Soft smoke bound, not a benchmark - just guards against an accidental O(n^2)
        // blowing up; see report for the real perf caveats (no JMH/Macrobenchmark here).
        assertTrue("buildDayRenderModel took ${elapsedMs}ms for 140 items", elapsedMs < 2000)
    }
}
