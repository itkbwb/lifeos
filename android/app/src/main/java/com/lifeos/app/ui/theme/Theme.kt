package com.lifeos.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val LifeOsColorScheme = darkColorScheme(
    primary = Lavender500,
    onPrimary = BackgroundDark,
    secondary = Lavender400,
    background = BackgroundDark,
    surface = SurfaceDark,
    onBackground = Lavender100,
    onSurface = Lavender100,
)

@Composable
fun LifeOsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LifeOsColorScheme,
        content = content,
    )
}
