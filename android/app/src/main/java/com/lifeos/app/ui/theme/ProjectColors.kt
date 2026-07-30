package com.lifeos.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

object ProjectColors {
    val palette: List<Pair<String, Color>> = listOf(
        "lavender" to Lavender500,
        "blue" to Blue500,
        "green" to Green500,
        "yellow" to Yellow500,
        "orange" to Orange500,
        "red" to Red500,
        "pink" to Pink500,
        "gray" to Gray500,
    )

    fun colorFor(id: String): Color = palette.firstOrNull { it.first == id }?.second ?: Gray500

    // Matches the alpha timeline blocks are actually drawn with (see DayTimelineView) -
    // e.g. Lavender500 reads dark enough once blended over the near-black background
    // that it needs white text, even though the raw swatch looks light.
    private const val BLOCK_ALPHA = 0.85f

    /**
     * Picks black or white text so a label stays readable on any project color, once
     * that color is alpha-blended over the timeline's dark background the way a
     * rendered block actually looks (not the raw, fully-opaque swatch).
     */
    fun contrastingTextColor(color: Color): Color {
        val blended = lerp(BackgroundDark, color, BLOCK_ALPHA)
        val yiq = (blended.red * 255 * 299 + blended.green * 255 * 587 + blended.blue * 255 * 114) / 1000
        return if (yiq >= 128) Color.Black else Color.White
    }

    /**
     * Timeline's actual paint color: the same visible shade a semi-transparent
     * `color.copy(alpha = BLOCK_ALPHA)` block would produce against the plain dark
     * background, but pre-blended into an opaque color rather than painted as an
     * overlay. Chapter 4 draws Dynamic Plan (with its own fill and label) directly
     * underneath Timeline; an actually-semi-transparent Timeline fill lets whatever is
     * beneath it - Dynamic's wash, its label text - bleed through as a visible ghost
     * wherever the two coincide. Painting the pre-blended solid color instead keeps
     * Timeline's look identical to before (same formula [contrastingTextColor] already
     * assumes) while genuinely occluding anything drawn under it.
     */
    fun timelineBlockColor(color: Color): Color = lerp(BackgroundDark, color, BLOCK_ALPHA)

    /**
     * A derived shade of a project color for the Static Plan outline (see chapter
     * 4.6/4.7 - Static never fills, only outlines, in a tint distinct from the
     * project's own solid color so it doesn't read as a second Timeline block).
     * Lightens toward white on dark themes, darkens toward black on light themes,
     * so the parameter is which theme is active rather than a hardcoded direction.
     */
    fun staticPlanOutlineColor(color: Color, isDarkTheme: Boolean = true): Color {
        val target = if (isDarkTheme) Color.White else Color.Black
        return lerp(color, target, 0.35f)
    }
}
