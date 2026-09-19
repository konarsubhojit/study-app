package dev.studyflow.app.widget

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.glance.GlanceTheme
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
