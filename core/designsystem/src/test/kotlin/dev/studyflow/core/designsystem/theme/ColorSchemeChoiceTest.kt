package dev.studyflow.core.designsystem.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ColorSchemeChoice")
class ColorSchemeChoiceTest {
    @Test
    fun `dynamic colour is used when it is both asked for and available`() {
        assertEquals(
            ColorSchemeChoice.DynamicLight,
            ColorSchemeChoice.of(darkTheme = false, dynamicColorRequested = true, dynamicColorSupported = true),
        )
        assertEquals(
            ColorSchemeChoice.DynamicDark,
            ColorSchemeChoice.of(darkTheme = true, dynamicColorRequested = true, dynamicColorSupported = true),
        )
    }

    @Test
    fun `the brand palette is the fallback when the platform cannot supply wallpaper colours`() {
        assertEquals(
            ColorSchemeChoice.BrandLight,
            ColorSchemeChoice.of(darkTheme = false, dynamicColorRequested = true, dynamicColorSupported = false),
        )
        assertEquals(
            ColorSchemeChoice.BrandDark,
            ColorSchemeChoice.of(darkTheme = true, dynamicColorRequested = true, dynamicColorSupported = false),
        )
    }

    @Test
    fun `a user who turns dynamic colour off gets the brand palette on a device that supports it`() {
        assertEquals(
            ColorSchemeChoice.BrandLight,
            ColorSchemeChoice.of(darkTheme = false, dynamicColorRequested = false, dynamicColorSupported = true),
        )
    }

    @Test
    fun `the dark request is honoured whatever the colour source`() {
        val dark = ColorSchemeChoice.entries.filter { it.isDark }

        assertEquals(listOf(ColorSchemeChoice.DynamicDark, ColorSchemeChoice.BrandDark), dark)
        assertTrue(ColorSchemeChoice.DynamicDark.isDynamic)
        assertFalse(ColorSchemeChoice.BrandDark.isDynamic)
    }
}
