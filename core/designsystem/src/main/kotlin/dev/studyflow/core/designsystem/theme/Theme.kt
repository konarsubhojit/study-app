package dev.studyflow.core.designsystem.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The single theme every StudyFlow screen is wrapped in (issue #17).
 *
 * Dynamic colour is the default because it is what a modern Android app is expected to do, but it
 * is a request rather than a guarantee: below API 31 the platform cannot supply wallpaper colours,
 * so [ColorSchemeChoice] falls back to the brand palette. Light and dark follow the system unless a
 * caller overrides them, which is what lets the settings screen offer a manual choice later without
 * a second theming path.
 *
 * The theme also turns the window edge-to-edge and keeps the system bar icons legible against
 * whichever scheme is in force, so no screen has to remember to do either.
 *
 * @param darkTheme whether to use the dark variant; follows the system by default.
 * @param dynamicColor whether to derive colours from the wallpaper where the platform supports it.
 * @param edgeToEdge draws behind the system bars; only ever switched off by a preview or a test.
 */
@Composable
public fun StudyFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    edgeToEdge: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val choice =
        ColorSchemeChoice.of(
            darkTheme = darkTheme,
            dynamicColorRequested = dynamicColor,
            dynamicColorSupported = isDynamicColorSupported,
        )
    val colorScheme = colorSchemeFor(choice, context)

    if (edgeToEdge) {
        EdgeToEdgeSystemBars(lightAppearance = !choice.isDark)
    }

    CompositionLocalProvider(LocalSpacing provides Spacing()) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = StudyFlowShapes,
            typography = StudyFlowTypography,
            content = content,
        )
    }
}

/** Wallpaper-derived colour arrived in Android 12; `minSdk` is lower, so this stays a runtime check. */
@get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
internal val isDynamicColorSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Materialises a [ColorSchemeChoice].
 *
 * The API level is re-checked here even though [ColorSchemeChoice.of] already accounted for it:
 * Lint only trusts a version guard it can see at the call site, and an unguarded
 * `dynamicLightColorScheme` would crash on Android 11.
 */
private fun colorSchemeFor(
    choice: ColorSchemeChoice,
    context: Context,
): ColorScheme =
    when {
        choice.isDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (choice.isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        choice.isDark -> {
            BrandDarkColorScheme
        }

        else -> {
            BrandLightColorScheme
        }
    }

/**
 * Lets the app draw behind the system bars and picks the icon contrast to match the scheme.
 *
 * Nothing is drawn *into* the bars: the platform draws them transparently from Android 15 onwards,
 * and the deprecated colour properties would be ignored. Layout still has to respect the insets,
 * which is what `StudyFlowScaffold` is for.
 */
@Composable
private fun EdgeToEdgeSystemBars(lightAppearance: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightAppearance
            isAppearanceLightNavigationBars = lightAppearance
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
