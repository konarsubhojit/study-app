package dev.studyflow.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale (issue #17).
 *
 * Material 3 has no spacing tokens of its own, which is why padding is the first thing to drift:
 * one screen uses 12.dp, the next 14.dp, and the app stops looking deliberate. Every value here
 * sits on a 4.dp grid and features pick a step by name — `MaterialTheme.spacing.medium`, never
 * `16.dp`.
 */
@Immutable
public data class Spacing(
    /** No gap; useful when a layout parameter demands a value. */
    val none: Dp = 0.dp,
    /** 4.dp — between a label and the control it describes. */
    val extraSmall: Dp = 4.dp,
    /** 8.dp — between items in a dense list. */
    val small: Dp = 8.dp,
    /** 16.dp — the default gap and the default screen margin on a phone. */
    val medium: Dp = 16.dp,
    /** 24.dp — between groups of related content. */
    val large: Dp = 24.dp,
    /** 32.dp — between sections of a screen. */
    val extraLarge: Dp = 32.dp,
    /** 48.dp — around content that stands alone, such as an empty state. */
    val huge: Dp = 48.dp,
)

/**
 * The spacing scale in effect.
 *
 * Static because the scale does not change while the app is running; a themed subtree that needs
 * a different one provides it explicitly.
 */
public val LocalSpacing: ProvidableCompositionLocal<Spacing> = staticCompositionLocalOf { Spacing() }

/** Reads the spacing scale the way the rest of the theme is read: `MaterialTheme.spacing.medium`. */
public val MaterialTheme.spacing: Spacing
    @Composable
    @ReadOnlyComposable
    get() = LocalSpacing.current
