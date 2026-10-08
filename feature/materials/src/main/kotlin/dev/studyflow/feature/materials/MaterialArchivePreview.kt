package dev.studyflow.feature.materials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.ui.state.EmptyState
import dev.studyflow.core.ui.state.LoadingState
import java.io.File

/**
 * The archive branch of the preview (issue #40): a read-only listing of what
 * [ArchiveEntryUiState] already knows is safe, with per-entry extraction and a plain-language
 * explanation whenever [ArchivePreviewUiState.message] says something was refused.
 */
@Composable
internal fun ArchivePreview(
    archivePreview: ArchivePreviewUiState?,
    onExtract: (String) -> Unit,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        archivePreview == null || archivePreview.loading -> {
            LoadingState(modifier = modifier.fillMaxSize())
        }

        archivePreview.entries.isEmpty() -> {
            EmptyState(
                message = archivePreview.message ?: "This archive has nothing safe to show.",
                modifier = modifier.fillMaxSize(),
            )
        }

        else -> {
            Column(
                modifier = modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                archivePreview.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
                    )
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(archivePreview.entries, key = { it.path }) { entry ->
                        ArchiveEntryRow(
                            entry = entry,
                            onExtract = { onExtract(entry.path) },
                            onCancel = { onCancel(entry.path) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveEntryRow(
    entry: ArchiveEntryUiState,
    onExtract: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.medium)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
                Text(text = formatSize(entry.sizeBytes), style = MaterialTheme.typography.bodySmall)
            }
            ArchiveEntryAction(entry = entry, onExtract = onExtract, onCancel = onCancel)
        }
        (entry.extraction as? ArchiveExtractionUiState.Failed)?.let { failed ->
            Text(
                text = failed.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ArchiveEntryAction(
    entry: ArchiveEntryUiState,
    onExtract: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    when (val extraction = entry.extraction) {
        ArchiveExtractionUiState.Idle -> {
            TextButton(onClick = onExtract) { Text(text = "Extract") }
        }

        ArchiveExtractionUiState.Extracting -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Extracting…", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onCancel) { Text(text = "Cancel") }
            }
        }

        is ArchiveExtractionUiState.Done -> {
            TextButton(
                onClick = { context.openLocalFile(File(extraction.localPath), guessMimeType(entry.name)) },
            ) {
                Text(text = "Open")
            }
        }

        is ArchiveExtractionUiState.Failed -> {
            TextButton(onClick = onExtract) { Text(text = "Retry") }
        }
    }
}
