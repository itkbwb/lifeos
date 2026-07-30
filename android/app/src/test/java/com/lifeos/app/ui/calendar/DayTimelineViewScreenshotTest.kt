package com.lifeos.app.ui.calendar

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.lifeos.app.data.DynamicPlanEntry
import com.lifeos.app.data.Event
import com.lifeos.app.data.PlanEntry
import com.lifeos.app.data.Project
import com.lifeos.app.ui.theme.LifeOsTheme
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test

/**
 * Golden screenshots for chapter 4.6's three-layer stacking - a handful of key scenarios
 * only (per plan), not a replacement for [DayRenderModelTest]'s exhaustive unit coverage.
 * Renders [DayTimelineContent] directly from fixture data, no server/network involved.
 *
 * Every scenario places its events in 08:00-18:00. Paparazzi's default Composable
 * snapshot mode measures content at "wrap height" (it ignores any height/clip Modifier
 * we tried applying and always renders the full day, however tall) - explicit
 * [SessionParams.RenderingMode.NORMAL] instead renders a real fixed-size device canvas,
 * like an actual screen, that a plain [Modifier.offset] can scroll-crop: shifting
 * [DayTimelineContent] up by 7 hours reveals a 08:00+ window instead of 00:00. The
 * device height (see `screenHeight` below) is sized generously past 18:00 so nothing
 * in these fixtures is cropped, but nowhere near the ~24h "whole day" stretch this file
 * used before - this is a single normal-looking screenshot, not a stretched one.
 */
class DayTimelineViewScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 3600),
        renderingMode = SessionParams.RenderingMode.NORMAL,
    )

    /** Hours 00:00-07:00 hold nothing in any scenario - shifting the whole day up by this
     * many hours puts 08:00 at the top of the frame instead of empty grid. */
    private val windowStartOffset = (-7 * 64).dp

    // The 8 real production project colors (see ProjectColors.palette), in palette order.
    private val allProjects = listOf(
        Project(1, "Lavender", "lavender", ""),
        Project(2, "Blue", "blue", ""),
        Project(3, "Green", "green", ""),
        Project(4, "Yellow", "yellow", ""),
        Project(5, "Orange", "orange", ""),
        Project(6, "Red", "red", ""),
        Project(7, "Pink", "pink", ""),
        Project(8, "Gray", "gray", ""),
    )

    private fun render(
        events: List<Event> = emptyList(),
        statics: List<PlanEntry> = emptyList(),
        dynamics: List<DynamicPlanEntry> = emptyList(),
    ) {
        val dayLayout = layoutDay(events, TEST_DATE, TEST_ZONE)
        val staticBlocks = layoutStaticPlan(statics, TEST_DATE, TEST_ZONE)
        val dynamicBlocks = layoutDynamicPlan(dynamics, TEST_DATE, TEST_ZONE)
        val renderItems = buildDayRenderModel(dayLayout, staticBlocks, dynamicBlocks)
        val eventsById = events.associateBy { it.id }

        paparazzi.snapshot {
            LifeOsTheme {
                DayTimelineContent(
                    renderItems = renderItems,
                    projects = allProjects,
                    eventsById = eventsById,
                    modifier = Modifier.offset(y = windowStartOffset),
                )
            }
        }
    }

    /** Builds one non-overlapping [start, start+90min] block for a project: Static and
     * Dynamic span the full 90 minutes, Timeline covers only the second half (45min-90min) -
     * showing bare Dynamic, a Dynamic label, opaque Timeline, a Timeline label, and the
     * Static dashed outline over both, all in one block. */
    private fun colorShowcaseBlock(
        projectId: Int,
        projectName: String,
        start: LocalTime,
        eventIdStart: Int,
        planIdStart: Int,
    ): Triple<List<Event>, PlanEntry, DynamicPlanEntry> {
        val end = start.plusMinutes(90)
        val timelineStart = start.plusMinutes(45)
        val events = listOf(
            startEvent(eventIdStart, projectId = projectId, time = timelineStart, label = "$projectName work"),
            endEvent(eventIdStart + 1, projectId = projectId, time = end),
        )
        val static = staticPlanEntry(planIdStart, projectId = projectId, start = start, end = end)
        val dynamic = dynamicPlanEntry(planIdStart, projectId = projectId, start = start, end = end, name = "$projectName plan")
        return Triple(events, static, dynamic)
    }

    @Test
    fun all_project_colors_part_1() {
        val starts = listOf(LocalTime.of(8, 0), LocalTime.of(10, 0), LocalTime.of(12, 0), LocalTime.of(14, 0))
        val projects = listOf(1 to "Lavender", 2 to "Blue", 3 to "Green", 4 to "Yellow")

        val events = mutableListOf<Event>()
        val statics = mutableListOf<PlanEntry>()
        val dynamics = mutableListOf<DynamicPlanEntry>()
        projects.forEachIndexed { i, (id, name) ->
            val (e, s, d) = colorShowcaseBlock(id, name, starts[i], eventIdStart = i * 2 + 1, planIdStart = i + 1)
            events += e
            statics += s
            dynamics += d
        }

        render(events, statics, dynamics)
    }

    @Test
    fun all_project_colors_part_2() {
        val starts = listOf(LocalTime.of(8, 0), LocalTime.of(10, 0), LocalTime.of(12, 0), LocalTime.of(14, 0))
        val projects = listOf(5 to "Orange", 6 to "Red", 7 to "Pink", 8 to "Gray")

        val events = mutableListOf<Event>()
        val statics = mutableListOf<PlanEntry>()
        val dynamics = mutableListOf<DynamicPlanEntry>()
        projects.forEachIndexed { i, (id, name) ->
            val (e, s, d) = colorShowcaseBlock(id, name, starts[i], eventIdStart = i * 2 + 1, planIdStart = i + 1)
            events += e
            statics += s
            dynamics += d
        }

        render(events, statics, dynamics)
    }

    @Test
    fun layer_geometry() {
        val events = mutableListOf<Event>()
        val statics = mutableListOf<PlanEntry>()
        val dynamics = mutableListOf<DynamicPlanEntry>()

        // Scenario A (08:00-10:00, project Blue): Timeline starts after the plan begins
        // and continues after the plan ends - plan-only, triple-overlap, and
        // timeline-only zones should all be visible in sequence.
        statics += staticPlanEntry(1, projectId = 2, start = LocalTime.of(8, 0), end = LocalTime.of(9, 30))
        dynamics += dynamicPlanEntry(1, projectId = 2, start = LocalTime.of(8, 0), end = LocalTime.of(9, 30), name = "A plan")
        events += startEvent(1, projectId = 2, time = LocalTime.of(8, 30), label = "A work")
        events += endEvent(2, projectId = 2, time = LocalTime.of(10, 0))

        // Scenario B (10:30-13:30, project Green): all three layers offset from each
        // other - static-only, static+dynamic, all-three, static+timeline, timeline-only.
        statics += staticPlanEntry(2, projectId = 3, start = LocalTime.of(10, 30), end = LocalTime.of(12, 30))
        dynamics += dynamicPlanEntry(2, projectId = 3, start = LocalTime.of(11, 0), end = LocalTime.of(12, 0), name = "B plan")
        events += startEvent(3, projectId = 3, time = LocalTime.of(11, 30), label = "B work")
        events += endEvent(4, projectId = 3, time = LocalTime.of(13, 30))

        // Scenario C (14:00-17:30): the plan (Yellow) was fully reassigned to a
        // different project (Orange) - what actually happened (Dynamic + Timeline)
        // diverges entirely from the original Static intent, in project as well as time.
        statics += staticPlanEntry(3, projectId = 4, start = LocalTime.of(14, 0), end = LocalTime.of(15, 30))
        dynamics += dynamicPlanEntry(3, projectId = 5, start = LocalTime.of(16, 0), end = LocalTime.of(17, 30), name = "C reassigned")
        events += startEvent(5, projectId = 5, time = LocalTime.of(16, 0), label = "C actual")
        events += endEvent(6, projectId = 5, time = LocalTime.of(17, 30))

        render(events, statics, dynamics)
    }

    @Test
    fun touching_intervals() {
        // Static: four colors touching back-to-back, no gaps, no overlap.
        val statics = listOf(
            staticPlanEntry(1, projectId = 1, start = LocalTime.of(8, 0), end = LocalTime.of(8, 40)),
            staticPlanEntry(2, projectId = 2, start = LocalTime.of(8, 40), end = LocalTime.of(9, 20)),
            staticPlanEntry(3, projectId = 3, start = LocalTime.of(9, 20), end = LocalTime.of(10, 0)),
            staticPlanEntry(4, projectId = 4, start = LocalTime.of(10, 0), end = LocalTime.of(10, 40)),
        )
        // Dynamic: another four colors touching back-to-back.
        val dynamics = listOf(
            dynamicPlanEntry(1, projectId = 5, start = LocalTime.of(11, 0), end = LocalTime.of(11, 40), name = "Orange"),
            dynamicPlanEntry(2, projectId = 6, start = LocalTime.of(11, 40), end = LocalTime.of(12, 20), name = "Red"),
            dynamicPlanEntry(3, projectId = 7, start = LocalTime.of(12, 20), end = LocalTime.of(13, 0), name = "Pink"),
            dynamicPlanEntry(4, projectId = 8, start = LocalTime.of(13, 0), end = LocalTime.of(13, 40), name = "Gray"),
        )
        // Timeline: touching intervals alternating projects (1, 3, 5, 7).
        val events = listOf(
            startEvent(1, projectId = 1, time = LocalTime.of(14, 0), label = "Lavender"),
            endEvent(2, projectId = 1, time = LocalTime.of(14, 40)),
            startEvent(3, projectId = 3, time = LocalTime.of(14, 40), label = "Green"),
            endEvent(4, projectId = 3, time = LocalTime.of(15, 20)),
            startEvent(5, projectId = 5, time = LocalTime.of(15, 20), label = "Orange"),
            endEvent(6, projectId = 5, time = LocalTime.of(16, 0)),
            startEvent(7, projectId = 7, time = LocalTime.of(16, 0), label = "Pink"),
            endEvent(8, projectId = 7, time = LocalTime.of(16, 40)),
        )

        render(events, statics, dynamics)
    }

    @Test
    fun instants_with_layers() {
        val events = listOf(
            // Timeline (project Lavender), 08:00-09:00, with 6 INSTANTs in and around it -
            // spaced 6-15 minutes apart (never exactly 1 minute), so their deterministic
            // horizontal offsets stay visually distinguishable.
            startEvent(1, projectId = 1, time = LocalTime.of(8, 0), label = "Timeline A"),
            endEvent(2, projectId = 1, time = LocalTime.of(9, 0)),
            instantEvent(3, projectId = 1, time = LocalTime.of(8, 7), label = "i1"),
            instantEvent(4, projectId = 1, time = LocalTime.of(8, 13), label = "i2"),
            instantEvent(5, projectId = 1, time = LocalTime.of(8, 22), label = "i3"),
            instantEvent(6, projectId = 1, time = LocalTime.of(8, 31), label = "i4"),
            instantEvent(7, projectId = 1, time = LocalTime.of(8, 46), label = "i5"),
            instantEvent(8, projectId = 1, time = LocalTime.of(9, 2), label = "i6"), // just after Timeline ends

            // Planning only (project Blue), no Timeline - 2 INSTANTs inside its span.
            instantEvent(9, projectId = 2, time = LocalTime.of(10, 11), label = "i7"),
            instantEvent(10, projectId = 2, time = LocalTime.of(10, 37), label = "i8"),

            // Open (unfinished) activity, project Green, started at 12:00, never closed -
            // one INSTANT inside its 20-minute fade window, one just after it.
            startEvent(11, projectId = 3, time = LocalTime.of(12, 0), label = "Unfinished C"),
            instantEvent(12, projectId = 3, time = LocalTime.of(12, 9), label = "i9"),
            instantEvent(13, projectId = 3, time = LocalTime.of(12, 41), label = "i10"),
        )
        val statics = listOf(staticPlanEntry(1, projectId = 2, start = LocalTime.of(10, 0), end = LocalTime.of(11, 0)))
        val dynamics = listOf(dynamicPlanEntry(1, projectId = 2, start = LocalTime.of(10, 0), end = LocalTime.of(11, 0), name = "Planning B"))

        render(events, statics, dynamics)
    }

    @Test
    fun short_intervals() {
        val events = listOf(
            // 10-minute Timeline interval.
            startEvent(1, projectId = 1, time = LocalTime.of(8, 0), label = "10 min"),
            endEvent(2, projectId = 1, time = LocalTime.of(8, 10)),
            // Timeline covering the second half of the 60-minute project D block.
            startEvent(3, projectId = 4, time = LocalTime.of(10, 30), label = "D work"),
            endEvent(4, projectId = 4, time = LocalTime.of(11, 0)),
        )
        val statics = listOf(
            // 30-minute Static-only block.
            staticPlanEntry(1, projectId = 3, start = LocalTime.of(9, 10), end = LocalTime.of(9, 40)),
            staticPlanEntry(2, projectId = 4, start = LocalTime.of(10, 0), end = LocalTime.of(11, 0)),
        )
        val dynamics = listOf(
            // 20-minute Dynamic-only block.
            dynamicPlanEntry(1, projectId = 2, start = LocalTime.of(8, 30), end = LocalTime.of(8, 50), name = "20 min"),
            dynamicPlanEntry(2, projectId = 4, start = LocalTime.of(10, 0), end = LocalTime.of(11, 0), name = "D plan"),
        )

        render(events, statics, dynamics)
    }
}
