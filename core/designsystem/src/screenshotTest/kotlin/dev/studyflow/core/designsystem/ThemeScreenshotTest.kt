package dev.studyflow.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.studyflow.core.designsystem.layout.StudyFlowScaffold
import dev.studyflow.core.designsystem.theme.StudyFlowTheme
import dev.studyflow.core.designsystem.theme.spacing

/**
 * Screenshot tests for theme switching (issue #17).
 *
 * `./gradlew :core:designsystem:validateDebugScreenshotTest` renders these previews and compares
 * them against the images in `src/screenshotTestDebug/reference`; `updateDebugScreenshotTest`
 * records them after an intended change. The four combinations are here because "it still looks
 * right in dark mode" and "the fallback palette is still wired up" are exactly the claims that
 * cannot be made by reading a diff — a colour role swapped for the wrong one compiles perfectly.
 *
 * The 200% font scale variant is a gate rather than a curiosity: the type scale is declared in
 * `sp`, so a clipped or overlapping layout shows up here rather than on a user's device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSampler(
    darkTheme: Boolean,
    dynamicColor: Boolean,
) {
    // Edge-to-edge is switched off: a preview has no Activity window to make transparent, and the
    // insets it would otherwise apply are not part of what these images assert.
    StudyFlowTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, edgeToEdge = false) {
        StudyFlowScaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { TopAppBar(title = { Text(text = "StudyFlow") }) },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .padding(padding)
                        .padding(MaterialTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                Text(text = "Focus session", style = MaterialTheme.typography.headlineSmall)
                Text(text = "Two hours today", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {}) { Text(text = "Start") }
                FilledTonalButton(onClick = {}) { Text(text = "Add material") }
                Card {
                    Text(
                        text = "Discrete maths",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(MaterialTheme.spacing.medium),
                    )
                }
            }
        }
    }
}

@PreviewTest
@Preview
@Composable
private fun BrandLightThemePreview() {
    ThemeSampler(darkTheme = false, dynamicColor = false)
}

@PreviewTest
@Preview
@Composable
private fun BrandDarkThemePreview() {
    ThemeSampler(darkTheme = true, dynamicColor = false)
}

@PreviewTest
@Preview
@Composable
private fun DynamicLightThemePreview() {
    ThemeSampler(darkTheme = false, dynamicColor = true)
}

@PreviewTest
@Preview
@Composable
private fun DynamicDarkThemePreview() {
    ThemeSampler(darkTheme = true, dynamicColor = true)
}

@PreviewTest
@Preview(fontScale = 2.0f)
@Composable
private fun LargeFontThemePreview() {
    ThemeSampler(darkTheme = false, dynamicColor = false)
}
