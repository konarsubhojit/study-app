package dev.studyflow.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.studyflow.core.designsystem.layout.StudyFlowScaffold
import dev.studyflow.core.designsystem.theme.StudyFlowTheme
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.domain.result.UserMessage
import dev.studyflow.core.ui.components.DurationText
import dev.studyflow.core.ui.components.PermissionRationaleSheet
import dev.studyflow.core.ui.components.StudyFlowDialog
import dev.studyflow.core.ui.components.StudyFlowListItem
import dev.studyflow.core.ui.components.StudyFlowTag
import dev.studyflow.core.ui.components.StudyFlowTopAppBar
import dev.studyflow.core.ui.components.TimeText
import dev.studyflow.core.ui.state.EmptyState
import dev.studyflow.core.ui.state.ErrorState
import kotlin.time.Duration.Companion.minutes

@Composable
private fun ComponentGallery(darkTheme: Boolean) {
    StudyFlowTheme(darkTheme = darkTheme, dynamicColor = false, edgeToEdge = false) {
        StudyFlowScaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { StudyFlowTopAppBar(title = "StudyFlow") },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .padding(padding)
                        .padding(MaterialTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                StudyFlowListItem(
                    headline = "Discrete mathematics",
                    supportingText = "Review today",
                    onClick = {},
                )
                StudyFlowTag(label = "Exam prep", onClick = {})
                DurationText(duration = 90.minutes)
                TimeText(time = "9:30 AM", contentDescription = "Nine thirty AM")
            }
        }
    }
}

@PreviewTest
@Preview
@Composable
private fun SharedComponentsLightPreview() {
    ComponentGallery(darkTheme = false)
}

@PreviewTest
@Preview
@Composable
private fun SharedComponentsDarkPreview() {
    ComponentGallery(darkTheme = true)
}

@PreviewTest
@Preview(fontScale = 2.0f)
@Composable
private fun SharedComponentsLargeFontPreview() {
    ComponentGallery(darkTheme = false)
}

@PreviewTest
@Preview(locale = "ar")
@Composable
private fun SharedComponentsRtlPreview() {
    ComponentGallery(darkTheme = false)
}

@PreviewTest
@Preview
@Composable
private fun EmptyStatePreview() {
    StudyFlowTheme(dynamicColor = false, edgeToEdge = false) {
        EmptyState(message = "No sessions yet")
    }
}

@PreviewTest
@Preview
@Composable
private fun ErrorStatePreview() {
    StudyFlowTheme(dynamicColor = false, edgeToEdge = false) {
        ErrorState(message = UserMessage.Network, onRetry = {})
    }
}

@PreviewTest
@Preview
@Composable
private fun DialogPreview() {
    StudyFlowTheme(dynamicColor = false, edgeToEdge = false) {
        StudyFlowDialog(
            title = "Delete session?",
            text = "This cannot be undone.",
            onDismissRequest = {},
            confirmButton = { Button(onClick = {}) { Text("Delete") } },
            dismissButton = { Button(onClick = {}) { Text("Cancel") } },
        )
    }
}

@PreviewTest
@Preview
@Composable
private fun PermissionRationalePreview() {
    StudyFlowTheme(dynamicColor = false, edgeToEdge = false) {
        PermissionRationaleSheet(
            title = "Allow notifications",
            message = "StudyFlow uses notifications to remind you about planned sessions.",
            onDismissRequest = {},
            action = { Button(onClick = {}) { Text("Continue") } },
        )
    }
}
