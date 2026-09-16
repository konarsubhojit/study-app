package dev.studyflow.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The Material 3 type scale, expressed once (issue #17).
 *
 * Sizes are declared in `sp` and never in `dp`, so the whole scale grows with the system font
 * setting; screens are expected to survive 200% font scale because no size is pinned. Features read
 * `MaterialTheme.typography` and never construct a [TextStyle] with a literal size of their own.
 */
private fun typeStyle(
    size: Int,
    lineHeight: Int,
    letterSpacing: Double = 0.0,
    weight: FontWeight = FontWeight.Normal,
): TextStyle =
    TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = letterSpacing.sp,
    )

/** The StudyFlow type scale: the Material 3 baseline, with medium weight on titles and labels. */
public val StudyFlowTypography: Typography =
    Typography(
        displayLarge = typeStyle(size = 57, lineHeight = 64, letterSpacing = -0.25),
        displayMedium = typeStyle(size = 45, lineHeight = 52),
        displaySmall = typeStyle(size = 36, lineHeight = 44),
        headlineLarge = typeStyle(size = 32, lineHeight = 40),
        headlineMedium = typeStyle(size = 28, lineHeight = 36),
        headlineSmall = typeStyle(size = 24, lineHeight = 32),
        titleLarge = typeStyle(size = 22, lineHeight = 28),
        titleMedium = typeStyle(size = 16, lineHeight = 24, letterSpacing = 0.15, weight = FontWeight.Medium),
        titleSmall = typeStyle(size = 14, lineHeight = 20, letterSpacing = 0.1, weight = FontWeight.Medium),
        bodyLarge = typeStyle(size = 16, lineHeight = 24, letterSpacing = 0.5),
        bodyMedium = typeStyle(size = 14, lineHeight = 20, letterSpacing = 0.25),
        bodySmall = typeStyle(size = 12, lineHeight = 16, letterSpacing = 0.4),
        labelLarge = typeStyle(size = 14, lineHeight = 20, letterSpacing = 0.1, weight = FontWeight.Medium),
        labelMedium = typeStyle(size = 12, lineHeight = 16, letterSpacing = 0.5, weight = FontWeight.Medium),
        labelSmall = typeStyle(size = 11, lineHeight = 16, letterSpacing = 0.5, weight = FontWeight.Medium),
    )
