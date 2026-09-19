package dev.studyflow.app.widget

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.cornerRadius
import androidx.glance.material3.ColorProviders
import dev.studyflow.core.designsystem.theme.StudyFlowColorSchemes

/**
 * The widget counterpart of `StudyFlowTheme` (issue #61).
 *
 * Glance resolves light and dark against the *host's* widget theme rather than the app's, which is
 * what makes a widget match the launcher it sits in. On Android 12 and later the default providers
 * are the wallpaper-derived `system_*` colours, so dynamic colour comes for free; below that the
 * platform has no such resources, and the app's own palette from `:core:designsystem` stands in —
 * never an invented colour literal.
 */
@Composable
internal fun StudyFlowGlanceTheme(content: @Composable () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        GlanceTheme(content = content)
    } else {
        GlanceTheme(
            colors = ColorProviders(light = StudyFlowColorSchemes.light, dark = StudyFlowColorSchemes.dark),
            content = content,
        )
    }
}

/**
 * Rounds a widget to whatever the host uses for its own widgets.
 *
 * `system_app_widget_background_radius` only exists from Android 12, where the launcher also clips
 * widgets to it; the fallback matches the platform's pre-12 widget background so the corner is not
 * a design-system decision but the host's.
 */
@SuppressLint("InlinedApi") // Guarded below; the resource exists from Android 12 onwards.
internal fun GlanceModifier.widgetCornerRadius(): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        cornerRadius(android.R.dimen.system_app_widget_background_radius)
    } else {
        cornerRadius(LEGACY_WIDGET_CORNER_RADIUS)
    }

private val LEGACY_WIDGET_CORNER_RADIUS = 16.dp
