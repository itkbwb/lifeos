package com.lifeos.app.ui.theme

import androidx.compose.ui.graphics.Color

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
}
