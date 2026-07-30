package com.lifeos.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers chapter 4.6/4.7's Static Plan outline color rule (scenario 12): derived from
 * the project's own color, theme-direction-consistent, deterministic, never turns the
 * outline into a fill via alpha. */
class ProjectColorsTest {

    private val paletteColors = listOf(
        "blue" to ProjectColors.colorFor("blue"),
        "yellow" to ProjectColors.colorFor("yellow"),
        "red" to ProjectColors.colorFor("red"),
        "green" to ProjectColors.colorFor("green"),
        "gray" to ProjectColors.colorFor("gray"),
    )

    @Test
    fun `same input always gives the same output`() {
        paletteColors.forEach { (_, color) ->
            val a = ProjectColors.staticPlanOutlineColor(color, isDarkTheme = true)
            val b = ProjectColors.staticPlanOutlineColor(color, isDarkTheme = true)
            assertEquals(a, b)
        }
    }

    @Test
    fun `dark theme lightens every channel toward white, never darker than the original`() {
        paletteColors.forEach { (name, color) ->
            val outline = ProjectColors.staticPlanOutlineColor(color, isDarkTheme = true)
            assertTrue("$name red channel should not darken", outline.red >= color.red)
            assertTrue("$name green channel should not darken", outline.green >= color.green)
            assertTrue("$name blue channel should not darken", outline.blue >= color.blue)
        }
    }

    @Test
    fun `light theme darkens every channel toward black, never lighter than the original`() {
        paletteColors.forEach { (name, color) ->
            val outline = ProjectColors.staticPlanOutlineColor(color, isDarkTheme = false)
            assertTrue("$name red channel should not lighten", outline.red <= color.red)
            assertTrue("$name green channel should not lighten", outline.green <= color.green)
            assertTrue("$name blue channel should not lighten", outline.blue <= color.blue)
        }
    }

    @Test
    fun `dark and light themes diverge in opposite directions for the same project color`() {
        paletteColors.forEach { (name, color) ->
            val dark = ProjectColors.staticPlanOutlineColor(color, isDarkTheme = true)
            val light = ProjectColors.staticPlanOutlineColor(color, isDarkTheme = false)
            assertTrue("$name: dark-theme outline should read lighter than light-theme outline", dark.red >= light.red)
            assertTrue("$name: dark-theme outline should read lighter than light-theme outline", dark.green >= light.green)
            assertTrue("$name: dark-theme outline should read lighter than light-theme outline", dark.blue >= light.blue)
        }
    }

    @Test
    fun `outline stays fully opaque - alpha never turns the contour into a translucent fill`() {
        paletteColors.forEach { (name, color) ->
            assertEquals("$name (dark)", 1f, ProjectColors.staticPlanOutlineColor(color, isDarkTheme = true).alpha)
            assertEquals("$name (light)", 1f, ProjectColors.staticPlanOutlineColor(color, isDarkTheme = false).alpha)
        }
    }

    @Test
    fun `all channels stay within the valid 0 to 1 range`() {
        paletteColors.forEach { (name, color) ->
            listOf(
                ProjectColors.staticPlanOutlineColor(color, isDarkTheme = true),
                ProjectColors.staticPlanOutlineColor(color, isDarkTheme = false),
            ).forEach { outline ->
                assertTrue("$name red in range", outline.red in 0f..1f)
                assertTrue("$name green in range", outline.green in 0f..1f)
                assertTrue("$name blue in range", outline.blue in 0f..1f)
            }
        }
    }

    // --- Chapter 3 regression: contrastingTextColor must still behave -----------------

    @Test
    fun `contrastingTextColor always resolves to pure black or pure white`() {
        paletteColors.forEach { (_, color) ->
            val text = ProjectColors.contrastingTextColor(color)
            assertTrue(text == Color.Black || text == Color.White)
        }
    }

    @Test
    fun `contrastingTextColor picks white for lavender, matching the block-alpha-blended background`() {
        assertEquals(Color.White, ProjectColors.contrastingTextColor(ProjectColors.colorFor("lavender")))
    }
}
