package com.lifeos.app.ui.calendar

import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.lifeos.app.data.Project
import com.lifeos.app.ui.theme.LifeOsTheme
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test

/**
 * Golden screenshots for chapter 4.6's three-layer stacking - a handful of key scenarios
 * only (per plan), not a replacement for [DayRenderModelTest]'s exhaustive unit coverage.
 * Renders [DayTimelineContent] directly from fixture data, no server/network involved.
 */
class DayTimelineViewScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private val projects = listOf(
        Project(1, "Project A", "lavender", ""),
        Project(2, "Project B", "green", ""),
        Project(3, "Project C", "red", ""),
    )

    private fun render(renderItems: List<DayRenderItem>, heightDp: Int = 500) {
        paparazzi.snapshot {
            LifeOsTheme {
                DayTimelineContent(
                    renderItems = renderItems,
                    projects = projects,
                    eventsById = emptyMap(),
                    modifier = Modifier.height(heightDp.dp),
                )
            }
        }
    }

    @Test
    fun `scenario 1 - static, dynamic and timeline fully coincide for one project`() {
        val dayLayout = layoutDay(
            listOf(
                startEvent(1, projectId = 1, time = LocalTime.of(0, 0)),
                endEvent(2, projectId = 1, time = LocalTime.of(1, 0)),
            ),
            TEST_DATE,
            TEST_ZONE,
        )
        val statics = layoutStaticPlan(
            listOf(staticPlanEntry(10, projectId = 1, start = LocalTime.of(0, 0), end = LocalTime.of(1, 0))),
            TEST_DATE,
            TEST_ZONE,
        )
        val dynamics = layoutDynamicPlan(
            listOf(dynamicPlanEntry(20, projectId = 1, start = LocalTime.of(0, 0), end = LocalTime.of(1, 0), name = "Совпадение")),
            TEST_DATE,
            TEST_ZONE,
        )
        render(buildDayRenderModel(dayLayout, statics, dynamics), heightDp = 200)
    }

    @Test
    fun `scenario 6 - three different projects stacked in the same time range`() {
        val dayLayout = layoutDay(
            listOf(
                startEvent(1, projectId = 3, time = LocalTime.of(0, 0)),
                endEvent(2, projectId = 3, time = LocalTime.of(1, 0)),
            ),
            TEST_DATE,
            TEST_ZONE,
        )
        val statics = layoutStaticPlan(
            listOf(staticPlanEntry(10, projectId = 1, start = LocalTime.of(0, 0), end = LocalTime.of(1, 0))),
            TEST_DATE,
            TEST_ZONE,
        )
        val dynamics = layoutDynamicPlan(
            listOf(dynamicPlanEntry(20, projectId = 2, start = LocalTime.of(0, 0), end = LocalTime.of(1, 0), name = "Проект B")),
            TEST_DATE,
            TEST_ZONE,
        )
        render(buildDayRenderModel(dayLayout, statics, dynamics), heightDp = 200)
    }

    @Test
    fun `scenario 8 - 12 consecutive short static and dynamic intervals stay visually distinct`() {
        val statics = (0 until 12).map { i ->
            val start = LocalTime.of(0, 0).plusMinutes(i * 15L)
            staticPlanEntry(100 + i, projectId = 1, start = start, end = start.plusMinutes(5))
        }
        val dynamics = (0 until 12).map { i ->
            val start = LocalTime.of(0, 0).plusMinutes(i * 15L)
            dynamicPlanEntry(200 + i, projectId = 2, start = start, end = start.plusMinutes(5))
        }
        val model = buildDayRenderModel(
            layoutDay(emptyList(), TEST_DATE, TEST_ZONE),
            layoutStaticPlan(statics, TEST_DATE, TEST_ZONE),
            layoutDynamicPlan(dynamics, TEST_DATE, TEST_ZONE),
        )
        render(model, heightDp = 400)
    }

    @Test
    fun `scenario 9 - 1, 5 and 10 minute intervals stay drawable at minimal height`() {
        val statics = listOf(
            staticPlanEntry(1, projectId = 1, start = LocalTime.of(0, 0), end = LocalTime.of(0, 1)),
            staticPlanEntry(2, projectId = 1, start = LocalTime.of(0, 10), end = LocalTime.of(0, 15)),
            staticPlanEntry(3, projectId = 1, start = LocalTime.of(0, 20), end = LocalTime.of(0, 30)),
        )
        val model = buildDayRenderModel(
            layoutDay(emptyList(), TEST_DATE, TEST_ZONE),
            layoutStaticPlan(statics, TEST_DATE, TEST_ZONE),
            emptyList(),
        )
        render(model, heightDp = 150)
    }
}
