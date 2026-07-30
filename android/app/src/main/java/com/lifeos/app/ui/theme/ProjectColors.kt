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
}
