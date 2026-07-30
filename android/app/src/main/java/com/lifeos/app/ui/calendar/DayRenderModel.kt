package com.lifeos.app.ui.calendar

import java.time.LocalTime

/** What a render item visually is - the draw code branches on this, not on which
 * source list it came from (see chapter 4.6/4.7: color always means project, the
 * entity's type is expressed purely by how it's drawn). */
enum class RenderLayerType { DYNAMIC_PLAN, TIMELINE_INTERVAL, TIMELINE_UNFINISHED, TIMELINE_INSTANT, STATIC_PLAN }

enum class LabelAlignment { START, END, NONE }

/** Single source of truth for Dynamic Plan's fill opacity (chapter 4.6/4.13) - change
 * this one constant during visual tuning, never a value duplicated in the renderer. */
const val DYNAMIC_PLAN_FILL_ALPHA = 0.2f

/** Timeline's solid-block opacity (chapter 3) - kept next to Dynamic's so both live in
 * one place instead of scattered magic numbers across the renderer. */
const val TIMELINE_FILL_ALPHA = 0.85f

/** Fixed cosmetic height (in minutes-of-fade) for an open (START without END) activity.
 * This is a rendering choice, not a temporal fact - an unfinished item's [DayRenderItem.endTime]
 * always equals its startTime; this constant is how long the fade visually reads as. */
const val UNFINISHED_FADE_MINUTES = 20

private fun layerZIndex(layerType: RenderLayerType): Int = when (layerType) {
    RenderLayerType.DYNAMIC_PLAN -> 0
    RenderLayerType.TIMELINE_INTERVAL, RenderLayerType.TIMELINE_UNFINISHED, RenderLayerType.TIMELINE_INSTANT -> 1
    RenderLayerType.STATIC_PLAN -> 2
}

/**
 * One drawable item for the day timeline, independent of any Compose/pixel concern -
 * bounds are expressed in [LocalTime], not Dp, so layer stacking, bounds, and label
 * placement can be asserted in a plain JVM unit test without a Compose runtime.
 *
 * [zIndex] encodes chapter 4.6's draw order (Dynamic bottom, Timeline middle, Static
 * top) as a plain ascending int - higher draws over lower, matching list order too.
 */
data class DayRenderItem(
    val layerType: RenderLayerType,
    val zIndex: Int,
    val projectId: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val label: String?,
    val labelAlignment: LabelAlignment,
    /** Flat fill opacity, or null when this item has no fill (Static's outline, an
     * INSTANT's line+icon). */
    val fillAlpha: Float?,
    val isDashedStroke: Boolean,
    /** True only for [RenderLayerType.TIMELINE_UNFINISHED] - its fill fades from
     * [fillAlpha] to transparent rather than being flat, over [UNFINISHED_FADE_MINUTES]. */
    val isFadeGradient: Boolean,
    /** The originating Event id or Plan entry id - lets a test confirm distinct
     * source records never got merged into one render item. */
    val sourceId: Int,
)

private fun normalizedLabel(name: String?): String? = name?.takeIf { it.isNotBlank() }

/**
 * Builds the ordered (bottom-to-top) render model for one day: Dynamic Plan, then
 * Timeline (intervals, the at-most-one unfinished block, INSTANT markers), then
 * Static Plan - matching chapter 4.6's layering rule exactly as the renderer already
 * draws it. No merging: each source list maps to its own items 1:1, so two layers
 * that fully coincide in time and project still produce two distinct render items.
 */
fun buildDayRenderModel(
    dayLayout: DayLayout,
    staticPlanBlocks: List<PlanBlockData>,
    dynamicPlanBlocks: List<PlanBlockData>,
): List<DayRenderItem> {
    val items = mutableListOf<DayRenderItem>()

    dynamicPlanBlocks.forEach { block ->
        items += DayRenderItem(
            layerType = RenderLayerType.DYNAMIC_PLAN,
            zIndex = layerZIndex(RenderLayerType.DYNAMIC_PLAN),
            projectId = block.projectId,
            startTime = block.startTime,
            endTime = block.endTime,
            label = normalizedLabel(block.name),
            labelAlignment = LabelAlignment.END,
            fillAlpha = DYNAMIC_PLAN_FILL_ALPHA,
            isDashedStroke = false,
            isFadeGradient = false,
            sourceId = block.id,
        )
    }

    dayLayout.intervals.forEach { block ->
        items += DayRenderItem(
            layerType = RenderLayerType.TIMELINE_INTERVAL,
            zIndex = layerZIndex(RenderLayerType.TIMELINE_INTERVAL),
            projectId = block.projectId,
            startTime = block.startTime,
            endTime = block.endTime,
            label = normalizedLabel(block.name),
            labelAlignment = LabelAlignment.START,
            fillAlpha = TIMELINE_FILL_ALPHA,
            isDashedStroke = false,
            isFadeGradient = false,
            sourceId = block.event.id,
        )
    }

    dayLayout.unfinished?.let { block ->
        items += DayRenderItem(
            layerType = RenderLayerType.TIMELINE_UNFINISHED,
            zIndex = layerZIndex(RenderLayerType.TIMELINE_UNFINISHED),
            projectId = block.projectId,
            startTime = block.startTime,
            endTime = block.startTime,
            label = normalizedLabel(block.name),
            labelAlignment = LabelAlignment.START,
            fillAlpha = TIMELINE_FILL_ALPHA,
            isDashedStroke = false,
            isFadeGradient = true,
            sourceId = block.event.id,
        )
    }

    dayLayout.instants.forEach { marker ->
        items += DayRenderItem(
            layerType = RenderLayerType.TIMELINE_INSTANT,
            zIndex = layerZIndex(RenderLayerType.TIMELINE_INSTANT),
            projectId = marker.projectId,
            startTime = marker.time,
            endTime = marker.time,
            label = null,
            labelAlignment = LabelAlignment.NONE,
            fillAlpha = null,
            isDashedStroke = false,
            isFadeGradient = false,
            sourceId = marker.event.id,
        )
    }

    staticPlanBlocks.forEach { block ->
        items += DayRenderItem(
            layerType = RenderLayerType.STATIC_PLAN,
            zIndex = layerZIndex(RenderLayerType.STATIC_PLAN),
            projectId = block.projectId,
            startTime = block.startTime,
            endTime = block.endTime,
            label = null,
            labelAlignment = LabelAlignment.NONE,
            fillAlpha = null,
            isDashedStroke = true,
            isFadeGradient = false,
            sourceId = block.id,
        )
    }

    return items
}
