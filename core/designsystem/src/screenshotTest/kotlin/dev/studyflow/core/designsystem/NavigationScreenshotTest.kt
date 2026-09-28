package dev.studyflow.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.studyflow.core.designsystem.navigation.StudyFlowNavigationItem
import dev.studyflow.core.designsystem.navigation.StudyFlowNavigationSuite
import dev.studyflow.core.designsystem.theme.StudyFlowTheme
import dev.studyflow.core.designsystem.theme.spacing

/**
 * Screenshot tests for the top-level navigation surface (issue #167).
 *
 * The bar is the first thing a user sees, and its two failure modes — a label wrapping onto a
 * second line, and a tablet-width window still showing a bottom bar instead of a rail — are both
 * invisible in a unit test and obvious in an image. The 200% font scale preview is the gate on the
 * first; the 1024dp preview is the gate on the second.
 */
private val samplerItems =
    listOf(
        StudyFlowNavigationItem("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
        StudyFlowNavigationItem("timer", "Timer", Icons.Outlined.Timer, Icons.Filled.Timer),
        StudyFlowNavigationItem(
            key = "materials",
            label = "Materials",
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            selectedIcon = Icons.AutoMirrored.Filled.MenuBook,
        ),
        StudyFlowNavigationItem("tasks", "Tasks", Icons.Outlined.CheckCircle, Icons.Filled.CheckCircle),
        StudyFlowNavigationItem("insights", "Insights", Icons.Outlined.Insights, Icons.Filled.Insights),
    )

@Composable
private fun NavigationSampler() {
    StudyFlowTheme(darkTheme = false, dynamicColor = false, edgeToEdge = false) {
        StudyFlowNavigationSuite(items = samplerItems, selected = "home", onSelect = {}) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Today",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(MaterialTheme.spacing.medium),
                )
            }
        }
    }
}

@PreviewTest
@Preview(widthDp = 411, heightDp = 640)
@Composable
private fun CompactNavigationBarPreview() {
    NavigationSampler()
}

@PreviewTest
@Preview(widthDp = 411, heightDp = 640, fontScale = 2.0f)
@Composable
private fun CompactNavigationBarLargeFontScalePreview() {
    NavigationSampler()
}

@PreviewTest
@Preview(widthDp = 1024, heightDp = 640)
@Composable
private fun ExpandedNavigationRailPreview() {
    NavigationSampler()
}
