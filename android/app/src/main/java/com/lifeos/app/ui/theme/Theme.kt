package com.lifeos.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val LifeOsColorScheme = darkColorScheme(
    primary = Lavender500,
    onPrimary = BackgroundDark,
    primaryContainer = Lavender600,
    onPrimaryContainer = Lavender100,
    secondary = Lavender400,
    onSecondary = BackgroundDark,
    secondaryContainer = SurfaceContainerHighDark,
    onSecondaryContainer = Lavender200,
    background = BackgroundDark,
    onBackground = Lavender100,
    surface = SurfaceDark,
    onSurface = Lavender100,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Lavender200,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerLow = SurfaceDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    outline = Lavender600,
    outlineVariant = SurfaceVariantDark,
)

@Composable
fun LifeOsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LifeOsColorScheme,
        content = content,
    )
}
