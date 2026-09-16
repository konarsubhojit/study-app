package dev.studyflow.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.studyflow.core.designsystem.adaptive.StudyFlowListDetail
import dev.studyflow.core.designsystem.theme.StudyFlowTheme
import dev.studyflow.core.designsystem.theme.spacing

/**
 * Screenshot tests for the adaptive list/detail layout (issue #17).
 *
 * The previews are rendered at a phone, a large-phone and a tablet width, which is the cheapest way
 * to keep the window-size-class branch honest: a regression that shows one pane on a tablet, or two
 * cramped panes on anything narrower, changes these images.
 */
@Composable
private fun ListDetailSampler(hasSelection: Boolean) {
    StudyFlowTheme(darkTheme = false, dynamicColor = false, edgeToEdge = false) {
        StudyFlowListDetail(
            hasSelection = hasSelection,
            listPane = { Pane(label = "Materials") },
            detailPane = { Pane(label = if (hasSelection) "Discrete maths" else "Nothing selected") },
        )
    }
}

@Composable
private fun Pane(label: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
        )
    }
}

@PreviewTest
@Preview(widthDp = 411, heightDp = 640)
@Composable
private fun CompactListPreview() {
    ListDetailSampler(hasSelection = false)
}

@PreviewTest
@Preview(widthDp = 411, heightDp = 640)
@Composable
private fun CompactDetailPreview() {
    ListDetailSampler(hasSelection = true)
}

@PreviewTest
@Preview(widthDp = 700, heightDp = 640)
@Composable
private fun MediumDetailPreview() {
    ListDetailSampler(hasSelection = true)
}

@PreviewTest
@Preview(widthDp = 1024, heightDp = 640)
@Composable
private fun ExpandedListDetailPreview() {
    ListDetailSampler(hasSelection = true)
}
