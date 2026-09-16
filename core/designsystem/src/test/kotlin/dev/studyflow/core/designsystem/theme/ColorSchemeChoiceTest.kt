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
        assertEquals(
            ColorSchemeChoice.BrandDark,
            ColorSchemeChoice.of(darkTheme = true, dynamicColorRequested = false, dynamicColorSupported = true),
        )
    }

    @Test
    fun `each scheme reports its brightness and its colour source`() {
        assertFalse(ColorSchemeChoice.BrandLight.isDark)
        assertFalse(ColorSchemeChoice.BrandLight.isDynamic)

        assertTrue(ColorSchemeChoice.BrandDark.isDark)
        assertFalse(ColorSchemeChoice.BrandDark.isDynamic)

        assertFalse(ColorSchemeChoice.DynamicLight.isDark)
        assertTrue(ColorSchemeChoice.DynamicLight.isDynamic)

        assertTrue(ColorSchemeChoice.DynamicDark.isDark)
        assertTrue(ColorSchemeChoice.DynamicDark.isDynamic)
    }
}
