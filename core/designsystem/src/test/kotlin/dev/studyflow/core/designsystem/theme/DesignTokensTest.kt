package dev.studyflow.core.designsystem.theme

import androidx.compose.ui.unit.TextUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Design tokens")
class DesignTokensTest {
    private val spacing = Spacing()

    @Test
    fun `every spacing step sits on the 4dp grid and grows`() {
        val steps =
            listOf(
                spacing.none,
                spacing.extraSmall,
                spacing.small,
                spacing.medium,
                spacing.large,
                spacing.extraLarge,
                spacing.huge,
            )

        steps.forEach { step ->
            assertEquals(0f, step.value % GRID, "$step is off the ${GRID}dp grid")
        }
        assertEquals(steps.sortedBy { it.value }, steps)
        assertEquals(steps.distinct(), steps)
    }

    @Test
    fun `type sizes scale with the system font setting`() {
        val sizes =
            with(StudyFlowTypography) {
                listOf(displayLarge, headlineLarge, titleLarge, bodyLarge, labelSmall).map { it.fontSize }
            }

        sizes.forEach { size: TextUnit ->
            assertTrue(size.isSp, "$size must be declared in sp so 200% font scale is honoured")
        }
        assertEquals(sizes.sortedByDescending { it.value }, sizes)
    }

    @Test
    fun `the brand light and dark schemes are genuinely different`() {
        assertNotEquals(BrandLightColorScheme.background, BrandDarkColorScheme.background)
        assertNotEquals(BrandLightColorScheme.primary, BrandDarkColorScheme.primary)
    }

    @Test
    fun `no brand colour role collides with the content drawn on top of it`() {
        listOf(
            BrandLightColorScheme.primary to BrandLightColorScheme.onPrimary,
            BrandLightColorScheme.surface to BrandLightColorScheme.onSurface,
            BrandLightColorScheme.error to BrandLightColorScheme.onError,
            BrandDarkColorScheme.primary to BrandDarkColorScheme.onPrimary,
            BrandDarkColorScheme.surface to BrandDarkColorScheme.onSurface,
            BrandDarkColorScheme.error to BrandDarkColorScheme.onError,
        ).forEach { (container, content) ->
            assertNotEquals(container, content)
        }
    }

    private companion object {
        const val GRID = 4f
    }
}
